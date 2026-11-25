package com.kobblo.gallery.file.explorer.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {
    private val pins = FingerprintPins()
    private val table = PeerTable(
        ownFingerprint = { "MINE" },
        pin = pins::pinFromNetwork,
        unpin = { address -> if (!pins.isFromCode(address)) pins.expected.remove(address) }
    )

    private fun peer(alias: String, ip: String, fingerprint: String = alias.uppercase(), port: Int = 53318, protocol: String = "https", seenMs: Long = 0) =
        NearbyPeer(ip, port, alias, null, "mobile", protocol, fingerprint, seenMs)

    private val listed get() = table.listed.value

    @Test fun whoeverIsHeardIsListedByName() {
        table.admit(peer("Zoe", "10.0.0.3"))
        table.admit(peer("Asha", "10.0.0.2"))

        assertEquals(listOf("Asha", "Zoe"), listed.map { it.alias })
    }

    @Test fun hearingADeviceAgainBringsItUpToDateWithoutListingItTwice() {
        table.admit(peer("Asha", "10.0.0.2", seenMs = 1))
        table.admit(peer("Asha's phone", "10.0.0.2", fingerprint = "ASHA", seenMs = 9))

        assertEquals(1, listed.size)
        assertEquals("Asha's phone", listed.single().alias)
        assertEquals(9L, listed.single().lastSeenMs)
    }

    @Test fun thisPhoneNeverListsItself() {
        table.admit(peer("Me", "10.0.0.9", fingerprint = "mine"))

        assertTrue(listed.isEmpty())
    }

    @Test fun aPortThatCouldNotBeCalledIsNotListed() {
        table.admit(peer("Zero", "10.0.0.4", port = 0))
        table.admit(peer("Huge", "10.0.0.5", port = 65536))
        table.admit(peer("Negative", "10.0.0.6", port = -1))
        table.admit(peer("Edge", "10.0.0.7", port = 65535))

        assertEquals(listOf("Edge"), listed.map { it.alias })
    }

    @Test fun anyProtocolButPlainHttpIsTakenAsTls() {
        table.admit(peer("A", "10.0.0.2", protocol = "http"))
        table.admit(peer("B", "10.0.0.3", protocol = "HTTP"))
        table.admit(peer("C", "10.0.0.4", protocol = "gopher"))

        assertEquals(listOf("http", "https", "https"), listed.map { it.protocol })
    }

    @Test fun listingADevicePinsItsAddressToWhatItClaimed() {
        table.admit(peer("Asha", "10.0.0.2", fingerprint = "AAAA"))

        assertEquals("AAAA", pins.expected["10.0.0.2:53318"])
    }

    @Test fun anAnnouncementCannotTakeOverAnAddressAScannedCodeSettled() {
        pins.pinFromCode("10.0.0.2:53318", "REAL")

        table.admit(peer("Impostor", "10.0.0.2", fingerprint = "FAKE"))
        assertTrue(listed.isEmpty())

        table.admit(peer("Asha", "10.0.0.2", fingerprint = "real"))
        assertEquals(listOf("Asha"), listed.map { it.alias })
        assertEquals("REAL", pins.expected["10.0.0.2:53318"])
    }

    @Test fun aDeviceThatGoesQuietIsDroppedAndItsClaimWithIt() {
        table.admit(peer("Gone", "10.0.0.2", seenMs = 1_000))
        table.admit(peer("Here", "10.0.0.3", seenMs = 20_000))

        table.dropSilent(nowMs = 26_001, silenceMs = 25_000)

        assertEquals(listOf("Here"), listed.map { it.alias })
        assertNull(pins.expected["10.0.0.2:53318"])
        assertEquals("HERE", pins.expected["10.0.0.3:53318"])
    }

    @Test fun exactlyTheSilenceAllowedIsStillHere() {
        table.admit(peer("Asha", "10.0.0.2", seenMs = 1_000))

        table.dropSilent(nowMs = 26_000, silenceMs = 25_000)

        assertEquals(1, listed.size)
    }

    @Test fun goingQuietDoesNotUndoAScannedCode() {
        pins.pinFromCode("10.0.0.2:53318", "REAL")
        table.admit(peer("Asha", "10.0.0.2", fingerprint = "REAL", seenMs = 0))

        table.dropSilent(nowMs = 60_000, silenceMs = 25_000)

        assertTrue(listed.isEmpty())
        assertEquals("REAL", pins.expected["10.0.0.2:53318"])
        assertTrue(pins.isFromCode("10.0.0.2:53318"))
    }

    @Test fun clearingEmptiesTheListAndLeavesThePinsAlone() {
        table.admit(peer("Asha", "10.0.0.2", fingerprint = "AAAA"))

        table.clear()

        assertTrue(listed.isEmpty())
        assertEquals("AAAA", pins.expected["10.0.0.2:53318"])
    }
}
