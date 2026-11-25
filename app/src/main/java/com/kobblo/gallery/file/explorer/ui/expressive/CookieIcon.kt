package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes

/** What a tile is painted with: the surface behind its glyph, and the glyph's own color. */
private class TilePaint(val fill: Color, val glyph: Color)

/**
 * Works out a tile's paint. A file-type indicator is never a fill: asked to fill a tile with one, the
 * tile stays on the snow surface and the indicator colors the glyph instead. The neutral indicator
 * stands for the theme's ink wherever it would be drawn.
 */
@Composable
private fun tilePaint(requestedFill: Color, requestedGlyph: Color): TilePaint {
    val colors = KobbloDesign.colors
    fun drawn(color: Color) = if (color == ExpressiveTokens.Neutral) colors.ink else color
    return when {
        ExpressiveTokens.isIndicator(requestedFill) -> TilePaint(fill = colors.snow, glyph = drawn(requestedFill))
        else -> TilePaint(fill = requestedFill, glyph = drawn(requestedGlyph))
    }
}

/** Half the tile, and never larger than a glyph still reads as a glyph. */
private fun glyphSizeFor(tile: Dp): Dp = (tile * 0.5f).coerceAtMost(36.dp)

/** The glyph centered in whatever [surface] has been drawn for it. */
@Composable
private fun TileGlyph(icon: ImageVector, tint: Color, size: Dp, label: String?, surface: Modifier) {
    Box(modifier = surface, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size))
    }
}

/** A glyph on a continuous-corner tile. */
@Composable
fun IconTile(
    icon: ImageVector,
    bgColor: Color,
    modifier: Modifier = Modifier,
    iconTint: Color = ExpressiveTokens.Neutral,
    size: Dp = 44.dp,
    shape: Shape? = null
) {
    val paint = tilePaint(bgColor, iconTint)
    TileGlyph(
        icon = icon,
        tint = paint.glyph,
        size = glyphSizeFor(size),
        label = null,
        surface = modifier
            .size(size)
            .clip(shape ?: KobbloShapes.tile)
            .background(paint.fill)
    )
}

/** A tappable [IconTile], round unless given a [shape]. */
@Composable
fun IconTileButton(
    onClick: () -> Unit,
    icon: ImageVector,
    bgColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    iconTint: Color = ExpressiveTokens.Neutral,
    size: Dp = 44.dp,
    iconSize: Dp = (size * 0.5f).coerceAtMost(36.dp),
    shape: Shape? = null
) {
    val presses = remember { MutableInteractionSource() }
    val paint = tilePaint(bgColor, iconTint)
    TileGlyph(
        icon = icon,
        tint = paint.glyph,
        size = iconSize,
        label = description,
        surface = modifier
            .size(size)
            .pressScale(pressedScale = 0.92f, interactionSource = presses)
            .clip(shape ?: CapsuleShape)
            .background(paint.fill)
            .clickable(interactionSource = presses, indication = null, onClick = onClick)
            .semantics { contentDescription = description }
    )
}
