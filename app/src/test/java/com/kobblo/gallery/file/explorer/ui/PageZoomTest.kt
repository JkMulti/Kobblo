package com.kobblo.gallery.file.explorer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageZoomTest {
    private fun assertZoom(scale: Float, x: Float, y: Float, actual: PageZoom) {
        assertEquals("scale", scale, actual.scale, 0.0001f)
        assertEquals("x", x, actual.x, 0.0001f)
        assertEquals("y", y, actual.y, 0.0001f)
    }

    @Test
    fun `a page starts at its natural size, in the middle`() {
        assertZoom(1f, 0f, 0f, PageZoom())
    }

    @Test
    fun `the button enlarges by half again each press, up to five times`() {
        var zoom = PageZoom()
        val seen = ArrayList<Float>()
        repeat(6) {
            zoom = zoom.steppedIn()
            seen += zoom.scale
        }
        assertEquals(listOf(1.5f, 2.25f, 3.375f, 5f, 5f, 5f), seen)
    }

    @Test
    fun `enlarging by the button leaves the page where it was dragged`() {
        assertZoom(3f, 40f, -25f, PageZoom(2f, 40f, -25f).steppedIn())
    }

    @Test
    fun `shrinking part of the way leaves the page where it was dragged`() {
        assertZoom(2f, 40f, -25f, PageZoom(3f, 40f, -25f).steppedOut())
    }

    @Test
    fun `shrinking back to natural size puts the page in the middle`() {
        assertEquals(PageZoom(), PageZoom(1.5f, 40f, -25f).steppedOut())
        assertEquals(PageZoom(), PageZoom(1.2f, 40f, -25f).steppedOut())
        assertEquals(PageZoom(), PageZoom().steppedOut())
    }

    @Test
    fun `stepping all the way in and all the way out ends where it began`() {
        var zoom = PageZoom()
        repeat(8) { zoom = zoom.steppedIn() }
        repeat(8) { zoom = zoom.steppedOut() }
        assertEquals(PageZoom(), zoom)
    }

    @Test
    fun `a pinch on a still page changes its size and nothing else`() {
        assertZoom(1.8f, 0f, 0f, PageZoom().pinched(1.8f))
    }

    @Test
    fun `a pinch can't shrink a page below natural size or enlarge it past five times`() {
        assertZoom(1f, 0f, 0f, PageZoom().pinched(0.4f))
        assertZoom(5f, 0f, 0f, PageZoom().pinched(9f))
    }

    @Test
    fun `a drag moves an enlarged page by the drag times its size`() {
        assertZoom(2f, 20f, -30f, PageZoom(2f).transformed(1f, panX = 10f, panY = -15f, width = 1000f, height = 2000f))
    }

    @Test
    fun `a page can be dragged until its edge meets the edge of the area, and no further`() {
        // At twice the size, half the area's width is hidden on each side.
        val zoom = PageZoom(2f)
        assertZoom(2f, 500f, 1000f, zoom.transformed(1f, panX = 9000f, panY = 9000f, width = 1000f, height = 2000f))
        assertZoom(2f, -500f, -1000f, zoom.transformed(1f, panX = -9000f, panY = -9000f, width = 1000f, height = 2000f))
    }

    @Test
    fun `the room to drag grows with the page`() {
        val grown = PageZoom(2f, 500f, 0f).transformed(2f, panX = 9000f, panY = 0f, width = 1000f, height = 2000f)
        assertZoom(4f, 1500f, 0f, grown)
    }

    @Test
    fun `a page pinched smaller is pulled back inside its new room`() {
        val shrunk = PageZoom(4f, 1500f, 3000f).transformed(0.5f, panX = 0f, panY = 0f, width = 1000f, height = 2000f)
        assertZoom(2f, 500f, 1000f, shrunk)
    }

    @Test
    fun `pinching an enlarged page back to natural size puts it in the middle`() {
        assertEquals(PageZoom(), PageZoom(1.5f, 100f, 50f).transformed(0.5f, panX = 30f, panY = 30f, width = 1000f, height = 2000f))
    }

    @Test
    fun `a pinch on an enlarged page stops at five times`() {
        assertZoom(5f, 0f, 0f, PageZoom(4f).transformed(3f, panX = 0f, panY = 0f, width = 1000f, height = 2000f))
    }
}
