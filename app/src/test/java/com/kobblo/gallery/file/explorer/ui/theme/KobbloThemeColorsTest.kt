package com.kobblo.gallery.file.explorer.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.ui.design.AmoledKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.DarkKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import com.kobblo.gallery.file.explorer.ui.design.KobbloColors
import com.kobblo.gallery.file.explorer.ui.design.LightKobbloColors
import com.kobblo.gallery.file.explorer.ui.design.withAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class KobbloThemeColorsTest {
    /** Every palette the theme can come up with: each mode, in each tint. */
    private val palettes: List<KobbloColors> = KobbloAccent.entries.flatMap { accent ->
        listOf(LightKobbloColors, DarkKobbloColors, AmoledKobbloColors).map { it.withAccent(accent) }
    }

    @Test
    fun lightIsLightWhateverTheDarkStyle() {
        for (style in DarkSurfaceStyle.entries) {
            assertEquals(LightKobbloColors, kobbloPalette(dark = false, darkSurface = style, accent = KobbloAccent.Default))
        }
    }

    @Test
    fun darkIsStandardOrAmoledAsChosen() {
        assertEquals(DarkKobbloColors, kobbloPalette(dark = true, darkSurface = DarkSurfaceStyle.STANDARD, accent = KobbloAccent.Default))
        assertEquals(AmoledKobbloColors, kobbloPalette(dark = true, darkSurface = DarkSurfaceStyle.AMOLED, accent = KobbloAccent.Default))
    }

    @Test
    fun theTintIsTakenForTheMode() {
        for (accent in KobbloAccent.entries) {
            assertEquals(accent.light, kobbloPalette(false, DarkSurfaceStyle.STANDARD, accent).action)
            assertEquals(accent.dark, kobbloPalette(true, DarkSurfaceStyle.STANDARD, accent).action)
            assertEquals(accent.dark, kobbloPalette(true, DarkSurfaceStyle.AMOLED, accent).action)
            assertEquals(LightKobbloColors.withAccent(accent), kobbloPalette(false, DarkSurfaceStyle.AMOLED, accent))
            assertEquals(AmoledKobbloColors.withAccent(accent), kobbloPalette(true, DarkSurfaceStyle.AMOLED, accent))
        }
    }

    @Test
    fun theTintIsMaterialsPrimaryWithWhiteOnIt() {
        for (palette in palettes) {
            val material = palette.materialColors()
            assertEquals(palette.action, material.primary)
            assertEquals(palette.onAction, material.onPrimary)
            assertEquals(palette.action, material.inversePrimary)
            assertEquals(palette.snow, material.primaryContainer)
            assertEquals(palette.ink, material.onPrimaryContainer)
        }
    }

    @Test
    fun secondaryAndTertiaryAreTheGrayOfSecondaryText() {
        for (palette in palettes) {
            val material = palette.materialColors()
            assertEquals(palette.smoke, material.secondary)
            assertEquals(palette.canvas, material.onSecondary)
            assertEquals(palette.snow, material.secondaryContainer)
            assertEquals(palette.ink, material.onSecondaryContainer)
            assertEquals(palette.smoke, material.tertiary)
            assertEquals(palette.canvas, material.onTertiary)
            assertEquals(palette.mist, material.tertiaryContainer)
            assertEquals(palette.ink, material.onTertiaryContainer)
        }
    }

    @Test
    fun errorsAreTheDestructiveRed() {
        for (palette in palettes) {
            val material = palette.materialColors()
            assertEquals(palette.danger, material.error)
            assertEquals(palette.onAction, material.onError)
            assertEquals(palette.snow, material.errorContainer)
            assertEquals(palette.danger, material.onErrorContainer)
        }
    }

    @Test
    fun surfacesAreThePageAndItsGroups() {
        for (palette in palettes) {
            val material = palette.materialColors()
            assertEquals(palette.canvas, material.background)
            assertEquals(palette.ink, material.onBackground)
            assertEquals(palette.canvas, material.surface)
            assertEquals(palette.ink, material.onSurface)
            assertEquals(palette.snow, material.surfaceVariant)
            assertEquals(palette.smoke, material.onSurfaceVariant)
            assertEquals(palette.ink, material.inverseSurface)
            assertEquals(palette.canvas, material.inverseOnSurface)
            assertEquals(palette.canvas, material.surfaceContainerLowest)
            assertEquals(palette.snow, material.surfaceContainerLow)
            assertEquals(palette.snow, material.surfaceContainer)
            assertEquals(palette.snow, material.surfaceContainerHigh)
            assertEquals(palette.mist, material.surfaceContainerHighest)
        }
    }

    @Test
    fun outlinesAndTheScrimComeFromTheDesign() {
        for (palette in palettes) {
            val material = palette.materialColors()
            assertEquals(palette.silver, material.outline)
            assertEquals(palette.hairline, material.outlineVariant)
            assertEquals(palette.scrim, material.scrim)
        }
    }

    @Test
    fun elevationNeverTintsASurface() {
        for (palette in palettes) assertEquals(Color.Transparent, palette.materialColors().surfaceTint)
    }

    @Test
    fun rolesKobbloLeavesAloneKeepMaterialsValueForTheMode() {
        for (palette in palettes) {
            val material = palette.materialColors()
            val untouched = if (palette.isDark) darkColorScheme() else lightColorScheme()
            assertEquals(untouched.surfaceBright, material.surfaceBright)
            assertEquals(untouched.surfaceDim, material.surfaceDim)
        }
    }

    @Test
    fun amoledDeepensTheGroupsAndLeavesThePageBlack() {
        val dark = DarkKobbloColors.materialColors()
        val amoled = AmoledKobbloColors.materialColors()
        assertEquals(Color.Black, dark.background)
        assertEquals(Color.Black, amoled.background)
        assertEquals(AmoledKobbloColors.snow, amoled.surfaceContainer)
        assertEquals(AmoledKobbloColors.mist, amoled.surfaceContainerHighest)
    }

    @Test
    fun theOlderTextColorFollowsTheThemeLastApplied() {
        CompatColors.follow(DarkKobbloColors)
        assertEquals(DarkKobbloColors.ink, CompatColors.TextPrimary)
        CompatColors.follow(AmoledKobbloColors.withAccent(KobbloAccent.Pink))
        assertEquals(AmoledKobbloColors.ink, CompatColors.TextPrimary)
        CompatColors.follow(LightKobbloColors)
        assertEquals(LightKobbloColors.ink, CompatColors.TextPrimary)
    }
}
