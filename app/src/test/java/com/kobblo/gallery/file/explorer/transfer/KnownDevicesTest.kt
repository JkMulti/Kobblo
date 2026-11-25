package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.transfer.core.NonceMemory
import com.kobblo.gallery.file.explorer.transfer.core.PeerMerge
import com.kobblo.gallery.file.explorer.transfer.core.PeerNames
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeerStore
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Date

class KnownDevicesTest {
    @get:Rule val temp = TemporaryFolder()

    private class Identity(val keys: KeyPair, val der: ByteArray) {
        val fingerprint = SenderProof.fingerprintOf(der)
    }

    private fun identity(): Identity {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=LocalSend User")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4_000_000_000_000), name, keys.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        return Identity(keys, holder.encoded)
    }

    private val now = 1_800_000_000_000L
    private val asha = identity()
    private val stranger = identity()
    private val me = identity()

    private fun proof(from: Identity = asha, to: String = me.fingerprint, at: Long = now) =
        SenderProof.create(from.keys.private, from.der, to, at)

    private fun verify(header: String?, claimed: String = asha.fingerprint, own: String = me.fingerprint, at: Long = now, nonces: NonceMemory = NonceMemory()) =
        SenderProof.verify(header, claimed, own, at, nonces)

    @Test fun aRealSignatureIsAccepted() {
        assertTrue(verify(proof()))
    }

    @Test fun noSignatureMeansUnproven() {
        assertFalse(verify(null))
        assertFalse(verify(""))
    }

    @Test fun aStrangerCantClaimSomeoneElsesFingerprint() {
        assertFalse(verify(proof(from = stranger), claimed = asha.fingerprint))
    }

    @Test fun aSignatureCantBeTakenToAnotherDevice() {
        val forSomeoneElse = proof(to = stranger.fingerprint)
        assertFalse(verify(forSomeoneElse))
    }

    @Test fun aSignatureCantBeUsedTwice() {
        val nonces = NonceMemory()
        val header = proof()
        assertTrue(verify(header, nonces = nonces))
        assertFalse(verify(header, nonces = nonces))
    }

    @Test fun anOldSignatureIsRefused() {
        assertFalse(verify(proof(at = now - SenderProof.MAX_AGE_MS - 1)))
    }

    @Test fun aSignatureFromTheFutureIsRefused() {
        assertFalse(verify(proof(at = now + SenderProof.MAX_AGE_MS + 1)))
    }

    @Test fun clocksCanDisagreeALittle() {
        assertTrue(verify(proof(at = now - 5 * 60 * 1000)))
    }

    @Test fun aTamperedSignatureIsRefused() {
        val parts = proof().split('.').toMutableList()
        parts[2] = (now + 1).toString()
        assertFalse(verify(parts.joinToString(".")))
    }

    @Test fun garbageIsJustUnproven() {
        assertFalse(verify("1.a.b.c.d"))
        assertFalse(verify("2.${proof().substringAfter('.')}"))
        assertFalse(verify("not a proof at all"))
    }

    @Test fun fingerprintsCompareWhateverTheirCase() {
        assertTrue(verify(proof(), claimed = asha.fingerprint.lowercase()))
    }

    private fun peer(
        ip: String,
        alias: String,
        fingerprint: String,
        protocol: String = "https"
    ) = NearbyPeer(ip, 1, alias, null, "mobile", protocol, fingerprint, 0)

    private fun saved(
        fingerprint: String,
        alias: String = "Asha",
        lastUsedMs: Long = 100,
        verified: Boolean = false,
        protocol: String = "https"
    ) = SavedPeer(fingerprint, alias, host = "10.0.0.5", port = 1, protocol = protocol, lastUsedMs = lastUsedMs, verified = verified)

    @Test fun knownDevicesComeFirstMostRecentFirst() {
        val merged = PeerMerge.merge(
            listOf(peer("1.1.1.1", "Apple", "AA"), peer("1.1.1.2", "Yusuf", "BB"), peer("1.1.1.3", "Zoe", "CC")),
            emptyList(),
            listOf(saved("BB", lastUsedMs = 200), saved("CC", lastUsedMs = 300))
        )
        assertEquals(listOf("Zoe", "Yusuf", "Apple"), merged.map { it.alias })
        assertEquals(listOf(true, true, false), merged.map { it.known })
    }

