package com.kobblo.gallery.file.explorer.viewer.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The sums behind the reader, with no Android in them so they can be tested on a desktop.
 *
 * Three spaces meet here. Points are the PDF's own units, top-left origin, as the file displays the page.
 * Document pixels are the pages stacked in a column at fit-width, which is zoom 1. Content pixels are
 * document pixels at the current zoom plus the padding that keeps the first page clear of the toolbar.
 */

internal data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/** The window pages are seen through. The padding belongs to the content, and doesn't scale with it. */
internal data class PdfView(
    val width: Float,
    val height: Float,
    val paddingTop: Float = 0f,
    val paddingBottom: Float = 0f
)

/** Where the window is. [x] and [y] are content pixels, and y can get too big for a Float's precision in a long file. */
internal data class PdfCamera(val zoom: Float = 1f, val x: Float = 0f, val y: Double = 0.0)

/**
 * A place in the document that doesn't depend on the layout: which page is just under the toolbar, and how far down
 * it. Going by the toolbar's edge rather than the window's means a page's top lands in view, and the very start of a
 * document is still the very start after the layout changes.
 */
internal data class PdfAnchor(val page: Int, val fraction: Float, val xFraction: Float = 0f)

/** A page's size in points. A freak of a file could say anything, so sizes are held to something that can be drawn. */
internal class PdfPageSizes(val count: Int, defaultWidth: Float, defaultHeight: Float) {
    private val values = FloatArray(count * 2).also { array ->
        val w = sanitize(defaultWidth)
        val h = sanitize(defaultHeight)
        for (i in 0 until count) {
            array[i * 2] = w
            array[i * 2 + 1] = h
        }
    }

    fun width(page: Int): Float = values[page * 2]
    fun height(page: Int): Float = values[page * 2 + 1]

    /** Takes in pairs of width and height from [from]. Returns whether anything differed from what was assumed. */
    fun update(from: Int, pairs: FloatArray): Boolean {
        var changed = false
        var page = from
        var i = 0
        while (i + 1 < pairs.size && page < count) {
            val w = sanitize(pairs[i])
            val h = sanitize(pairs[i + 1])
            if (values[page * 2] != w || values[page * 2 + 1] != h) {
                values[page * 2] = w
                values[page * 2 + 1] = h
                changed = true
            }
            page++
            i += 2
        }
        return changed
    }

    private fun sanitize(value: Float): Float =
        if (value.isNaN() || value <= 0f) DefaultPoints else value.coerceIn(MinPoints, MaxPoints)

    private companion object {
        const val DefaultPoints = 612f
        const val MinPoints = 1f
        const val MaxPoints = 200_000f
    }
}

/**
 * The pages stacked in a column, each as wide as the window at zoom 1. Fitting each page to the width
 * means a stray small or sideways page still reads properly among the rest.
 */
