package com.kobblo.gallery.file.explorer.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintPinsTest {
    private val pins = FingerprintPins()
    private val laptop = "192.168.1.20:53318"

    @Test fun fingerprintsMatchWhateverTheirCaseOrColons() {
        assertTrue(Fingerprints.same("AB12CD", "ab12cd"))
        assertTrue(Fingerprints.same("AB:12:CD", "ab12cd"))
        assertTrue(Fingerprints.same("ab12cd", "AB:12:CD"))
        assertFalse(Fingerprints.same("AB12CD", "AB12CE"))
        assertFalse(Fingerprints.same("AB12CD", "AB12"))
    }

    @Test fun nothingToExpectIsNeverAMatch() {
        assertFalse(Fingerprints.same("", ""))
        assertFalse(Fingerprints.same("", "AB12"))
        assertFalse(Fingerprints.same("AB12", ""))
    }

    @Test fun theSharedFactsAgreeWithTheParts() {
        assertTrue(LocalSendSecurity.fingerprintsMatch("AB:12", "ab12"))
        assertFalse(LocalSendSecurity.fingerprintsMatch("", "ab12"))
        assertEquals("X-Kobblo-Seen-Fingerprint", LocalSendSecurity.SEEN_FINGERPRINT_HEADER)
    }

    @Test fun whatTheNetworkSaysIsNotedAndCanBeSaidAgainDifferently() {
        assertTrue(pins.pinFromNetwork(laptop, "AAAA"))
        assertEquals("AAAA", pins.expected[laptop])
        assertFalse(pins.isFromCode(laptop))

        assertTrue(pins.pinFromNetwork(laptop, "BBBB"))
        assertEquals("BBBB", pins.expected[laptop])
    }

    @Test fun aScannedCodeSettlesAnAddressAgainstAnythingHeardLater() {
        pins.pinFromCode(laptop, "AAAA")
        assertTrue(pins.isFromCode(laptop))

        assertFalse(pins.pinFromNetwork(laptop, "BBBB"))
        assertEquals("AAAA", pins.expected[laptop])
        // Hearing the same thing the code said is fine, however it's written.
        assertTrue(pins.pinFromNetwork(laptop, "aa:aa"))
        assertEquals("AAAA", pins.expected[laptop])
    }

    @Test fun aScannedCodeReplacesWhatWasOnlyHeard() {
        pins.pinFromNetwork(laptop, "BBBB")
        pins.pinFromCode(laptop, "AAAA")

        assertEquals("AAAA", pins.expected[laptop])
        assertTrue(pins.isFromCode(laptop))
    }

    @Test fun forgettingAnAddressLetsItBeAnyoneAgain() {
        pins.pinFromCode(laptop, "AAAA")
        pins.forget(laptop)

        assertNull(pins.expected[laptop])
        assertFalse(pins.isFromCode(laptop))
        assertTrue(pins.pinFromNetwork(laptop, "BBBB"))
    }

    @Test fun onePinSaysNothingAboutAnotherAddress() {
        pins.pinFromCode(laptop, "AAAA")

        assertTrue(pins.pinFromNetwork("192.168.1.20:53317", "BBBB"))
        assertTrue(pins.pinFromNetwork("192.168.1.21:53318", "CCCC"))
    }
}
