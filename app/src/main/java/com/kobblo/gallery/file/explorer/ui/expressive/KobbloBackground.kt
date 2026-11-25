package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign

/** The app's canvas. Deliberately flat: content is the only thing on the page. */
@Composable
fun KobbloBackground(
    modifier: Modifier = Modifier,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    val canvas = modifier.fillMaxSize().background(KobbloDesign.colors.canvas)
    if (content == null) Box(canvas) else Box(canvas, content = content)
}
