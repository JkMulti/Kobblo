package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle

/** The most a corner is ever rounded, however round the person likes theirs. */
private const val RoundestCorner = 100f

/**
 * Corners as round as Settings asks for. At its middle value of 0.5 they come out at [defaultRadius];
 * at 0 they're square, and at 1 twice as round.
 */
@Composable
fun getDynamicCornerShape(defaultRadius: Float, cornerRoundness: Float): Shape {
    val radius = defaultRadius * cornerRoundness * 2
    return RoundedRectangle(radius.coerceIn(0f, RoundestCorner).dp)
}
