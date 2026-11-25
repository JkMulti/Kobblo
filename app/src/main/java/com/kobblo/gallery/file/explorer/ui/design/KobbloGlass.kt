package com.kobblo.gallery.file.explorer.ui.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * Marks what glass shows through. Below Android 12 glass is opaque, so nothing reads what this would
 * record, and those older phones are spared drawing every screen through an extra layer.
 */
fun Modifier.kobbloGlassSource(backdrop: LayerBackdrop): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) layerBackdrop(backdrop) else this

/**
 * Liquid glass for floating controls. Blur needs Android 12 and refraction needs Android 13, so
 * older devices get an opaque surface instead of unreadable, unblurred content behind the bar.
 */
fun Modifier.kobbloGlass(backdrop: Backdrop?, shape: Shape): Modifier = composed {
    val colors = KobbloDesign.colors
    if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(10.dp.toPx())
                lens(10.dp.toPx(), 18.dp.toPx())
            },
            highlight = { Highlight.Ambient.copy(alpha = if (colors.isDark) 0.5f else 0.7f) },
            shadow = { Shadow(radius = 30.dp, offset = DpOffset.Zero, color = colors.ambient) },
            onDrawSurface = { drawRect(colors.glass) }
        )
    } else {
        ambientShadow(shape, colors.ambient)
            .clip(shape)
            .background(colors.snow)
            .border(1.dp, colors.hairline, shape)
    }
}
