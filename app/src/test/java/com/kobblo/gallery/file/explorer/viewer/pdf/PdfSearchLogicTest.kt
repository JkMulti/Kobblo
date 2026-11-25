package com.kobblo.gallery.file.explorer.viewer.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchLogicTest {
    private fun hit(page: Int, tag: Float = 0f) = PdfHit(page, floatArrayOf(tag, 0f, tag + 1f, 1f))

    @Test
    fun `finds every match ignoring case`() {
        assertEquals(listOf(0..6, 14..20), PdfTextMatch.find("Gallery and a gallery", "GALLERY"))
    }

    @Test
    fun `matches don't overlap`() {
        assertEquals(listOf(0..1, 2..3), PdfTextMatch.find("aaaa", "aa"))
    }

    @Test
    fun `spaces that extraction guessed wrong don't hide a word`() {
        assertEquals(listOf(10..17), PdfTextMatch.find("left mark co rn er mark", "corner"))
        assertEquals(listOf(0..10), PdfTextMatch.find("quick brown fox", "quickbrown"))
        assertEquals(listOf(0..10), PdfTextMatch.find("quick brown fox", "quick brown"))
    }

    @Test
    fun `nothing to find finds nothing`() {
        assertTrue(PdfTextMatch.find("words", "").isEmpty())
        assertTrue(PdfTextMatch.find("words", "   ").isEmpty())
        assertTrue(PdfTextMatch.find("", "words").isEmpty())
        assertTrue(PdfTextMatch.find("wo", "words").isEmpty())
    }

    @Test
    fun `what's typed reads the way extraction writes text`() {
        assertEquals("quick brown", PdfTextMatch.normalizeQuery("  quick \n  brown "))
    }

    @Test
    fun `characters on a line join into one rectangle`() {
        val chars = floatArrayOf(
            10f, 100f, 20f, 112f,
            20f, 100f, 30f, 112f,
            30f, 100f, 40f, 112f
        )
        assertArrayEquals(floatArrayOf(10f, 100f, 40f, 112f), PdfTextMatch.rectsFor(chars, 0, 3), 0.001f)
    }

    @Test
    fun `a match that wraps gives a rectangle per line`() {
        val chars = floatArrayOf(
            400f, 100f, 410f, 112f,
            0f, 0f, 0f, 0f, // the space, which has no rectangle
            72f, 114f, 82f, 126f
        )
        val rects = PdfTextMatch.rectsFor(chars, 0, 3)
        assertEquals(8, rects.size)
        assertEquals(400f, rects[0], 0.001f)
        assertEquals(72f, rects[4], 0.001f)
    }

    @Test
    fun `asking past the end of the characters is harmless`() {
        val chars = floatArrayOf(10f, 100f, 20f, 112f)
        assertArrayEquals(floatArrayOf(10f, 100f, 20f, 112f), PdfTextMatch.rectsFor(chars, 0, 50), 0.001f)
    }

    @Test
    fun `results stay in document order however the scan wrapped round`() {
        val results = PdfSearchResults()
        results.add(50, listOf(hit(50)))
        results.add(99, listOf(hit(99)))
        // The scan started at page 50, and has come round to the start.
        results.add(3, listOf(hit(3)))
        assertEquals(3, results.selectFrom(0)?.page)
        assertEquals(50, results.next(0)?.page)
        assertEquals(99, results.next(0)?.page)
        assertEquals(3, results.next(0)?.page)
    }

    @Test
    fun `selecting from a page finds the next match on or after it, wrapping`() {
        val results = PdfSearchResults()
        results.add(10, listOf(hit(10)))
        results.add(20, listOf(hit(20)))
        assertEquals(10, results.selectFrom(5)?.page)
        assertEquals(10, results.selectFrom(10)?.page)
        assertEquals(20, results.selectFrom(11)?.page)
        assertEquals(10, results.selectFrom(21)?.page)
    }

    @Test
    fun `what's current stays the same match as more arrive`() {
        val results = PdfSearchResults()
        val watched = hit(50)
        results.add(50, listOf(watched))
        results.selectFrom(50)
        results.add(3, listOf(hit(3)))
        results.add(7, listOf(hit(7), hit(7, 1f)))
        assertTrue(results.current === watched)
        assertEquals(3, results.currentIndex)
    }

    @Test
    fun `previous wraps to the end, and moving works from nothing selected`() {
        val results = PdfSearchResults()
        results.add(1, listOf(hit(1)))
        results.add(2, listOf(hit(2)))
        assertEquals(1, results.next(0)?.page)
        assertEquals(2, results.previous(0)?.page)
        assertEquals(1, results.previous(0)?.page)
    }

    @Test
    fun `a page knows its own matches`() {
        val results = PdfSearchResults()
        results.add(7, listOf(hit(7), hit(7, 1f)))
        assertEquals(2, results.onPage(7).size)
        assertTrue(results.onPage(8).isEmpty())
    }

    @Test
    fun `with no matches there's nowhere to go`() {
        val results = PdfSearchResults()
        assertNull(results.next(0))
        assertNull(results.previous(0))
        assertNull(results.selectFrom(0))
    }

    @Test
    fun `clearing forgets everything`() {
        val results = PdfSearchResults()
        results.add(7, listOf(hit(7)))
        results.selectFrom(0)
        results.clear()
        assertEquals(0, results.count)
        assertEquals(-1, results.currentIndex)
        assertTrue(results.onPage(7).isEmpty())
    }

    @Test
    fun `a selection starts on the nearest character, preferring the right line`() {
        val rects = floatArrayOf(
            0f, 0f, 10f, 10f,
            10f, 0f, 20f, 10f,
            0f, 0f, 0f, 0f, // a space
            0f, 12f, 10f, 22f
        )
        assertEquals(1, PdfSelectionLogic.nearestChar(rects, 15f, 5f))
        // Far off to the side of the first line, and still not on the second.
        assertEquals(1, PdfSelectionLogic.nearestChar(rects, 200f, 5f))
        assertEquals(3, PdfSelectionLogic.nearestChar(rects, 5f, 20f))
    }

    @Test
    fun `a page with no characters has nothing to select`() {
        assertEquals(-1, PdfSelectionLogic.nearestChar(FloatArray(0), 0f, 0f))
    }

    @Test
    fun `a word stretches to its edges and keeps apostrophes`() {
        val text = "one don't three"
        assertEquals(0..2, PdfSelectionLogic.wordAround(text, 1))
        assertEquals(4..8, PdfSelectionLogic.wordAround(text, 6))
        assertEquals(10..14, PdfSelectionLogic.wordAround(text, 14))
        assertEquals(3..3, PdfSelectionLogic.wordAround(text, 3))
        assertTrue(PdfSelectionLogic.wordAround(text, 99).isEmpty())
    }

    @Test
    fun `a link's rectangle lands where the page shows it, whichever way the file turns the page`() {
        // A rectangle 10 in from the left and 20 up from the bottom, on a page 600 wide and 800 tall.
        val r0 = PdfLinkGeometry.toDisplay(10f, 20f, 110f, 40f, 600f, 800f, 0)
        assertEquals(PdfRect(10f, 760f, 110f, 780f), r0)
        // Turned clockwise the bottom-left corner of the page goes to the top-left of the screen.
        val r90 = PdfLinkGeometry.toDisplay(10f, 20f, 110f, 40f, 600f, 800f, 90)
        assertEquals(PdfRect(20f, 10f, 40f, 110f), r90)
        val r180 = PdfLinkGeometry.toDisplay(10f, 20f, 110f, 40f, 600f, 800f, 180)
        assertEquals(PdfRect(490f, 20f, 590f, 40f), r180)
        val r270 = PdfLinkGeometry.toDisplay(10f, 20f, 110f, 40f, 600f, 800f, 270)
        assertEquals(PdfRect(760f, 490f, 780f, 590f), r270)
    }

    @Test
    fun `a tap finds a link with a little room to spare`() {
        val links = listOf(PdfLink(PdfRect(100f, 100f, 200f, 110f), 5, null))
        assertEquals(5, PdfLinkGeometry.hit(links, 150f, 105f, 0f)?.page)
        assertEquals(5, PdfLinkGeometry.hit(links, 150f, 112f, 4f)?.page)
        assertNull(PdfLinkGeometry.hit(links, 150f, 130f, 4f))
    }

    @Test
    fun `only ordinary addresses are handed to other apps`() {
        assertTrue(PdfLinkGeometry.isOpenableUri("https://example.com"))
        assertTrue(PdfLinkGeometry.isOpenableUri("HTTP://example.com"))
        assertTrue(PdfLinkGeometry.isOpenableUri("mailto:a@example.com"))
        assertTrue(!PdfLinkGeometry.isOpenableUri("file:///data/data/secret"))
        assertTrue(!PdfLinkGeometry.isOpenableUri("content://x/y"))
        assertTrue(!PdfLinkGeometry.isOpenableUri("intent:#Intent;end"))
        assertTrue(!PdfLinkGeometry.isOpenableUri("javascript:alert(1)"))
        assertTrue(!PdfLinkGeometry.isOpenableUri("no scheme"))
    }

    @Test
    fun `a character's box follows the way its text runs on screen`() {
        // Upright text, baseline at 100,200: runs right, with its top towards the top of the screen.
        assertEquals(PdfRect(110f, 188f, 120f, 203f), PdfCharBox.rect(0, 100f, 200f, 10f, 20f, 12f, 3f))
        // A page turned clockwise: text runs down the screen, and its top faces right.
        assertEquals(PdfRect(97f, 210f, 112f, 220f), PdfCharBox.rect(90, 100f, 200f, 10f, 20f, 12f, 3f))
        // Upside down: runs left, with its top towards the bottom of the screen.
        assertEquals(PdfRect(80f, 197f, 90f, 212f), PdfCharBox.rect(180, 100f, 200f, 10f, 20f, 12f, 3f))
        // Reading upwards, with its top facing left.
        assertEquals(PdfRect(88f, 180f, 103f, 190f), PdfCharBox.rect(270, 100f, 200f, 10f, 20f, 12f, 3f))
    }

    @Test
    fun `a page's turn and a text's direction add up the right way round`() {
        assertEquals(0, PdfCharBox.displayAngle(0, 0))
        assertEquals(90, PdfCharBox.displayAngle(90, 0))
        assertEquals(180, PdfCharBox.displayAngle(180, 0))
        assertEquals(270, PdfCharBox.displayAngle(270, 0))
        // Text written going up the page reads upwards on an unturned page.
        assertEquals(270, PdfCharBox.displayAngle(0, 90))
        // And turning that page clockwise lays it back flat.
        assertEquals(0, PdfCharBox.displayAngle(90, 90))
    }
}
