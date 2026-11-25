package com.kobblo.gallery.file.explorer.viewer.decoder

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.core.graphics.PathParser
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import kotlin.math.min

/**
 * A photo or video that won't decode draws a quiet glyph instead of leaving a blank tile, however the
 * screen asked for it. A screen with its own fallback still hears about the failure through its
 * listener, so it can swap the glyph for something else.
 */
internal class PlaceholderInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed(chain.request)
        if (result !is ErrorResult || result.drawable != null) return result
        val name = when (val data = chain.request.data) {
            is File -> data.name
            is com.kobblo.gallery.file.explorer.gallery.GalleryThumb -> data.path.substringAfterLast('/')
            is Uri -> data.lastPathSegment
            is String -> data.substringAfterLast('/')
            else -> null
        } ?: return result
        val glyph = when {
            FileKinds.isVideo(name) -> MediaGlyphDrawable(VideoGlyph, null)
            FileKinds.isImage(name) -> MediaGlyphDrawable(PhotoGlyph, PhotoGlyphDot)
            else -> return result
        }
        return result.copy(drawable = glyph)
    }

    private companion object {
        // Copies of AppIcons.Video and AppIcons.Photo, which only exist as Compose vectors there.
        const val VideoGlyph = "M6 5.75h7a3 3 0 0 1 3 3v6.5a3 3 0 0 1 -3 3h-7a3 3 0 0 1 -3 -3v-6.5a3 3 0 0 1 3 -3ZM16 10.5l3.55-2.25c.67-.42 1.45.05 1.45.8v5.9c0 .75-.78 1.22-1.45.8L16 13.5"
        const val PhotoGlyph = "M6.25 4.75h11.5a3 3 0 0 1 3 3v8.5a3 3 0 0 1 -3 3h-11.5a3 3 0 0 1 -3 -3v-8.5a3 3 0 0 1 3 -3ZM3.75 16.5l4.5-4.35a1.9 1.9 0 0 1 2.65 0l3.1 3.05M12.5 13.75l1.95-1.85a1.9 1.9 0 0 1 2.65 0l3.4 3.2"
        const val PhotoGlyphDot = "M14.3 8.9a1.45 1.45 0 1 0 2.9 0a1.45 1.45 0 1 0 -2.9 0Z"
    }
}

/** One glyph centered in whatever space it is given, with no size of its own. */
internal class MediaGlyphDrawable(stroke: String, fill: String?) : Drawable() {
    private val strokePath: Path = PathParser.createPathFromPathData(stroke)
    private val fillPath: Path? = fill?.let { PathParser.createPathFromPathData(it) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = GlyphColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlyphColor }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val side = min(area.width(), area.height()) * 0.36f
        val checkpoint = canvas.save()
        canvas.translate(area.exactCenterX() - side / 2f, area.exactCenterY() - side / 2f)
        canvas.scale(side / GridSize, side / GridSize)
        canvas.drawPath(strokePath, strokePaint)
        fillPath?.let { canvas.drawPath(it, fillPaint) }
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val GridSize = 24f

        /** Sits midway so it reads on the tile's mist in both light and dark. */
        const val GlyphColor = 0xFF8E8E93.toInt()
    }
}
