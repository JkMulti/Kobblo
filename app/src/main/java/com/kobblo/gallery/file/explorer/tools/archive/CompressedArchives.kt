package com.kobblo.gallery.file.explorer.tools.archive

import com.kobblo.gallery.file.explorer.archive.ArchiveEntryInfo
import com.kobblo.gallery.file.explorer.fileops.ExtractGuard
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The archive formats beyond ZIP and RAR, which the older handlers keep. */
enum class CompressedFormat { TAR, TAR_GZ, TAR_BZ2, TAR_XZ, SEVEN_Z, GZ, BZ2, XZ }

object ArchiveFormats {
    /** ZIPs and RARs under another name, sent to the handler that matches their bytes. */
    private val ZipFamily = setOf("jar", "aar", "war", "ear", "cbz", "cbr", "ipa")

    private val tarSuffixes = listOf(
        ".tar.gz" to CompressedFormat.TAR_GZ,
        ".tgz" to CompressedFormat.TAR_GZ,
        ".tar.bz2" to CompressedFormat.TAR_BZ2,
        ".tbz2" to CompressedFormat.TAR_BZ2,
        ".tbz" to CompressedFormat.TAR_BZ2,
        ".tar.xz" to CompressedFormat.TAR_XZ,
        ".txz" to CompressedFormat.TAR_XZ,
        ".tar" to CompressedFormat.TAR,
        ".7z" to CompressedFormat.SEVEN_Z,
        ".gz" to CompressedFormat.GZ,
        ".bz2" to CompressedFormat.BZ2,
        ".xz" to CompressedFormat.XZ
    )

    fun fromName(name: String): CompressedFormat? {
        val lower = name.lowercase()
        return tarSuffixes.firstOrNull { (suffix, _) -> lower.endsWith(suffix) }?.second
    }

