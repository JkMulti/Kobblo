package com.kobblo.gallery.file.explorer.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

internal data class Argon2Cost(val memoryKib: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        // OWASP's baseline for Argon2id.
        val Default = Argon2Cost(memoryKib = 19_456, iterations = 2, parallelism = 1)
    }
}

/**
 * How the Kobblo lock is stored: a salted Argon2id hash in the PHC string format, so each value
 * carries its own salt and cost.
 *
 * Older versions stored an unsalted SHA-256 of the password. Such a value still matches its password,
 * and [isLegacy] tells the store to replace it once it has. A value that's neither matches nothing,
 * so damage to it never opens the lock.
 */
internal object LockHash {
    private const val ARGON2ID = "argon2id"
    private const val VERSION = "v=19"
    private val COST_FIELD = Regex("m=(\\d+),t=(\\d+),p=(\\d+)")
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private const val MIN_SALT_BYTES = 8
    private const val MIN_HASH_BYTES = 16
    private const val LEGACY_HASH_LENGTH = 64

    private val random = SecureRandom()

    fun hash(secret: String, cost: Argon2Cost = Argon2Cost.Default): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val output = argon2id(secret, salt, cost, HASH_BYTES)
        val costField = "m=${cost.memoryKib},t=${cost.iterations},p=${cost.parallelism}"
        val encoder = Base64.getEncoder().withoutPadding()
        return listOf("", ARGON2ID, VERSION, costField, encoder.encodeToString(salt), encoder.encodeToString(output))
            .joinToString("$")
    }

    fun matches(secret: String, stored: String): Boolean {
        if (isLegacy(stored)) return constantTimeEquals(legacyHash(secret), stored.toByteArray(Charsets.US_ASCII))
        val parsed = parse(stored) ?: return false
        return constantTimeEquals(argon2id(secret, parsed.salt, parsed.cost, parsed.hash.size), parsed.hash)
    }

    fun isLegacy(stored: String): Boolean =
        stored.length == LEGACY_HASH_LENGTH && stored.all { it in '0'..'9' || it in 'a'..'f' }

    /** Whether [stored] is a value this object wrote, as opposed to an old or damaged one. */
    fun isCurrent(stored: String): Boolean = parse(stored) != null

    // Exactly what older versions hashed: the raw bytes, with no normalization.
    private fun legacyHash(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)

    private class Parsed(val cost: Argon2Cost, val salt: ByteArray, val hash: ByteArray)

    private fun parse(stored: String): Parsed? {
        val parts = stored.split('$')
        if (parts.size != 6 || parts[0].isNotEmpty() || parts[1] != ARGON2ID || parts[2] != VERSION) return null
        val costFields = COST_FIELD.matchEntire(parts[3])?.groupValues ?: return null
        val memoryKib = costFields[1].toIntOrNull() ?: return null
        val iterations = costFields[2].toIntOrNull() ?: return null
        val parallelism = costFields[3].toIntOrNull() ?: return null
        if (iterations < 1 || parallelism < 1 || memoryKib < 8L * parallelism) return null
        val salt: ByteArray
        val hash: ByteArray
        try {
            salt = Base64.getDecoder().decode(parts[4])
            hash = Base64.getDecoder().decode(parts[5])
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (salt.size < MIN_SALT_BYTES || hash.size < MIN_HASH_BYTES) return null
        return Parsed(Argon2Cost(memoryKib, iterations, parallelism), salt, hash)
    }

    private fun argon2id(secret: String, salt: ByteArray, cost: Argon2Cost, outputBytes: Int): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(cost.memoryKib)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .withSalt(salt)
            .build()
        // Keyboards can spell an accented letter two ways, and a password must not depend on which.
        val secretBytes = Normalizer.normalize(secret, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val output = ByteArray(outputBytes)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(secretBytes, output)
        secretBytes.fill(0)
        return output
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
