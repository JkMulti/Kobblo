package com.kobblo.gallery.file.explorer.fileops

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32

/** Why something couldn't be done, in the terms a person can act on. */
enum class FailReason { MISSING, NO_SPACE, INTO_ITSELF, DENIED, NAME_TOO_LONG, CHECK_FAILED, NOT_POSSIBLE, UNKNOWN }

class FileOpException(val reason: FailReason, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads the reason out of whatever the system threw. Its text holds the only record of the errno. */
internal fun reasonFor(error: Throwable): FailReason {
    if (error is FileOpException) return error.reason
    var cause: Throwable? = error
    while (cause != null) {
        val text = cause.message.orEmpty()
        when {
            text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true) -> return FailReason.NO_SPACE
            text.contains("ENAMETOOLONG") || text.contains("name too long", ignoreCase = true) -> return FailReason.NAME_TOO_LONG
            text.contains("EACCES") || text.contains("EPERM") || text.contains("EROFS") ||
                text.contains("Permission denied", ignoreCase = true) ||
                text.contains("not permitted", ignoreCase = true) ||
                text.contains("Read-only", ignoreCase = true) -> return FailReason.DENIED
            text.contains("ENOENT") || text.contains("No such file", ignoreCase = true) -> return FailReason.MISSING
        }
        cause = cause.cause
    }
    return FailReason.UNKNOWN
}

internal object SafeFiles {
    /** The most bytes a name can be on the file systems phones use. */
    const val MaxNameBytes = 255

    /** Where a file is written until it's whole. The name is short because the real one may already be 255 bytes. */
    private const val PartPrefix = ".kobblo-part-"

    fun isPartName(name: String): Boolean = name.startsWith(PartPrefix)

    fun partFileIn(folder: File): File {
        while (true) {
            val candidate = File(folder, PartPrefix + java.lang.Long.toHexString(System.nanoTime() xor Thread.currentThread().id))
            if (!candidate.exists()) return candidate
        }
    }

    /**
     * A copy killed partway leaves its hidden temporary file behind. The original is safe, so all it does is
     * take up space. Anything this old can't belong to a copy still running.
     */
    fun clearStaleParts(folder: File, now: Long = System.currentTimeMillis()) {
        folder.listFiles { _, name -> isPartName(name) }?.forEach { part ->
            if (now - part.lastModified() > STALE_PART_MILLIS) deleteTree(part)
        }
    }

    private const val STALE_PART_MILLIS = 6L * 60 * 60 * 1000

    /** What's at [file] itself, never what a link there points at. Null if there's nothing. */
    fun attributesOf(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: java.nio.file.InvalidPathException) {
        null
    }

    fun existsAtAll(file: File): Boolean = attributesOf(file) != null

    /**
     * Deletes [root]. Kotlin's `deleteRecursively` walks into a link to a folder and empties the folder it
     * points at, which can be anything anywhere. Here a link goes and what it points at stays. Returns
     * whether everything went.
     */
    fun deleteTree(root: File): Boolean {
        val attributes = attributesOf(root) ?: return true
        var everything = true
        if (attributes.isDirectory) {
            val children = root.listFiles()
            if (children == null) {
                everything = false
            } else {
                for (child in children) if (!deleteTree(child)) everything = false
            }
        }
        if (!root.delete() && existsAtAll(root)) everything = false
        return everything
    }

    /** How much is in [root], not following links. A folder that can't be read makes [unreadable] true. */
    class TreeSize(var bytes: Long = 0L, var files: Int = 0, var unreadable: Boolean = false)

    fun sizeOf(root: File, into: TreeSize = TreeSize()): TreeSize {
        val attributes = attributesOf(root) ?: return into
        if (attributes.isDirectory) {
            val children = root.listFiles()
            if (children == null) into.unreadable = true else children.forEach { sizeOf(it, into) }
        } else if (attributes.isRegularFile) {
            into.bytes += attributes.size()
            into.files++
        }
        return into
    }

