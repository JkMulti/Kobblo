package com.kobblo.gallery.file.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.kobblo.gallery.file.explorer.ui.design.LocalKobbloColors

/**
 * The page color behind a full-screen viewer. Kobblo's canvas is one flat color, so despite the name
 * there is no gradient to draw.
 */
fun Modifier.appGradientBackground(): Modifier = composed {
    background(LocalKobbloColors.current.canvas)
}
