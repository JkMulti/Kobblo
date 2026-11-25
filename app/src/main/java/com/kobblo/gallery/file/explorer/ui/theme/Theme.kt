package com.kobblo.gallery.file.explorer.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.IconShapeType
import com.kobblo.gallery.file.explorer.ui.design.AmoledKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.DarkKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import com.kobblo.gallery.file.explorer.ui.design.KobbloColors
import com.kobblo.gallery.file.explorer.ui.design.KobbloMaterialShapes
import com.kobblo.gallery.file.explorer.ui.design.LightKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.LocalKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.kobbloMaterialTypography
import com.kobblo.gallery.file.explorer.ui.design.withAccent
import com.kobblo.gallery.file.explorer.ui.expressive.LocalCornerRoundness
import com.kobblo.gallery.file.explorer.ui.expressive.LocalExpressiveColors
import com.kobblo.gallery.file.explorer.ui.expressive.LocalIconShapeType
import com.kobblo.gallery.file.explorer.ui.expressive.toExpressive

/**
 * The font family a screen puts on its text. Kobblo is set in the system's font throughout, so this
 * only ever gives [FontFamily.Default]; it's here for the screens that still ask.
 */
val LocalAppFont = compositionLocalOf<FontFamily> { FontFamily.Default }

/**
 * Text color for code that can't read [LocalKobbloColors], kept in step with the theme: it holds the
 * ink of whichever [KobbloTheme] was composed last.
 */
object CompatColors {
    var TextPrimary: Color = LightKobbloColors.ink
        private set

    internal fun follow(palette: KobbloColors) {
        TextPrimary = palette.ink
    }
}

/**
 * Kobblo's look, given to everything inside [content]: the design system's own colors, and Material's
 * color roles, type and shapes filled in from them, so a Material component placed anywhere comes
 * out in Kobblo's colors without being told.
 *
 * Three things choose the colors: [darkTheme], [darkSurfaceStyle] (which only has a say in dark) and
 * the [accent] picked in Settings. [mainTextScale] and [subTextScale] size Material's type and
 * [iconShape] is handed on to icon tiles. The remaining parameters are settings from before there
 * was one design for the whole app. Callers still pass them and they change nothing.
 */
@Composable
fun KobbloTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    darkSurfaceStyle: DarkSurfaceStyle = DarkSurfaceStyle.STANDARD,
    accent: KobbloAccent = KobbloAccent.Default,
    dynamicColor: Boolean = true,
    customHue: Float = 262f,
    lightnessOffset: Float = 0f,
    fontStyle: String = "System",
    textDecorations: Set<String> = emptySet(),
    mainTextScale: Float = 1.0f,
    subTextScale: Float = 1.0f,
    invertText: Boolean = false,
    cornerRoundness: Float = 0.5f,
    iconShape: IconShapeType = IconShapeType.IOS,
    content: @Composable () -> Unit
) {
    val palette = kobbloPalette(darkTheme, darkSurfaceStyle, accent)
    CompatColors.follow(palette)
    ClearSystemBars(darkIcons = !darkTheme)

    CompositionLocalProvider(
        LocalKobbloColors provides palette,
        LocalExpressiveColors provides palette.toExpressive(),
        LocalCornerRoundness provides SettledCornerRoundness,
        LocalIconShapeType provides iconShape,
        LocalAppFont provides FontFamily.Default
    ) {
        MaterialTheme(
            colorScheme = palette.materialColors(),
            typography = kobbloMaterialTypography(mainTextScale, subTextScale),
            shapes = KobbloMaterialShapes,
            content = content
        )
    }
}

/** How round corners are, on the scale the old slider used. It's no longer a choice. */
private const val SettledCornerRoundness = 0.5f

/**
 * The design system's colors for a mode: light, dark, or dark with the deeper AMOLED groups, with the
 * tint taken from [accent]. AMOLED is a kind of dark, so in light it's ignored.
 */
internal fun kobbloPalette(dark: Boolean, darkSurface: DarkSurfaceStyle, accent: KobbloAccent): KobbloColors {
    val base = when {
        !dark -> LightKobbloColors
        darkSurface == DarkSurfaceStyle.AMOLED -> AmoledKobbloColors
        else -> DarkKobbloColors
    }
    return base.withAccent(accent)
}

/**
 * Material's color roles, each given the Kobblo color that does that job. Roles Kobblo has no word
 * on keep Material's own value for the mode.
 *
 * Kobblo has one tint and a gray scale, so Material's three accent families collapse: primary is the
 * tint, and secondary and tertiary are both the gray of secondary text. Every step of surface
 * container is a group's white apart from the lowest, which is the page, and the highest, which is
 * the nested gray. Elevation never tints a surface.
 */
internal fun KobbloColors.materialColors(): ColorScheme {
    val materialDefaults = if (isDark) darkColorScheme() else lightColorScheme()
    return materialDefaults.copy(
        primary = action,
        onPrimary = onAction,
        primaryContainer = snow,
        onPrimaryContainer = ink,
        inversePrimary = action,

        secondary = smoke,
        onSecondary = canvas,
        secondaryContainer = snow,
        onSecondaryContainer = ink,

        tertiary = smoke,
        onTertiary = canvas,
        tertiaryContainer = mist,
        onTertiaryContainer = ink,

        error = danger,
        onError = onAction,
        errorContainer = snow,
        onErrorContainer = danger,

        background = canvas,
        onBackground = ink,
        surface = canvas,
        onSurface = ink,
        surfaceVariant = snow,
        onSurfaceVariant = smoke,
        inverseSurface = ink,
        inverseOnSurface = canvas,
        surfaceTint = Color.Transparent,

        surfaceContainerLowest = canvas,
        surfaceContainerLow = snow,
        surfaceContainer = snow,
        surfaceContainerHigh = snow,
        surfaceContainerHighest = mist,

        outline = silver,
        outlineVariant = hairline,
        scrim = scrim
    )
}

/**
 * Lets the app draw behind the status and navigation bars, and keeps their icons readable over it:
 * dark icons over a light theme, light over a dark one. The system would otherwise lay its own tinted
 * band behind each bar. A layout preview has no window to set up.
 */
@Composable
private fun ClearSystemBars(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect { (view.context as Activity).window.clearSystemBars(view, darkIcons) }
}

private fun Window.clearSystemBars(view: View, darkIcons: Boolean) {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }
    with(WindowCompat.getInsetsController(this, view)) {
        isAppearanceLightStatusBars = darkIcons
        isAppearanceLightNavigationBars = darkIcons
    }
}
