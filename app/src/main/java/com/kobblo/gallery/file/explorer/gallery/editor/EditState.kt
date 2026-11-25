package com.kobblo.gallery.file.explorer.gallery.editor

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything an edit is, as plain values. Edits are never applied to the photo until saving, so undo
 * is just going back to an earlier one of these, and the preview and the saved file are drawn from the
 * same numbers.
 */
@Immutable
data class EditState(
    val geometry: Geometry = Geometry(),
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterPreset = FilterPreset.ORIGINAL,
    val strokes: List<BrushStroke> = emptyList(),
    /** How much of the full size to save at, from 0 to 1. */
    val outputScale: Float = 1f
) {
    val isUnchanged: Boolean get() = this == EditState()
}

/** Each runs from -1 to 1, and 0 leaves the photo alone. */
@Immutable
data class Adjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f
)

enum class FilterPreset { ORIGINAL, VIVID, WARM, COOL, DRAMATIC, FADE, MONO, NOIR, SEPIA }

enum class BrushMode { BLUR, PIXELATE }

/**
 * One stroke of the brush. Points are alternating x and y as fractions of the upright photo, and the
 * radius is a fraction of its short side, so the same stroke fits the preview and the full-size file.
 */
@Immutable
data class BrushStroke(val points: List<Float>, val radius: Float, val mode: BrushMode)

/** Fractions of the frame the photo fills once rotated. */
@Immutable
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The photo's rotation, mirroring, tilt and crop.
 *
 * Quarter turns happen first, then mirroring, then the tilt, each as seen on screen. Holding them in a
 * fixed order means a later rotate or flip has to rewrite the others so that what's on screen is what
 * turns, and [rotatedLeft], [flippedHorizontally] and [flippedVertically] do that.
 */
@Immutable
data class Geometry(
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Degrees clockwise, from -45 to 45. */
    val straighten: Float = 0f,
    val crop: CropRect = CropRect(),
    /** The crop's locked width over height, or null when free. */
    val aspect: Float? = null
) {
    fun rotatedLeft(): Geometry = copy(
        quarterTurns = (quarterTurns + 3) % 4,
        // A quarter turn swaps which way a mirror runs.
        flipHorizontal = flipVertical,
        flipVertical = flipHorizontal,
        crop = CropRect(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left),
        aspect = aspect?.let { 1f / it }
    )

    fun flippedHorizontally(): Geometry = copy(
        flipHorizontal = !flipHorizontal,
        straighten = reversedTilt(),
        crop = CropRect(1f - crop.right, crop.top, 1f - crop.left, crop.bottom)
    )

    fun flippedVertically(): Geometry = copy(
        flipVertical = !flipVertical,
        straighten = reversedTilt(),
        crop = CropRect(crop.left, 1f - crop.bottom, crop.right, 1f - crop.top)
    )

    /** A mirror reverses a tilt. No tilt stays exactly zero, rather than becoming a negative zero. */
    private fun reversedTilt(): Float = if (straighten == 0f) 0f else -straighten

    val isTurnedSideways: Boolean get() = quarterTurns % 2 != 0
}

/** The frame's size once the quarter turns are done, before any crop. */
fun frameSize(sourceWidth: Int, sourceHeight: Int, geometry: Geometry): Pair<Int, Int> =
    if (geometry.isTurnedSideways) sourceHeight to sourceWidth else sourceWidth to sourceHeight

/** The final picture's size in pixels. */
fun outputSize(sourceWidth: Int, sourceHeight: Int, geometry: Geometry, scale: Float): Pair<Int, Int> {
    val (frameWidth, frameHeight) = frameSize(sourceWidth, sourceHeight, geometry)
    val width = (frameWidth * geometry.crop.width * scale).roundToInt().coerceAtLeast(1)
    val height = (frameHeight * geometry.crop.height * scale).roundToInt().coerceAtLeast(1)
    return width to height
}

/** How far a tilted photo must grow so that it still covers its whole frame, with no empty corners. */
fun straightenScale(frameWidth: Float, frameHeight: Float, degrees: Float): Float {
    if (degrees == 0f || frameWidth <= 0f || frameHeight <= 0f) return 1f
    val radians = Math.toRadians(degrees.toDouble())
    val sine = abs(sin(radians)).toFloat()
    val cosine = abs(cos(radians)).toFloat()
    return max(
        (frameWidth * cosine + frameHeight * sine) / frameWidth,
        (frameWidth * sine + frameHeight * cosine) / frameHeight
    )
}

