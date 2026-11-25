package com.kobblo.gallery.file.explorer.haptics

import com.kobblo.gallery.file.explorer.HapticEvent
import com.kobblo.gallery.file.explorer.HapticsSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test
    fun eachKindOfEventHasItsOwnLength() {
        assertEquals(12L, HapticEvent.FileOpen.pulseMillis)
        assertEquals(14L, HapticEvent.CopyPaste.pulseMillis)
        assertEquals(18L, HapticEvent.Delete.pulseMillis)
        assertEquals(22L, HapticEvent.Cleaner.pulseMillis)
        assertEquals(12L, HapticEvent.SettingsToggle.pulseMillis)
        assertEquals(16L, HapticEvent.Fab.pulseMillis)
        assertEquals(12L, HapticEvent.General.pulseMillis)
    }

    @Test
    fun noPulseIsLongEnoughToReadAsAWarning() {
        for (event in HapticEvent.entries) {
            assertTrue("$event", event.pulseMillis in 10L..25L)
        }
    }

    @Test
    fun aTickIsLighterThanAnyTap() {
        assertEquals(5L, FallbackTickMillis)
        assertTrue(HapticEvent.entries.all { it.pulseMillis > FallbackTickMillis })
    }

    @Test
    fun withNoMotorNothingIsBuiltOrPlayed() {
        // Building an effect needs a real phone, so this passing shows none was built.
        val controller = HapticsController(vibrator = null) { HapticsSettings() }
        for (event in HapticEvent.entries) controller.fire(event)
        controller.fileOpen()
        controller.copyPaste()
        controller.delete()
        controller.cleaner()
        controller.settingsToggle()
        controller.fab()
        controller.tap()
        controller.tick()
    }

    @Test
    fun theSettingsAreAskedAfreshForEveryEvent() {
        var asked = 0
        val controller = HapticsController(vibrator = null) {
            asked++
            HapticsSettings(masterEnabled = false)
        }
        controller.tap()
        controller.delete()
        controller.tick()
        assertEquals(3, asked)
    }
}
