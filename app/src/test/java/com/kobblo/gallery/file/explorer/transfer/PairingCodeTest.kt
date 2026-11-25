package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.PairingCode
import com.kobblo.gallery.file.explorer.transfer.core.PairingParse
import com.kobblo.gallery.file.explorer.transfer.core.PairingWifi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    private val fp = "AB".repeat(32)

    private fun ok(text: String): PairingCode = (PairingCode.parse(text) as PairingParse.Ok).code

    private fun assertInvalid(text: String) {
        assertTrue("expected invalid: $text", PairingCode.parse(text) is PairingParse.Invalid)
    }

    @Test fun roundTrip() {
        val code = PairingCode(listOf("192.168.1.5"), 53318, "https", fp, "Pixel 8")
        assertEquals(code, ok(code.encode()))
    }

    @Test fun roundTripWithNetwork() {
        val code = PairingCode(
            hosts = listOf("192.168.49.1", "10.0.0.2"),
            port = 53318,
            fingerprint = fp,
            alias = "Alex's Phone",
            wifi = PairingWifi("AndroidShare_1234", "p@ss word&=+#1")
        )
        assertEquals(code, ok(code.encode()))
    }

    @Test fun unicodeNamesSurvive() {
        val code = PairingCode(listOf("10.0.0.1"), 1, fingerprint = fp, alias = "写真の電話", wifi = PairingWifi("Café", null))
        assertEquals(code, ok(code.encode()))
    }

    @Test fun otherQrCodesAreNotPairing() {
        for (text in listOf("https://example.com", "WIFI:S:x;T:WPA;P:y;;", "hello", "", "kobblo://other?v=1", "kobblo://pairing?v=1")) {
            assertEquals("for [$text]", PairingParse.NotPairing, PairingCode.parse(text))
        }
    }

    @Test fun missingOrBadFieldsAreInvalid() {
        assertInvalid("kobblo://pair")
        assertInvalid("kobblo://pair?p=1&h=1.2.3.4&f=$fp")
        assertInvalid("kobblo://pair?v=1&p=1&f=$fp")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&f=$fp")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=0&f=$fp")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=70000&f=$fp")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=abc&f=$fp")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&s=ftp&f=$fp")
    }

    @Test fun newerVersionIsRefusedNotMisread() {
        assertInvalid("kobblo://pair?v=2&h=1.2.3.4&p=1&f=$fp")
    }

    @Test fun httpsNeedsARealFingerprint() {
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&f=abc")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&f=${"ZZ".repeat(32)}")
    }

    @Test fun httpDoesNotNeedOne() {
        val code = ok("kobblo://pair?v=1&h=1.2.3.4&p=1&s=http")
        assertEquals("http", code.protocol)
        assertNull(code.fingerprint)
    }

    @Test fun fingerprintIsNormalized() {
        val lower = "ab:".repeat(31) + "ab"
        assertEquals(fp, ok("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$lower").fingerprint)
    }

    @Test fun hostsCantSmuggleAUrl() {
        for (host in listOf(
            "evil.com/path", "user@evil.com", "evil.com:443", "1.2.3.4/x", "1.2.3.4#x", "a b", "[::1]",
            "999.1.1.1", "1.2.3", "1.2.3.4.5", "-bad.com", "%2F%2Fevil.com", "evil.com%2Fx", "exämple.com"
        )) {
            assertInvalid("kobblo://pair?v=1&h=$host&p=1&f=$fp")
        }
    }

    @Test fun firstValueOfARepeatedFieldWins() {
        val code = ok("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&h=6.6.6.6")
        assertEquals(listOf("1.2.3.4"), code.hosts)
    }

    @Test fun aliasIsCleanedAndCapped() {
        val alias = ok("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&n=a%0Ab").alias
        assertEquals("ab", alias)
        assertEquals(64, ok("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&n=${"x".repeat(200)}").alias?.length)
    }

    @Test fun networkFieldsAreChecked() {
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&w=x&k=short")
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&w=${"x".repeat(33)}&k=longenough")
        assertEquals(PairingWifi("open", null), ok("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&w=open").wifi)
    }

    @Test fun wpa3SurvivesTheRoundTrip() {
        val code = PairingCode(listOf("10.0.0.1"), 1, fingerprint = fp, wifi = PairingWifi("Net", "longenough", wpa3 = true))
        assertEquals(code, ok(code.encode()))
        assertFalse(ok(code.copy(wifi = PairingWifi("Net", "longenough")).encode()).wifi!!.wpa3)
    }

    @Test fun oversizedCodeIsRefused() {
        assertInvalid("kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp&n=${"x".repeat(3000)}")
    }

    @Test fun whitespaceAroundACodeIsFine() {
        assertEquals(listOf("1.2.3.4"), ok("  kobblo://pair?v=1&h=1.2.3.4&p=1&f=$fp\n").hosts)
    }

    @Test fun hostChecks() {
        assertTrue(PairingCode.isValidHost("192.168.1.1"))
        assertTrue(PairingCode.isValidHost("my-mac.local"))
        assertFalse(PairingCode.isValidHost(""))
        assertFalse(PairingCode.isValidHost("256.0.0.1"))
    }
}