/** A 2D transform, as plain numbers so it can be tested without a device. x' = ax + cy + e, y' = bx + dy + f. */
@Immutable
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val e: Float = 0f,
    val f: Float = 0f
) {
    /** This transform followed by [next]. */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f
    )

    fun apply(x: Float, y: Float): Pair<Float, Float> = (a * x + c * y + e) to (b * x + d * y + f)

    /** Undoes this transform, for turning a touch back into a place on the photo. */
    fun inverse(): Affine {
        val det = a * d - b * c
        if (det == 0f) return Affine()
        return Affine(
            a = d / det,
            b = -b / det,
            c = -c / det,
            d = a / det,
            e = (c * f - d * e) / det,
            f = (b * e - a * f) / det
        )
    }

    /** How much this transform enlarges things, whatever its rotation. */
    val magnification: Float get() = kotlin.math.sqrt(a * a + b * b)

    companion object {
        fun translate(x: Float, y: Float) = Affine(e = x, f = y)
        fun scale(x: Float, y: Float = x) = Affine(a = x, d = y)
        fun rotate(degrees: Float): Affine {
            val radians = Math.toRadians(degrees.toDouble())
            val sine = sin(radians).toFloat()
            val cosine = cos(radians).toFloat()
            return Affine(a = cosine, b = sine, c = -sine, d = cosine)
        }
    }
}

/**
 * Maps the upright source's pixels onto the picture. With [applyCrop] off it maps onto the whole frame
 * instead, which is what the crop screen shows.
 */
fun geometryTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    geometry: Geometry,
    scale: Float = 1f,
    applyCrop: Boolean = true
): Affine {
    val (frameWidth, frameHeight) = frameSize(sourceWidth, sourceHeight, geometry)
    val tilt = straightenScale(frameWidth.toFloat(), frameHeight.toFloat(), geometry.straighten)
    var m = Affine.translate(-sourceWidth / 2f, -sourceHeight / 2f)
        .then(Affine.rotate(geometry.quarterTurns * 90f))
        .then(Affine.scale(if (geometry.flipHorizontal) -1f else 1f, if (geometry.flipVertical) -1f else 1f))
    if (geometry.straighten != 0f) m = m.then(Affine.rotate(geometry.straighten)).then(Affine.scale(tilt))
    m = m.then(Affine.translate(frameWidth / 2f, frameHeight / 2f))
    if (applyCrop) m = m.then(Affine.translate(-geometry.crop.left * frameWidth, -geometry.crop.top * frameHeight))
    return m.then(Affine.scale(scale))
}

enum class CropHandle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, MOVE }

private const val MinCropFraction = 0.06f

/** The largest crop of a given shape that fits in the frame, centered. */
fun fitAspect(frameWidth: Int, frameHeight: Int, aspect: Float): CropRect {
    // The shape as fractions of the frame, which usually isn't square itself.
    val fraction = aspect * frameHeight / frameWidth
    val width = if (fraction <= 1f) fraction else 1f
    val height = if (fraction <= 1f) 1f else 1f / fraction
    val left = (1f - width) / 2f
    val top = (1f - height) / 2f
    return CropRect(left, top, left + width, top + height)
}

/** The crop after a drag of ([dx], [dy]), both fractions of the frame. It stays in the frame and keeps a locked shape. */
fun dragCrop(
    crop: CropRect,
    handle: CropHandle,
    dx: Float,
    dy: Float,
    frameWidth: Int,
    frameHeight: Int,
    aspect: Float?
): CropRect {
    if (handle == CropHandle.MOVE) {
        val x = dx.coerceIn(-crop.left, 1f - crop.right)
        val y = dy.coerceIn(-crop.top, 1f - crop.bottom)
        return CropRect(crop.left + x, crop.top + y, crop.right + x, crop.bottom + y)
    }
    return if (aspect == null) dragFree(crop, handle, dx, dy) else dragLocked(crop, handle, dx, dy, aspect * frameHeight / frameWidth)
}