internal class PdfLayout(
    private val sizes: PdfPageSizes,
    val rotation: Int,
    val pageWidth: Float,
    val gap: Float
) {
    val count: Int = sizes.count
    private val heights = FloatArray(count)

    // Doubles, because a few thousand pages at full zoom is past where a Float can tell one pixel from the next.
    private val tops = DoubleArray(count)

    val totalHeight: Double

    init {
        var y = 0.0
        for (i in 0 until count) {
            tops[i] = y
            // Never under a pixel: a page has to have somewhere to be.
            heights[i] = max(pageWidth * displayHeight(i) / displayWidth(i), 1f)
            y += heights[i] + gap
        }
        totalHeight = if (count > 0) y - gap else 0.0
    }

    /** The page's size in points as it stands on screen, which turning the view swaps the sides of. */
    fun displayWidth(page: Int): Float = if (isSideways) sizes.height(page) else sizes.width(page)
    fun displayHeight(page: Int): Float = if (isSideways) sizes.width(page) else sizes.height(page)

    private val isSideways: Boolean get() = rotation % 180 != 0

    fun pointsWidth(page: Int): Float = sizes.width(page)
    fun pointsHeight(page: Int): Float = sizes.height(page)

    fun top(page: Int): Double = tops[page]
    fun height(page: Int): Float = heights[page]

    /** The page at a document y, where a gap counts as part of the page above it. */
    fun pageAt(documentY: Double): Int {
        if (count == 0) return 0
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (tops[mid] <= documentY) low = mid else high = mid - 1
        }
        return low
    }

    /** The pages any part of which falls in the window. */
    fun visiblePages(camera: PdfCamera, view: PdfView): IntRange {
        if (count == 0) return IntRange.EMPTY
        val first = pageAt((camera.y - view.paddingTop) / camera.zoom)
        val last = pageAt((camera.y + view.height - view.paddingTop) / camera.zoom)
        return first..last
    }

    /**
     * The page the indicator names: the one a third of the way down, where eyes tend to be. The middle would name the
     * wrong one straight after going to a short page, as it fills only the top of the window.
     */
    fun currentPage(camera: PdfCamera, view: PdfView): Int {
        if (count == 0) return 0
        return pageAt((camera.y + view.height * CurrentPageLine - view.paddingTop) / camera.zoom)
    }

    /** A page's place on screen, in pixels from the window's corner. */
    fun screenRect(page: Int, camera: PdfCamera, view: PdfView): PdfRect {
        val top = (tops[page] * camera.zoom + view.paddingTop - camera.y).toFloat()
        val left = -camera.x
        return PdfRect(left, top, left + pageWidth * camera.zoom, top + heights[page] * camera.zoom)
    }

    private fun contentHeight(zoom: Float, view: PdfView): Double =
        totalHeight * zoom + view.paddingTop + view.paddingBottom

    /** Stops at the document's ends, and at the pages' sides when zoomed in. */
    fun clamp(camera: PdfCamera, view: PdfView): PdfCamera {
        val zoom = camera.zoom.coerceIn(MinZoom, MaxZoom)
        val maxX = max(pageWidth * zoom - view.width, 0f)
        val maxY = max(contentHeight(zoom, view) - view.height, 0.0)
        return PdfCamera(zoom, camera.x.coerceIn(0f, maxX), camera.y.coerceIn(0.0, maxY))
    }

    fun panned(camera: PdfCamera, dx: Float, dy: Float, view: PdfView): PdfCamera =
        clamp(camera.copy(x = camera.x + dx, y = camera.y + dy), view)

    /** Zooms so that whatever is under the focus stays under it. */
    fun zoomed(camera: PdfCamera, zoom: Float, focusX: Float, focusY: Float, view: PdfView): PdfCamera {
        val target = zoom.coerceIn(MinZoom, MaxZoom)
        val documentX = (camera.x + focusX) / camera.zoom
        val documentY = (camera.y + focusY - view.paddingTop) / camera.zoom
        return clamp(
            PdfCamera(target, documentX * target - focusX, documentY * target + view.paddingTop - focusY),
            view
        )
    }

    fun anchorOf(camera: PdfCamera, view: PdfView): PdfAnchor {
        if (count == 0) return PdfAnchor(0, 0f)
        val documentY = camera.y / camera.zoom
        val page = pageAt(documentY)
        val fraction = ((documentY - tops[page]) / heights[page]).toFloat().coerceIn(0f, 1f)
        val width = pageWidth * camera.zoom
        val xFraction = if (width > 0f) (camera.x / width).coerceIn(0f, 1f) else 0f
        return PdfAnchor(page, fraction, xFraction)
    }

    fun cameraFor(anchor: PdfAnchor, zoom: Float, view: PdfView): PdfCamera {
        if (count == 0) return PdfCamera(zoom)
        val page = anchor.page.coerceIn(0, count - 1)
        val documentY = tops[page] + heights[page] * anchor.fraction
        return clamp(
            PdfCamera(zoom, anchor.xFraction * pageWidth * zoom, documentY * zoom),
            view
        )
    }

    /** A camera with a place in points in view, a little down from the top, and on screen sideways if zoomed. */
    fun cameraShowing(page: Int, rect: PdfRect, current: PdfCamera, view: PdfView): PdfCamera {
        val shown = PdfGeometry.pointsToPage(rect, this, page)
        val y = (tops[page] + shown.top) * current.zoom + view.paddingTop - view.height / 3.0
        val visibleLeft = current.x / current.zoom
        val visibleRight = (current.x + view.width) / current.zoom
        val x = if (shown.left >= visibleLeft && shown.right <= visibleRight) {
            current.x
        } else {
            (shown.left + shown.width / 2f) * current.zoom - view.width / 2f
        }
        return clamp(PdfCamera(current.zoom, x, y), view)
    }

    companion object {
        const val MinZoom = 1f
        const val MaxZoom = 8f
        private const val CurrentPageLine = 0.3
    }
}

