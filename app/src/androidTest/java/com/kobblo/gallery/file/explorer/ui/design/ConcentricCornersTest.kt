package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kobblo.gallery.file.explorer.HapticsSettings
import com.kobblo.gallery.file.explorer.gallery.WorkPill
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.theme.KobbloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A round button at a capsule's end shares the center of that end, so the gap around it is even. */
@RunWith(AndroidJUnit4::class)
class ConcentricCornersTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun workPillCancelSitsOnTheCenterOfThePillsRoundEnd() = checkPill(fontScale = 1f)

    @Test
    fun workPillCancelStaysCenteredWhenALargerFontMakesThePillTaller() = checkPill(fontScale = 2f)

    private fun checkPill(fontScale: Float) {
        rule.setContent {
            KobbloTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalHaptics provides HapticsController(null) { HapticsSettings() },
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    Box(Modifier.width(380.dp)) {
                        WorkPill(title = "Copying to Trips", done = 3, total = 9, modifier = Modifier.testTag("pill"), onCancel = {})
                    }
                }
            }
        }
        val outer = rule.onNodeWithTag("pill").getUnclippedBoundsInRoot()
        // The pill sits 16dp inside what it's given on each side, and fills its height.
        val pillRight = outer.right - 16.dp
        val radius = (outer.bottom - outer.top) / 2
        val cancel = rule.onNodeWithContentDescription("Cancel").getUnclippedBoundsInRoot()
        val centerX = (cancel.left + cancel.right) / 2
        val centerY = (cancel.top + cancel.bottom) / 2

        assertEquals("across", (pillRight - radius).value, centerX.value, 0.75f)
        assertEquals("down", (outer.top + radius).value, centerY.value, 0.75f)
    }
}