    /** True for every archive the app can open, whichever handler takes it. */
    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".rar") || fromName(lower) != null ||
            lower.substringAfterLast('.', "") in ZipFamily
    }

    /** The name with its archive extension gone, for the folder it extracts into: `a.tar.gz` gives `a`. */
    fun stem(name: String): String {
        val lower = name.lowercase()
        val suffix = tarSuffixes.firstOrNull { (suffix, _) -> lower.endsWith(suffix) }?.first
            ?: return name.substringBeforeLast('.').ifEmpty { name }
        return name.dropLast(suffix.length).ifEmpty { name }
    }

    /**
     * The format from a file's first bytes. A compressed stream could hold a tar or a single file,
     * and the name decides between them.
     */
    fun fromMagic(head: ByteArray, name: String): CompressedFormat? {
        fun at(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { (head[it].toInt() and 0xFF) == bytes[it] }
        val named = fromName(name)
        return when {
            at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> CompressedFormat.SEVEN_Z
            at(0x1F, 0x8B) -> if (named == CompressedFormat.TAR_GZ) named else CompressedFormat.GZ
            at(0x42, 0x5A, 0x68) -> if (named == CompressedFormat.TAR_BZ2) named else CompressedFormat.BZ2
            at(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> if (named == CompressedFormat.TAR_XZ) named else CompressedFormat.XZ
            head.size >= 262 && String(head, 257, 5, Charsets.ISO_8859_1) == "ustar" -> CompressedFormat.TAR
            else -> null
        }
    }
}

/** Lists and extracts [CompressedFormat]s. Knows nothing of Android, so it's tested on the JVM. */
object CompressedArchives {

    fun list(archive: File, format: CompressedFormat, password: String? = null): List<ArchiveEntryInfo> = when (format) {
        CompressedFormat.SEVEN_Z -> openSevenZ(archive, password).use { sevenZ ->
            sevenZ.entries.map { entry ->
                ArchiveEntryInfo(
                    name = entry.name.orEmpty(),
                    sizeBytes = if (entry.hasStream()) entry.size else 0,
                    isDirectory = entry.isDirectory,
                    isEncrypted = false
                )
            }
        }
        CompressedFormat.GZ, CompressedFormat.BZ2, CompressedFormat.XZ -> listOf(
            ArchiveEntryInfo(ArchiveFormats.stem(archive.name), -1, isDirectory = false, isEncrypted = false)
        )
        else -> openTar(archive, format).use { tar ->
            val entries = ArrayList<ArchiveEntryInfo>()
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entry.isSymbolicLink || entry.isLink) continue
                entries += ArchiveEntryInfo(entry.name, entry.size, entry.isDirectory, isEncrypted = false)
            }
            entries
        }
    }

    /**
     * Extracts into [destination].
     *
     * Entries come from whoever made the archive, so each path is checked to stay inside
     * [destination], and links are skipped because one could point a later entry outside it.
     *
     * @param onEntry is told each entry's name and how many are done, and can stop the extraction by
     * throwing.
     */
    fun extract(
        archive: File,
        format: CompressedFormat,
        destination: File,
        password: String? = null,
        freeSpace: (File) -> Long = { it.usableSpace },
        onEntry: (name: String, index: Int) -> Unit = { _, _ -> }
    ) {
        destination.mkdirs()
        // These formats may not say how much they hold until it's all been read, so writing watches the disk.
        fun outputTo(target: File) = ExtractGuard.guarded(target.outputStream(), destination, freeSpace)
        when (format) {
            CompressedFormat.SEVEN_Z -> openSevenZ(archive, password).use { sevenZ ->
                var index = 0
                while (true) {
                    val entry = sevenZ.nextEntry ?: break
                    onEntry(entry.name.orEmpty(), index++)
                    val target = safeTarget(destination, entry.name.orEmpty())
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else if (entry.hasStream()) {
                        target.parentFile?.mkdirs()
                        outputTo(target).use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val read = sevenZ.read(buffer)
                                if (read < 0) break
                                onEntry(entry.name.orEmpty(), index - 1)
                                output.write(buffer, 0, read)
                            }
                        }
                    } else {
                        // An empty file has no stream at all in a 7z.
                        target.parentFile?.mkdirs()
                        target.createNewFile()
                    }
                }
            }
            CompressedFormat.GZ, CompressedFormat.BZ2, CompressedFormat.XZ -> {
                val name = ArchiveFormats.stem(archive.name)
                onEntry(name, 0)
                decompress(archive, format).use { input ->
                    outputTo(safeTarget(destination, name)).use { output -> copy(input, output) { onEntry(name, 0) } }
                }
            }
            else -> openTar(archive, format).use { tar ->
                var index = 0
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isSymbolicLink || entry.isLink) continue
                    onEntry(entry.name, index++)
                    val target = safeTarget(destination, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        outputTo(target).use { output -> copy(tar, output) { onEntry(entry.name, index - 1) } }
                    }
                }
            }
        }
    }

    /** Throws for a name that would land outside [destination], such as `../../etc/passwd`. */
    internal fun safeTarget(destination: File, name: String): File {
        val root = destination.canonicalFile
        val target = File(root, name.replace('\\', '/')).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw IOException("Archive entry would land outside the folder: $name")
        }
        return target
    }

    private fun openSevenZ(archive: File, password: String?): SevenZFile =
        if (password != null) SevenZFile(archive, password.toCharArray()) else SevenZFile(archive)

    private fun openTar(archive: File, format: CompressedFormat): TarArchiveInputStream =
        TarArchiveInputStream(if (format == CompressedFormat.TAR) BufferedInputStream(archive.inputStream()) else decompress(archive, format))

    private fun decompress(archive: File, format: CompressedFormat): InputStream {
        val raw = BufferedInputStream(archive.inputStream(), BUFFER)
        return try {
            when (format) {
                CompressedFormat.TAR_GZ, CompressedFormat.GZ -> GzipCompressorInputStream(raw, true)
                CompressedFormat.TAR_BZ2, CompressedFormat.BZ2 -> BZip2CompressorInputStream(raw, true)
                CompressedFormat.TAR_XZ, CompressedFormat.XZ -> XZCompressorInputStream(raw, true)
                else -> raw
            }
        } catch (e: IOException) {
            raw.close()
            throw e
        }
    }

    private inline fun copy(input: InputStream, output: java.io.OutputStream, onBlock: () -> Unit) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            onBlock()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private const val BUFFER = 128 * 1024
}
