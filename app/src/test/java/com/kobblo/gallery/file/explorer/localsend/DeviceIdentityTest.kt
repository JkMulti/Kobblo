package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.NonceMemory
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant

class DeviceIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theFirstUseMakesAKeyAndACertificateAndKeepsThemWhereEveryBuildHas() {
        val dir = temp.newFolder()
        val identity = DeviceIdentity.at(dir)

        assertEquals(setOf("localsend_key.der", "localsend_cert.der"), dir.list()!!.toSet())
        // The key is PKCS#8 and the certificate plain DER, which is how they've always been written.
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(File(dir, "localsend_key.der").readBytes()))
        assertEquals(2048, (key as RSAPrivateKey).modulus.bitLength())
        assertTrue(File(dir, "localsend_cert.der").readBytes().contentEquals(identity.certificate.encoded))
    }

    @Test fun theCertificateIsSelfSignedForAnRsaKeyAndNeverNeedsRenewing() {
        val certificate = DeviceIdentity.at(temp.newFolder()).certificate

        assertEquals("CN=LocalSend User", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertEquals(2048, (certificate.publicKey as RSAPublicKey).modulus.bitLength())
        assertEquals("SHA256withRSA", certificate.sigAlgName.replace("WITH", "with"))
        certificate.verify(certificate.publicKey)
        assertEquals(Instant.parse("1975-01-01T00:00:00Z"), certificate.notBefore.toInstant())
        assertEquals(Instant.parse("4096-01-01T00:00:00Z"), certificate.notAfter.toInstant())
        assertTrue(certificate.serialNumber.signum() >= 0 && certificate.serialNumber.bitLength() <= 64)
    }

    @Test fun theFingerprintIsTheCertificatesSha256InCapitalHex() {
        val identity = DeviceIdentity.at(temp.newFolder())
        val expected = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded)
            .joinToString("") { "%02X".format(it) }

        assertEquals(expected, identity.fingerprint)
        assertEquals(64, identity.fingerprint.length)
        assertTrue(identity.fingerprint.all { it in "0123456789ABCDEF" })
        assertEquals(expected, Fingerprints.of(identity.certificate.encoded))
    }

    @Test fun thePhoneStaysTheSameDeviceFromOneRunToTheNext() {
        val dir = temp.newFolder()
        val first = DeviceIdentity.at(dir)
        val again = DeviceIdentity.at(dir)

        assertEquals(first.fingerprint, again.fingerprint)
        assertEquals(first.certificate, again.certificate)
    }

    @Test fun twoPhonesAreNeverTheSameDevice() {
        assertNotEquals(DeviceIdentity.at(temp.newFolder()).fingerprint, DeviceIdentity.at(temp.newFolder()).fingerprint)
    }

    @Test fun filesThatCannotBeReadAreReplacedByANewIdentity() {
        val dir = temp.newFolder()
        val lost = DeviceIdentity.at(dir).fingerprint
        File(dir, "localsend_cert.der").writeText("not a certificate")

        val fresh = DeviceIdentity.at(dir)

        assertNotEquals(lost, fresh.fingerprint)
        assertEquals(fresh.fingerprint, DeviceIdentity.at(dir).fingerprint)
    }

    @Test fun halfAnIdentityIsNoIdentity() {
        val dir = temp.newFolder()
        val lost = DeviceIdentity.at(dir).fingerprint
        File(dir, "localsend_key.der").delete()

        val fresh = DeviceIdentity.at(dir)

        assertNotEquals(lost, fresh.fingerprint)
        assertTrue(File(dir, "localsend_key.der").exists())
        assertEquals(fresh.fingerprint, DeviceIdentity.at(dir).fingerprint)
    }

    @Test fun itCanProveWhichDeviceItIsToOneReceiverOnly() {
        val sender = DeviceIdentity.at(temp.newFolder())
        val receiver = DeviceIdentity.at(temp.newFolder())
        val someoneElse = DeviceIdentity.at(temp.newFolder())
        val now = 1_800_000_000_000L

        val proof = sender.proofFor(receiver.fingerprint, now)

        assertTrue(SenderProof.verify(proof, sender.fingerprint, receiver.fingerprint, now, NonceMemory()))
        assertFalse(SenderProof.verify(proof, sender.fingerprint, someoneElse.fingerprint, now, NonceMemory()))
        assertFalse(SenderProof.verify(proof, someoneElse.fingerprint, receiver.fingerprint, now, NonceMemory()))
    }
}