/** A bitmap to draw: its size, and how the page's points map onto it. */
internal class PdfRenderSpec(val width: Int, val height: Int, val matrix: FloatArray)

internal object PdfGeometry {
    /** Past this a whole page isn't worth holding: a sharper window on it comes from a detail layer instead. */
    const val MaxBaseWidth = 1600
    const val MaxBasePixels = 4_000_000

    /** A detail layer's most, which is about one and a half full-HD-plus screens. */
    const val MaxDetailPixels = 8_000_000

    /** How much past the window a detail layer reaches, so a small pan stays sharp. */
    const val DetailMargin = 0.15f

    /** The transform of points to pixels at [scale], for the view turned by [rotation], with the picture moved up and left by the offset. */
    fun matrix(
        pointsWidth: Float,
        pointsHeight: Float,
        rotation: Int,
        scale: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): FloatArray {
        // Each row is a, b, c: x' = a*x + b*y + c.
        val (ax, bx, cx, ay, by, cy) = when (rotation.mod(360)) {
            90 -> Sextuple(0f, -scale, pointsHeight * scale, scale, 0f, 0f)
            180 -> Sextuple(-scale, 0f, pointsWidth * scale, 0f, -scale, pointsHeight * scale)
            270 -> Sextuple(0f, scale, 0f, -scale, 0f, pointsWidth * scale)
            else -> Sextuple(scale, 0f, 0f, 0f, scale, 0f)
        }
        return floatArrayOf(ax, bx, cx - offsetX, ay, by, cy - offsetY)
    }

    private data class Sextuple(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)

    /** A whole page at fit-width, capped so that even a page as long as a receipt roll fits in memory. */
    fun baseSpec(layout: PdfLayout, page: Int): PdfRenderSpec {
        val displayWidth = layout.displayWidth(page)
        val displayHeight = layout.displayHeight(page)
        var scale = min(layout.pageWidth, MaxBaseWidth.toFloat()) / displayWidth
        val pixels = displayWidth * scale * displayHeight * scale
        if (pixels > MaxBasePixels) scale *= sqrt(MaxBasePixels / pixels)
        val width = max((displayWidth * scale).toInt(), 1)
        val height = max((displayHeight * scale).toInt(), 1)
        // Scales by how the pixels actually rounded, so the page's edges land on the bitmap's.
        val exact = width / displayWidth
        return PdfRenderSpec(
            width,
            height,
            matrix(layout.pointsWidth(page), layout.pointsHeight(page), layout.rotation, exact)
        )
    }

    /** Whether the whole-page bitmap is too coarse for this zoom. */
    fun needsDetail(layout: PdfLayout, page: Int, zoom: Float): Boolean =
        layout.pageWidth * zoom > baseSpec(layout, page).width * DetailThreshold

    private const val DetailThreshold = 1.1f

