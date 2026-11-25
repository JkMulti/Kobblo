package com.kobblo.gallery.file.explorer.vault

import android.content.SharedPreferences

/**
 * What the Vault's own preferences say about it, apart from its keys: that it was set up, when, and
 * the password hint. The names are the ones every earlier version wrote, so they can't change.
 *
 * The same file also holds the key slots (`VaultKeyring`), the count of wrong passwords
 * (`VaultAttempts`), and whether fingerprint unlock has been offered (`VaultBiometrics`).
 */
internal class VaultSettings(private val prefs: SharedPreferences) {
    /** Setup ran to its end. On its own this isn't a usable vault: the keyring must hold a password too. */
    val isSetUp: Boolean
        get() = prefs.getBoolean(SET_UP, false)

    /** The hint shown on request at the unlock screen. Stored in the clear, as its field warns. */
    var hint: String
        get() = prefs.getString(HINT, "") ?: ""
        set(value) = prefs.edit().putString(HINT, value.trim()).apply()

    /** Records a finished setup. False if it couldn't be written, and then there is no vault yet. */
    fun markSetUp(hint: String, now: Long): Boolean = prefs.edit()
        .putBoolean(SET_UP, true)
        .putString(HINT, hint.trim())
        .putLong(CREATED_AT, now)
        .commit()

    companion object {
        const val FILE_NAME = "kobblo_vault_prefs"
        private const val SET_UP = "vault_setup_completed"
        private const val HINT = "vault_password_hint"
        private const val CREATED_AT = "vault_created_at"
    }
}
