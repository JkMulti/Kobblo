package com.kobblo.gallery.file.explorer.explorer.drag

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.expressive.FileTypeArt
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

// How big the art for a file with no picture is drawn, and how much of its card it takes up.
private val ArtSize = 104.dp
private const val ArtShare = 0.78f
private val MarkSize = 34.dp

/**
 * What's drawn over the app while something's being carried. Goes above the floating bars, and costs one read of one value
 * the rest of the time.
 */
@Composable
internal fun FileDragOverlay() {
    val session = FileDrag.session ?: return
    key(session) { Stack(session) }
}

@Composable
private fun Stack(session: DragSession) {
    LaunchedEffect(session) { session.run() }

    // Back, a call or the phone turning all mean stop, and nothing has happened yet that needs undoing.
    BackHandler(enabled = session.isCarrying) { session.cancel() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) session.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val orientation = LocalConfiguration.current.orientation
    val liftedIn = remember(session) { intArrayOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation != liftedIn[0] && orientation != Configuration.ORIENTATION_UNDEFINED) session.cancel()
    }

    val colors = KobbloDesign.colors
    val density = LocalDensity.current
    val painter = remember(session, colors, density) { CardPainter(colors, density.density) }

    Box(modifier = Modifier.fillMaxSize()) {
        val shown = session.hover ?: session.pulseTarget
        if (shown != null) TargetGlow(session, shown)
        // One pass over every card on its own layer, so that only this gets drawn again each frame.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawBehind {
                    @Suppress("UNUSED_EXPRESSION")
                    session.tick
                    drawIntoCanvas { painter.draw(it.nativeCanvas, session, size.width, size.height) }
                }
        )
        if (session.total > 1) CountBadge(session)
        session.pictures.artToDraw.forEach { art -> key(art.key) { DrawArt(art) } }
    }
}

/**
 * Draws the art for a file type into a bitmap, once, and nothing to the screen. It's the real thing the list draws, so it's
 * the same glyph, and after this it costs a card no more than a picture does.
 */
@Composable
private fun DrawArt(art: TileArt) {
    val colors = KobbloDesign.colors
    val file = art.file
    Box(
        modifier = Modifier
            .requiredSize(if (file == null) MarkSize else ArtSize)
            .drawWithContent {
                if (art.bitmap != null) return@drawWithContent
                val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                draw(this, layoutDirection, Canvas(bitmap), size) { this@drawWithContent.drawContent() }
                art.bitmap = bitmap.asAndroidBitmap()
            }
            .then(if (file == null) Modifier.background(Color.Black.copy(alpha = 0.5f), CapsuleShape) else Modifier.background(colors.snow)),
        contentAlignment = Alignment.Center
    ) {
        if (file == null) {
            Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(MarkSize * 0.55f))
        } else {
            FileTypeArt(file, Modifier.size(ArtSize * ArtShare))
        }
    }
}

/** The true total, which is more than the stack shows and more than the screen does. */
@Composable
private fun CountBadge(session: DragSession) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .wrapContentSize()
            .graphicsLayer {
                @Suppress("UNUSED_EXPRESSION")
                session.tick
                val top = session.top
                val half = top.size / 2f
                translationX = top.x + half - size.width * 0.72f
                translationY = top.y - half - size.height * 0.28f
                val pop = session.badge
                scaleX = pop
                scaleY = pop
                alpha = top.alpha.coerceAtMost(pop)
            }
            .background(colors.action, CapsuleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "%,d".format(session.total),
            style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = colors.onAction,
            maxLines = 1
        )
    }
}

/**
 * A tint over whatever's under the stack, with its picture drawn again a little larger. Drawn from here rather than by the
 * row, so a row has nothing to pay for it the rest of the time.
 */
@Composable
private fun TargetGlow(session: DragSession, target: DropView) {
    val colors = KobbloDesign.colors
    val action = colors.action
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                @Suppress("UNUSED_EXPRESSION")
                session.tick
                val hovering = session.hover === target
                // An action or a path segment lights itself while hovered, and this is only for its pulse.
                if (hovering && target.chrome != null) return@drawBehind
                val amount = if (hovering) session.hoverAmount else 1f - session.pulse
                if (amount <= 0f) return@drawBehind
                val grow = if (hovering) 0f else session.pulse * 10.dp.toPx()
                if (target.hit is DropHit.Here) {
                    glowHere(target.bounds, action, amount)
                } else {
                    val outset = 4.dp.toPx() + grow
                    val box = target.thumb ?: target.bounds
                    val whole = if (target.chrome != null) box else target.bounds
                    drawRoundRect(
                        color = action.copy(alpha = 0.18f * amount),
                        topLeft = Offset(whole.left - outset, whole.top - outset),
                        size = Size(whole.width + outset * 2f, whole.height + outset * 2f),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                }
            }
    )
    val file = target.file
    val source = target.source
    val thumb = target.thumb
    if (file != null && source != null && thumb != null) {
        val size = with(LocalDensity.current) { thumb.width.toDp() }
        GrownThumbnail(session, target, source, file, size)
    }
}

/** The whole list has become the target, so its edge says so. */
private fun DrawScope.glowHere(bounds: Rect, color: Color, amount: Float) {
    val inset = 6.dp.toPx()
    val topLeft = Offset(bounds.left + inset, bounds.top + inset)
    val size = Size(bounds.width - inset * 2f, bounds.height - inset * 2f)
    val radius = CornerRadius(20.dp.toPx())
    drawRoundRect(color.copy(alpha = 0.07f * amount), topLeft, size, radius)
    drawRoundRect(color.copy(alpha = 0.55f * amount), topLeft, size, radius, style = Stroke(2.dp.toPx()))
}

@Composable
private fun GrownThumbnail(
    session: DragSession,
    target: DropView,
    source: DragSource,
    file: FileItem,
    size: Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                @Suppress("UNUSED_EXPRESSION")
                session.tick
                val thumb = target.thumb ?: return@graphicsLayer
                translationX = thumb.left
                translationY = thumb.top
                val hovering = session.hover === target
                val amount = if (hovering) session.hoverAmount else 1f - session.pulse
                val pulse = if (hovering) 0f else pulseCurve(session.pulse)
                val grow = 1f + 0.07f * amount + 0.09f * pulse
                scaleX = grow
                scaleY = grow
                alpha = amount.coerceIn(0f, 1f)
            }
    ) {
        source.Thumbnail(file, size)
    }
}

/** Up and back again, soonest first. */
private fun pulseCurve(t: Float): Float {
    val rising = (t / 0.3f).coerceIn(0f, 1f)
    val falling = ((1f - t) / 0.7f).coerceIn(0f, 1f)
    return minOf(rising, falling)
}
