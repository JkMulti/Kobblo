package com.kobblo.gallery.file.explorer.vault

import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Base64

internal interface VaultKeyStore {
    fun read(name: String): String?

    /** Applies every change or none. A null value removes the entry. */
    fun write(changes: Map<String, String?>): Boolean
}

internal class SharedPreferencesKeyStore(private val prefs: SharedPreferences) : VaultKeyStore {
    override fun read(name: String): String? = prefs.getString(name, null)

    override fun write(changes: Map<String, String?>): Boolean {
        val editor = prefs.edit()
        changes.forEach { (name, value) ->
            if (value == null) editor.remove(name) else editor.putString(name, value)
        }
        return editor.commit()
    }
}

internal enum class RecoveryKeyCheck { Valid, Malformed, WrongKey }

internal sealed interface VaultUnlock {
    class Opened(val vaultKey: ByteArray) : VaultUnlock
    data object WrongPassword : VaultUnlock

    /** The password is right, but a vault from before recovery keys couldn't get a key: a full disk. */
    data object NoKeyYet : VaultUnlock
}

/** The vault key sealed by a key in Android Keystore, which only opens after a biometric check. */
internal class KeystoreSealedKey(val iv: ByteArray, val ciphertext: ByteArray) {
    fun encode(): String = listOf(VERSION, base64(iv), base64(ciphertext)).joinToString(".")

