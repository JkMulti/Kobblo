package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.ui.theme.KobbloTheme

/** The app's theme, with everything Settings can change about it. */
@Composable
internal fun RootTheme(state: AppState, content: @Composable () -> Unit) {
    val dark = when (state.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    KobbloTheme(
        darkTheme = dark, darkSurfaceStyle = state.darkSurfaceStyle,
        accent = state.tint, dynamicColor = state.useDynamicSystemTheme,
        customHue = state.themeHue, lightnessOffset = state.themeLightnessOffset,
        fontStyle = state.fontStyle, textDecorations = state.textDecorations, invertText = state.invertText,
        mainTextScale = state.mainTextScale, subTextScale = state.subTextScale,
        cornerRoundness = state.cornerRoundness, iconShape = state.activeIconShape,
        content = content
    )
}
