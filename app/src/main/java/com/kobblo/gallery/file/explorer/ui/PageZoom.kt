package com.kobblo.gallery.file.explorer.ui

/**
 * How far a document page is enlarged, and how far it has been dragged off center while it is.
 *
 * A page at its natural size sits still, so that a drag on it turns the page. Once enlarged it can be
 * dragged, but only until its edge reaches the edge of the area it's shown in. Every change gives back
 * a new value, which keeps the sums here free of Compose and testable on a desktop.
 */
internal data class PageZoom(val scale: Float = Min, val x: Float = 0f, val y: Float = 0f) {

    /** A press of the zoom-in button. */
    fun steppedIn(): PageZoom = copy(scale = (scale * ButtonStep).coerceAtMost(Max))

    /** A press of the zoom-out button. Back at its natural size the page returns to the middle. */
    fun steppedOut(): PageZoom {
        val smaller = (scale / ButtonStep).coerceAtLeast(Min)
        return if (smaller == Min) PageZoom() else copy(scale = smaller)
    }

    /** A pinch by [factor] on a page that can't be dragged yet. */
    fun pinched(factor: Float): PageZoom = copy(scale = (scale * factor).coerceIn(Min, Max))

    /**
     * A pinch by [factor] and a drag of [panX], [panY] on an enlarged page shown in an area [width] by
     * [height]. The drag is in the page's own pixels, so it covers more ground the more it's enlarged.
     */
    fun transformed(factor: Float, panX: Float, panY: Float, width: Float, height: Float): PageZoom {
        val enlarged = (scale * factor).coerceIn(Min, Max)
        if (enlarged <= Min) return PageZoom()
        val reachX = (enlarged - 1f) * width / 2
        val reachY = (enlarged - 1f) * height / 2
        return PageZoom(
            scale = enlarged,
            x = (x + panX * enlarged).coerceIn(-reachX, reachX),
            y = (y + panY * enlarged).coerceIn(-reachY, reachY)
        )
    }

    companion object {
        const val Min = 1f
        const val Max = 5f
        private const val ButtonStep = 1.5f
    }
}
