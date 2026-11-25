package com.kobblo.gallery.file.explorer.transfer.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** Where to hold a file that might be carried on with later, and how much of it is already there. */
data class KeptPart(val name: String, val startAt: Long = 0)

/** How one incoming file ended. Only [Saved] leaves anything visible behind. */
sealed interface ReceiveResult {
    data class Saved(val file: File, val bytes: Long) : ReceiveResult
    data class Truncated(val got: Long, val expected: Long) : ReceiveResult
    data object ChecksumMismatch : ReceiveResult
    data object DiskFull : ReceiveResult
    data object Cancelled : ReceiveResult
    data class Failed(val reason: String) : ReceiveResult
}

/**
 * Writes one incoming file safely. It goes to a hidden `.part` beside its destination and only takes
 * its real name once every byte has arrived and the length and any checksum agree, so a dropped
 * connection or a full disk can never leave a broken file that looks like a whole one.
 */
class ReceiveSink(
    private val root: File,
    relativeName: String,
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it) },
    private val openAppend: (File) -> OutputStream = { FileOutputStream(it, true) }
) {
    private val relative = ReceivePaths.sanitizeRelative(relativeName)

    /** The file's name as it will be shown and saved, before any `(2)` for a clash. */
    val displayName: String get() = relative.joinToString("/")

    /**
     * Reads exactly [expectedBytes] from [input], or what's left of them after [keep]'s starting point.
     * Never reads past them, because the stream is the connection itself and the next request may follow
     * on it. With [keep], a connection that drops leaves what arrived in its hidden file to carry on from.
     */
    fun receive(
        input: InputStream,
        expectedBytes: Long,
        sha256: String? = null,
        isCancelled: () -> Boolean = { false },
        keep: KeptPart? = null,
        onProgress: (Long) -> Unit = {}
    ): ReceiveResult {
        val target = try {
            ReceivePaths.resolveInside(root, relative)
        } catch (e: IOException) {
            return ReceiveResult.Failed(e.message ?: "Bad path")
        }
        val dir = target.parentFile ?: return ReceiveResult.Failed("Bad path")
        val createdDirs = missingDirs(dir)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return ReceiveResult.Failed("Can't create ${dir.name}")
        }
        val part = File(dir, keep?.name ?: ".${UUID.randomUUID()}$PART_SUFFIX")
        val startAt = keep?.startAt ?: 0L
        val digest = sha256?.takeIf { it.isNotBlank() }?.let { MessageDigest.getInstance("SHA-256") }
        if (digest != null && startAt > 0) {
            try {
                part.inputStream().use { old ->
                    val buffer = ByteArray(BUFFER_BYTES.toInt())
                    while (true) {
                        val read = old.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } catch (e: IOException) {
                return ReceiveResult.Failed("Couldn't read what arrived earlier")
            }
        }

        fun discard() {
            part.delete()
            createdDirs.forEach { it.delete() } // Only succeeds on the empty ones, deepest first.
        }

        var written = startAt
        try {
            val file = if (startAt > 0) openAppend(part) else openOutput(part)
            // A secure connection hands data over a few kilobytes at a time, and each write to shared
            // storage is a trip through the system's file service, so they're gathered first.
            // No bigger than the file, because a folder of small ones would otherwise churn through memory.
            BufferedOutputStream(file, expectedBytes.coerceIn(MIN_BUFFER_BYTES, WRITE_BYTES).toInt()).use { output ->
                val buffer = ByteArray(expectedBytes.coerceIn(MIN_BUFFER_BYTES, BUFFER_BYTES).toInt())
                while (written < expectedBytes) {
                    if (isCancelled()) {
                        discard()
                        return ReceiveResult.Cancelled
                    }
                    val want = minOf(buffer.size.toLong(), expectedBytes - written).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    digest?.update(buffer, 0, read)
                    written += read
                    onProgress(written)
                }
                output.flush()
                (file as? FileOutputStream)?.fd?.sync()
            }
        } catch (e: IOException) {
            // Asked before discarding, because removing the partial file frees the space again.
            val full = isDiskFull(e, dir)
            val stopped = isCancelled()
            if (keep == null || full || stopped) discard()
            return when {
                full -> ReceiveResult.DiskFull
                stopped -> ReceiveResult.Cancelled
                else -> ReceiveResult.Truncated(written, expectedBytes)
            }
        }

        if (written != expectedBytes) {
            if (keep == null) discard()
            return ReceiveResult.Truncated(written, expectedBytes)
        }
        if (digest != null) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                discard()
                return ReceiveResult.ChecksumMismatch
            }
        }
        return try {
            ReceiveResult.Saved(ReceivePaths.moveToFreeName(part, dir, target.name), written)
        } catch (e: IOException) {
            discard()
            ReceiveResult.Failed(e.message ?: "Couldn't save")
        }
    }

    private fun missingDirs(dir: File): List<File> {
        val missing = mutableListOf<File>()
        var current: File? = dir
        while (current != null && !current.exists() && current != root) {
            missing.add(current)
            current = current.parentFile
        }
        return missing
    }

    // Shared storage on Android usually runs out as a quota rather than as a full disk.
    private fun isDiskFull(e: IOException, dir: File): Boolean {
        val message = e.message.orEmpty()
        return message.contains("ENOSPC") ||
            message.contains("EDQUOT") ||
            message.contains("No space left", ignoreCase = true) ||
            message.contains("quota", ignoreCase = true) ||
            dir.usableSpace < BUFFER_BYTES
    }

    companion object {
        const val PART_SUFFIX = ".iexpart"
        private const val MIN_BUFFER_BYTES = 8 * 1024L
        private const val BUFFER_BYTES = 64 * 1024L
        private const val WRITE_BYTES = 512 * 1024L
        private const val STALE_PART_MS = 24 * 60 * 60 * 1000L
        private const val MAX_WALK_DEPTH = 18

        /**
         * The hidden file for one sender's copy of one file. It's only found again by exactly that: the
         * same device, name, size and modified time, because anything else isn't the same file.
         */
        fun keptPartName(senderFingerprint: String, relativeName: String, size: Long, modifiedMs: Long): String {
            val key = "$senderFingerprint|$relativeName|$size|$modifiedMs"
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return "." + digest.take(16).joinToString("") { "%02x".format(it) } + PART_SUFFIX
        }

        /** The hash that shows two copies of a file agree just before where they'd join up. */
        fun tailHash(stream: InputStream, position: Long): String? {
            val tailBytes = minOf(position, TAIL_BYTES)
            var toSkip = position - tailBytes
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped > 0) {
                    toSkip -= skipped
                } else {
                    if (stream.read() < 0) return null
                    toSkip--
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES.toInt())
            var left = tailBytes
            while (left > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) return null
                digest.update(buffer, 0, read)
                left -= read
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private const val TAIL_BYTES = 1024 * 1024L

        /** Clears what a crash left behind. Young ones are skipped: they may be uploads in flight. */
        fun deleteStaleParts(root: File, nowMs: Long = System.currentTimeMillis()): Int {
            if (!root.isDirectory) return 0
            var removed = 0
            root.walkTopDown().maxDepth(MAX_WALK_DEPTH).forEach { file ->
                if (file.isFile && file.name.endsWith(PART_SUFFIX) && nowMs - file.lastModified() > STALE_PART_MS) {
                    if (file.delete()) removed++
                }
            }
            return removed
        }
    }
}
