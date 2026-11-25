package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultCryptoTest {
    private val fastCost = KdfCost(memoryKib = 64, iterations = 1, parallelism = 1)

    @Test
    fun argon2id_matchesTheReferenceImplementation() {
        // Produced by the reference CLI: printf password | argon2 somesalt -id -t 2 -k 65536 -p 1 -l 32 -r
        val output = VaultCrypto.argon2id(
            password = "password".toByteArray(),
            salt = "somesalt".toByteArray(),
            cost = KdfCost(memoryKib = 65_536, iterations = 2, parallelism = 1)
        )
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", output.toHex())
    }

    @Test
    fun hkdf_matchesRfc5869() {
        // Test case 1. A single block of output is the first 32 of its 42 bytes.
        val output = VaultCrypto.hkdfSha256(
            secret = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".fromHex(),
            salt = "000102030405060708090a0b0c".fromHex(),
            info = "f0f1f2f3f4f5f6f7f8f9".fromHex()
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", output.toHex())
    }

    @Test
    fun recoveryKey_isTheExpectedShapeAndNeverRepeats() {
        val keys = List(200) { RecoveryKey.generate() }
        keys.forEach { key ->
            assertEquals(RecoveryKey.LENGTH, key.length)
            assertTrue(key, key.all { it.isDigit() || it in 'A'..'Z' })
            assertFalse(key, key.any { it in "ILOU" })
            assertEquals(key, RecoveryKey.parse(key))
        }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun recoveryKey_parsesHowPeopleActuallyTypeIt() {
        val key = RecoveryKey.generate()
        val formatted = RecoveryKey.formatted(key)
        assertEquals(7, formatted.split('-').size)
        assertEquals(key, RecoveryKey.parse(formatted))
        assertEquals(key, RecoveryKey.parse(formatted.lowercase()))
        assertEquals(key, RecoveryKey.parse(formatted.replace('-', ' ')))
        assertEquals(key, RecoveryKey.parse("  ${RecoveryKey.groups(key).joinToString("\n")}  "))
    }

    @Test
    fun recoveryKey_readsLookalikeLettersAsTheDigitsTheyResemble() {
        val key = generateSequence { RecoveryKey.generate() }.first { '0' in it && '1' in it }
        val misread = key.replace('0', 'O').replace('1', 'L')
        assertEquals(key, RecoveryKey.parse(misread))
        assertEquals(key, RecoveryKey.parse(key.replace('1', 'I')))
    }

    @Test
    fun recoveryKey_rejectsAlmostEveryMistype() {
        val key = RecoveryKey.generate()
        assertNull(RecoveryKey.parse(""))
        assertNull(RecoveryKey.parse(key.dropLast(1)))
        assertNull(RecoveryKey.parse(key + "0"))
        assertNull(RecoveryKey.parse(key.replaceRange(0, 1, "U")))

        // Two check characters miss about 1 in 1024 mistypes, so count across many rather than
        // asserting on one.
        var missed = 0
        val attempts = 2_000
        repeat(attempts) {
            val original = RecoveryKey.generate()
            val position = it % RecoveryKey.LENGTH
            val replacement = if (original[position] == '7') '8' else '7'
            val mistyped = original.replaceRange(position, position + 1, replacement.toString())
            if (RecoveryKey.parse(mistyped) != null) missed++
        }
        assertTrue("$missed of $attempts mistypes got through", missed < attempts / 100)
    }

    @Test
    fun password_opensWhatItSealed() {
        val vaultKey = VaultCrypto.newVaultKey()
        val wrapped = VaultCrypto.wrapWithPassword(vaultKey, "hunter2", fastCost)
        assertArrayEquals(vaultKey, VaultCrypto.unwrapWithPassword(wrapped, "hunter2"))
        assertNull(VaultCrypto.unwrapWithPassword(wrapped, "hunter3"))
    }

    @Test
    fun password_doesNotDependOnHowAKeyboardSpellsAnAccent() {
        val vaultKey = VaultCrypto.newVaultKey()
        val wrapped = VaultCrypto.wrapWithPassword(vaultKey, "café", fastCost)
        assertArrayEquals(vaultKey, VaultCrypto.unwrapWithPassword(wrapped, "café"))
    }

    @Test
    fun recoveryKey_opensWhatItSealed() {
        val vaultKey = VaultCrypto.newVaultKey()
        val recoveryKey = RecoveryKey.generate()
        val wrapped = VaultCrypto.wrapWithRecoveryKey(vaultKey, recoveryKey)
        assertArrayEquals(vaultKey, VaultCrypto.unwrapWithRecoveryKey(wrapped, recoveryKey))
        assertNull(VaultCrypto.unwrapWithRecoveryKey(wrapped, RecoveryKey.generate()))
    }

    @Test
    fun slots_cannotStandInForEachOther() {
        val vaultKey = VaultCrypto.newVaultKey()
        val secret = RecoveryKey.generate()
        assertNull(VaultCrypto.unwrapWithRecoveryKey(VaultCrypto.wrapWithPassword(vaultKey, secret, fastCost), secret))
        assertNull(VaultCrypto.unwrapWithPassword(VaultCrypto.wrapWithRecoveryKey(vaultKey, secret), secret))
    }

    @Test
    fun wrappedKey_survivesStorageAndKeepsItsCost() {
        val vaultKey = VaultCrypto.newVaultKey()
        val password = VaultCrypto.wrapWithPassword(vaultKey, "hunter2", fastCost)
        val restored = WrappedKey.decode(password.encode())
        assertNotNull(restored)
        assertEquals(fastCost, restored!!.cost)
        assertArrayEquals(vaultKey, VaultCrypto.unwrapWithPassword(restored, "hunter2"))

        val recoveryKey = RecoveryKey.generate()
        val recovery = WrappedKey.decode(VaultCrypto.wrapWithRecoveryKey(vaultKey, recoveryKey).encode())
        assertNull(recovery!!.cost)
        assertArrayEquals(vaultKey, VaultCrypto.unwrapWithRecoveryKey(recovery, recoveryKey))
    }

    @Test
    fun wrappedKey_rejectsAnythingMalformed() {
        val good = VaultCrypto.wrapWithPassword(VaultCrypto.newVaultKey(), "hunter2", fastCost).encode()
        assertNull(WrappedKey.decode(""))
        assertNull(WrappedKey.decode("not a key"))
        assertNull(WrappedKey.decode(good.replaceFirst("v1", "v9")))
        assertNull(WrappedKey.decode(good.replaceFirst("argon2id", "md5")))
        assertNull(WrappedKey.decode(good.substringBeforeLast('.')))
        assertNull(WrappedKey.decode("$good!"))
    }

    @Test
    fun tamperedCiphertext_doesNotOpen() {
        val wrapped = VaultCrypto.wrapWithPassword(VaultCrypto.newVaultKey(), "hunter2", fastCost)
        val tampered = wrapped.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(
            VaultCrypto.unwrapWithPassword(
                WrappedKey(wrapped.cost, wrapped.salt, wrapped.iv, tampered),
                "hunter2"
            )
        )
    }

    @Test
    fun sealing_neverRepeatsItself() {
        val vaultKey = VaultCrypto.newVaultKey()
        assertNotEquals(
            VaultCrypto.wrapWithPassword(vaultKey, "hunter2", fastCost).encode(),
            VaultCrypto.wrapWithPassword(vaultKey, "hunter2", fastCost).encode()
        )
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
