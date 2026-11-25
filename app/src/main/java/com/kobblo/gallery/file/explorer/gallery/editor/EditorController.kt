package com.kobblo.gallery.file.explorer.gallery.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

enum class EditorMode { ADJUST, FILTERS, CROP, BRUSH }

/** The tools on the Adjust row. */
enum class AdjustTool { EXPOSURE, BRIGHTNESS, CONTRAST, SATURATION, WARMTH, TINT }

fun Adjustments.valueOf(tool: AdjustTool): Float = when (tool) {
    AdjustTool.EXPOSURE -> exposure
    AdjustTool.BRIGHTNESS -> brightness
    AdjustTool.CONTRAST -> contrast
    AdjustTool.SATURATION -> saturation
    AdjustTool.WARMTH -> warmth
    AdjustTool.TINT -> tint
}

fun Adjustments.with(tool: AdjustTool, value: Float): Adjustments = when (tool) {
    AdjustTool.EXPOSURE -> copy(exposure = value)
    AdjustTool.BRIGHTNESS -> copy(brightness = value)
    AdjustTool.CONTRAST -> copy(contrast = value)
    AdjustTool.SATURATION -> copy(saturation = value)
    AdjustTool.WARMTH -> copy(warmth = value)
    AdjustTool.TINT -> copy(tint = value)
}

/** The preview never needs more than this, and it keeps every photo quick to work on. */
private const val PreviewMaxSide = 2048

/**
 * One editing session. The preview is a copy small enough to draw at full speed, and the photo itself
 * is only read again when saving. [state] follows the finger, and [commit] is what makes an undo step,
 * so a slider drag undoes as one.
 */
@Stable
internal class EditorController(
    private val context: Context,
    val path: String,
    private val scope: CoroutineScope
) {
    private val history = EditHistory()

    var state by mutableStateOf(EditState())
        private set
    var mode by mutableStateOf(EditorMode.ADJUST)
    var adjustTool by mutableStateOf(AdjustTool.BRIGHTNESS)
    var brushMode by mutableStateOf(BrushMode.PIXELATE)
    var brushSize by mutableFloatStateOf(0.45f)

    var loadFailed by mutableStateOf(false)
        private set

    /** The photo at preview size, untouched. */
    var preview by mutableStateOf<Bitmap?>(null)
        private set

    /** The preview with the brush strokes painted on. The same object as [preview] until there are any. */
    var working by mutableStateOf<Bitmap?>(null)
        private set

    /** Bumped when [working] is drawn on, since its pixels change without it becoming a new object. */
    var workingVersion by mutableIntStateOf(0)
        private set

    /** The photo's real size, upright. */
    var fullWidth by mutableIntStateOf(0)
        private set
    var fullHeight by mutableIntStateOf(0)
        private set

    var effects: BrushEffects? = null
        private set

    /** The stroke the finger is drawing, as alternating x and y fractions of the photo. */
    var liveStroke by mutableStateOf<List<Float>?>(null)
    var liveRadius by mutableFloatStateOf(0f)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    fun load() {
        scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                try {
                    val bounds = EditorDecoder.boundsOf(path)
                    if (bounds != null) {
                        val longSide = max(bounds.width, bounds.height)
                        var sample = 1
                        while (longSide / (sample * 2) >= PreviewMaxSide) sample *= 2
                        val decoded = EditorDecoder.decode(path, sample)
                        decoded?.let { Triple(fitPreview(it), bounds.width, bounds.height) }
                    } else {
                        val decoded = EditorDecoder.decodeThroughCoil(context, path, PreviewMaxSide)
                        decoded?.let { Triple(it, it.width, it.height) }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PhotoEditor", "Couldn't open the photo", e)
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
            if (loaded == null) {
                loadFailed = true
                return@launch
            }
            preview = loaded.first
            working = loaded.first
            effects = BrushEffects(loaded.first)
            fullWidth = loaded.second
            fullHeight = loaded.third
        }
    }

    private fun fitPreview(bitmap: Bitmap): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= PreviewMaxSide) return bitmap
        val ratio = PreviewMaxSide.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Follows a gesture without making an undo step. */
    fun preview(next: EditState) {
        state = next
    }

    /** Makes the current state an undo step. */
    fun commit(next: EditState = state) {
        state = next
        history.push(next)
        syncHistory()
    }

    fun undo() {
        apply(history.undo())
    }

    fun redo() {
        apply(history.redo())
    }

    fun reset() {
        apply(history.reset())
    }

    private fun apply(next: EditState) {
        val strokesChanged = next.strokes != state.strokes
        state = next
        syncHistory()
        if (strokesChanged) repaint(next.strokes)
    }

    private fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** Adds a finished stroke. The others are already on the picture, so only this one needs painting. */
    fun addStroke(points: List<Float>, radius: Float) {
        val source = preview ?: return
        val stroke = BrushStroke(points, radius, brushMode)
        var target = working ?: return
        if (target === source) {
            target = source.copy(Bitmap.Config.ARGB_8888, true)
            working = target
        }
        effects?.bake(target, listOf(stroke))
        liveStroke = null
        workingVersion++
        commit(state.copy(strokes = state.strokes + stroke))
    }

    /** Undoing a stroke means starting again from the clean preview. */
    private fun repaint(strokes: List<BrushStroke>) {
        val source = preview ?: return
        if (strokes.isEmpty()) {
            working = source
            workingVersion++
            return
        }
        scope.launch {
            val painted = withContext(Dispatchers.Default) {
                source.copy(Bitmap.Config.ARGB_8888, true).also { effects?.bake(it, strokes) }
            }
            // A quick second undo or redo may have overtaken this one.
            if (state.strokes == strokes) {
                working = painted
                workingVersion++
            }
        }
    }
}
