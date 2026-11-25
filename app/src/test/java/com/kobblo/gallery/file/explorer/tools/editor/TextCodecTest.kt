package com.kobblo.gallery.file.explorer.tools.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextCodecTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun assertRoundTrips(input: ByteArray) {
        val decoded = TextCodec.decode(input)
        assertTrue("expected a round trip", decoded.roundTrips)
        assertArrayEquals(input, TextCodec.encode(decoded.text, decoded.format))
    }

    @Test fun `plain utf-8 round trips byte for byte`() {
        assertRoundTrips("Synthetic notes file.\nSecond line.\n".toByteArray())
        assertRoundTrips("no newline at the end".toByteArray())
        assertRoundTrips("héllo wörld 日本語 🎉\n".toByteArray())
        assertRoundTrips(ByteArray(0))
    }

    @Test fun `crlf is kept and shown to the editor as lf`() {
        val input = "one\r\ntwo\r\n".toByteArray()
        val decoded = TextCodec.decode(input)
        assertEquals("one\ntwo\n", decoded.text)
        assertEquals(LineEnding.CRLF, decoded.format.lineEnding)
        assertRoundTrips(input)
    }

    @Test fun `old mac line endings are kept`() {
        val input = "one\rtwo\r".toByteArray()
        val decoded = TextCodec.decode(input)
        assertEquals("one\ntwo\n", decoded.text)
        assertEquals(LineEnding.CR, decoded.format.lineEnding)
        assertRoundTrips(input)
    }

    @Test fun `mixed line endings are reported and pick the commonest`() {
        val decoded = TextCodec.decode("a\r\nb\r\nc\n".toByteArray())
        assertTrue(decoded.format.mixedLineEndings)
        assertEquals(LineEnding.CRLF, decoded.format.lineEnding)
        assertEquals("a\nb\nc\n", decoded.text)
        // Valid text: mixing is reported on its own rather than blocking edits.
        assertTrue(decoded.roundTrips)
    }

    @Test fun `byte order marks are detected and kept`() {
        val utf8 = bytes(0xEF, 0xBB, 0xBF) + "hi\n".toByteArray()
        val decoded = TextCodec.decode(utf8)
        assertEquals(Charsets.UTF_8, decoded.format.charset)
        assertTrue(decoded.format.hasBom)
        assertEquals("hi\n", decoded.text)
        assertRoundTrips(utf8)

        val utf16le = bytes(0xFF, 0xFE) + "hi\r\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, TextCodec.decode(utf16le).format.charset)
        assertRoundTrips(utf16le)

        val utf16be = bytes(0xFE, 0xFF) + "hi\n".toByteArray(Charsets.UTF_16BE)
        assertEquals(Charsets.UTF_16BE, TextCodec.decode(utf16be).format.charset)
        assertRoundTrips(utf16be)
    }

    @Test fun `utf-16 without a mark is recognized from its zero bytes`() {
        val input = "Hello, this is plain English text.\n".toByteArray(Charsets.UTF_16LE)
        assertEquals(Charsets.UTF_16LE, TextCodec.detectCharset(input))
        assertEquals(Charsets.UTF_16BE, TextCodec.detectCharset("Hello, this is plain English text.\n".toByteArray(Charsets.UTF_16BE)))
        assertRoundTrips(input)
    }

    @Test fun `western text that isn't utf-8 falls back without losing bytes`() {
        val input = "café “quoted”\n".toByteArray(Charset.forName("windows-1252"))
        val decoded = TextCodec.decode(input)
        assertEquals(Charset.forName("windows-1252"), decoded.format.charset)
        assertEquals("café “quoted”\n", decoded.text)
        assertRoundTrips(input)
    }

    @Test fun `bytes undefined in windows-1252 still round trip through latin-1`() {
        val input = bytes(0x61, 0x81, 0x8D, 0x62, 0x0A)
        assertEquals(Charsets.ISO_8859_1, TextCodec.detectCharset(input))
        assertRoundTrips(input)
    }

    @Test fun `reading bytes in the wrong encoding is flagged as unsafe to edit`() {
        // Invalid as UTF-8: decoding replaces these bytes, so saving would change them.
        val decoded = TextCodec.decode(bytes(0x61, 0xFF, 0x62), Charsets.UTF_8)
        assertFalse(decoded.roundTrips)
    }

    @Test fun `changing a line ending re-encodes every line`() {
        val decoded = TextCodec.decode("a\nb\n".toByteArray())
        val crlf = decoded.format.copy(lineEnding = LineEnding.CRLF)
        assertArrayEquals("a\r\nb\r\n".toByteArray(), TextCodec.encode(decoded.text, crlf))
    }

    @Test fun `text an encoding can't hold is caught before saving`() {
        assertTrue(TextCodec.canEncode("café", Charsets.ISO_8859_1))
        assertFalse(TextCodec.canEncode("🎉", Charsets.ISO_8859_1))
        assertTrue(TextCodec.canEncode("🎉", Charsets.UTF_8))
    }

    @Test fun `binary content is told apart from text`() {
        assertTrue(TextCodec.looksLikeText("plain text\n".toByteArray()))
        assertTrue(TextCodec.looksLikeText(ByteArray(0)))
        assertTrue(TextCodec.looksLikeText("hi there friend\n".toByteArray(Charsets.UTF_16LE)))
        assertFalse(TextCodec.looksLikeText(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)))
        assertFalse(TextCodec.looksLikeText(ByteArray(200) { (it % 30).toByte() }))
    }
}