private fun dragFree(crop: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
    var (left, top, right, bottom) = crop
    if (handle == CropHandle.TOP_LEFT || handle == CropHandle.LEFT || handle == CropHandle.BOTTOM_LEFT) {
        left = (left + dx).coerceIn(0f, right - MinCropFraction)
    }
    if (handle == CropHandle.TOP_RIGHT || handle == CropHandle.RIGHT || handle == CropHandle.BOTTOM_RIGHT) {
        right = (right + dx).coerceIn(left + MinCropFraction, 1f)
    }
    if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP || handle == CropHandle.TOP_RIGHT) {
        top = (top + dy).coerceIn(0f, bottom - MinCropFraction)
    }
    if (handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM || handle == CropHandle.BOTTOM_RIGHT) {
        bottom = (bottom + dy).coerceIn(top + MinCropFraction, 1f)
    }
    return CropRect(left, top, right, bottom)
}

/** [ratio] is the crop's width over height, measured in fractions of the frame. */
private fun dragLocked(crop: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float): CropRect {
    val minWidth = max(MinCropFraction, MinCropFraction * ratio)
    when (handle) {
        CropHandle.TOP, CropHandle.BOTTOM, CropHandle.LEFT, CropHandle.RIGHT -> {
            // An edge grows the crop away from the opposite edge, and evenly along the other axis.
            val centerX = (crop.left + crop.right) / 2f
            val centerY = (crop.top + crop.bottom) / 2f
            return when (handle) {
                CropHandle.LEFT, CropHandle.RIGHT -> {
                    val toRight = handle == CropHandle.RIGHT
                    val room = if (toRight) 1f - crop.left else crop.right
                    val roomAcross = 2f * min(centerY, 1f - centerY) * ratio
                    val width = (crop.width + if (toRight) dx else -dx).coerceIn(minWidth, min(room, roomAcross).coerceAtLeast(minWidth))
                    val height = width / ratio
                    val left = if (toRight) crop.left else crop.right - width
                    CropRect(left, centerY - height / 2f, left + width, centerY + height / 2f)
                }
                else -> {
                    val toBottom = handle == CropHandle.BOTTOM
                    val room = if (toBottom) 1f - crop.top else crop.bottom
                    val roomAcross = 2f * min(centerX, 1f - centerX) / ratio
                    val height = (crop.height + if (toBottom) dy else -dy).coerceIn(minWidth / ratio, min(room, roomAcross).coerceAtLeast(minWidth / ratio))
                    val width = height * ratio
                    val top = if (toBottom) crop.top else crop.bottom - height
                    CropRect(centerX - width / 2f, top, centerX + width / 2f, top + height)
                }
            }
        }
        else -> {
            val right = handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT
            val bottom = handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT
            val anchorX = if (right) crop.left else crop.right
            val anchorY = if (bottom) crop.top else crop.bottom
            val width = crop.width + if (right) dx else -dx
            val height = crop.height + if (bottom) dy else -dy
            val roomX = if (right) 1f - anchorX else anchorX
            val roomY = if (bottom) 1f - anchorY else anchorY
            // Follow whichever way the finger moved the corner further.
            val newWidth = max(width, height * ratio).coerceIn(minWidth, min(roomX, roomY * ratio).coerceAtLeast(minWidth))
            val newHeight = newWidth / ratio
            val left = if (right) anchorX else anchorX - newWidth
            val top = if (bottom) anchorY else anchorY - newHeight
            return CropRect(left, top, left + newWidth, top + newHeight)
        }
    }
}

/** A color matrix in the system's layout: four rows of five, with offsets on the 0 to 255 scale. */
typealias ColorRows = FloatArray

private val IdentityRows: ColorRows = floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)

/** [first] then [second]. */
fun concatColors(first: ColorRows, second: ColorRows): ColorRows {
    val out = FloatArray(20)
    for (row in 0 until 4) {
        val r = row * 5
        for (column in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += second[r + k] * first[k * 5 + column]
            out[r + column] = sum
        }
        var offset = second[r + 4]
        for (k in 0 until 4) offset += second[r + k] * first[k * 5 + 4]
        out[r + 4] = offset
    }
    return out
}

private fun gains(red: Float, green: Float, blue: Float, offset: Float = 0f): ColorRows = floatArrayOf(
    red, 0f, 0f, 0f, offset,
    0f, green, 0f, 0f, offset,
    0f, 0f, blue, 0f, offset,
    0f, 0f, 0f, 1f, 0f
)

private fun contrastRows(amount: Float): ColorRows = gains(amount, amount, amount, 127.5f * (1f - amount))

private fun saturationRows(amount: Float): ColorRows {
    val inverse = 1f - amount
    val r = 0.2126f * inverse
    val g = 0.7152f * inverse
    val b = 0.0722f * inverse
    return floatArrayOf(
        r + amount, g, b, 0f, 0f,
        r, g + amount, b, 0f, 0f,
        r, g, b + amount, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )
}

