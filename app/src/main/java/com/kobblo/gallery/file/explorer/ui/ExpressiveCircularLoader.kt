package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader

/** The older name of [KobbloLoader]. */
@Composable
fun ExpressiveCircularLoader(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    color: Color = KobbloDesign.colors.action
) {
    KobbloLoader(modifier = modifier, size = size, color = color)
}
