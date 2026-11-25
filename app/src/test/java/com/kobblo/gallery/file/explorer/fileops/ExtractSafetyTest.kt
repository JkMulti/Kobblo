package com.kobblo.gallery.file.explorer.fileops

import com.kobblo.gallery.file.explorer.tools.archive.CompressedArchives
import com.kobblo.gallery.file.explorer.tools.archive.CompressedFormat
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What archives can do to a phone. A hostile archive is built by hand here, since no ordinary tool will
 * make one, and run through the same library calls the app makes.
 */
class ExtractSafetyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zipWith(name: String, entries: Map<String, ByteArray>): File {
        val archive = temp.newFile(name)
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((entry, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `zip4j refuses an entry climbing out of the folder`() {
        val archive = zipWith("evil.zip", mapOf("fine.txt" to "ok".toByteArray(), "../../escaped.txt" to "bad".toByteArray()))
        val out = temp.newFolder("sandbox", "a", "out")
        try {
            ZipFile(archive).extractAll(out.path)
        } catch (_: Exception) {
            // Refusing out loud is as good as refusing quietly. What matters is where nothing landed.
        }
        assertFalse(File(out.parentFile!!.parentFile, "escaped.txt").exists())
        assertFalse(File(out.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `the zip route that isn't zip4j refuses it too`() {
        val out = temp.newFolder("out")
        assertThrows(IOException::class.java) { CompressedArchives.safeTarget(out, "../escaped.txt") }
        assertThrows(IOException::class.java) { CompressedArchives.safeTarget(out, "a/../../escaped.txt") }
        assertThrows(IOException::class.java) { CompressedArchives.safeTarget(out, "..\\escaped.txt") }
        // A name that starts the same isn't inside.
        assertThrows(IOException::class.java) { CompressedArchives.safeTarget(out, "../out-evil/escaped.txt") }
        assertEquals(File(out.canonicalFile, "a/b.txt"), CompressedArchives.safeTarget(out, "a/b.txt"))
    }

    private fun tarGzWith(name: String, build: TarArchiveOutputStream.() -> Unit): File {
        val archive = temp.newFile(name)
        TarArchiveOutputStream(GzipCompressorOutputStream(archive.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.build()
        }
        return archive
    }

    private fun TarArchiveOutputStream.file(name: String, bytes: ByteArray) {
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        write(bytes)
        closeArchiveEntry()
    }

    @Test
    fun `tar refuses an entry climbing out`() {
        val archive = tarGzWith("evil.tar.gz") { file("../escaped.txt", "bad".toByteArray()) }
        val out = temp.newFolder("sandbox", "out")
        assertThrows(IOException::class.java) { CompressedArchives.extract(archive, CompressedFormat.TAR_GZ, out) }
        assertFalse(File(out.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `tar skips a link so a later entry can't write through it`() {
        val outside = temp.newFolder("outside")
        val archive = tarGzWith("link.tar.gz") {
            putArchiveEntry(TarArchiveEntry("away", TarArchiveEntry.LF_SYMLINK).apply { linkName = outside.path })
            closeArchiveEntry()
            file("away/written.txt", "bad".toByteArray())
        }
        val out = temp.newFolder("out")
        CompressedArchives.extract(archive, CompressedFormat.TAR_GZ, out)
        assertFalse(outside.resolve("written.txt").exists())
    }

    @Test
    fun `an archive too big for the space is refused before anything is written`() {
        val out = temp.newFolder("out")
        val thrown = assertThrows(ExtractGuard.NotEnoughSpace::class.java) {
            ExtractGuard.requireRoom(out, 42L * 1024 * 1024 * 1024) { 2L * 1024 * 1024 * 1024 }
        }
        assertTrue(thrown.message, thrown.message!!.contains("42.0 GB"))
        assertTrue(thrown.message, thrown.message!!.contains("2.0 GB"))
    }

    @Test
    fun `room is left for everything else`() {
        val out = temp.newFolder("out")
        val free = 1024L * 1024 * 1024
        ExtractGuard.requireRoom(out, free - ExtractGuard.ReserveBytes) { free }
        assertThrows(ExtractGuard.NotEnoughSpace::class.java) {
            ExtractGuard.requireRoom(out, free - ExtractGuard.ReserveBytes + 1) { free }
        }
    }

    @Test
    fun `not knowing the free space doesn't stop anything`() {
        val out = temp.newFolder("out")
        ExtractGuard.requireRoom(out, Long.MAX_VALUE / 2) { 0L }
        assertFalse(ExtractGuard.isLow(out) { 0L })
    }

    @Test
    fun `a stream that unpacks past the space stops partway, however little it claimed`() {
        // A gzip says nothing about how large it is. 64 MB of zeros squeezes down to next to nothing.
        val bomb = temp.newFile("bomb.gz")
        GzipCompressorOutputStream(bomb.outputStream()).use { gz ->
            val zeros = ByteArray(1024 * 1024)
            repeat(64) { gz.write(zeros) }
        }
        assertTrue("The bomb is small: ${bomb.length()}", bomb.length() < 1024 * 1024)

        val out = temp.newFolder("out")
        var free = 100L * 1024 * 1024 + 20L * 1024 * 1024
        assertThrows(ExtractGuard.NotEnoughSpace::class.java) {
            CompressedArchives.extract(bomb, CompressedFormat.GZ, out, freeSpace = {
                // The disk fills as it's written. Whatever's on it, this one goes down by what's been written.
                val written = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                free - written
            })
        }
        val written = out.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("Wrote $written", written < 64L * 1024 * 1024)
    }

    @Test
    fun `an ordinary archive is untouched by the guard`() {
        val archive = tarGzWith("ok.tar.gz") { file("dir/a.txt", "contents".toByteArray()) }
        val out = temp.newFolder("out")
        CompressedArchives.extract(archive, CompressedFormat.TAR_GZ, out)
        assertEquals("contents", out.resolve("dir/a.txt").readText())
    }

    @Test
    fun `a guarded stream passes every byte through`() {
        val sink = ByteArrayOutputStream()
        val out = temp.newFolder("out")
        ExtractGuard.guarded(sink, out) { Long.MAX_VALUE }.use { stream ->
            stream.write(1)
            stream.write(byteArrayOf(2, 3, 4), 1, 2)
        }
        assertEquals(listOf<Byte>(1, 3, 4), sink.toByteArray().toList())
    }

    @Test
    fun `sizes read the way people read them`() {
        assertEquals("512 B", ExtractGuard.formatBytes(512))
        assertEquals("1.5 KB", ExtractGuard.formatBytes(1536))
        assertEquals("3.0 GB", ExtractGuard.formatBytes(3L * 1024 * 1024 * 1024))
    }

    /** A real RAR 4 archive with a stored entry, put together byte by byte, because nothing else makes one here. */
    private fun rar4With(entryName: String, content: ByteArray): File {
        val archive = temp.newFile("evil.rar")
        val crc = CRC32().apply { update(content) }.value
        val nameBytes = entryName.toByteArray()

        fun Long.le(count: Int) = ByteArray(count) { ((this ushr (8 * it)) and 0xFF).toByte() }

        fun Int.le2() = toLong().le(2)

        fun headerCrc(body: ByteArray): ByteArray {
            val computed = CRC32().apply { update(body) }.value and 0xFFFF
            return computed.le(2)
        }

        // Main archive header: type, flags, size, then two reserved fields.
        val mainBody = byteArrayOf(0x73) + 0.le2() + 13.le2() + 0.le2() + 0L.le(4)
        // File header: type, flags (long block), size, packed, unpacked, host, CRC, time, version, method (stored),
        // name length, attributes, then the name.
        val fileHeaderSize = 32 + nameBytes.size
        val fileBody = byteArrayOf(0x74) +
            0x8000.le2() +
            fileHeaderSize.le2() +
            content.size.toLong().le(4) +
            content.size.toLong().le(4) +
            byteArrayOf(3) +
            crc.le(4) +
            0L.le(4) +
            byteArrayOf(29) +
            byteArrayOf(0x30) +
            nameBytes.size.le2() +
            0x20L.le(4) +
            nameBytes
        // End of archive.
        val endBody = byteArrayOf(0x7B) + 0x4000.le2() + 7.le2()

        archive.outputStream().use { out ->
            out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
            out.write(headerCrc(mainBody)); out.write(mainBody)
            out.write(headerCrc(fileBody)); out.write(fileBody); out.write(content)
            out.write(headerCrc(endBody)); out.write(endBody)
        }
        return archive
    }

    @Test
    fun `rar puts a climbing entry inside the folder or refuses it`() {
        val archive = rar4With("../../escaped.txt", "bad".toByteArray())
        val out = temp.newFolder("sandbox", "a", "out")
        try {
            be.stef.rar.Unrar5j.extract(archive.path, out.path, null)
        } catch (_: Exception) {
            // Refusing is fine. Only where nothing landed matters.
        }
        assertFalse("Landed above the folder", File(out.parentFile!!.parentFile, "escaped.txt").exists())
        assertFalse("Landed beside the folder", File(out.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `the hand-made rar is a real one, so the test above means something`() {
        val archive = rar4With("fine.txt", "contents".toByteArray())
        val out = temp.newFolder("out")
        val result = be.stef.rar.Unrar5j.extract(archive.path, out.path, null)
        assertEquals("Errors: ${result.errors}", 0, result.errorCount)
        assertEquals("contents", out.resolve("fine.txt").readText())
    }
}
