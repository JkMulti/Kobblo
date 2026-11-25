package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kobblo.gallery.file.explorer.ui.design.AmoledKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.DarkKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.KobbloColors
import com.kobblo.gallery.file.explorer.ui.design.LightKobbloColors

/**
 * Compatibility layer: screens written against these names read Kobblo Design's tokens.
 * New code should use `KobbloDesign.colors` directly.
 */
@Immutable
object ExpressiveTokens {
    val DarkInk = DarkKobbloColors.onAction
    val DarkSand = DarkKobbloColors.smoke
    val DarkText = DarkKobbloColors.ink
    val DarkMuted = DarkKobbloColors.smoke
    val LightText = LightKobbloColors.ink
    val LightMuted = LightKobbloColors.smoke

    // ── File-type indicators ──
    // These tint icons on a neutral tile and are never fills. A neutral tile takes the theme's ink when
    // drawn. Each differs from its Kobblo Design token by one step so that a real surface passed as a tile's
    // fill, such as the action blue, is never mistaken for an indicator.
    val Neutral = Color(0xFF101011)
    val ImageTint = Color(0xFF5856D7)
    val VideoTint = Color(0xFFFF2D56)
    val AudioTint = Color(0xFFFF9501)
    val DocumentTint = Color(0xFF007AFE)
    val AppTint = Color(0xFF34C75A)
    val DownloadTint = Color(0xFF30B0C8)
    val ArchiveTint = Color(0xFFA2845F)
    val LargeFileTint = Color(0xFF8E8E94)

    private val indicators = setOf(Neutral, ImageTint, VideoTint, AudioTint, DocumentTint, AppTint, DownloadTint, ArchiveTint, LargeFileTint)

    /** True when [color] is a file-type indicator rather than a real surface color. */
    fun isIndicator(color: Color): Boolean = color in indicators

    fun categoryTint(category: String): Color = when (category.lowercase()) {
        "images", "image" -> ImageTint
        "videos", "video" -> VideoTint
        "audio", "music" -> AudioTint
        "docs", "doc", "documents" -> DocumentTint
        "apps", "apk" -> AppTint
        "downloads", "download" -> DownloadTint
        "archives", "archive", "zip", "rar", "7z" -> ArchiveTint
        "large", "big" -> LargeFileTint
        else -> Neutral
    }

    /** A file type's tint, ready to draw: the neutral one takes the theme's ink. */
    @Composable
    fun categoryIconTint(category: String): Color {
        val tint = categoryTint(category)
        return if (tint == Neutral) com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors.ink else tint
    }
}

/**
 * Kobblo Design's colors under the names the compatibility layer offers. It holds no colors of its
 * own: each name reads one design token, so a screen written against either set draws the same thing.
 */
@Immutable
class ExpressiveColorScheme internal constructor(private val design: KobbloColors) {
    val bg: Color get() = design.canvas
    val container: Color get() = design.canvas
    val card: Color get() = design.snow
    val insetCard: Color get() = design.mist
    val card2: Color get() = insetCard
    val accent: Color get() = design.action
    /** What is drawn on top of [accent], not the text color: that is [text]. */
    val ink: Color get() = design.onAction
    val sand: Color get() = design.smoke
    val text: Color get() = design.ink
    val muted: Color get() = design.smoke
    /** The hairline at full strength. Its own transparency is kept apart, in [lineAlpha]. */
    val line: Color = design.hairline.copy(alpha = 1f)
    val lineAlpha: Float get() = design.hairline.alpha
    val shadow: Color get() = design.ambient
    val danger: Color get() = design.danger
    val glow: Color get() = Color.Transparent
    val isAmoled: Boolean get() = design.isAmoled

    // Two schemes over the same tokens are the same scheme, so providing a fresh one for an unchanged
    // theme redraws nothing.
    override fun equals(other: Any?): Boolean = other is ExpressiveColorScheme && other.design == design
    override fun hashCode(): Int = design.hashCode()
}

internal fun KobbloColors.toExpressive() = ExpressiveColorScheme(this)

val DarkExpressiveColorScheme = DarkKobbloColors.toExpressive()
val AmoledExpressiveColorScheme = AmoledKobbloColors.toExpressive()
val LightExpressiveColorScheme = LightKobbloColors.toExpressive()

val LocalExpressiveColors = staticCompositionLocalOf { LightExpressiveColorScheme }

/** Where a screen reads the scheme from: `ExpressiveTheme.colors`. */
object ExpressiveTheme {
    val colors: ExpressiveColorScheme
        @Composable @ReadOnlyComposable get() = LocalExpressiveColors.current
}
