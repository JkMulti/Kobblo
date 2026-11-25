package com.kobblo.gallery.file.explorer.archive

import com.kobblo.gallery.file.explorer.tools.archive.CompressedFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveKindsTest {
    private val none = ByteArray(0)
    private val zip = bytes('P'.code, 'K'.code, 0x03, 0x04, 0x14, 0x00)
    private val rar4 = "Rar!".toByteArray() + bytes(0x1A, 0x07, 0x00, 0xCF)
    private val rar5 = "Rar!".toByteArray() + bytes(0x1A, 0x07, 0x01, 0x00, 0x33)
    private val sevenZ = bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0x00, 0x04)
    private val gzip = bytes(0x1F, 0x8B, 0x08, 0x00)
    private val plainText = "just some words in a file".toByteArray()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun tar(): ByteArray = ByteArray(512).also { "ustar".toByteArray().copyInto(it, 257) }

    @Test
    fun `the first bytes say what it is`() {
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(zip, "/sdcard/a.zip"))
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(rar4, "/sdcard/a.rar"))
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(rar5, "/sdcard/a.rar"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.SEVEN_Z), ArchiveKinds.of(sevenZ, "/sdcard/a.7z"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.TAR), ArchiveKinds.of(tar(), "/sdcard/a.tar"))
    }

    @Test
    fun `the bytes are believed over a wrong name`() {
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(zip, "/sdcard/really a zip.rar"))
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(rar5, "/sdcard/really a rar.zip"))
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(zip, "content://media/external/file/1234"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.TAR), ArchiveKinds.of(tar(), "/sdcard/backup.bin"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.SEVEN_Z), ArchiveKinds.of(sevenZ, "/sdcard/a.zip"))
    }

    @Test
    fun `zips that go by another name are zips`() {
        for (name in listOf("app.apk", "library.jar", "comic.cbz", "bundle.aar", "no extension")) {
            assertEquals(name, ArchiveKind.Zip, ArchiveKinds.of(zip, "/sdcard/$name"))
        }
    }

    @Test
    fun `a compressed stream is a tar only if its name says so`() {
        assertEquals(ArchiveKind.Compressed(CompressedFormat.TAR_GZ), ArchiveKinds.of(gzip, "/sdcard/site.tar.gz"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.TAR_GZ), ArchiveKinds.of(gzip, "/sdcard/site.tgz"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.GZ), ArchiveKinds.of(gzip, "/sdcard/log.gz"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.GZ), ArchiveKinds.of(gzip, "/sdcard/unnamed"))
    }

    @Test
    fun `the name decides when nothing could be read`() {
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(none, "/sdcard/a.zip"))
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(none, "/sdcard/a.jar"))
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(none, "/sdcard/a.apk"))
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(none, "/sdcard/a.rar"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.SEVEN_Z), ArchiveKinds.of(none, "/sdcard/a.7z"))
        assertEquals(ArchiveKind.Compressed(CompressedFormat.TAR_XZ), ArchiveKinds.of(none, "/sdcard/a.tar.xz"))
    }

    @Test
    fun `the name decides when the bytes say nothing`() {
        // An empty ZIP has no first entry, and starts with the mark that normally ends one.
        val emptyZip = bytes('P'.code, 'K'.code, 0x05, 0x06, 0, 0, 0, 0)
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(emptyZip, "/sdcard/empty.zip"))
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(plainText, "/sdcard/damaged.rar"))
    }

    @Test
    fun `too few bytes to be sure fall back on the name`() {
        assertEquals(ArchiveKind.Zip, ArchiveKinds.of(zip.copyOf(3), "/sdcard/a.zip"))
        assertNull(ArchiveKinds.of(zip.copyOf(3), "/sdcard/a.bin"))
        assertNull(ArchiveKinds.of(rar4.copyOf(6), "/sdcard/a.bin"))
        // Seven bytes are enough for the older format, and not for RAR 5.
        assertEquals(ArchiveKind.Rar, ArchiveKinds.of(rar4.copyOf(7), "/sdcard/a.bin"))
        assertNull(ArchiveKinds.of(rar5.copyOf(7), "/sdcard/a.bin"))
    }

    @Test
    fun `what is neither by bytes nor by name isn't an archive`() {
        assertNull(ArchiveKinds.of(plainText, "/sdcard/notes.txt"))
        assertNull(ArchiveKinds.of(none, "/sdcard/photo.jpg"))
        assertNull(ArchiveKinds.of(none, ""))
        // A ZIP under another name is only known by its bytes.
        assertNull(ArchiveKinds.of(none, "/sdcard/comic.cbz"))
    }

    @Test
    fun `the name is expected in lower case`() {
        assertNull(ArchiveKinds.of(none, "/sdcard/A.ZIP"))
    }
}
