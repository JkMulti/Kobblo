package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.DeviceName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNameTest {
    @Test fun thePhonesOwnNameComesFirst() {
        assertEquals("Galaxy A56", DeviceName.choose("Galaxy A56", "samsung", "SM-A566E"))
        assertEquals("Alex's phone", DeviceName.choose("  Alex's phone ", "samsung", "SM-A566E"))
    }

    @Test fun withoutOneTheMakerAndModelAreUsed() {
        assertEquals("Samsung SM-A566E", DeviceName.choose(null, "samsung", "SM-A566E"))
        assertEquals("Samsung SM-A566E", DeviceName.choose("   ", "samsung", "SM-A566E"))
    }

    @Test fun aModelThatAlreadyNamesItsMakerIsNotDoubled() {
        assertEquals("Google Pixel 8", DeviceName.choose(null, "Google", "Google Pixel 8"))
        assertEquals("motorola edge", DeviceName.choose(null, "Motorola", "motorola edge"))
    }

    @Test fun anUnknownMakerIsLeftOut() {
        assertEquals("Android SDK built for arm64", DeviceName.choose(null, "unknown", "Android SDK built for arm64"))
    }

    @Test fun aMakerAloneIsEnough() {
        assertEquals("Samsung", DeviceName.choose(null, "samsung", ""))
    }

    // The app's resources then name it, in the phone's language.
    @Test fun withNothingToGoOnItsLeftToTheApp() {
        assertNull(DeviceName.choose(null, "unknown", ""))
        assertNull(DeviceName.choose(null, null, null))
    }
}
