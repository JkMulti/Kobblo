package com.kobblo.gallery.file.explorer.viewer.pdf

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val DoubleTapZoom = 2.5f

/**
 * Night reading: dark where the paper is white, while hues stay where they were, so a photo looks odd but is
 * still recognizably of what it's of and a chart keeps its colors' meanings. The range is pulled in a little,
 * as pure white on pure black is harsh in the dark.
 */
private val DarkPageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f * Dim, -1.430f * Dim, -0.144f * Dim, 0f, Bright,
            -0.426f * Dim, -0.430f * Dim, -0.144f * Dim, 0f, Bright,
            -0.426f * Dim, -1.430f * Dim, 0.856f * Dim, 0f, Bright,
            0f, 0f, 0f, 1f, 0f
        )
    )
)
private const val Dim = 0.86f
private const val Bright = 237f

/**
 * All the pages, drawn in one canvas that knows only the camera, as opposed to a list of composables. That keeps
 * a page count of any size costing nothing, and lets one drag move both ways across page boundaries at any zoom.
 */
@Composable
internal fun PdfPagesCanvas(
    state: PdfReaderState,
    search: PdfSearchState,
    selection: PdfSelectionState,
    darkPages: Boolean,
    highlight: Color,
    currentHighlight: Color,
    failedPageMessage: String,
    failedPageColor: Color,
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val reduced = ExpressiveMotion.isReducedMotion()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val fling = remember { arrayOfNulls<Job>(1) }
    val measurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { currentOnTap(it) },
                    onLongPress = { currentOnLongPress(it) },
                    onDoubleTap = { tap ->
                        fling[0]?.cancel()
                        val start = state.camera
                        val target = if (start.zoom > 1.05f) 1f else DoubleTapZoom
                        scope.launch {
                            animate(
                                initialValue = start.zoom,
                                targetValue = target,
                                animationSpec = if (reduced) snap() else KobbloMotion.smooth()
                            ) { value, _ -> state.zoomFrom(start, value, tap.x, tap.y) }
                        }
                    }
                )
            }
            .pointerInput(state) {
                val decay = splineBasedDecay<Float>(this)
                val slop = viewConfiguration.touchSlop
                val maxFling = viewConfiguration.maximumFlingVelocity
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fling[0]?.cancel()
                    state.fingersDown = true
                    val handle = selection.handleAt(state, down.position)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var moved = false
                    var pinched = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (handle != null) {
                            // Moving an end of a selection, rather than the page under it.
                            val change = event.changes.firstOrNull { it.id == down.id && it.pressed } ?: break
                            selection.dragHandle(state, handle, change.position)
                            change.consume()
                        } else if (fingers >= 2) {
                            val centroid = event.calculateCentroid()
                            if (centroid.isSpecified) {
                                pinched = true
                                moved = true
                                state.transformBy(event.calculateZoom(), centroid.x, centroid.y, event.calculatePan().x, event.calculatePan().y)
                            }
                            event.changes.forEach { it.consume() }
                            tracker.resetTracking()
                        } else if (fingers == 1) {
                            val change = event.changes.first { it.pressed }
                            val delta = change.positionChange()
                            if (!moved) {
                                travelled += delta
                                if (travelled.getDistance() > slop) moved = true
                            }
                            if (moved) {
                                state.panBy(-delta.x, -delta.y)
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    state.fingersDown = false
                    if (moved && !pinched && handle == null) {
                        val velocity = tracker.calculateVelocity(androidx.compose.ui.unit.Velocity(maxFling, maxFling))
                        fling[0] = scope.launch {
                            launch {
                                var last = 0f
                                AnimationState(0f, -velocity.y).animateDecay(decay) {
                                    val step = value - last
                                    last = value
                                    if (!state.panBy(0f, step) && step != 0f) cancelAnimation()
                                }
                            }
                            if (state.camera.zoom > 1.01f) {
                                launch {
                                    var last = 0f
                                    AnimationState(0f, -velocity.x).animateDecay(decay) {
                                        val step = value - last
                                        last = value
                                        if (!state.panBy(step, 0f) && step != 0f) cancelAnimation()
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // Read here, so that rendering a page redraws this and nothing else.
        val revision = state.redraw + search.revision + selection.revision
        if (revision < 0) return@Canvas
        val layout = state.layout ?: return@Canvas
        val camera = state.camera
        val view = state.view
        val filter = if (darkPages) DarkPageFilter else null
        for (page in layout.visiblePages(camera, view)) {
            val rect = layout.screenRect(page, camera, view)
            drawRect(Color.White, Offset(rect.left, rect.top), Size(rect.width, rect.height), colorFilter = filter)
            val whole = state.pageBitmap(page)
            if (whole != null) {
                val left = floor(rect.left)
                val top = floor(rect.top)
                drawImage(
                    image = whole.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(whole.width, whole.height),
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(ceil(rect.right - left).toInt(), ceil(rect.bottom - top).toInt()),
                    filterQuality = FilterQuality.Low,
                    colorFilter = filter
                )
            } else if (state.isPageFailed(page)) {
                drawFailedPage(measurer, failedPageMessage, failedPageColor, rect)
            }
            state.detailTile(page)?.let { tile ->
                val scale = camera.zoom / tile.zoom
                val left = rect.left + tile.region.left * scale
                val top = rect.top + tile.region.top * scale
                drawImage(
                    image = tile.image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tile.image.width, tile.image.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(max((tile.region.width * scale).roundToInt(), 1), max((tile.region.height * scale).roundToInt(), 1)),
                    filterQuality = FilterQuality.Low,
                    colorFilter = filter
                )
            }
            drawHits(search, layout, page, rect, camera.zoom, highlight, currentHighlight)
            selection.draw(this, layout, page, rect, camera.zoom, highlight)
        }
    }
}

private fun DrawScope.drawHits(
    search: PdfSearchState,
    layout: PdfLayout,
    page: Int,
    pageRect: PdfRect,
    zoom: Float,
    highlight: Color,
    currentHighlight: Color
) {
    val hits = search.results.onPage(page)
    if (hits.isEmpty()) return
    val current = search.results.current
    for (hit in hits) {
        val color = if (hit === current) currentHighlight else highlight
        var at = 0
        while (at + 3 < hit.rects.size) {
            val shown = PdfGeometry.pointsToPage(
                PdfRect(hit.rects[at], hit.rects[at + 1], hit.rects[at + 2], hit.rects[at + 3]),
                layout,
                page
            )
            drawRect(
                color,
                Offset(pageRect.left + shown.left * zoom, pageRect.top + shown.top * zoom),
                Size(shown.width * zoom, shown.height * zoom)
            )
            at += 4
        }
    }
}

private fun DrawScope.drawFailedPage(measurer: TextMeasurer, message: String, color: Color, rect: PdfRect) {
    val width = rect.width.toInt().coerceAtLeast(1)
    val measured = measurer.measure(
        message,
        TextStyle(color = color, fontSize = 15.sp, textAlign = TextAlign.Center),
        constraints = Constraints(maxWidth = width)
    )
    drawText(measured, topLeft = Offset(rect.left, rect.top + (rect.height - measured.size.height) / 2f))
}
