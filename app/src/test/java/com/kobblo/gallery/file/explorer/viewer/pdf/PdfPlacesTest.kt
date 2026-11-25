package com.kobblo.gallery.file.explorer.viewer.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class PdfPlacesTest {
    @Test
    fun `a place survives being written down`() {
        val place = PdfPlace(41, 0.25f, 2.5f, 0.1f, 90, listOf(3, 40))
        val back = PdfPlaceCodec.decode(PdfPlaceCodec.encode(place, 1000L))
        assertEquals(place, back)
        assertEquals(1000L, PdfPlaceCodec.decodeSavedAt(PdfPlaceCodec.encode(place, 1000L)))
    }

    @Test
    fun `no bookmarks reads as none`() {
        val place = PdfPlace(41, 0.25f, 2.5f, 0.1f, 0)
        assertEquals(emptyList<Int>(), PdfPlaceCodec.decode(PdfPlaceCodec.encode(place, 1L))?.bookmarks)
    }

    @Test
    fun `nonsense is no place at all`() {
        assertNull(PdfPlaceCodec.decode(null))
        assertNull(PdfPlaceCodec.decode(""))
        assertNull(PdfPlaceCodec.decode("a,b,c,d,e,f"))
        assertNull(PdfPlaceCodec.decode("-1,0,1,0,0,0"))
        assertNull(PdfPlaceCodec.decode("1,5,1,0,0,0"))
        assertNull(PdfPlaceCodec.decode("1,0,-2,0,0,0"))
        assertNull(PdfPlaceCodec.decode("1,0,1,0,45,0"))
    }

    @Test
    fun `the very start of a file isn't worth writing down`() {
        assertFalse(PdfPlaceCodec.isWorthKeeping(PdfPlace(0, 0f, 1f, 0f, 0)))
        assertTrue(PdfPlaceCodec.isWorthKeeping(PdfPlace(1, 0f, 1f, 0f, 0)))
        assertTrue(PdfPlaceCodec.isWorthKeeping(PdfPlace(0, 0.5f, 1f, 0f, 0)))
        assertTrue(PdfPlaceCodec.isWorthKeeping(PdfPlace(0, 0f, 2f, 0f, 0)))
        assertTrue(PdfPlaceCodec.isWorthKeeping(PdfPlace(0, 0f, 1f, 0f, 90)))
        assertTrue(PdfPlaceCodec.isWorthKeeping(PdfPlace(0, 0f, 1f, 0f, 0, listOf(5))))
    }

    @Test
    fun `a file's fingerprint goes by what's in it, wherever it is`() {
        val folder = createTempFolder()
        val a = File(folder, "a.pdf").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val moved = File(folder, "somewhere_else.pdf").apply { writeBytes(a.readBytes()) }
        assertEquals(fingerprint(a), fingerprint(moved))
    }

    @Test
    fun `a different file with the same name doesn't inherit its place`() {
        val folder = createTempFolder()
        val a = File(folder, "a.pdf").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val first = fingerprint(a)
        a.writeBytes(ByteArray(300_000) { (it % 241).toByte() })
        assertNotEquals(first, fingerprint(a))
    }

    @Test
    fun `changing only the length changes the fingerprint`() {
        val folder = createTempFolder()
        val a = File(folder, "a.pdf").apply { writeBytes(ByteArray(10)) }
        val b = File(folder, "b.pdf").apply { writeBytes(ByteArray(11)) }
        assertNotEquals(fingerprint(a), fingerprint(b))
    }

    @Test
    fun `an edit near the end of a large file is noticed`() {
        val folder = createTempFolder()
        val bytes = ByteArray(400_000) { (it % 251).toByte() }
        val a = File(folder, "a.pdf").apply { writeBytes(bytes) }
        val first = fingerprint(a)
        bytes[bytes.size - 10] = 99
        a.writeBytes(bytes)
        assertNotEquals(first, fingerprint(a))
    }

    @Test
    fun `tiny and empty files have fingerprints too`() {
        val folder = createTempFolder()
        val empty = File(folder, "e.pdf").apply { writeBytes(ByteArray(0)) }
        assertTrue(fingerprint(empty).isNotEmpty())
        val tiny = File(folder, "t.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(32, fingerprint(tiny).length)
    }

    private fun fingerprint(file: File): String =
        RandomAccessFile(file, "r").use { fingerprintOf(it.channel, it.length()) }

    private fun createTempFolder(): File =
        File.createTempFile("pdfplaces", "").let { temp ->
            temp.delete()
            temp.mkdirs()
            temp.deleteOnExit()
            temp
        }
}
