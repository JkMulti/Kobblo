package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class VaultKeyringTest {
    private class MemoryKeyStore : VaultKeyStore {
        val entries = mutableMapOf<String, String>()
        var failWrites = false

        override fun read(name: String) = entries[name]

        override fun write(changes: Map<String, String?>): Boolean {
            if (failWrites) return false
            changes.forEach { (name, value) ->
                if (value == null) entries.remove(name) else entries[name] = value
            }
            return true
        }
    }

    private val store = MemoryKeyStore()
    private val keyring = VaultKeyring(store, KdfCost(memoryKib = 64, iterations = 1, parallelism = 1))
    private val recoveryKey = RecoveryKey.generate()

    /** The exact state the app left behind before recovery keys: a bare SHA-256 of the password. */
    private fun seedLegacyVault(password: String) {
        store.entries["vault_password_hash"] = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun newVault_opensWithItsPasswordAndNothingElse() {
        assertFalse(keyring.hasPassword)
        assertTrue(keyring.create("hunter2", recoveryKey))
        assertTrue(keyring.hasPassword)
        assertTrue(keyring.hasRecoveryKey)
        assertTrue(keyring.verifyPassword("hunter2"))
        assertFalse(keyring.verifyPassword("hunter3"))
        assertFalse(keyring.verifyPassword(""))
    }

    @Test
    fun storedValues_neverContainEitherSecret() {
        keyring.create("hunter2-distinctive", recoveryKey)
        val stored = store.entries.values.joinToString("\n")
        assertFalse(stored.contains("hunter2-distinctive"))
        assertFalse(stored.contains(recoveryKey))
        assertFalse(stored.contains(RecoveryKey.formatted(recoveryKey)))
        assertNull(store.entries["vault_password_hash"])
    }

    @Test
    fun recoveryKey_setsANewPasswordAndKeepsWorking() {
        keyring.create("forgotten", recoveryKey)

        assertTrue(keyring.resetPassword(RecoveryKey.formatted(recoveryKey).lowercase(), "new-password"))

        assertTrue(keyring.verifyPassword("new-password"))
        assertFalse(keyring.verifyPassword("forgotten"))
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(recoveryKey))
        assertTrue(keyring.resetPassword(recoveryKey, "newer-password"))
        assertTrue(keyring.verifyPassword("newer-password"))
    }

    @Test
    fun recovery_tellsAMistypeFromTheWrongKey() {
        keyring.create("hunter2", recoveryKey)
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(recoveryKey))
        assertEquals(RecoveryKeyCheck.Malformed, keyring.checkRecoveryKey(recoveryKey.dropLast(1)))
        assertEquals(RecoveryKeyCheck.WrongKey, keyring.checkRecoveryKey(RecoveryKey.generate()))
    }

    @Test
    fun wrongRecoveryKey_changesNothing() {
        keyring.create("hunter2", recoveryKey)
        val before = store.entries.toMap()
        assertFalse(keyring.resetPassword(RecoveryKey.generate(), "attacker"))
        assertFalse(keyring.resetPassword("not a key", "attacker"))
        assertEquals(before, store.entries)
        assertTrue(keyring.verifyPassword("hunter2"))
    }

    @Test
    fun changingPassword_keepsTheRecoveryKeyWorking() {
        keyring.create("hunter2", recoveryKey)
        assertTrue(keyring.changePassword("hunter2", "changed"))
        assertTrue(keyring.verifyPassword("changed"))
        assertFalse(keyring.verifyPassword("hunter2"))
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(recoveryKey))
    }

    @Test
    fun changingPassword_needsTheCurrentOne() {
        keyring.create("hunter2", recoveryKey)
        assertFalse(keyring.changePassword("wrong", "changed"))
        assertTrue(keyring.verifyPassword("hunter2"))
    }

    @Test
    fun replacingRecoveryKey_retiresTheOldOne() {
        keyring.create("hunter2", recoveryKey)
        val replacement = RecoveryKey.generate()
        assertFalse(keyring.setRecoveryKey("wrong", replacement))
        assertEquals(RecoveryKeyCheck.WrongKey, keyring.checkRecoveryKey(replacement))

        assertTrue(keyring.setRecoveryKey("hunter2", replacement))

        assertEquals(RecoveryKeyCheck.WrongKey, keyring.checkRecoveryKey(recoveryKey))
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(replacement))
    }

    @Test
    fun legacyVault_keepsWorkingAndUpgradesOnUnlock() {
        seedLegacyVault("old-password")
        assertTrue(keyring.hasPassword)
        assertFalse(keyring.hasRecoveryKey)

        assertFalse(keyring.verifyPassword("wrong"))
        assertTrue("A wrong password must not upgrade anything", store.entries.containsKey("vault_password_hash"))

        assertTrue(keyring.verifyPassword("old-password"))
        assertFalse(store.entries.containsKey("vault_password_hash"))
        assertFalse(keyring.hasRecoveryKey)

        assertTrue(keyring.verifyPassword("old-password"))
        assertFalse(keyring.verifyPassword("wrong"))
    }

    @Test
    fun legacyVault_adoptsARecoveryKeyEvenBeforeItsFirstUpgrade() {
        // Someone who unlocks with a fingerprint reaches this with the old hash still in place.
        seedLegacyVault("old-password")
        assertFalse(keyring.setRecoveryKey("wrong", recoveryKey))
        assertFalse(keyring.hasRecoveryKey)

        assertTrue(keyring.setRecoveryKey("old-password", recoveryKey))

        assertTrue(keyring.hasRecoveryKey)
        assertFalse(store.entries.containsKey("vault_password_hash"))
        assertTrue(keyring.resetPassword(recoveryKey, "recovered"))
        assertTrue(keyring.verifyPassword("recovered"))
        assertFalse(keyring.verifyPassword("old-password"))
    }

    @Test
    fun legacyVault_withNoRecoveryKeyCannotBeReset() {
        seedLegacyVault("old-password")
        assertEquals(RecoveryKeyCheck.WrongKey, keyring.checkRecoveryKey(recoveryKey))
        assertFalse(keyring.resetPassword(recoveryKey, "attacker"))
        assertTrue(keyring.verifyPassword("old-password"))
    }

    @Test
    fun fullDisk_doesNotLockAnyoneOutOfALegacyVault() {
        seedLegacyVault("old-password")
        store.failWrites = true

        assertTrue(keyring.verifyPassword("old-password"))
        assertFalse(keyring.verifyPassword("wrong"))
        assertFalse(keyring.setRecoveryKey("old-password", recoveryKey))
        assertTrue(store.entries.containsKey("vault_password_hash"))

        store.failWrites = false
        assertTrue(keyring.verifyPassword("old-password"))
        assertFalse(store.entries.containsKey("vault_password_hash"))
    }

    @Test
    fun fullDisk_leavesExistingSecretsWorking() {
        keyring.create("hunter2", recoveryKey)
        store.failWrites = true

        assertFalse(keyring.changePassword("hunter2", "changed"))
        assertFalse(keyring.resetPassword(recoveryKey, "changed"))
        assertFalse(keyring.setRecoveryKey("hunter2", RecoveryKey.generate()))

        store.failWrites = false
        assertTrue(keyring.verifyPassword("hunter2"))
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(recoveryKey))
    }

    @Test
    fun corruptedSlots_failClosed() {
        keyring.create("hunter2", recoveryKey)
        store.entries["vault_key_password"] = "garbage"
        store.entries["vault_key_recovery"] = "garbage"
        assertFalse(keyring.verifyPassword("hunter2"))
        assertEquals(RecoveryKeyCheck.WrongKey, keyring.checkRecoveryKey(recoveryKey))
    }

    @Test
    fun unlock_handsBackTheSameKeyForEitherSecret() {
        keyring.create("hunter2", recoveryKey)

        val withPassword = (keyring.unlock("hunter2") as VaultUnlock.Opened).vaultKey
        val withRecoveryKey = keyring.unlockWithRecoveryKey(RecoveryKey.formatted(recoveryKey).lowercase())

        assertEquals(32, withPassword.size)
        assertArrayEquals(withPassword, withRecoveryKey)
    }

    @Test
    fun unlock_tellsAWrongSecretApart() {
        keyring.create("hunter2", recoveryKey)
        assertEquals(VaultUnlock.WrongPassword, keyring.unlock("hunter3"))
        assertNull(keyring.unlockWithRecoveryKey(RecoveryKey.generate()))
        assertNull(keyring.unlockWithRecoveryKey("not a key"))
    }

    @Test
    fun theVaultKey_survivesChangingEitherSecret() {
        keyring.create("hunter2", recoveryKey)
        val original = (keyring.unlock("hunter2") as VaultUnlock.Opened).vaultKey

        keyring.changePassword("hunter2", "changed")
        val newRecoveryKey = RecoveryKey.generate()
        keyring.setRecoveryKey("changed", newRecoveryKey)
        keyring.resetPassword(newRecoveryKey, "reset")

        assertArrayEquals(original, (keyring.unlock("reset") as VaultUnlock.Opened).vaultKey)
        assertArrayEquals(original, keyring.unlockWithRecoveryKey(newRecoveryKey))
    }

    @Test
    fun legacyVault_getsOneKeyThatStaysTheSame() {
        seedLegacyVault("old-password")

        assertEquals(VaultUnlock.WrongPassword, keyring.unlock("wrong"))
        assertNotNull("A wrong password mustn't upgrade anything", store.entries["vault_password_hash"])

        val first = (keyring.unlock("old-password") as VaultUnlock.Opened).vaultKey
        assertNull(store.entries["vault_password_hash"])
        assertArrayEquals(first, (keyring.unlock("old-password") as VaultUnlock.Opened).vaultKey)
    }

    @Test
    fun legacyVault_thatCantBeUpgraded_stillOpensWithoutAKey() {
        seedLegacyVault("old-password")
        store.failWrites = true

        assertEquals(VaultUnlock.NoKeyYet, keyring.unlock("old-password"))
        assertEquals(VaultUnlock.WrongPassword, keyring.unlock("wrong"))

        store.failWrites = false
        assertTrue(keyring.unlock("old-password") is VaultUnlock.Opened)
    }

    /**
     * Slots written before files were encrypted, and the recovery key that goes with them. If this
     * fails, a key someone already wrote on paper has stopped working.
     */
    @Test
    fun secretsIssuedBeforeEncryption_stillOpenTheVault() {
        store.entries["vault_key_recovery"] = "v1.hkdf.GTUBKs8JZ5_GyxTvF2ZwcQ.54SJf6dRi2FYALyl.nyGZORaYBqo_Np0O2PKu9I54K50HCaqJAc-GmbENqkCcEAd8gWK1eb5c8XLVYPs1"
        store.entries["vault_key_password"] = "v1.argon2id:64:1:1.z0ubhObVjyN43NoB-tBWWw.lPTyTh8Y0E9bnixl.EvKZZ5GAROmU8WeednFTYl9Zz-OQjuHIgJiBKOHhNZ1Dw_iC_B1jq5YHwqUB5wZ1"
        val expected = ByteArray(32) { it.toByte() }

        assertArrayEquals(expected, keyring.unlockWithRecoveryKey("6CWDF8JYTAS708968YQZXQF4KCXD"))
        assertArrayEquals(expected, (keyring.unlock("golden password") as VaultUnlock.Opened).vaultKey)
    }

    @Test
    fun biometricSlot_isOptionalAndComesAndGoes() {
        keyring.create("hunter2", recoveryKey)
        assertFalse(keyring.hasBiometricKey)
        assertNull(keyring.readBiometricKey())

        assertTrue(keyring.saveBiometricKey(KeystoreSealedKey(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7))))

        assertTrue(keyring.hasBiometricKey)
        val read = keyring.readBiometricKey()!!
        assertArrayEquals(byteArrayOf(1, 2, 3), read.iv)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), read.ciphertext)
        assertTrue("The other secrets mustn't care", keyring.verifyPassword("hunter2"))

        assertTrue(keyring.removeBiometricKey())
        assertFalse(keyring.hasBiometricKey)
        assertEquals(RecoveryKeyCheck.Valid, keyring.checkRecoveryKey(recoveryKey))
    }

    @Test
    fun aNewVault_doesNotInheritAnOldBiometricSlot() {
        store.entries["vault_key_biometric"] = KeystoreSealedKey(byteArrayOf(1), byteArrayOf(2)).encode()
        keyring.create("hunter2", recoveryKey)
        assertFalse(keyring.hasBiometricKey)
    }

    @Test
    fun corruptedBiometricSlot_readsAsNothing() {
        store.entries["vault_key_biometric"] = "garbage"
        assertNull(keyring.readBiometricKey())
    }
}
