package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.IncomingRules
import com.kobblo.gallery.file.explorer.transfer.core.NetworkDiagnosis
import com.kobblo.gallery.file.explorer.transfer.core.NetworkFacts
import com.kobblo.gallery.file.explorer.transfer.core.PromptCooldown
import com.kobblo.gallery.file.explorer.transfer.core.ReachProblem
import com.kobblo.gallery.file.explorer.transfer.core.ReceivePaths
import com.kobblo.gallery.file.explorer.transfer.core.ResumeGrants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IncomingRulesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aNoteIsOneTextFileWithItsContentInThePreview() {
        assertTrue(IncomingRules.isNote(1, "text/plain", "hello"))
        assertTrue(IncomingRules.isNote(1, "TEXT/plain", ""))
    }

    @Test fun anythingElseIsAFile() {
        assertFalse(IncomingRules.isNote(1, "text/plain", null))
        assertFalse(IncomingRules.isNote(2, "text/plain", "hello"))
        assertFalse(IncomingRules.isNote(1, "image/png", "hello"))
        assertFalse(IncomingRules.isNote(1, null, "hello"))
    }

    @Test fun linksAreFoundInAMessage() {
        assertEquals("https://example.com/a?b=1", IncomingRules.firstLink("look at https://example.com/a?b=1 now"))
        assertEquals("http://example.com", IncomingRules.firstLink("http://example.com."))
        assertEquals("https://example.com/x", IncomingRules.firstLink("(https://example.com/x)"))
    }

    @Test fun onlyWebAddressesCount() {
        assertNull(IncomingRules.firstLink("javascript:alert(1)"))
        assertNull(IncomingRules.firstLink("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(IncomingRules.firstLink("content://media/external/1"))
        assertNull(IncomingRules.firstLink("file:///sdcard/secret"))
        assertNull(IncomingRules.firstLink("market://details?id=x"))
        assertNull(IncomingRules.firstLink("just words"))
        assertNull(IncomingRules.firstLink("https://"))
    }

    @Test fun theHostIsWhatsShown() {
        assertEquals("example.com", IncomingRules.linkHost("https://example.com/a"))
        assertEquals("evil.example", IncomingRules.linkHost("https://paypal.com@evil.example/login"))
    }

    @Test fun modifiedTimesParseFromTheProtocolsFormats() {
        assertEquals(1_000L, IncomingRules.parseModified("1970-01-01T00:00:01Z"))
        assertEquals(1_000L, IncomingRules.parseModified("1970-01-01T01:00:01+01:00"))
        assertNull(IncomingRules.parseModified("yesterday"))
        assertNull(IncomingRules.parseModified(null))
    }

    private fun existing(size: Int, modified: Long): File =
        temp.newFile().apply {
            writeBytes(ByteArray(size))
            setLastModified(modified)
        }

    @Test fun theSameFileFromAnEarlierTryIsNotSavedAgain() {
        assertTrue(IncomingRules.alreadyHave(existing(10, 100_000), 10, 100_000))
    }

    @Test fun timesWithinWhatFilesystemsRoundToStillMatch() {
        assertTrue(IncomingRules.alreadyHave(existing(10, 100_000), 10, 101_500))
    }

    @Test fun aDifferentSizeIsADifferentFile() {
        assertFalse(IncomingRules.alreadyHave(existing(10, 100_000), 11, 100_000))
    }

    @Test fun aDifferentTimeIsADifferentFile() {
        assertFalse(IncomingRules.alreadyHave(existing(10, 100_000), 10, 200_000))
    }

    @Test fun withNoTimeItCantTell() {
        assertFalse(IncomingRules.alreadyHave(existing(10, 100_000), 10, null))
    }

    @Test fun nothingThereMeansNotHere() {
        assertFalse(IncomingRules.alreadyHave(File(temp.root, "nope"), 10, 100_000))
    }

    private val files = listOf("a.jpg" to 10L, "b.jpg" to 20L)

    @Test fun aRetryOfAcceptedFilesNeedsNoSecondYes() {
        val grants = ResumeGrants()
        grants.remember("asha", files, 1_000)
        assertTrue(grants.covers("asha", listOf("b.jpg" to 20L), 2_000))
    }

    @Test fun grantsDontCoverOtherFiles() {
        val grants = ResumeGrants()
        grants.remember("asha", files, 1_000)
        assertFalse(grants.covers("asha", listOf("c.jpg" to 20L), 2_000))
        assertFalse(grants.covers("asha", listOf("a.jpg" to 11L), 2_000))
        assertFalse(grants.covers("asha", listOf("a.jpg" to 10L, "new.jpg" to 1L), 2_000))
    }

    @Test fun grantsDontCoverOtherSenders() {
        val grants = ResumeGrants()
        grants.remember("asha", files, 1_000)
        assertFalse(grants.covers("stranger", files, 2_000))
    }

    @Test fun grantsExpire() {
        val grants = ResumeGrants(windowMs = 100)
        grants.remember("asha", files, 1_000)
        assertFalse(grants.covers("asha", files, 1_200))
    }

    @Test fun anEmptyOfferIsNotCovered() {
        val grants = ResumeGrants()
        grants.remember("asha", files, 1_000)
        assertFalse(grants.covers("asha", emptyList(), 1_001))
    }

    @Test fun declinedSendersWaitTheirTurn() {
        val cooldown = PromptCooldown(cooldownMs = 100)
        assertFalse(cooldown.isCooling("1.1.1.1", 1_000))
        cooldown.declined("1.1.1.1", 1_000)
        assertTrue(cooldown.isCooling("1.1.1.1", 1_050))
        assertFalse(cooldown.isCooling("2.2.2.2", 1_050))
        assertFalse(cooldown.isCooling("1.1.1.1", 1_100))
    }

    @Test fun whyUnreachableSaysTheMostUsefulThing() {
        val wifi = listOf("192.168.1.20")
        assertEquals(ReachProblem.NoNetwork, NetworkDiagnosis.whyUnreachable(NetworkFacts(emptyList(), vpnOn = true), "192.168.1.5"))
        assertEquals(ReachProblem.Vpn, NetworkDiagnosis.whyUnreachable(NetworkFacts(wifi, vpnOn = true), "192.168.1.5"))
        assertEquals(ReachProblem.DifferentNetwork, NetworkDiagnosis.whyUnreachable(NetworkFacts(wifi, vpnOn = false), "10.0.0.5"))
        assertEquals(ReachProblem.Blocked, NetworkDiagnosis.whyUnreachable(NetworkFacts(wifi, vpnOn = false), "192.168.1.5"))
    }

    @Test fun aScanStaysOnThisNetwork() {
        val hosts = NetworkDiagnosis.neighbours("192.168.1.20")
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.20" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
        assertEquals(listOf("192.168.1.19", "192.168.1.21"), hosts.take(2).sorted())
    }

    @Test fun aScanNeverLeavesPrivateAddresses() {
        assertTrue(NetworkDiagnosis.neighbours("8.8.8.8").isEmpty())
        assertTrue(NetworkDiagnosis.neighbours("100.64.0.1").isEmpty())
        assertTrue(NetworkDiagnosis.neighbours("172.32.0.1").isEmpty())
        assertTrue(NetworkDiagnosis.neighbours("not an address").isEmpty())
        assertFalse(NetworkDiagnosis.neighbours("172.16.0.1").isEmpty())
        assertFalse(NetworkDiagnosis.neighbours("10.0.2.15").isEmpty())
    }

    @Test fun movingToAFreeNameNeverReplaces() {
        val dir = temp.newFolder()
        File(dir, "a.txt").writeText("first")
        val moved = ReceivePaths.moveToFreeName(File(dir, ".part").apply { writeText("second") }, dir, "a.txt")
        assertEquals("a (2).txt", moved.name)
        assertEquals("first", File(dir, "a.txt").readText())
        assertEquals("second", moved.readText())
    }

    @Test fun twoUploadsOfTheSameNameBothLand() {
        val dir = temp.newFolder()
        val names = (1..16).toList().parallelStream().map { i ->
            ReceivePaths.moveToFreeName(File(dir, ".p$i").apply { writeText("$i") }, dir, "same.txt").name
        }.toList()
        assertEquals(16, names.toSet().size)
    }

    @Test fun aFinishedTransferDoesntLetTheSameThingInAgainUnasked() {
        val grants = ResumeGrants()
        val files = listOf("a.jpg" to 5L)
        grants.remember("alpha", files, 1_000)
        assertTrue(grants.covers("alpha", files, 2_000))
        grants.forget("alpha")
        assertFalse(grants.covers("alpha", files, 2_000))
    }
}
