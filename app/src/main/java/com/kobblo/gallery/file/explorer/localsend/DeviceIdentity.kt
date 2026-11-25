package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Who this phone is to every other device: an RSA key, and a certificate for it that the phone signed itself.
 * The certificate's fingerprint is the phone's name on the network, the thing other devices pin and remember,
 * so both are made once and kept for as long as the app is installed.
 *
 * They live in the app's private files, where every build so far has put them and so where they stay:
 * - `localsend_key.der`: the private key, as PKCS#8.
 * - `localsend_cert.der`: the certificate, as X.509 DER.
 */
internal class DeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    val certificate: X509Certificate
) {
    val fingerprint: String = Fingerprints.of(certificate.encoded)

    /** For a server that answers as this device. It asks nothing of whoever connects. */
    fun serverSocketFactory(): SSLServerSocketFactory {
        // The key store never leaves memory. It has a password only because the API insists on one.
        val password = "password".toCharArray()
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("localsend", privateKey, password, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }.serverSocketFactory
    }

    /**
     * A signed statement, for the device with [receiverFingerprint] alone, that this is the device its
     * certificate says. See [SenderProof]. Null if it couldn't be signed.
     */
    fun proofFor(receiverFingerprint: String, nowMs: Long): String? =
        runCatching { SenderProof.create(privateKey, certificate.encoded, receiverFingerprint, nowMs) }.getOrNull()

    companion object {
        private const val KEY_FILE = "localsend_key.der"
        private const val CERTIFICATE_FILE = "localsend_cert.der"

        /** What other LocalSend devices put in theirs. Nothing reads it: a device is known by its fingerprint. */
        private const val SUBJECT = "CN=LocalSend User"
        private const val KEY_BITS = 2048
        private const val SERIAL_BITS = 64

        // It never needs renewing, because nothing checks the dates: a peer is held to the fingerprint alone.
        private val validFrom = LocalDate.of(1975, 1, 1)
        private val validUntil = LocalDate.of(4096, 1, 1)

        /**
         * The identity kept in [dir], or a new one saved there if there's none yet or what's there can't be
         * read. A new one changes the fingerprint, so every device that knew this phone meets a stranger.
         */
        fun at(dir: File): DeviceIdentity {
            val keyFile = File(dir, KEY_FILE)
            val certificateFile = File(dir, CERTIFICATE_FILE)
            if (keyFile.exists() && certificateFile.exists()) {
                runCatching { read(keyFile, certificateFile) }.onSuccess { return it }
                keyFile.delete()
                certificateFile.delete()
            }
            return create().also { fresh ->
                // Saved before it's used for anything, so the phone is never known by a fingerprint it then loses.
                keyFile.writeBytes(fresh.privateKey.encoded)
                certificateFile.writeBytes(fresh.certificate.encoded)
            }
        }

        private fun read(keyFile: File, certificateFile: File): DeviceIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val certificate = certificateFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            return DeviceIdentity(key, certificate)
        }

        private fun create(): DeviceIdentity {
            val random = SecureRandom()
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS, random) }.generateKeyPair()
            val subject = X500Name(SUBJECT)
            val unsigned = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(SERIAL_BITS, random),
                validFrom.asDate(),
                validUntil.asDate(),
                subject,
                keys.public
            )
            val signed = unsigned.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
            return DeviceIdentity(keys.private, JcaX509CertificateConverter().getCertificate(signed))
        }

        private fun LocalDate.asDate(): Date = Date.from(atStartOfDay(ZoneOffset.UTC).toInstant())
    }
}
