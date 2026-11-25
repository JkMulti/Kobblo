package com.kobblo.gallery.file.explorer.transfer.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.Locale

/**
 * Proves who is sending. LocalSend's request only *says* which fingerprint its sender has, and the
 * sender presents no certificate, so a stranger can claim anyone's. Connecting back to check doesn't
 * settle it either, because a stranger can simply relay that connection to the real device.
 *
 * So this phone signs a short statement with the private key behind its own certificate: "I, holder of
 * this fingerprint, am talking to that fingerprint, now." It travels in a header other LocalSend apps
 * ignore, and one that's missing just means unproven. Naming the receiver means a device that's been
 * sent to can't reuse the proof on anyone else. Nothing that matters is ever granted without it.
 */
object SenderProof {
    const val HEADER = "X-Kobblo-Proof"
    private const val VERSION = "1"
    private const val DOMAIN = "kobblo-send"
    private const val ALGORITHM = "SHA256withRSA"

    /** Two phones' clocks can disagree by this much. It only bounds how long a proof stays usable. */
    internal const val MAX_AGE_MS = 15 * 60 * 1000L

    fun create(
        privateKey: PrivateKey,
        certificateDer: ByteArray,
        receiverFingerprint: String,
        nowMs: Long,
        random: SecureRandom = SecureRandom()
    ): String {
        val nonce = ByteArray(16).also(random::nextBytes).let(encoder::encodeToString)
        val statement = statement(fingerprintOf(certificateDer), receiverFingerprint, nowMs, nonce)
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(statement)
            sign()
        }
        return listOf(VERSION, encoder.encodeToString(certificateDer), nowMs.toString(), nonce, encoder.encodeToString(signature))
            .joinToString(".")
    }

    /** True only if [header] was made just now by the holder of [claimedFingerprint], for [ownFingerprint]. */
    fun verify(
        header: String?,
        claimedFingerprint: String,
        ownFingerprint: String,
        nowMs: Long,
        nonces: NonceMemory
    ): Boolean {
        if (header.isNullOrBlank() || claimedFingerprint.isBlank() || ownFingerprint.isBlank()) return false
        return runCatching {
            val parts = header.split('.')
            if (parts.size != 5 || parts[0] != VERSION) return false
            val certificateDer = decoder.decode(parts[1])
            val timeMs = parts[2].toLong()
            val nonce = parts[3]
            if (kotlin.math.abs(nowMs - timeMs) > MAX_AGE_MS) return false
            val fingerprint = fingerprintOf(certificateDer)
            if (normalize(fingerprint) != normalize(claimedFingerprint)) return false
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateDer))
            val valid = Signature.getInstance(ALGORITHM).run {
                initVerify(certificate.publicKey)
                update(statement(fingerprint, ownFingerprint, timeMs, nonce))
                verify(decoder.decode(parts[4]))
            }
            // Checked last, so an unsigned request can't fill the memory or use up someone's nonce.
            valid && nonces.firstUse(nonce, nowMs)
        }.getOrDefault(false)
    }

    fun fingerprintOf(certificateDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificateDer).joinToString("") { "%02X".format(it) }

    private fun statement(sender: String, receiver: String, timeMs: Long, nonce: String): ByteArray =
        listOf(DOMAIN, VERSION, normalize(sender), normalize(receiver), timeMs.toString(), nonce)
            .joinToString("|")
            .toByteArray(Charsets.UTF_8)

    private fun normalize(fingerprint: String) = fingerprint.replace(":", "").uppercase(Locale.ROOT)

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
}

/** Which proofs have been seen, so one can't be replayed. It forgets them once they've expired anyway. */
class NonceMemory(private val maxEntries: Int = 512) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun firstUse(nonce: String, nowMs: Long): Boolean {
        seen.entries.removeAll { nowMs - it.value > SenderProof.MAX_AGE_MS * 2 }
        if (seen.containsKey(nonce)) return false
        if (seen.size >= maxEntries) seen.remove(seen.keys.first())
        seen[nonce] = nowMs
        return true
    }
}
