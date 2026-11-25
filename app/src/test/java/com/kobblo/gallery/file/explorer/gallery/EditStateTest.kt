package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.gallery.editor.Adjustments
import com.kobblo.gallery.file.explorer.gallery.editor.Affine
import com.kobblo.gallery.file.explorer.gallery.editor.CropHandle
import com.kobblo.gallery.file.explorer.gallery.editor.CropRect
import com.kobblo.gallery.file.explorer.gallery.editor.EditHistory
import com.kobblo.gallery.file.explorer.gallery.editor.EditState
import com.kobblo.gallery.file.explorer.gallery.editor.FilterPreset
import com.kobblo.gallery.file.explorer.gallery.editor.Geometry
import com.kobblo.gallery.file.explorer.gallery.editor.applyColorRows
import com.kobblo.gallery.file.explorer.gallery.editor.colorRowsFor
import com.kobblo.gallery.file.explorer.gallery.editor.dragCrop
import com.kobblo.gallery.file.explorer.gallery.editor.editedFileName
import com.kobblo.gallery.file.explorer.gallery.editor.fitAspect
import com.kobblo.gallery.file.explorer.gallery.editor.frameSize
import com.kobblo.gallery.file.explorer.gallery.editor.geometryTransform
import com.kobblo.gallery.file.explorer.gallery.editor.outputSize
import com.kobblo.gallery.file.explorer.gallery.editor.sampleSizeFor
import com.kobblo.gallery.file.explorer.gallery.editor.straightenScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditStateTest {
    private val eps = 0.001f

    private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>) {
        assertEquals(x, actual.first, 0.01f)
        assertEquals(y, actual.second, 0.01f)
    }

    private fun assertCrop(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun `no edit maps every pixel to itself`() {
        val m = geometryTransform(400, 300, Geometry())
        assertPoint(0f, 0f, m.apply(0f, 0f))
        assertPoint(400f, 300f, m.apply(400f, 300f))
    }

    @Test
    fun `rotating left sends the top right corner to the top left`() {
        val g = Geometry().rotatedLeft()
        assertEquals(300 to 400, frameSize(400, 300, g))
        val m = geometryTransform(400, 300, g)
        assertPoint(0f, 0f, m.apply(400f, 0f))
        assertPoint(300f, 400f, m.apply(0f, 300f))
    }

    @Test
    fun `four left turns come back around`() {
        val crop = CropRect(0.1f, 0.2f, 0.7f, 0.9f)
        val g = Geometry(crop = crop).rotatedLeft().rotatedLeft().rotatedLeft().rotatedLeft()
        assertEquals(0, g.quarterTurns)
        assertCrop(crop, g.crop)
    }

    @Test
    fun `mirroring swaps the sides`() {
        val m = geometryTransform(400, 300, Geometry().flippedHorizontally())
        assertPoint(400f, 0f, m.apply(0f, 0f))
        val v = geometryTransform(400, 300, Geometry().flippedVertically())
        assertPoint(0f, 300f, v.apply(0f, 0f))
    }

    @Test
    fun `what's on screen is what turns, after a flip`() {
        // Flip, then rotate left. Whatever was top-right on screen after the flip ends up top-left.
        val flipped = Geometry().flippedHorizontally()
        val afterFlip = geometryTransform(400, 300, flipped)
        // The source's top-left corner is shown at the top right after the flip.
        assertPoint(400f, 0f, afterFlip.apply(0f, 0f))
        val turned = geometryTransform(400, 300, flipped.rotatedLeft())
        assertPoint(0f, 0f, turned.apply(0f, 0f))
    }

    @Test
    fun `crop follows a turn and a flip`() {
        val crop = CropRect(0.1f, 0.2f, 0.5f, 0.6f)
        assertCrop(CropRect(0.2f, 0.5f, 0.6f, 0.9f), Geometry(crop = crop).rotatedLeft().crop)
        assertCrop(CropRect(0.5f, 0.2f, 0.9f, 0.6f), Geometry(crop = crop).flippedHorizontally().crop)
        assertCrop(CropRect(0.1f, 0.4f, 0.5f, 0.8f), Geometry(crop = crop).flippedVertically().crop)
    }

    @Test
    fun `a turn inverts a locked shape`() {
        assertEquals(0.5f, Geometry(aspect = 2f).rotatedLeft().aspect!!, eps)
    }

    @Test
    fun `crop picks the picture's origin and size`() {
        val g = Geometry(crop = CropRect(0.25f, 0.5f, 0.75f, 1f))
        assertEquals(200 to 150, outputSize(400, 300, g, 1f))
        assertEquals(100 to 75, outputSize(400, 300, g, 0.5f))
        val m = geometryTransform(400, 300, g)
        assertPoint(0f, 0f, m.apply(100f, 150f))
    }

    @Test
    fun `a tilted photo always covers its frame`() {
        val w = 400f
        val h = 300f
        for (degrees in listOf(-45f, -30f, -5f, 1f, 12f, 45f)) {
            val g = Geometry(straighten = degrees)
            val m = geometryTransform(400, 300, g)
            // Each corner of the frame must land inside the photo when mapped back.
            val det = m.a * m.d - m.b * m.c
            for ((x, y) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
                val dx = x - m.e
                val dy = y - m.f
                val sx = (m.d * dx - m.c * dy) / det
                val sy = (-m.b * dx + m.a * dy) / det
                assertTrue("corner $x,$y at $degrees maps outside: $sx,$sy", sx >= -0.5f && sx <= w + 0.5f && sy >= -0.5f && sy <= h + 0.5f)
            }
        }
        assertEquals(1f, straightenScale(w, h, 0f), eps)
    }

    @Test
    fun `a flip reverses the tilt so the picture doesn't jump`() {
        assertEquals(-10f, Geometry(straighten = 10f).flippedHorizontally().straighten, eps)
    }

    @Test
    fun `transforms compose in order`() {
        val m = Affine.translate(10f, 0f).then(Affine.scale(2f))
        assertPoint(22f, 0f, m.apply(1f, 0f))
    }

    @Test
    fun `a touch maps back to the same place on the photo`() {
        val m = geometryTransform(400, 300, Geometry(straighten = 12f, crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f)).rotatedLeft())
            .then(Affine.scale(2.5f))
            .then(Affine.translate(30f, 70f))
        val (x, y) = m.apply(123f, 45f)
        assertPoint(123f, 45f, m.inverse().apply(x, y))
        assertEquals(2.5f, Affine.scale(2.5f).then(Affine.rotate(30f)).magnification, eps)
    }

    @Test
    fun `flipping an untilted photo leaves no negative zero behind`() {
        assertEquals(0f.toBits(), Geometry().flippedHorizontally().straighten.toBits())
    }

    @Test
    fun `aspect presets fit the frame`() {
        assertCrop(CropRect(0.125f, 0f, 0.875f, 1f), fitAspect(400, 300, 1f))
        assertCrop(CropRect(0f, 0.125f, 1f, 0.875f), fitAspect(300, 400, 1f))
        val wide = fitAspect(400, 300, 16f / 9f)
        assertEquals(16f / 9f, wide.width * 400 / (wide.height * 300), eps)
    }

    @Test
    fun `free crop stays in the frame and keeps a minimum size`() {
        val start = CropRect()
        assertCrop(CropRect(0.2f, 0f, 1f, 1f), dragCrop(start, CropHandle.LEFT, 0.2f, 0f, 400, 300, null))
        assertCrop(CropRect(0f, 0f, 1f, 1f), dragCrop(start, CropHandle.RIGHT, 0.5f, 0f, 400, 300, null))
        val tiny = dragCrop(CropRect(0f, 0f, 0.1f, 0.1f), CropHandle.BOTTOM_RIGHT, -0.5f, -0.5f, 400, 300, null)
        assertTrue(tiny.width > 0f && tiny.height > 0f)
    }

    @Test
    fun `moving a crop stops at the frame`() {
        val moved = dragCrop(CropRect(0.1f, 0.1f, 0.5f, 0.5f), CropHandle.MOVE, -0.5f, 0.2f, 400, 300, null)
        assertCrop(CropRect(0f, 0.3f, 0.4f, 0.7f), moved)
    }

    @Test
    fun `a locked crop keeps its shape from every handle`() {
        val frameW = 400
        val frameH = 300
        val aspect = 1f
        val start = fitAspect(frameW, frameH, aspect).let { CropRect(it.left + 0.1f, it.top + 0.1f, it.right - 0.1f, it.bottom - 0.1f) }
        for (handle in CropHandle.entries.filter { it != CropHandle.MOVE }) {
            for ((dx, dy) in listOf(0.05f to 0.05f, -0.05f to -0.05f, 0.5f to 0.5f, -0.5f to -0.5f)) {
                val out = dragCrop(start, handle, dx, dy, frameW, frameH, aspect)
                assertEquals("shape from $handle $dx", aspect, out.width * frameW / (out.height * frameH), 0.02f)
                assertTrue("in frame from $handle $dx: $out", out.left >= -eps && out.top >= -eps && out.right <= 1f + eps && out.bottom <= 1f + eps)
            }
        }
    }

    @Test
    fun `no adjustment leaves a color alone`() {
        val (r, g, b) = applyColorRows(colorRowsFor(Adjustments(), FilterPreset.ORIGINAL), 10f, 120f, 240f)
        assertEquals(10f, r, eps)
        assertEquals(120f, g, eps)
        assertEquals(240f, b, eps)
    }

    @Test
    fun `adjustments push colors the expected way`() {
        fun out(a: Adjustments, f: FilterPreset = FilterPreset.ORIGINAL) = applyColorRows(colorRowsFor(a, f), 100f, 120f, 140f)
        assertTrue(out(Adjustments(brightness = 0.5f)).first > 100f)
        assertTrue(out(Adjustments(brightness = -0.5f)).first < 100f)
        assertTrue(out(Adjustments(exposure = 0.5f)).second > 120f)
        assertTrue(out(Adjustments(warmth = 1f)).first > 100f)
        assertTrue(out(Adjustments(warmth = 1f)).third < 140f)
        assertTrue(out(Adjustments(warmth = -1f)).third > 140f)
        // Contrast pivots around the middle.
        val bright = applyColorRows(colorRowsFor(Adjustments(contrast = 1f), FilterPreset.ORIGINAL), 200f, 200f, 200f)
        val dark = applyColorRows(colorRowsFor(Adjustments(contrast = 1f), FilterPreset.ORIGINAL), 50f, 50f, 50f)
        assertTrue(bright.first > 200f)
        assertTrue(dark.first < 50f)
    }

    @Test
    fun `removing all saturation makes gray`() {
        val (r, g, b) = applyColorRows(colorRowsFor(Adjustments(saturation = -1f), FilterPreset.ORIGINAL), 200f, 50f, 10f)
        assertEquals(r, g, 0.1f)
        assertEquals(g, b, 0.1f)
        val (mr, mg, mb) = applyColorRows(colorRowsFor(Adjustments(), FilterPreset.MONO), 200f, 50f, 10f)
        assertEquals(mr, mg, 0.1f)
        assertEquals(mg, mb, 0.1f)
    }

    @Test
    fun `adjustments apply before the filter`() {
        // Pure red through "make it gray" then "brighten" differs from the other order only by the offset.
        val rows = colorRowsFor(Adjustments(brightness = 1f), FilterPreset.MONO)
        val (r, g, b) = applyColorRows(rows, 0f, 0f, 0f)
        assertEquals(r, g, 0.1f)
        assertEquals(g, b, 0.1f)
        assertEquals(70f, r, 0.1f)
    }

    @Test
    fun `undo and redo walk the states and reset can be undone`() {
        val history = EditHistory()
        val one = EditState(filter = FilterPreset.MONO)
        val two = one.copy(outputScale = 0.5f)
        assertFalse(history.canUndo)
        history.push(one)
        history.push(two)
        assertEquals(one, history.undo())
        assertTrue(history.canRedo)
        assertEquals(two, history.redo())
        assertEquals(EditState(), history.reset())
        assertEquals(two, history.undo())
    }

    @Test
    fun `an unchanged state adds no undo step, and a new edit clears redo`() {
        val history = EditHistory()
        history.push(EditState())
        assertFalse(history.canUndo)
        history.push(EditState(filter = FilterPreset.NOIR))
        history.undo()
        history.push(EditState(filter = FilterPreset.SEPIA))
        assertFalse(history.canRedo)
    }

    @Test
    fun `edited names never collide`() {
        assertEquals("IMG_1_edited.jpg", editedFileName("IMG_1.jpg", "jpg") { false })
        val taken = setOf("IMG_1_edited.jpg", "IMG_1_edited_2.jpg")
        assertEquals("IMG_1_edited_3.jpg", editedFileName("IMG_1.jpg", "jpg") { it in taken })
        // Editing an edit doesn't stack up suffixes.
        assertEquals("IMG_1_edited_2.jpg", editedFileName("IMG_1_edited.jpg", "jpg") { it == "IMG_1_edited.jpg" })
        assertEquals("a_edited.png", editedFileName("a.heic", "png") { false })
    }

    @Test
    fun `big photos sample down to fit the budget`() {
        assertEquals(1, sampleSizeFor(4000, 3000, 20_000_000))
        assertEquals(2, sampleSizeFor(12000, 9000, 40_000_000))
        assertEquals(4, sampleSizeFor(12000, 9000, 10_000_000))
    }
}
