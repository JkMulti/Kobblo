package com.kobblo.gallery.file.explorer.explorer.drag

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import com.kobblo.gallery.file.explorer.ui.design.KobbloColors

/**
 * Draws every card in one pass over one canvas, back to front, as bitmaps with transforms. Nothing is composed or allocated
 * per card or per frame, so the cost of carrying more is the cost of drawing more and nothing else.
 *
 * A card is square and has no border. What sets it apart is the soft shadow around it, plus a hairline in the theme's own
 * color, which is what still shows when a dark picture is on a dark screen or a light picture on a light one. It's the
 * shadow that costs: the same blurred square is drawn for every card from one small bitmap, and only the few at the front
 * get the wide one, as the rest are mostly covered.
 */
internal class CardPainter(colors: KobbloColors, density: Float) {
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.mist.toArgb() }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.hairline.toArgb() }
    private val edgeWidth = (density * EdgeDp).coerceAtLeast(1f)
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = colors.ambient.toArgb() }

    private val edgeAlpha = (colors.hairline.alpha * EdgeStrength * 255f).toInt()
    private val wideAlpha = (colors.ambient.alpha * WideStrength * 255f).toInt().coerceAtMost(255)
    private val tightAlpha = (colors.ambient.alpha * TightStrength * 255f).toInt().coerceAtMost(255)

    private val wideShadow = blurredSquare(WideShadowPx, WideShadowMargin)
    private val tightShadow = blurredSquare(TightShadowPx, TightShadowMargin)
    private val area = RectF()

    fun draw(canvas: Canvas, session: DragSession, width: Float, height: Float) {
        // Until the pictures have had time to arrive, the originals are what's showing.
        if (!session.hasAppeared) return
        val now = System.nanoTime()
        val canDrawHardware = canvas.isHardwareAccelerated
        val cards = session.cards
        val mark = session.pictures.videoMark.bitmap
        for (i in cards.lastIndex downTo 0) {
            val model = cards[i]
            val body = model.body
            if (body.alpha <= 0f) continue

            val half = body.size / 2f
            // A turned square reaches about 1.41 times as far as its side, and its shadow further still.
            val reach = half * 1.75f
            if (body.x + reach < 0f || body.x - reach > width || body.y + reach < 0f || body.y - reach > height) continue

            val picture = model.picture?.takeIf {
                !it.isRecycled && (canDrawHardware || it.config != Bitmap.Config.HARDWARE)
            }
            val art = model.art?.bitmap
            // Still where it started and nothing to show for it yet, so the tile under it is the better picture.
            if (picture == null && art == null && !body.travelled) continue

            val opacity = body.alpha * body.depthFade
            val alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(body.x, body.y)
            canvas.rotate(body.rotation)

            if (i < WideShadowCards) {
                drawShadow(canvas, wideShadow, half * (1f + 2f * WideShadowMargin), wideAlpha, opacity)
            } else {
                drawShadow(canvas, tightShadow, half * (1f + 2f * TightShadowMargin), tightAlpha, opacity)
            }

            // A plain square that's only seen around the picture, since a turned stroke costs far more to draw than a fill.
            val outer = half + edgeWidth
            area.set(-outer, -outer, outer, outer)
            edgePaint.alpha = (edgeAlpha * opacity).toInt()
            canvas.drawRect(area, edgePaint)

            area.set(-half, -half, half, half)
            val reveal = if (picture == null || model.pictureSinceNanos == 0L) {
                1f
            } else {
                ((now - model.pictureSinceNanos) / RevealNanos).coerceIn(0f, 1f)
            }
            if (picture == null || reveal < 1f) {
                if (art != null) {
                    picturePaint.alpha = alpha
                    canvas.drawBitmap(art, null, area, picturePaint)
                } else {
                    tilePaint.alpha = alpha
                    canvas.drawRect(area, tilePaint)
                }
            }
            if (picture != null) {
                picturePaint.alpha = (alpha * reveal).toInt()
                canvas.drawBitmap(picture, model.pictureArea, area, picturePaint)
                if (model.isVideo && mark != null) {
                    val markHalf = half * MarkShare
                    area.set(-markHalf, -markHalf, markHalf, markHalf)
                    canvas.drawBitmap(mark, null, area, picturePaint)
                }
            }
            canvas.restore()
        }
    }

    private fun drawShadow(canvas: Canvas, shadow: Bitmap, half: Float, strength: Int, alpha: Float) {
        shadowPaint.alpha = (strength * alpha).toInt()
        area.set(-half, -half, half, half)
        canvas.drawBitmap(shadow, null, area, shadowPaint)
    }

    private companion object {
        const val EdgeDp = 0.5f
        const val EdgeStrength = 0.6f

        const val WideShadowCards = 10
        const val WideShadowPx = 96
        const val WideShadowMargin = 0.24f
        const val WideStrength = 2.2f
        const val TightShadowPx = 64
        const val TightShadowMargin = 0.1f
        const val TightStrength = 1.6f

        const val MarkShare = 0.16f
        const val RevealNanos = 120_000_000f

        /**
         * A blurred square filling [side] but for [margin] of its own size on each side. Only ever seen scaled up and soft,
         * and only the alpha is used, so it's small. The color is whatever's painting it.
         */
        fun blurredSquare(side: Int, margin: Float): Bitmap {
            val bitmap = createBitmap(side, side, Bitmap.Config.ALPHA_8)
            val inset = side * margin / (1f + 2f * margin)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(inset * 0.9f, BlurMaskFilter.Blur.NORMAL) }
            Canvas(bitmap).drawRect(inset, inset, side - inset, side - inset, paint)
            return bitmap
        }
    }
}
