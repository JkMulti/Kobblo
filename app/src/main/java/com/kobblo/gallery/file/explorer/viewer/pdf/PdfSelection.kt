package com.kobblo.gallery.file.explorer.viewer.pdf

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Finding a place in a page's text from a place on the page. */
internal object PdfSelectionLogic {
    /** The character nearest a point in points, or -1 if there's none on the page at all. */
    fun nearestChar(rects: FloatArray, x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        var index = 0
        var at = 0
        while (at + 3 < rects.size) {
            val left = rects[at]
            val top = rects[at + 1]
            val right = rects[at + 2]
            val bottom = rects[at + 3]
            if (right > left && bottom > top) {
                val dx = if (x < left) left - x else if (x > right) x - right else 0f
                val dy = if (y < top) top - y else if (y > bottom) y - bottom else 0f
                // Lines are close together and long, so being on the right line matters far more than where along it.
                val distance = dx + dy * LinePreference
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            index++
            at += 4
        }
        return best
    }

    /** The word around a character, up to but not including where it ends. */
    fun wordAround(text: String, index: Int): IntRange {
        if (index !in text.indices) return IntRange.EMPTY
        if (!text[index].isLetterOrDigit()) return index..index
        var start = index
        var end = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.lastIndex && isWordChar(text[end + 1])) end++
        return start..end
    }

    private fun isWordChar(char: Char) = char.isLetterOrDigit() || char == '\'' || char == '’'

    private const val LinePreference = 4f
}

/**
 * Selecting text on one page. Characters and their places come from PDFBox on every Android version, so this
 * works the same on an old phone as a new one. Across pages isn't offered: Copy Page Text covers a page at a time.
 */
@Stable
internal class PdfSelectionState(private val handleReachPx: Float) {
    enum class Handle { Start, End }

    var page by mutableIntStateOf(-1)
        private set
    private var chars: PdfPageChars? = null
    var start by mutableIntStateOf(0)
        private set
    var end by mutableIntStateOf(0)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private var markRects = FloatArray(0)

    var isLoading by mutableStateOf(false)

    val isActive: Boolean get() = page >= 0

    val selectedText: String
        get() {
            val text = chars?.text ?: return ""
            return text.substring(start.coerceIn(0, text.length), (end + 1).coerceIn(0, text.length)).trim()
        }

    fun clear() {
        page = -1
        chars = null
        markRects = FloatArray(0)
        revision++
    }

    /** Starts on the word at this spot, in points. Returns false if there's nothing there to select. */
    fun begin(pageIndex: Int, pageChars: PdfPageChars, x: Float, y: Float): Boolean {
        val nearest = PdfSelectionLogic.nearestChar(pageChars.rects, x, y)
        if (nearest < 0) return false
        val word = PdfSelectionLogic.wordAround(pageChars.text, nearest)
        if (word.isEmpty()) return false
        page = pageIndex
        chars = pageChars
        start = word.first
        end = word.last
        refresh()
        return true
    }

    fun selectAllOnPage() {
        val text = chars?.text ?: return
        start = 0
        end = text.lastIndex
        refresh()
    }

    private fun refresh() {
        markRects = chars?.let { PdfTextMatch.rectsFor(it.rects, start, end + 1) } ?: FloatArray(0)
        revision++
    }

    private fun handleSpot(state: PdfReaderState, which: Handle): Offset? {
        val layout = state.layout ?: return null
        if (!isActive || markRects.size < 4 || page >= layout.count) return null
        val at = if (which == Handle.Start) 0 else markRects.size - 4
        val shown = PdfGeometry.pointsToPage(
            PdfRect(markRects[at], markRects[at + 1], markRects[at + 2], markRects[at + 3]),
            layout,
            page
        )
        val pageRect = layout.screenRect(page, state.camera, state.view)
        val zoom = state.camera.zoom
        return if (which == Handle.Start) {
            Offset(pageRect.left + shown.left * zoom, pageRect.top + shown.top * zoom)
        } else {
            Offset(pageRect.left + shown.right * zoom, pageRect.top + shown.bottom * zoom)
        }
    }

    fun handleAt(state: PdfReaderState, position: Offset): Handle? {
        if (!isActive) return null
        for (which in listOf(Handle.End, Handle.Start)) {
            val spot = handleSpot(state, which) ?: continue
            if ((spot - position).getDistance() < handleReachPx) return which
        }
        return null
    }

    fun dragHandle(state: PdfReaderState, which: Handle, position: Offset) {
        val layout = state.layout ?: return
        val pageChars = chars ?: return
        val pageRect = layout.screenRect(page, state.camera, state.view)
        val zoom = state.camera.zoom
        val (x, y) = PdfGeometry.pageToPoints((position.x - pageRect.left) / zoom, (position.y - pageRect.top) / zoom, layout, page)
        val nearest = PdfSelectionLogic.nearestChar(pageChars.rects, x, y)
        if (nearest < 0) return
        // An end can't pass the other, so the selection never turns inside out under the finger.
        when (which) {
            Handle.Start -> start = nearest.coerceAtMost(end)
            Handle.End -> end = nearest.coerceAtLeast(start)
        }
        refresh()
    }

    fun draw(scope: DrawScope, layout: PdfLayout, pageIndex: Int, pageRect: PdfRect, zoom: Float, color: Color) {
        if (pageIndex != page || markRects.size < 4) return
        var at = 0
        while (at + 3 < markRects.size) {
            val shown = PdfGeometry.pointsToPage(
                PdfRect(markRects[at], markRects[at + 1], markRects[at + 2], markRects[at + 3]),
                layout,
                pageIndex
            )
            scope.drawRect(
                color,
                Offset(pageRect.left + shown.left * zoom, pageRect.top + shown.top * zoom),
                Size(shown.width * zoom, shown.height * zoom)
            )
            at += 4
        }
        val handleColor = color.copy(alpha = 1f)
        val radius = 5f * scope.density
        val startRect = PdfGeometry.pointsToPage(PdfRect(markRects[0], markRects[1], markRects[2], markRects[3]), layout, pageIndex)
        val last = markRects.size - 4
        val endRect = PdfGeometry.pointsToPage(PdfRect(markRects[last], markRects[last + 1], markRects[last + 2], markRects[last + 3]), layout, pageIndex)
        val startTop = Offset(pageRect.left + startRect.left * zoom, pageRect.top + startRect.top * zoom)
        val startBottom = Offset(startTop.x, pageRect.top + startRect.bottom * zoom)
        scope.drawLine(handleColor, startTop, startBottom, strokeWidth = 2f * scope.density)
        scope.drawCircle(handleColor, radius, startTop)
        val endTop = Offset(pageRect.left + endRect.right * zoom, pageRect.top + endRect.top * zoom)
        val endBottom = Offset(endTop.x, pageRect.top + endRect.bottom * zoom)
        scope.drawLine(handleColor, endTop, endBottom, strokeWidth = 2f * scope.density)
        scope.drawCircle(handleColor, radius, endBottom)
    }
}