    /**
     * The part of a page worth rendering sharply, in page pixels at this zoom: what's in the window and a
     * margin around it, within limits. Null when none of the page is in the window.
     */
    fun detailRegion(layout: PdfLayout, page: Int, camera: PdfCamera, view: PdfView): PdfRect? {
        val screen = layout.screenRect(page, camera, view)
        val visibleLeft = max(0f, -screen.left)
        val visibleTop = max(0f, -screen.top)
        val visibleRight = min(screen.width, view.width - screen.left)
        val visibleBottom = min(screen.height, view.height - screen.top)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null

        var marginX = view.width * DetailMargin
        var marginY = view.height * DetailMargin
        var region = expandedWithin(visibleLeft, visibleTop, visibleRight, visibleBottom, marginX, marginY, screen)
        // A huge screen might not fit with its margins. Only the window itself has to.
        if (region.width * region.height > MaxDetailPixels) {
            marginX = 0f
            marginY = 0f
            region = expandedWithin(visibleLeft, visibleTop, visibleRight, visibleBottom, marginX, marginY, screen)
        }
        return region
    }

    private fun expandedWithin(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        marginX: Float,
        marginY: Float,
        page: PdfRect
    ) = PdfRect(
        max(0f, left - marginX),
        max(0f, top - marginY),
        min(page.width, right + marginX),
        min(page.height, bottom + marginY)
    )

    fun detailSpec(layout: PdfLayout, page: Int, zoom: Float, region: PdfRect): PdfRenderSpec {
        val scale = layout.pageWidth * zoom / layout.displayWidth(page)
        return PdfRenderSpec(
            max(region.width.toInt(), 1),
            max(region.height.toInt(), 1),
            matrix(layout.pointsWidth(page), layout.pointsHeight(page), layout.rotation, scale, region.left, region.top)
        )
    }

    /** Whether a detail layer made for this zoom and region still does for the window. */
    fun detailStillServes(
        layout: PdfLayout,
        page: Int,
        tileZoom: Float,
        tileRegion: PdfRect,
        camera: PdfCamera,
        view: PdfView
    ): Boolean {
        if (kotlin.math.abs(tileZoom - camera.zoom) / camera.zoom > 0.02f) return false
        val wanted = layout.screenRect(page, camera, view)
        val visibleLeft = max(0f, -wanted.left)
        val visibleTop = max(0f, -wanted.top)
        val visibleRight = min(wanted.width, view.width - wanted.left)
        val visibleBottom = min(wanted.height, view.height - wanted.top)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return true
        val scale = tileZoom / camera.zoom
        // A pixel's slack, or rounding would have it render over and over for no difference.
        return visibleLeft * scale >= tileRegion.left - 1f &&
            visibleTop * scale >= tileRegion.top - 1f &&
            visibleRight * scale <= tileRegion.right + 1f &&
            visibleBottom * scale <= tileRegion.bottom + 1f
    }

    /** A rectangle in points to document pixels within its page at zoom 1, for the view as it's turned. */
    fun pointsToPage(rect: PdfRect, layout: PdfLayout, page: Int): PdfRect {
        val w = layout.pointsWidth(page)
        val h = layout.pointsHeight(page)
        val scale = layout.pageWidth / layout.displayWidth(page)
        return when (layout.rotation.mod(360)) {
            90 -> PdfRect((h - rect.bottom) * scale, rect.left * scale, (h - rect.top) * scale, rect.right * scale)
            180 -> PdfRect((w - rect.right) * scale, (h - rect.bottom) * scale, (w - rect.left) * scale, (h - rect.top) * scale)
            270 -> PdfRect(rect.top * scale, (w - rect.right) * scale, rect.bottom * scale, (w - rect.left) * scale)
            else -> PdfRect(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
        }
    }

    /** The inverse of [pointsToPage] for a single spot: a place on the page in zoom-1 pixels to points. */
    fun pageToPoints(x: Float, y: Float, layout: PdfLayout, page: Int): Pair<Float, Float> {
        val w = layout.pointsWidth(page)
        val h = layout.pointsHeight(page)
        val scale = layout.pageWidth / layout.displayWidth(page)
        val px = x / scale
        val py = y / scale
        return when (layout.rotation.mod(360)) {
            90 -> py to h - px
            180 -> w - px to h - py
            270 -> w - py to px
            else -> px to py
        }
    }
}
