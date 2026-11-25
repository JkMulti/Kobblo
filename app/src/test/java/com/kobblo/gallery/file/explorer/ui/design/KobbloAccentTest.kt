package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class KobbloAccentTest {
    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun everyTintReadsOnThePageAndUnderWhiteText() {
        val white = Color.White
        for (accent in KobbloAccent.entries) {
            val light = accent.color(isDark = false)
            val dark = accent.color(isDark = true)
            assertTrue("$accent as text on white: ${contrast(light, white)}", contrast(light, white) >= 3.0)
            assertTrue("white text on $accent in light: ${contrast(white, light)}", contrast(white, light) >= 3.0)
            assertTrue("$accent as text on black: ${contrast(dark, Color.Black)}", contrast(dark, Color.Black) >= 4.0)
            assertTrue("white text on $accent in dark: ${contrast(white, dark)}", contrast(white, dark) >= 3.0)
        }
    }

    @Test
    fun blueIsTheDefaultAndTheOriginalTint() {
        assertEquals(KobbloAccent.Blue, KobbloAccent.Default)
        assertEquals(LightKobbloColors.action, KobbloAccent.Blue.light)
        assertEquals(DarkKobbloColors.action, KobbloAccent.Blue.dark)
    }

    @Test
    fun aSavedNameComesBackAsItsTint() {
        for (accent in KobbloAccent.entries) {
            assertEquals(accent, KobbloAccent.fromName(accent.name))
        }
    }

    @Test
    fun aMissingOrUnknownNameFallsBackToBlue() {
        assertEquals(KobbloAccent.Blue, KobbloAccent.fromName(null))
        assertEquals(KobbloAccent.Blue, KobbloAccent.fromName(""))
        assertEquals(KobbloAccent.Blue, KobbloAccent.fromName("Red"))
    }

    @Test
    fun aTintChangesOnlyTheActionColorForTheMode() {
        val light = LightKobbloColors.withAccent(KobbloAccent.Purple)
        assertEquals(KobbloAccent.Purple.light, light.action)
        assertEquals(LightKobbloColors.copy(action = KobbloAccent.Purple.light), light)

        val amoled = AmoledKobbloColors.withAccent(KobbloAccent.Green)
        assertEquals(KobbloAccent.Green.dark, amoled.action)
        assertEquals(AmoledKobbloColors.copy(action = KobbloAccent.Green.dark), amoled)
    }
}
