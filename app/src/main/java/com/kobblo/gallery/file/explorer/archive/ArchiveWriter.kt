package com.kobblo.gallery.file.explorer.archive

import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.FileOpException
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.ArrayCache
import org.tukaani.xz.BasicArrayCache
import org.tukaani.xz.LZMA2Options
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.cancellation.CancellationException

/** What an archive can be made as. Zip is the one that opens everywhere, and the only one here with a password. */
enum class ArchiveFormat(val extension: String, val label: String) {
    ZIP(".zip", "ZIP"),
    SEVEN_Z(".7z", "7z"),
    TAR_GZ(".tar.gz", "tar.gz");

    /** [name] ending the way this format does, in place of any other archive's ending it has. */
    fun nameFor(name: String): String {
        val lower = name.lowercase()
        val known = KnownEndings.firstOrNull { lower.endsWith(it) }
        return (if (known != null) name.dropLast(known.length) else name) + extension
    }

    private companion object {
        val KnownEndings = listOf(".tar.gz", ".tgz", ".tar", ".zip", ".7z")
    }
}

/**
 * Writes the formats Commons Compress makes, and checks what it wrote. It holds no file in memory: tar.gz streams
 * from start to end, and 7z goes through a file of its own, because its index is written last and points back.
 */
internal class ArchiveWriter(
    private val isStopped: () -> Boolean,
    private val onProgress: (percent: Int, currentFile: String) -> Unit
) {
    class Entry(val file: File, val name: String, val isFolder: Boolean, val size: Long, val modified: Long)

    /** What actually went in, since a file can change between being listed and being read. */
    data class Written(var files: Int = 0, var folders: Int = 0, var bytes: Long = 0L)

    private val buffer = ByteArray(BufferSize)
    private var doneBytes = 0L
    private var totalWork = 1L
    private var lastReport = 0L

    /**
     * Everything to put in, by the name it has inside. Nothing is followed through a link, so the archive can't
     * reach out of what was selected, and a folder that can't be read stops it, since someone may delete the
     * originals next. The same name picked from two folders gets a number rather than one replacing the other.
     */
    fun list(sources: List<File>): List<Entry> {
        val entries = ArrayList<Entry>()
        val usedNames = HashSet<String>()
        for (source in sources) {
            val name = SafeFiles.freeName(source.name, isFolder = source.isDirectory) { it.lowercase() in usedNames }
            usedNames += name.lowercase()
            addTree(source, name, entries)
        }
        if (entries.isEmpty()) throw FileOpException(FailReason.MISSING, "Nothing to put in the archive")
        return entries
    }

    private fun addTree(file: File, name: String, into: MutableList<Entry>) {
        ensureRunning()
        // A temporary file here may be this very archive, if the selection reaches the folder it's being made in.
        if (SafeFiles.isPartName(file.name)) return
        val attributes = SafeFiles.attributesOf(file) ?: return
        val modified = attributes.lastModifiedTime().toMillis()
        when {
            attributes.isSymbolicLink -> Unit
            attributes.isDirectory -> {
                into += Entry(file, name, isFolder = true, size = 0L, modified = modified)
                val children = file.listFiles() ?: throw FileOpException(FailReason.DENIED, "Can't read ${file.name}")
                for (child in children) addTree(child, "$name/${child.name}", into)
            }
            attributes.isRegularFile -> into += Entry(file, name, isFolder = false, size = attributes.size(), modified = modified)
        }
    }

    fun write(format: ArchiveFormat, entries: List<Entry>, part: File): Written {
        doneBytes = 0L
        // Each entry counts for a little, so a folder of empty files still moves the bar.
        totalWork = entries.sumOf { it.size + EntryWork }.coerceAtLeast(1L)
        return when (format) {
            ArchiveFormat.SEVEN_Z -> writeSevenZ(entries, part)
            ArchiveFormat.TAR_GZ -> writeTarGz(entries, part)
            ArchiveFormat.ZIP -> throw IllegalArgumentException("Zip is written by zip4j")
        }
    }

    private fun writeSevenZ(entries: List<Entry>, part: File): Written {
        val written = Written()
        // It starts a new compressor for every file, so without a cache each small file costs a large allocation.
        ArrayCache.setDefaultCache(BasicArrayCache.getInstance())
        SevenZOutputFile(part).use { out ->
            // The library's default wants about 94 MB of heap, which is a lot to ask beside a gallery's caches.
            out.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, LZMA2Options(SevenZPreset))))
            for (item in entries) {
                ensureRunning()
                val entry = SevenZArchiveEntry().apply {
                    name = item.name
                    isDirectory = item.isFolder
                    setLastModifiedDate(Date(item.modified))
                }
                out.putArchiveEntry(entry)
                if (item.isFolder) {
                    written.folders++
                } else {
                    written.bytes += copy(item, Long.MAX_VALUE) { data, length -> out.write(data, 0, length) }
                    written.files++
                }
                out.closeArchiveEntry()
                finishedEntry(item)
            }
        }
        return written
    }

    private fun writeTarGz(entries: List<Entry>, part: File): Written {
        val written = Written()
        val file = BufferedOutputStream(FileOutputStream(part), BufferSize)
        GZIPOutputStream(file, BufferSize).use { gz ->
            TarArchiveOutputStream(gz, "UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                tar.setAddPaxHeadersForNonAsciiNames(true)
                for (item in entries) {
                    ensureRunning()
                    val entry = TarArchiveEntry(if (item.isFolder) "${item.name}/" else item.name, true)
                    entry.setModTime(Date(item.modified))
                    if (item.isFolder) {
                        tar.putArchiveEntry(entry)
                        written.folders++
                    } else {
                        // The header goes first and can't change, so take the size as late as possible and hold to it.
                        val size = SafeFiles.attributesOf(item.file)?.size() ?: throw IOException("${item.file.name} has gone")
                        entry.size = size
                        tar.putArchiveEntry(entry)
                        written.bytes += copy(item, size) { data, length -> tar.write(data, 0, length) }
                        written.files++
                    }
                    tar.closeArchiveEntry()
                    finishedEntry(item)
                }
                tar.finish()
            }
        }
        return written
    }

    private inline fun copy(item: Entry, limit: Long, write: (ByteArray, Int) -> Unit): Long {
        var copied = 0L
        FileInputStream(item.file).use { input ->
            while (copied < limit) {
                ensureRunning()
                val read = input.read(buffer, 0, minOf(limit - copied, buffer.size.toLong()).toInt())
                if (read < 0) break
                write(buffer, read)
                copied += read
                doneBytes += read
                report(item.file.name)
            }
        }
        return copied
    }

    private fun finishedEntry(item: Entry) {
        doneBytes += EntryWork
        report(item.file.name)
    }

    /** Stays short of 100 until the archive is whole. */
    private fun report(name: String) {
        val now = System.nanoTime()
        if (now - lastReport < ReportGapNanos) return
        lastReport = now
        onProgress((doneBytes * 99 / totalWork).toInt().coerceIn(0, 99), name)
    }

    /** Reads it back, because someone may delete the originals next. */
    fun check(format: ArchiveFormat, part: File, expected: Written) {
        val found = when (format) {
            ArchiveFormat.SEVEN_Z -> readSevenZ(part)
            ArchiveFormat.TAR_GZ -> readTarGz(part)
            ArchiveFormat.ZIP -> throw IllegalArgumentException("Zip is checked by zip4j")
        }
        if (found != expected) throw FileOpException(FailReason.CHECK_FAILED, "The archive is missing some of the files")
    }

    private fun readSevenZ(part: File): Written {
        val found = Written()
        SevenZFile.builder().setFile(part).get().use { archive ->
            for (entry in archive.entries) {
                ensureRunning()
                if (entry.isDirectory) found.folders++ else {
                    found.files++
                    found.bytes += entry.size
                }
            }
        }
        return found
    }

    /** Reads every byte, which is what gets gzip to compare its checksum at the end. */
    private fun readTarGz(part: File): Written {
        val found = Written()
        GZIPInputStream(BufferedInputStream(FileInputStream(part), BufferSize), BufferSize).use { gz ->
            val tar = TarArchiveInputStream(gz, "UTF-8")
            while (true) {
                ensureRunning()
                val entry = tar.nextEntry ?: break
                if (entry.isDirectory) {
                    found.folders++
                    continue
                }
                found.files++
                while (true) {
                    ensureRunning()
                    val read = tar.read(buffer)
                    if (read < 0) break
                    found.bytes += read
                }
            }
            // The tar ends before the gzip does, and its checksum is in what's left.
            while (gz.read(buffer) >= 0) ensureRunning()
        }
        return found
    }

    private fun ensureRunning() {
        if (isStopped()) throw CancellationException("Archiving was cancelled")
    }

    private companion object {
        const val BufferSize = 64 * 1024
        const val EntryWork = 4 * 1024L
        const val SevenZPreset = 3
        const val ReportGapNanos = 50_000_000L
    }
}
