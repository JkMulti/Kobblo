package com.kobblo.gallery.file.explorer.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What it costs to turn a password into a key. Saved with each wrapped key so it can rise later. */
internal data class KdfCost(val memoryKib: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        // OWASP's baseline for Argon2id.
        val Default = KdfCost(memoryKib = 19_456, iterations = 2, parallelism = 1)
    }
}

/**
 * The key a person writes down. Crockford's base32 leaves out I, L, O, and U, so nothing on paper
 * reads as anything else. 26 random characters carry 130 bits, far beyond guessing, so it needs no
 * slow key derivation. Two check characters catch a mistyped key before any crypto runs.
 */
internal object RecoveryKey {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val BODY_LENGTH = 26
    private const val GROUP_LENGTH = 4
    const val LENGTH = BODY_LENGTH + 2

    fun generate(random: SecureRandom = SecureRandom()): String {
        val body = String(CharArray(BODY_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
        return body + checkCharacters(body)
    }

    /** The canonical form of what someone typed or pasted, or null if it can't be a recovery key. */
    fun parse(input: String): String? {
        val key = input.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map { char ->
                when (char) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> char
                }
            }
            .joinToString("")
        if (key.length != LENGTH || key.any { it !in ALPHABET }) return null
        val body = key.take(BODY_LENGTH)
        return if (key.drop(BODY_LENGTH) == checkCharacters(body)) key else null
    }

    fun groups(key: String): List<String> = key.chunked(GROUP_LENGTH)

    fun formatted(key: String): String = groups(key).joinToString("-")

    private fun checkCharacters(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII))
        val first = digest[0].toInt() and 0xFF
        val second = digest[1].toInt() and 0xFF
        val high = first ushr 3
        val low = ((first and 0x07) shl 2) or (second ushr 6)
        return "${ALPHABET[high]}${ALPHABET[low]}"
    }
}

/** The vault key sealed under a secret, in the form that gets stored. */
internal class WrappedKey(
    /** Null when the secret is a recovery key, which needs no stretching. */
    val cost: KdfCost?,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    fun encode(): String {
        val kdf = cost?.let { "$ARGON2ID:${it.memoryKib}:${it.iterations}:${it.parallelism}" } ?: HKDF
        return listOf(VERSION, kdf, base64(salt), base64(iv), base64(ciphertext)).joinToString(".")
    }

    companion object {
        private const val VERSION = "v1"
        private const val ARGON2ID = "argon2id"
        private const val HKDF = "hkdf"

        fun decode(text: String): WrappedKey? {
            val parts = text.split('.')
            if (parts.size != 5 || parts[0] != VERSION) return null
            return try {
                val cost = if (parts[1] == HKDF) {
                    null
                } else {
                    val fields = parts[1].split(':')
                    if (fields.size != 4 || fields[0] != ARGON2ID) return null
                    KdfCost(fields[1].toInt(), fields[2].toInt(), fields[3].toInt())
                }
                WrappedKey(cost, unbase64(parts[2]), unbase64(parts[3]), unbase64(parts[4]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun unbase64(text: String) = Base64.getUrlDecoder().decode(text)
    }
}

internal object VaultCrypto {
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    // Sealing is bound to the slot, so a stored value can't be moved from one slot to the other.
    private val PASSWORD_SLOT = "kobblo.vault.v1.password".toByteArray(Charsets.US_ASCII)
    private val RECOVERY_SLOT = "kobblo.vault.v1.recovery".toByteArray(Charsets.US_ASCII)

    private val random = SecureRandom()

    fun newVaultKey(): ByteArray = randomBytes(KEY_BYTES)

    fun wrapWithPassword(vaultKey: ByteArray, password: String, cost: KdfCost): WrappedKey {
        val salt = randomBytes(SALT_BYTES)
        val wrappingKey = argon2id(passwordBytes(password), salt, cost)
        return seal(vaultKey, wrappingKey, PASSWORD_SLOT, cost, salt)
    }

    fun unwrapWithPassword(wrapped: WrappedKey, password: String): ByteArray? {
        val cost = wrapped.cost ?: return null
        val wrappingKey = argon2id(passwordBytes(password), wrapped.salt, cost)
        return open(wrapped, wrappingKey, PASSWORD_SLOT)
    }

    fun wrapWithRecoveryKey(vaultKey: ByteArray, recoveryKey: String): WrappedKey {
        val salt = randomBytes(SALT_BYTES)
        val wrappingKey = hkdfSha256(recoveryKey.toByteArray(Charsets.US_ASCII), salt, RECOVERY_SLOT)
        return seal(vaultKey, wrappingKey, RECOVERY_SLOT, null, salt)
    }

    fun unwrapWithRecoveryKey(wrapped: WrappedKey, recoveryKey: String): ByteArray? {
        if (wrapped.cost != null) return null
        val wrappingKey = hkdfSha256(recoveryKey.toByteArray(Charsets.US_ASCII), wrapped.salt, RECOVERY_SLOT)
        return open(wrapped, wrappingKey, RECOVERY_SLOT)
    }

    fun argon2id(password: ByteArray, salt: ByteArray, cost: KdfCost, outputBytes: Int = KEY_BYTES): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(cost.memoryKib)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .withSalt(salt)
            .build()
        val output = ByteArray(outputBytes)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, output)
        return output
    }

    /** HKDF from RFC 5869, for one block of output: exactly what a 256-bit key needs. */
    fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extracted = hmacSha256(salt, secret)
        return hmacSha256(extracted, info + 0x01.toByte()).also { extracted.fill(0) }
    }

    // Keyboards can spell an accented letter two ways, and a password must not depend on which.
    private fun passwordBytes(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    private fun seal(
        vaultKey: ByteArray,
        wrappingKey: ByteArray,
        slot: ByteArray,
        cost: KdfCost?,
        salt: ByteArray
    ): WrappedKey {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(slot)
        return WrappedKey(cost, salt, iv, cipher.doFinal(vaultKey)).also { wrappingKey.fill(0) }
    }

    private fun open(wrapped: WrappedKey, wrappingKey: ByteArray, slot: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(TAG_BITS, wrapped.iv))
            cipher.updateAAD(slot)
            cipher.doFinal(wrapped.ciphertext)
        } catch (_: GeneralSecurityException) {
            null
        } finally {
            wrappingKey.fill(0)
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
}