private val SepiaRows: ColorRows = floatArrayOf(
    0.393f, 0.769f, 0.189f, 0f, 0f,
    0.349f, 0.686f, 0.168f, 0f, 0f,
    0.272f, 0.534f, 0.131f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)

fun filterRows(filter: FilterPreset): ColorRows = when (filter) {
    FilterPreset.ORIGINAL -> IdentityRows
    FilterPreset.VIVID -> concatColors(saturationRows(1.35f), contrastRows(1.1f))
    FilterPreset.WARM -> concatColors(gains(1.09f, 1.01f, 0.88f), saturationRows(1.05f))
    FilterPreset.COOL -> concatColors(gains(0.9f, 1f, 1.1f), saturationRows(0.98f))
    FilterPreset.DRAMATIC -> concatColors(concatColors(contrastRows(1.28f), saturationRows(1.08f)), gains(0.97f, 0.97f, 0.97f))
    FilterPreset.FADE -> concatColors(concatColors(contrastRows(0.82f), saturationRows(0.82f)), gains(1f, 1f, 1f, 14f))
    FilterPreset.MONO -> saturationRows(0f)
    FilterPreset.NOIR -> concatColors(saturationRows(0f), contrastRows(1.38f))
    FilterPreset.SEPIA -> SepiaRows
}

/** The color work of an edit: adjustments, then the filter on top. */
fun colorRowsFor(adjustments: Adjustments, filter: FilterPreset): ColorRows {
    var rows = IdentityRows
    with(adjustments) {
        if (exposure != 0f) {
            val stops = 2f.pow(exposure * 1.5f)
            rows = concatColors(rows, gains(stops, stops, stops))
        }
        if (brightness != 0f) rows = concatColors(rows, gains(1f, 1f, 1f, brightness * 70f))
        if (contrast != 0f) rows = concatColors(rows, contrastRows(1f + contrast * if (contrast > 0f) 0.7f else 0.55f))
        if (saturation != 0f) rows = concatColors(rows, saturationRows(1f + saturation))
        if (warmth != 0f) rows = concatColors(rows, gains(1f + warmth * 0.16f, 1f + warmth * 0.02f, 1f - warmth * 0.16f))
        if (tint != 0f) rows = concatColors(rows, gains(1f + tint * 0.05f, 1f - tint * 0.13f, 1f + tint * 0.05f))
    }
    return if (filter == FilterPreset.ORIGINAL) rows else concatColors(rows, filterRows(filter))
}

/** Applies a color matrix to one color, for testing and for picking colors without a canvas. */
fun applyColorRows(rows: ColorRows, red: Float, green: Float, blue: Float): Triple<Float, Float, Float> {
    fun channel(r: Int) = (rows[r] * red + rows[r + 1] * green + rows[r + 2] * blue + rows[r + 3] * 255f + rows[r + 4]).coerceIn(0f, 255f)
    return Triple(channel(0), channel(5), channel(10))
}

/** Undo and redo over whole states. Reset is one more step, so it can be undone too. */
class EditHistory(private val initial: EditState = EditState()) {
    private val past = ArrayList<EditState>()
    private val future = ArrayList<EditState>()
    var current: EditState = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(state: EditState) {
        if (state == current) return
        past.add(current)
        future.clear()
        current = state
    }

    fun undo(): EditState {
        if (past.isNotEmpty()) {
            future.add(current)
            current = past.removeAt(past.lastIndex)
        }
        return current
    }

    fun redo(): EditState {
        if (future.isNotEmpty()) {
            past.add(current)
            current = future.removeAt(future.lastIndex)
        }
        return current
    }

    fun reset(): EditState {
        push(initial)
        return current
    }
}

/** The name for a saved copy, without colliding with anything already there. */
fun editedFileName(originalName: String, extension: String, taken: (String) -> Boolean): String {
    val base = originalName.substringBeforeLast('.', originalName).removeSuffix("_edited")
    var candidate = "${base}_edited.$extension"
    var counter = 2
    while (taken(candidate)) {
        candidate = "${base}_edited_$counter.$extension"
        counter++
    }
    return candidate
}

/** Which sizes the short of memory can afford: the sample rate that brings a photo within a pixel budget. */
fun sampleSizeFor(width: Int, height: Int, maxPixels: Long): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
    return sample
}
