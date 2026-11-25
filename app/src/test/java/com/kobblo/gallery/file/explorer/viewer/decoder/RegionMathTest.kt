package com.kobblo.gallery.file.explorer.viewer.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RegionMathTest {
    private val d = 0.01f

    @Test
    fun `unzoomed the whole picture is visible`() {
        val rect = RegionMath.visibleRect(4000f, 3000f, 1000f, 2000f, 1f, 0f, 0f)!!
        assertEquals(0f, rect.left, d)
        assertEquals(0f, rect.top, d)
        assertEquals(4000f, rect.right, d)
        assertEquals(3000f, rect.bottom, d)
    }

    @Test
    fun `zoomed twice with no offset shows the middle half across`() {
        // Fitted, 4000 pixels span the 1000-wide screen. At twice that, 2000 pixels do.
        val rect = RegionMath.visibleRect(4000f, 3000f, 1000f, 2000f, 2f, 0f, 0f)!!
        assertEquals(1000f, rect.left, d)
        assertEquals(3000f, rect.right, d)
        // Height still fits on screen, so all of it stays visible.
        assertEquals(0f, rect.top, d)
        assertEquals(3000f, rect.bottom, d)
    }

    @Test
    fun `moving the picture right shows more of its left`() {
        val centered = RegionMath.visibleRect(4000f, 3000f, 1000f, 2000f, 2f, 0f, 0f)!!
        val moved = RegionMath.visibleRect(4000f, 3000f, 1000f, 2000f, 2f, 250f, 0f)!!
        // 250 screen pixels at 2000/1000 source pixels each.
        assertEquals(centered.left - 500f, moved.left, d)
        assertEquals(centered.right - 500f, moved.right, d)
    }

    @Test
    fun `a picture moved fully off screen has nothing visible`() {
        assertNull(RegionMath.visibleRect(4000f, 3000f, 1000f, 2000f, 2f, 5000f, 0f))
        assertNull(RegionMath.visibleRect(0f, 3000f, 1000f, 2000f, 2f, 0f, 0f))
    }

    @Test
    fun `each rotation maps the corner it should`() {
        val corner = SourceRect(0f, 0f, 100f, 50f) // The top left of the picture as shown.

        // Unrotated, it's the same corner.
        assertEquals(corner, RegionMath.toStored(corner, 1, 4000f, 3000f))

        // Turned half way, the shown top left is stored at the bottom right.
        assertEquals(SourceRect(3900f, 2950f, 4000f, 3000f), RegionMath.toStored(corner, 3, 4000f, 3000f))

        // Stored 4000 by 3000 and shown a quarter turn clockwise: what's shown top left was
        // stored at the bottom left, running up.
        assertEquals(SourceRect(0f, 2900f, 50f, 3000f), RegionMath.toStored(corner, 6, 4000f, 3000f))

        // A quarter turn the other way: stored at the top right.
        assertEquals(SourceRect(3950f, 0f, 4000f, 100f), RegionMath.toStored(corner, 8, 4000f, 3000f))
    }

    @Test
    fun `a mirrored photo isn't handled`() {
        assertNull(RegionMath.toStored(SourceRect(0f, 0f, 1f, 1f), 2, 10f, 10f))
        assertNotNull(RegionMath.toStored(SourceRect(0f, 0f, 1f, 1f), 0, 10f, 10f))
    }

    @Test
    fun `the sample never makes the region softer than the screen`() {
        assertEquals(1, RegionMath.sampleSize(1000f, 2000f, 1000f, 2000f))
        assertEquals(1, RegionMath.sampleSize(1900f, 3900f, 1000f, 2000f))
        assertEquals(2, RegionMath.sampleSize(2000f, 4000f, 1000f, 2000f))
        assertEquals(4, RegionMath.sampleSize(5000f, 9000f, 1000f, 2000f))
        // A thin region is judged by its short side, so it keeps its detail there.
        assertEquals(1, RegionMath.sampleSize(8000f, 500f, 1000f, 2000f))
    }
}