    /**
     * Whether [candidate] is [folder] or anywhere inside it. It asks the file system too, because shared
     * storage folds case, so `/x/Photos/a` is inside `/x/photos` though no comparison of the text says so.
     */
    fun isSameOrInside(folder: File, candidate: File): Boolean {
        val outer = folder.canonicalFile
        val inner = candidate.canonicalFile
        if (inner == outer || inner.path.startsWith(outer.path + File.separator)) return true
        if (!outer.exists()) return false
        var walk: File? = inner
        while (walk != null) {
            if (walk.exists() && sameFile(walk, outer)) return true
            walk = walk.parentFile
        }
        return false
    }

    fun sameFile(a: File, b: File): Boolean = try {
        Files.isSameFile(a.toPath(), b.toPath())
    } catch (_: IOException) {
        false
    }

    /**
     * The first name free for [name], in the app's usual `photo (1).jpg` form, kept within the limit on a
     * name's length. A folder has no extension to keep, whatever dots it has.
     */
    fun freeName(name: String, isFolder: Boolean = false, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val hidden = name.startsWith(".")
        val bare = if (hidden) name.substring(1) else name
        val split = if (isFolder) bare.length else extensionStart(bare)
        val stem = bare.substring(0, split)
        val extension = bare.substring(split)
        val prefix = if (hidden) "." else ""
        var counter = 1
        while (true) {
            val suffix = " ($counter)$extension"
            val room = MaxNameBytes - utf8Length(prefix) - utf8Length(suffix)
            val candidate = prefix + clipToBytes(stem, room) + suffix
            if (!isTaken(candidate)) return candidate
            counter++
        }
    }

    private fun extensionStart(name: String): Int {
        val lower = name.lowercase()
        for (double in DoubleExtensions) {
            if (lower.endsWith(double) && lower.length > double.length) return name.length - double.length
        }
        val dot = name.lastIndexOf('.')
        return if (dot > 0) dot else name.length
    }

