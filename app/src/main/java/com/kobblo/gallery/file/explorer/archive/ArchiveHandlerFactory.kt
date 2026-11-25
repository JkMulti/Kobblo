package com.kobblo.gallery.file.explorer.archive

import android.content.Context
import android.net.Uri
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.tools.archive.CompressedArchiveHandler
import com.kobblo.gallery.file.explorer.tools.archive.CompressedFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class UnsupportedArchiveFormatException(message: String) : Exception(message)

/** Which handler reads an archive. */
internal sealed class ArchiveKind {
    object Zip : ArchiveKind()
    object Rar : ArchiveKind()
    data class Compressed(val format: CompressedFormat) : ArchiveKind()
}

/**
 * Tells what an archive is. Its first bytes are believed over its name, because archives get renamed and
 * plenty of formats are ZIPs that go by something else. The name only decides when the bytes say nothing,
 * as with a file that couldn't be read or a ZIP that's empty.
 */
internal object ArchiveKinds {
    private val ZipStart = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)

    /** "Rar!", then the bytes that tell the older format from RAR 5. */
    private val Rar4Start = "Rar!".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x1A, 0x07, 0x00)
    private val Rar5Start = "Rar!".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x1A, 0x07, 0x01, 0x00)

    private val ZipEndings = listOf(".zip", ".jar", ".apk")

    /**
     * @param head the start of the file, as much of it as could be read, which may be none.
     * @param path the file's path or address in lower case.
     * @return null for something that isn't an archive the app can open.
     */
    fun of(head: ByteArray, path: String): ArchiveKind? = when {
        head.startsWith(ZipStart) -> ArchiveKind.Zip
        head.startsWith(Rar4Start) || head.startsWith(Rar5Start) -> ArchiveKind.Rar
        else -> ArchiveFormats.fromMagic(head, path)?.let(ArchiveKind::Compressed) ?: byName(path)
    }

    private fun byName(path: String): ArchiveKind? = when {
        ZipEndings.any { path.endsWith(it) } -> ArchiveKind.Zip
        path.endsWith(".rar") -> ArchiveKind.Rar
        else -> ArchiveFormats.fromName(path)?.let(ArchiveKind::Compressed)
    }

    private fun ByteArray.startsWith(start: ByteArray): Boolean =
        size >= start.size && start.indices.all { this[it] == start[it] }
}

object ArchiveHandlerFactory {
    /** Tar's mark sits at byte 257, so this much is read to recognize a tar that's been misnamed. */
    private const val HeadBytes = 512

    /** The handler for the archive at [source], or [UnsupportedArchiveFormatException] when it isn't one. */
    suspend fun create(context: Context, source: Uri): ArchiveHandler = withContext(Dispatchers.IO) {
        val path = (source.path ?: source.toString()).lowercase()
        when (val kind = ArchiveKinds.of(headOf(context, source), path)) {
            ArchiveKind.Zip -> ZipArchiveHandler(context)
            ArchiveKind.Rar -> RarArchiveHandler(context)
            is ArchiveKind.Compressed -> CompressedArchiveHandler(context, kind.format)
            null -> throw UnsupportedArchiveFormatException("This doesn't look like an archive Kobblo can open.")
        }
    }

    private fun headOf(context: Context, source: Uri): ByteArray {
        val head = ByteArray(HeadBytes)
        var length = 0
        try {
            open(context, source)?.use { length = it.read(head) }
        } catch (_: Exception) {
            // What was read still counts. With nothing read, the name is left to decide.
        }
        return if (length > 0) head.copyOf(length) else ByteArray(0)
    }

    /** A path that turns out not to be a file on disk may still be something a provider answers for. */
    private fun open(context: Context, source: Uri): InputStream? {
        val file = if (source.scheme == "file" || source.scheme == null) File(source.path ?: source.toString()) else null
        return if (file != null && file.exists()) FileInputStream(file) else context.contentResolver.openInputStream(source)
    }
}
