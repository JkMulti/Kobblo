package com.kobblo.gallery.file.explorer.viewer.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfGeometryTest {
    private val view = PdfView(width = 1080f, height = 2400f, paddingTop = 200f, paddingBottom = 100f)

    private fun uniform(count: Int = 1000, rotation: Int = 0): PdfLayout =
        PdfLayout(PdfPageSizes(count, 612f, 792f), rotation, view.width, gap = 10f)

    @Test
    fun `pages stack at fit width with gaps between`() {
        val layout = uniform(3)
        val height = 1080f * 792f / 612f
        assertEquals(height, layout.height(0), 0.01f)
        assertEquals(0.0, layout.top(0), 0.001)
        assertEquals(height + 10.0, layout.top(1), 0.01)
        assertEquals(height * 3 + 20.0, layout.totalHeight, 0.05)
    }

    @Test
    fun `a page is found by where it is, with a gap belonging to the page above`() {
        val layout = uniform(3)
        assertEquals(0, layout.pageAt(0.0))
        assertEquals(0, layout.pageAt(layout.top(1) - 1.0))
        assertEquals(1, layout.pageAt(layout.top(1)))
        assertEquals(2, layout.pageAt(layout.totalHeight + 500.0))
        assertEquals(0, layout.pageAt(-40.0))
    }

    @Test
    fun `memory stays out of it, only the pages in the window count`() {
        val layout = uniform(100_000)
        val camera = layout.cameraFor(PdfAnchor(50_000, 0f), 1f, view)
        val visible = layout.visiblePages(camera, view)
        assertTrue(50_000 in visible)
        assertTrue("window of ${visible.count()} pages", visible.count() <= 3)
    }

    @Test
    fun `a page deep in a very long file keeps its place at full zoom`() {
        val layout = uniform(100_000)
        val anchor = PdfAnchor(99_000, 0.25f, 0.5f)
        val camera = layout.cameraFor(anchor, PdfLayout.MaxZoom, view)
        val back = layout.anchorOf(camera, view)
        assertEquals(99_000, back.page)
        assertEquals(0.25f, back.fraction, 0.001f)
    }

    @Test
    fun `the camera stops at the document's ends and the page's sides`() {
        val layout = uniform(3)
        assertEquals(0.0, layout.clamp(PdfCamera(1f, 0f, -500.0), view).y, 0.001)
        val end = layout.totalHeight + view.paddingTop + view.paddingBottom - view.height
        assertEquals(end, layout.clamp(PdfCamera(1f, 0f, 1e9), view).y, 0.01)
        // At fit width there's nothing to pan to, and zoomed in there's only as far as the page goes.
        assertEquals(0f, layout.clamp(PdfCamera(1f, 300f, 0.0), view).x, 0.001f)
        assertEquals(1080f, layout.clamp(PdfCamera(2f, 5000f, 0.0), view).x, 0.001f)
    }

    @Test
    fun `a short document has nothing to scroll`() {
        val layout = PdfLayout(PdfPageSizes(1, 800f, 100f), 0, view.width, 10f)
        assertEquals(0.0, layout.clamp(PdfCamera(1f, 0f, 400.0), view).y, 0.001)
    }

    @Test
    fun `zooming keeps what's under the fingers under them`() {
        val layout = uniform()
        val start = layout.cameraFor(PdfAnchor(10, 0.5f), 1f, view)
        val focusX = 300f
        val focusY = 900f
        val zoomed = layout.zoomed(start, 3f, focusX, focusY, view)
        val beforeX = (start.x + focusX) / start.zoom
        val afterX = (zoomed.x + focusX) / zoomed.zoom
        assertEquals(beforeX, afterX, 0.01f)
        val beforeY = (start.y + focusY - view.paddingTop) / start.zoom
        val afterY = (zoomed.y + focusY - view.paddingTop) / zoomed.zoom
        assertEquals(beforeY, afterY, 0.01)
    }

    @Test
    fun `zoom is held to its limits`() {
        val layout = uniform()
        assertEquals(PdfLayout.MaxZoom, layout.zoomed(PdfCamera(), 100f, 0f, 0f, view).zoom, 0.001f)
        assertEquals(PdfLayout.MinZoom, layout.zoomed(PdfCamera(), 0.1f, 0f, 0f, view).zoom, 0.001f)
    }

    @Test
    fun `sizes learned later move later pages and leave the anchor alone`() {
        val sizes = PdfPageSizes(100, 612f, 792f)
        val before = PdfLayout(sizes, 0, view.width, 10f)
        val anchor = PdfAnchor(50, 0.5f)
        assertTrue(sizes.update(10, floatArrayOf(792f, 612f, 2400f, 300f)))
        val after = PdfLayout(sizes, 0, view.width, 10f)
        assertTrue(after.top(50) < before.top(50))
        val camera = after.cameraFor(anchor, 1f, view)
        assertEquals(50, after.anchorOf(camera, view).page)
        assertEquals(0.5f, after.anchorOf(camera, view).fraction, 0.001f)
    }

    @Test
    fun `reporting the same sizes changes nothing`() {
        val sizes = PdfPageSizes(4, 612f, 792f)
        assertFalse(sizes.update(0, floatArrayOf(612f, 792f, 612f, 792f)))
    }

    @Test
    fun `a nonsense size from a broken file still gives a drawable page`() {
        val sizes = PdfPageSizes(2, 612f, 792f)
        sizes.update(0, floatArrayOf(0f, Float.NaN, -3f, 1e12f))
        assertTrue(sizes.width(0) > 0f && sizes.height(0) > 0f)
        assertTrue(sizes.height(1) <= 200_000f)
        val layout = PdfLayout(sizes, 0, 1080f, 0f)
        assertTrue(layout.height(0) >= 1f)
    }

    @Test
    fun `a whole page is capped however long the page is`() {
        val sizes = PdfPageSizes(1, 200f, 20_000f)
        val layout = PdfLayout(sizes, 0, 1080f, 0f)
        val spec = PdfGeometry.baseSpec(layout, 0)
        assertTrue("${spec.width}x${spec.height}", spec.width.toLong() * spec.height <= PdfGeometry.MaxBasePixels.toLong() + spec.width + spec.height)
        assertTrue(PdfGeometry.needsDetail(layout, 0, 1f))
    }

    @Test
    fun `a wide screen holds the page at the cap and sharpens the rest with a detail layer`() {
        val layout = PdfLayout(PdfPageSizes(1, 612f, 792f), 0, 2400f, 0f)
        val spec = PdfGeometry.baseSpec(layout, 0)
        assertEquals(PdfGeometry.MaxBaseWidth, spec.width)
        assertTrue(PdfGeometry.needsDetail(layout, 0, 1f))
    }

    @Test
    fun `at fit width on a phone the whole page is sharp enough`() {
        val layout = uniform(1)
        assertFalse(PdfGeometry.needsDetail(layout, 0, 1f))
        assertTrue(PdfGeometry.needsDetail(layout, 0, 2f))
    }

    @Test
    fun `the detail layer covers the window and a margin, and no more than the page`() {
        val layout = uniform()
        val camera = layout.cameraFor(PdfAnchor(10, 0.2f), 4f, view)
        val region = assertNotNullRegion(PdfGeometry.detailRegion(layout, 10, camera, view))
        val screen = layout.screenRect(10, camera, view)
        // The window in page pixels.
        val visibleLeft = -screen.left
        val visibleRight = view.width - screen.left
        assertTrue(region.left <= visibleLeft)
        assertTrue(region.right >= visibleRight)
        assertTrue(region.left >= 0f && region.right <= screen.width)
        assertTrue(region.width * region.height <= PdfGeometry.MaxDetailPixels)
    }

    private fun assertNotNullRegion(region: PdfRect?): PdfRect {
        assertNotNull(region)
        return region!!
    }

    @Test
    fun `a page out of the window needs no detail`() {
        val layout = uniform()
        val camera = layout.cameraFor(PdfAnchor(10, 0.2f), 4f, view)
        assertNull(PdfGeometry.detailRegion(layout, 500, camera, view))
    }

    @Test
    fun `a detail layer stops serving when the window leaves it or the zoom changes`() {
        val layout = uniform()
        val camera = layout.cameraFor(PdfAnchor(10, 0.2f), 4f, view)
        val region = PdfGeometry.detailRegion(layout, 10, camera, view)!!
        assertTrue(PdfGeometry.detailStillServes(layout, 10, 4f, region, camera, view))
        val nudged = layout.panned(camera, 20f, 20f, view)
        assertTrue(PdfGeometry.detailStillServes(layout, 10, 4f, region, nudged, view))
        val far = layout.panned(camera, 2000f, 0f, view)
        assertFalse(PdfGeometry.detailStillServes(layout, 10, 4f, region, far, view))
        val zoomed = layout.zoomed(camera, 5f, 100f, 100f, view)
        assertFalse(PdfGeometry.detailStillServes(layout, 10, 4f, region, zoomed, view))
    }

    private fun apply(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        matrix[0] * x + matrix[1] * y + matrix[2] to matrix[3] * x + matrix[4] * y + matrix[5]

    @Test
    fun `the matrix puts a page's corners where the turned view has them`() {
        val w = 612f
        val h = 792f
        val s = 2f
        val m0 = PdfGeometry.matrix(w, h, 0, s)
        assertEquals(0f to 0f, apply(m0, 0f, 0f))
        assertEquals(w * s to h * s, apply(m0, w, h))

        // Clockwise, so the top-left goes to the top-right.
        val m90 = PdfGeometry.matrix(w, h, 90, s)
        assertEquals(h * s to 0f, apply(m90, 0f, 0f))
        assertEquals(0f to w * s, apply(m90, w, h))

        val m180 = PdfGeometry.matrix(w, h, 180, s)
        assertEquals(w * s to h * s, apply(m180, 0f, 0f))
        assertEquals(0f to 0f, apply(m180, w, h))

        val m270 = PdfGeometry.matrix(w, h, 270, s)
        assertEquals(0f to w * s, apply(m270, 0f, 0f))
        assertEquals(h * s to 0f, apply(m270, w, h))
    }

    @Test
    fun `the matrix moves the picture for a region`() {
        val m = PdfGeometry.matrix(612f, 792f, 0, 2f, offsetX = 100f, offsetY = 50f)
        assertEquals(-100f to -50f, apply(m, 0f, 0f))
    }

    @Test
    fun `a hit lands in the same place as the matrix puts its page`() {
        // Whichever way the view is turned, a rectangle mapped for highlighting has to agree with the drawing.
        for (rotation in listOf(0, 90, 180, 270)) {
            val layout = uniform(1, rotation)
            val scale = layout.pageWidth / layout.displayWidth(0)
            val matrix = PdfGeometry.matrix(612f, 792f, rotation, scale)
            val rect = PdfRect(100f, 200f, 150f, 220f)
            val shown = PdfGeometry.pointsToPage(rect, layout, 0)
            val (x1, y1) = apply(matrix, rect.left, rect.top)
            val (x2, y2) = apply(matrix, rect.right, rect.bottom)
            assertEquals("rotation $rotation", minOf(x1, x2), shown.left, 0.5f)
            assertEquals("rotation $rotation", minOf(y1, y2), shown.top, 0.5f)
            assertEquals("rotation $rotation", maxOf(x1, x2), shown.right, 0.5f)
            assertEquals("rotation $rotation", maxOf(y1, y2), shown.bottom, 0.5f)
        }
    }

    @Test
    fun `a spot on the page maps back to the points it came from`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val layout = uniform(1, rotation)
            val rect = PdfRect(100f, 200f, 100f, 200f)
            val shown = PdfGeometry.pointsToPage(rect, layout, 0)
            val (x, y) = PdfGeometry.pageToPoints(shown.left, shown.top, layout, 0)
            assertEquals("rotation $rotation", 100f, x, 0.5f)
            assertEquals("rotation $rotation", 200f, y, 0.5f)
        }
    }

    @Test
    fun `turning the view swaps the page's shape`() {
        val layout = uniform(1, 90)
        assertEquals(792f, layout.displayWidth(0), 0.001f)
        assertEquals(612f, layout.displayHeight(0), 0.001f)
        assertEquals(1080f * 612f / 792f, layout.height(0), 0.01f)
    }

    @Test
    fun `showing a hit brings it into the window`() {
        val layout = uniform()
        val rect = PdfRect(72f, 300f, 200f, 312f)
        val camera = layout.cameraShowing(600, rect, PdfCamera(), view)
        assertEquals(600, layout.currentPage(camera, view))
        val screen = layout.screenRect(600, camera, view)
        val shown = PdfGeometry.pointsToPage(rect, layout, 600)
        val y = screen.top + shown.top * camera.zoom
        assertTrue("y was $y", y > 0f && y < view.height)
    }
}