    private val DoubleExtensions = listOf(".tar.gz", ".tar.bz2", ".tar.xz")

    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** The start of [text] in at most [maxBytes] of UTF-8, without cutting a character in two. */
    fun clipToBytes(text: String, maxBytes: Int): String {
        if (utf8Length(text) <= maxBytes) return text
        var used = 0
        var end = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = when {
                point < 0x80 -> 1
                point < 0x800 -> 2
                point < 0x10000 -> 3
                else -> 4
            }
            if (used + size > maxBytes) break
            used += size
            end += Character.charCount(point)
        }
        return text.substring(0, end)
    }

    /**
     * Writes [target] so that it's either all there or as it was. A crash, a full disk or a dead battery
     * mid-write leaves the old file alone. A link stays a link, with the file it points at updated, and the
     * new file keeps the old one's permissions.
     */
    fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val real = if (Files.isSymbolicLink(target.toPath())) target.canonicalFile else target
        val folder = real.parentFile ?: throw IOException("No folder for ${real.name}")
        val part = partFileIn(folder)
        try {
            FileOutputStream(part).use { stream ->
                write(stream)
                stream.flush()
                stream.fd.sync()
            }
            copyPermissions(real, part)
            if (!part.renameTo(real)) throw IOException("Couldn't replace ${real.name}")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun writeAtomically(target: File, text: String) {
        writeAtomically(target) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Shared storage has no permissions to speak of, so failing to copy them never fails a save. */
    fun copyPermissions(from: File, to: File) {
        if (!from.exists()) return
        try {
            Files.setPosixFilePermissions(to.toPath(), Files.getPosixFilePermissions(from.toPath()))
        } catch (_: Exception) {
        }
    }
}

/**
 * Copies files and folders so that a file only ever appears under its real name once it's whole: it's
 * written under a temporary name, flushed to the disk, checked, and then renamed. So a failure, a kill or a
 * cancel can't leave half a file looking complete.
 *
 * It doesn't follow links. Copying what one points at could pull in a whole disk, or never end, so a link
 * is copied as a link where the file system has them and counted in [linksSkipped] where it hasn't.
 *
 * @param verify reads each new file back and compares it with what was read from the source. Needed when
 * the source is about to be deleted, and not worth doing twice the reading when it isn't.
 * @param checkpoint runs between blocks and is how a pause or a cancel gets in.
 */
internal class TreeCopier(
    private val verify: Boolean,
    private val checkpoint: suspend () -> Unit = {},
    private val onBytes: (Long) -> Unit = {},
    private val openInput: (File) -> InputStream = { FileInputStream(it) },
    private val openOutput: (File) -> OutputStream = { FileOutputStream(it) }
) {
    var linksSkipped = 0
        private set

    /**
     * [dest] must not exist, which is what makes it safe for the caller to delete it after a failure:
     * everything there came from this copy.
     */
    suspend fun copy(source: File, dest: File) {
        val attributes = SafeFiles.attributesOf(source)
            ?: throw FileOpException(FailReason.MISSING, "${source.name} isn't there any more")
        if (SafeFiles.existsAtAll(dest)) throw FileOpException(FailReason.NOT_POSSIBLE, "${dest.name} already exists")
        when {
            attributes.isSymbolicLink -> copyLink(source, dest)
            attributes.isDirectory -> copyFolder(source, dest)
            attributes.isRegularFile -> copyFile(source, dest)
            // A pipe or a device would block forever, or never end.
            else -> throw FileOpException(FailReason.NOT_POSSIBLE, "${source.name} isn't a regular file")
        }
    }

    private fun copyLink(source: File, dest: File) {
        try {
            Files.createSymbolicLink(dest.toPath(), Files.readSymbolicLink(source.toPath()))
        } catch (_: Exception) {
            linksSkipped++
        }
    }

    private suspend fun copyFolder(source: File, dest: File) {
        if (!dest.mkdirs() && !dest.isDirectory) throw FileOpException(FailReason.DENIED, "Couldn't create ${dest.name}")
        val children = source.listFiles() ?: throw FileOpException(FailReason.DENIED, "Couldn't read ${source.name}")
        for (child in children) {
            checkpoint()
            copy(child, File(dest, child.name))
        }
        dest.setLastModified(source.lastModified())
    }

    private suspend fun copyFile(source: File, dest: File) {
        val folder = dest.parentFile ?: throw FileOpException(FailReason.NOT_POSSIBLE, "No folder for ${dest.name}")
        if (SafeFiles.utf8Length(dest.name) > SafeFiles.MaxNameBytes) {
            throw FileOpException(FailReason.NAME_TOO_LONG, "${dest.name} is too long a name")
        }
        val part = SafeFiles.partFileIn(folder)
        try {
            val written = CRC32()
            var total = 0L
            openInput(source).use { input ->
                openOutput(part).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        if (verify) written.update(buffer, 0, read)
                        total += read
                        onBytes(read.toLong())
                    }
                    output.flush()
                    // Only when the source is about to go. A plain copy still has its original, and a sync
                    // per file is what would make a folder of thousands crawl.
                    if (verify && output is FileOutputStream) output.fd.sync()
                }
            }
            if (total != source.length() || part.length() != total) {
                throw FileOpException(FailReason.CHECK_FAILED, "${source.name} didn't copy whole")
            }
            if (verify && crcOf(part) != written.value) {
                throw FileOpException(FailReason.CHECK_FAILED, "${source.name} came out different")
            }
            part.setLastModified(source.lastModified())
            // A rename replaces what's there, so one last look. Whatever turned up since must not be lost.
            if (SafeFiles.existsAtAll(dest)) throw FileOpException(FailReason.NOT_POSSIBLE, "${dest.name} already exists")
            if (!part.renameTo(dest)) throw FileOpException(FailReason.UNKNOWN, "Couldn't finish ${dest.name}")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private suspend fun crcOf(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                checkpoint()
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private companion object {
        const val BUFFER = 256 * 1024
    }
}