    @Test fun aNameDoesntMakeADeviceKnown() {
        val merged = PeerMerge.merge(listOf(peer("1.1.1.1", "Asha", "EVIL")), emptyList(), listOf(saved("AA", alias = "Asha")))
        assertFalse(merged.single().known)
    }

    @Test fun aPlainHttpAnnouncementCantBorrowAKnownFingerprint() {
        val merged = PeerMerge.merge(
            listOf(peer("6.6.6.6", "Asha", "AA", protocol = "http")),
            emptyList(),
            listOf(saved("AA", verified = true))
        )
        assertFalse(merged.single().known)
        assertFalse(merged.single().verified)
    }

    @Test fun aPlainHttpAnnouncementCantPushOutTheRealDevice() {
        val merged = PeerMerge.merge(
            listOf(peer("6.6.6.6", "Asha", "AA", protocol = "http")),
            listOf(peer("10.0.0.5", "Asha", "AA")),
            listOf(saved("AA"))
        )
        assertEquals(2, merged.size)
        assertEquals("10.0.0.5", merged.first().ip)
        assertTrue(merged.first().known)
    }

    @Test fun absentListsKnownDevicesThatAreNotHere() {
        val saved = listOf(saved("AA", "Asha", 100), saved("BB", "Ben", 200), saved("CC", "Connected only", 0))
        val absent = PeerMerge.absent(saved, listOf(peer("1.1.1.1", "Asha", "aa")))
        assertEquals(listOf("Ben"), absent.map { it.alias })
    }

    @Test fun borrowedNamesAreSpotted() {
        val known = listOf(saved("AA", "Asha's Phone"))
        assertTrue(PeerMerge.impersonates(" asha's phone", "EVIL", known))
        assertFalse(PeerMerge.impersonates("Asha's Phone", "aa", known))
        assertFalse(PeerMerge.impersonates("Ben", "EVIL", known))
    }

    @Test fun namesAreCleanedOfTricks() {
        assertEquals("Ashaenohp.exe", PeerNames.clean("Asha‮enohp.exe", "Unknown"))
        assertEquals("Asha Phone", PeerNames.clean("  Asha\u0000 Phone\n", "Unknown"))
        assertEquals("Unknown", PeerNames.clean("​​", "Unknown"))
        assertEquals(64, PeerNames.clean("x".repeat(500), "Unknown").length)
    }

    private fun store() = SavedPeerStore(File(temp.root, "known.json"))

    @Test fun aChoiceToAcceptSurvivesAReconnect() {
        val store = store()
        store.save(saved("AA").copy(canProve = true))
        store.setAutoAccept("aa", true)
        val updated = store.save(saved("AA", lastUsedMs = 0))
        assertTrue(updated.single().autoAccept)
        assertTrue(updated.single().canProve)
        assertEquals(100, updated.single().lastUsedMs)
    }

    @Test fun turningItOffStaysOff() {
        val store = store()
        store.save(saved("AA").copy(canProve = true, autoAccept = true))
        store.setAutoAccept("AA", false)
        assertFalse(store.save(saved("AA")).single().autoAccept)
    }

    @Test fun forgettingTakesEverythingWithIt() {
        val store = store()
        store.save(saved("AA").copy(canProve = true, autoAccept = true))
        assertTrue(store.forget("aa").isEmpty())
        assertFalse(store.save(saved("AA")).single().autoAccept)
    }

    @Test fun anOlderFileStillLoads() {
        val file = File(temp.root, "old.json").apply {
            writeText("""[{"fingerprint":"AA","alias":"Mac","deviceType":"desktop","host":"10.0.0.1","port":53317,"protocol":"https","verified":true}]""")
        }
        val loaded = SavedPeerStore(file).all().single()
        assertEquals(0L, loaded.lastUsedMs)
        assertFalse(loaded.autoAccept)
    }

    @Test fun theStoreKeepsTheMostRecentlyUsed() {
        val store = SavedPeerStore(File(temp.root, "small.json"), maxPeers = 2)
        store.save(saved("AA", lastUsedMs = 1))
        store.save(saved("BB", lastUsedMs = 3))
        val kept = store.save(saved("CC", lastUsedMs = 2))
        assertEquals(listOf("BB", "CC"), kept.map { it.fingerprint })
    }
}
