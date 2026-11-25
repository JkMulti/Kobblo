package com.kobblo.gallery.file.explorer.gallery.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import androidx.exifinterface.media.ExifInterface
import coil.Coil
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import kotlin.math.max
import kotlin.math.min

fun Affine.toMatrix(): Matrix = Matrix().apply {
    setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
}

/** How many blocks across the photo's short side a pixelate stroke shows. Few enough that text can't be read back. */
private const val PixelBlocks = 40
private const val BlurSamples = 40

/**
 * What the brush paints with: a tiny copy of the photo, stretched back over it. Only the tiny copy
 * ever reaches the stroke, so what was underneath is really gone from the saved file, and no full-size
 * second copy is needed however large the photo is.
 */
internal class BrushEffects(private val source: Bitmap) {
    private val width = source.width
    private val height = source.height

    private val pixelated: Bitmap by lazy { shrink(PixelBlocks) }
    private val blurred: Bitmap by lazy { boxBlurred(shrink(BlurSamples)) }

    private fun shrink(shortSide: Int): Bitmap {
        val ratio = shortSide.toFloat() / min(width, height)
        return Bitmap.createScaledBitmap(
            source,
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true
        ).let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    /** Smooths out the grid the stretch would otherwise show, so it reads as a blur and not as blocks. */
    private fun boxBlurred(small: Bitmap): Bitmap {
        val w = small.width
        val h = small.height
        var pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            val next = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val px = x + dx
                    val py = y + dy
                    if (px < 0 || py < 0 || px >= w || py >= h) continue
                    val c = pixels[py * w + px]
                    r += (c shr 16) and 255
                    g += (c shr 8) and 255
                    b += c and 255
                    count++
                }
                next[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
            pixels = next
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun paintFor(mode: BrushMode, radius: Float, feather: Boolean): Paint {
        val small = if (mode == BrushMode.PIXELATE) pixelated else blurred
        val strokeWidth = radius * 2f * min(width, height)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(width.toFloat() / small.width, height.toFloat() / small.height) })
            }
            isFilterBitmap = mode == BrushMode.BLUR
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.strokeWidth = strokeWidth
            if (feather && mode == BrushMode.BLUR) {
                maskFilter = BlurMaskFilter(max(1f, strokeWidth * 0.08f), BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    /** Paints every stroke onto [target], which must be the same size as the photo these effects came from. */
    fun bake(target: Bitmap, strokes: List<BrushStroke>) {
        if (strokes.isEmpty()) return
        val canvas = Canvas(target)
        for (stroke in strokes) {
            canvas.drawPath(strokePath(stroke.points, width, height), paintFor(stroke.mode, stroke.radius, feather = true))
        }
    }
}

/** A smooth line through a stroke's points, in the photo's pixels. A single touch makes a dot. */
internal fun strokePath(points: List<Float>, width: Int, height: Int): Path {
    val path = Path()
    if (points.size < 2) return path
    var lastX = points[0] * width
    var lastY = points[1] * height
    path.moveTo(lastX, lastY)
    if (points.size == 2) {
        path.lineTo(lastX + 0.1f, lastY)
        return path
    }
    var i = 2
    while (i + 1 < points.size) {
        val x = points[i] * width
        val y = points[i + 1] * height
        path.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
        lastX = x
        lastY = y
        i += 2
    }
    path.lineTo(lastX, lastY)
    return path
}

internal object EditorRender {
    fun colorFilter(state: EditState): ColorMatrixColorFilter? {
        if (state.filter == FilterPreset.ORIGINAL && state.adjustments == Adjustments()) return null
        return ColorMatrixColorFilter(colorRowsFor(state.adjustments, state.filter))
    }

    /** Draws the finished picture. The strokes must already be painted onto [source]. */
    fun render(source: Bitmap, state: EditState, scale: Float): Bitmap {
        val (width, height) = outputSize(source.width, source.height, state.geometry, scale)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            colorFilter = colorFilter(state)
        }
        canvas.concat(geometryTransform(source.width, source.height, state.geometry, scale).toMatrix())
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}

/** Reads photos the right way up, at a size memory can hold. */
internal object EditorDecoder {
    class Bounds(val width: Int, val height: Int)

    /** The photo's size once upright, or null if it isn't something the system decoder knows. */
    fun boundsOf(path: String): Bounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val sideways = orientationOf(path).let { it in 5..8 }
        return if (sideways) Bounds(options.outHeight, options.outWidth) else Bounds(options.outWidth, options.outHeight)
    }

    fun orientationOf(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Decodes at 1/[sample] size and turns it upright. May throw OutOfMemoryError for the caller to retry smaller. */
    fun decode(path: String, sample: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(path, options) ?: return null
        val matrix = Matrix()
        when (orientationOf(path)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return raw
        }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (upright !== raw) raw.recycle()
        return upright
    }

    /**
     * For formats only the app's own decoders know. Coil can't report a size up front, so the first
     * decode here also tells the editor how big the photo is.
     */
    suspend fun decodeThroughCoil(context: Context, path: String, maxSide: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(File(path))
            .size(Size(maxSide, maxSide))
            .allowHardware(false)
            .build()
        val drawable = Coil.imageLoader(context).execute(request).drawable
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
}