    companion object {
        private const val VERSION = "v1"

        fun decode(text: String): KeystoreSealedKey? {
            val parts = text.split('.')
            if (parts.size != 3 || parts[0] != VERSION) return null
            return try {
                KeystoreSealedKey(unbase64(parts[1]), unbase64(parts[2]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun unbase64(text: String) = Base64.getUrlDecoder().decode(text)
    }
}

/**
 * Holds the vault key, which encrypts the vault's files. It's sealed once under the password and
 * once under the recovery key. Either secret opens it, so either one can set the other without the
 * password ever being stored, and changing one never touches the files.
 *
 * A third, optional seal lets a fingerprint open it: see `VaultBiometrics`.
 *
 * Password calls run a slow key derivation, so call these off the main thread.
 */
internal class VaultKeyring(
    private val store: VaultKeyStore,
    private val cost: KdfCost = KdfCost.Default
) {
    val hasPassword: Boolean
        get() = store.read(PASSWORD_SLOT) != null || store.read(LEGACY_PASSWORD_HASH) != null

    val hasRecoveryKey: Boolean
        get() = store.read(RECOVERY_SLOT) != null

    val hasBiometricKey: Boolean
        get() = store.read(BIOMETRIC_SLOT) != null

    fun create(password: String, recoveryKey: String): Boolean {
        val vaultKey = VaultCrypto.newVaultKey()
        return try {
            store.write(
                mapOf(
                    PASSWORD_SLOT to VaultCrypto.wrapWithPassword(vaultKey, password, cost).encode(),
                    RECOVERY_SLOT to VaultCrypto.wrapWithRecoveryKey(vaultKey, recoveryKey).encode(),
                    BIOMETRIC_SLOT to null,
                    LEGACY_PASSWORD_HASH to null
                )
            )
        } finally {
            vaultKey.fill(0)
        }
    }

    /** Hands back the vault key itself. The caller owns it and should wipe it when done. */
    fun unlock(password: String): VaultUnlock {
        if (store.read(LEGACY_PASSWORD_HASH) != null) {
            if (!matchesLegacyHash(password)) return VaultUnlock.WrongPassword
            if (!upgradeLegacyVault(password)) return VaultUnlock.NoKeyYet
        }
        return openWithPassword(password)?.let(VaultUnlock::Opened) ?: VaultUnlock.WrongPassword
    }

    /** Hands back the vault key itself. The caller owns it and should wipe it when done. */
    fun unlockWithRecoveryKey(input: String): ByteArray? =
        RecoveryKey.parse(input)?.let(::openWithRecoveryKey)

    fun readBiometricKey(): KeystoreSealedKey? = store.read(BIOMETRIC_SLOT)?.let(KeystoreSealedKey::decode)

    fun saveBiometricKey(sealed: KeystoreSealedKey): Boolean =
        store.write(mapOf(BIOMETRIC_SLOT to sealed.encode()))

    fun removeBiometricKey(): Boolean = store.write(mapOf(BIOMETRIC_SLOT to null))

    fun verifyPassword(password: String): Boolean {
        if (store.read(LEGACY_PASSWORD_HASH) != null) {
            if (!matchesLegacyHash(password)) return false
            // A full disk mustn't lock anyone out of their vault. The next unlock tries again.
            upgradeLegacyVault(password)
            return true
        }
        val vaultKey = openWithPassword(password) ?: return false
        vaultKey.fill(0)
        return true
    }

    /** Sets the recovery key, or replaces it. The one it replaces stops working. */
    fun setRecoveryKey(password: String, recoveryKey: String): Boolean {
        val vaultKey = openWithPassword(password) ?: return false
        return try {
            store.write(mapOf(RECOVERY_SLOT to VaultCrypto.wrapWithRecoveryKey(vaultKey, recoveryKey).encode()))
        } finally {
            vaultKey.fill(0)
        }
    }

    fun changePassword(currentPassword: String, newPassword: String): Boolean {
        val vaultKey = openWithPassword(currentPassword) ?: return false
        return try {
            store.write(mapOf(PASSWORD_SLOT to VaultCrypto.wrapWithPassword(vaultKey, newPassword, cost).encode()))
        } finally {
            vaultKey.fill(0)
        }
    }

    fun checkRecoveryKey(input: String): RecoveryKeyCheck {
        val recoveryKey = RecoveryKey.parse(input) ?: return RecoveryKeyCheck.Malformed
        val vaultKey = openWithRecoveryKey(recoveryKey) ?: return RecoveryKeyCheck.WrongKey
        vaultKey.fill(0)
        return RecoveryKeyCheck.Valid
    }

    /** Sets a new password using the recovery key. The recovery key keeps working afterward. */
    fun resetPassword(recoveryKeyInput: String, newPassword: String): Boolean {
        val recoveryKey = RecoveryKey.parse(recoveryKeyInput) ?: return false
        val vaultKey = openWithRecoveryKey(recoveryKey) ?: return false
        return try {
            store.write(
                mapOf(
                    PASSWORD_SLOT to VaultCrypto.wrapWithPassword(vaultKey, newPassword, cost).encode(),
                    LEGACY_PASSWORD_HASH to null
                )
            )
        } finally {
            vaultKey.fill(0)
        }
    }

    private fun openWithPassword(password: String): ByteArray? {
        if (store.read(LEGACY_PASSWORD_HASH) != null) {
            if (!matchesLegacyHash(password) || !upgradeLegacyVault(password)) return null
        }
        val wrapped = store.read(PASSWORD_SLOT)?.let(WrappedKey::decode) ?: return null
        return VaultCrypto.unwrapWithPassword(wrapped, password)
    }

    private fun openWithRecoveryKey(recoveryKey: String): ByteArray? {
        val wrapped = store.read(RECOVERY_SLOT)?.let(WrappedKey::decode) ?: return null
        return VaultCrypto.unwrapWithRecoveryKey(wrapped, recoveryKey)
    }

    // Vaults from before recovery keys hold only an unsalted hash. The first time someone proves
    // they know the password, that hash is replaced with a real vault key sealed under it.
    private fun upgradeLegacyVault(password: String): Boolean {
        val vaultKey = VaultCrypto.newVaultKey()
        return try {
            store.write(
                mapOf(
                    PASSWORD_SLOT to VaultCrypto.wrapWithPassword(vaultKey, password, cost).encode(),
                    LEGACY_PASSWORD_HASH to null
                )
            )
        } finally {
            vaultKey.fill(0)
        }
    }

    private fun matchesLegacyHash(password: String): Boolean {
        val stored = store.read(LEGACY_PASSWORD_HASH) ?: return false
        val hash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(hash.toByteArray(Charsets.US_ASCII), stored.toByteArray(Charsets.US_ASCII))
    }

    private companion object {
        const val PASSWORD_SLOT = "vault_key_password"
        const val RECOVERY_SLOT = "vault_key_recovery"
        const val BIOMETRIC_SLOT = "vault_key_biometric"
        const val LEGACY_PASSWORD_HASH = "vault_password_hash"
    }
}
