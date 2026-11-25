package com.kobblo.gallery.file.explorer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPreviewTest {
    @Test
    fun `each kind of file gets the preview that suits it`() {
        assertEquals(DocumentPreview.FirstPage, DocumentPreview.of("pdf"))
        assertEquals(DocumentPreview.AppIcon, DocumentPreview.of("apk"))
        for (office in listOf("docx", "pptx", "xlsx", "odt", "ods", "odp")) {
            assertEquals(office, DocumentPreview.EmbeddedPicture, DocumentPreview.of(office))
        }
        for (text in listOf("txt", "log", "md", "json", "xml", "kt", "java", "py", "css", "js")) {
            assertEquals(text, DocumentPreview.OpeningLines, DocumentPreview.of(text))
        }
    }

    @Test
    fun `the extension's case doesn't matter`() {
        assertEquals(DocumentPreview.FirstPage, DocumentPreview.of("PDF"))
        assertEquals(DocumentPreview.EmbeddedPicture, DocumentPreview.of("Docx"))
    }

    @Test
    fun `other files have no preview here`() {
        // The old Word format carries no picture of itself, and bundles of APKs aren't packages.
        for (other in listOf("", "doc", "apks", "jpg", "zip", "html", "csv")) {
            assertNull(other, DocumentPreview.of(other))
        }
    }

    @Test
    fun `a picture that already fits isn't shrunk`() {
        assertEquals(1, sampleSizeToFit(512, 512, side = 512))
        assertEquals(1, sampleSizeToFit(100, 40, side = 512))
    }

    @Test
    fun `a picture is halved until both sides fit`() {
        assertEquals(2, sampleSizeToFit(513, 100, side = 512))
        assertEquals(2, sampleSizeToFit(1024, 768, side = 512))
        // Halves round down, so one pixel over twice the side still fits after one halving.
        assertEquals(2, sampleSizeToFit(1025, 768, side = 512))
        assertEquals(4, sampleSizeToFit(1026, 768, side = 512))
        assertEquals(4, sampleSizeToFit(300, 2000, side = 512))
        assertEquals(32, sampleSizeToFit(16_000, 9_000, side = 512))
    }

    @Test
    fun `a few lines sit one under another from the top of the card`() {
        val lines = TextCard.lines("first\nsecond\nthird")
        assertEquals(listOf("first", "second", "third"), lines.map { it.words })
        assertEquals(listOf(40f, 74f, 108f), lines.map { it.baseline })
    }

    @Test
    fun `the card holds thirteen lines and leaves its bottom edge clear`() {
        val lines = TextCard.lines((1..40).joinToString("\n") { "line $it" })
        assertEquals(13, lines.size)
        assertEquals("line 13", lines.last().words)
        assertEquals(448f, lines.last().baseline, 0f)
        assertTrue(lines.last().baseline < TextCard.Height - 40f)
    }

    @Test
    fun `a long line is cut at fifty characters`() {
        val lines = TextCard.lines("x".repeat(500) + "\nshort")
        assertEquals("x".repeat(50), lines[0].words)
        assertEquals("short", lines[1].words)
    }

    @Test
    fun `blank lines keep their place`() {
        val lines = TextCard.lines("title\n\nbody")
        assertEquals(listOf("title", "", "body"), lines.map { it.words })
        assertEquals(108f, lines[2].baseline, 0f)
    }

    @Test
    fun `an empty file is one empty line`() {
        assertEquals(listOf(""), TextCard.lines("").map { it.words })
    }
}
