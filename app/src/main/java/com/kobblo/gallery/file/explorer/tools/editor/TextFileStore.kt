package com.kobblo.gallery.file.explorer.tools.editor

import android.content.Context
import com.kobblo.gallery.file.explorer.tools.storage.FolderSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/** What opening a text file came to. */
internal sealed interface TextOpenResult {
    /** Small enough to hold in memory and edit. */
    data class Editable(
        val decoded: DecodedText,
        val bytes: ByteArray,
        val readOnlyReason: ReadOnlyReason?,
        val length: Long,
        val modified: Long
    ) : TextOpenResult

    /** Too big for an editing surface: read through a line index instead. */
    data class TooLarge(val index: LineIndex) : TextOpenResult

    /** Its first bytes aren't text. It takes a deliberate choice to open it as text anyway. */
    data object Binary : TextOpenResult

    data class Failed(val message: String?) : TextOpenResult
}

internal enum class ReadOnlyReason { NOT_WRITABLE, TEMPORARY_COPY, UNSAFE_ENCODING }

internal sealed interface SaveResult {
    data class Saved(val length: Long, val modified: Long) : SaveResult
    data class Failed(val message: String?) : SaveResult
}

internal object TextFileStore {
    /**
     * Past this the editing surface gets slow to lay out, so a file opens through the line index,
     * read-only. Measured on the test device.
     */
    const val EDIT_LIMIT_BYTES = 1024L * 1024L

    private const val SAMPLE = 64 * 1024

    suspend fun open(
        context: Context,
        path: String,
        forceText: Boolean,
        charset: Charset? = null,
        onProgress: (Float) -> Unit = {},
        isActive: () -> Boolean = { true }
    ): TextOpenResult = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            val length = file.length()
            val sample = file.inputStream().use { input ->
                val buffer = ByteArray(SAMPLE)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
            if (!forceText && !TextCodec.looksLikeText(sample)) return@withContext TextOpenResult.Binary
            if (length > EDIT_LIMIT_BYTES) {
                val detected = charset ?: TextCodec.detectCharset(sample.trimToWholeUtf8())
                return@withContext TextOpenResult.TooLarge(LineIndex.build(file, detected, onProgress, isActive))
            }
            val bytes = file.readBytes()
            val decoded = if (charset != null) TextCodec.decode(bytes, charset) else TextCodec.decode(bytes)
            val reason = when {
                path.startsWith(context.cacheDir.path) -> ReadOnlyReason.TEMPORARY_COPY
                !file.canWrite() -> ReadOnlyReason.NOT_WRITABLE
                !decoded.roundTrips -> ReadOnlyReason.UNSAFE_ENCODING
                else -> null
            }
            TextOpenResult.Editable(decoded, bytes, reason, length, file.lastModified())
        } catch (e: Exception) {
            TextOpenResult.Failed(e.localizedMessage)
        }
    }

    /**
     * Writes beside the original and swaps it in, so a failure partway, or a phone dying, can't leave
     * half a file. A card's file system may refuse the swap, and then it writes in place.
     */
    suspend fun save(context: Context, path: String, bytes: ByteArray): SaveResult = withContext(Dispatchers.IO) {
        // A rename would replace a link with a file, and leave the file it points at as it was.
        val target = File(path).let { if (java.nio.file.Files.isSymbolicLink(it.toPath())) it.canonicalFile else it }
        // Short and unrelated to the name, which may already be as long as a name can be.
        val temp = com.kobblo.gallery.file.explorer.fileops.SafeFiles.partFileIn(target.parentFile ?: File("/"))
        target.parentFile?.let { com.kobblo.gallery.file.explorer.fileops.SafeFiles.clearStaleParts(it) }
        try {
            // A folder can refuse new files while its files stay writable. Only then does it write in
            // place: a failure partway through a write, such as a full disk, must leave the original.
            val tempOut = try {
                FileOutputStream(temp)
            } catch (_: java.io.FileNotFoundException) {
                null
            }
            if (tempOut != null) {
                tempOut.use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                com.kobblo.gallery.file.explorer.fileops.SafeFiles.copyPermissions(target, temp)
            }
            if (tempOut == null || !temp.renameTo(target)) {
                FileOutputStream(target).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                temp.delete()
            }
            FolderSizes.invalidate(path)
            try {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            } catch (_: Exception) {
            }
            SaveResult.Saved(target.length(), target.lastModified())
        } catch (e: Exception) {
            temp.delete()
            SaveResult.Failed(e.localizedMessage)
        }
    }

    /** A sample may end in the middle of a character, which mustn't count as invalid UTF-8. */
    private fun ByteArray.trimToWholeUtf8(): ByteArray {
        var end = size
        var back = 0
        while (back < 3 && end > 0 && (this[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            back++
        }
        if (end > 0 && (this[end - 1].toInt() and 0x80) != 0) end--
        return copyOf(end)
    }
}
