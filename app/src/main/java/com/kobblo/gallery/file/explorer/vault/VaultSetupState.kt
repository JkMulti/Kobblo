package com.kobblo.gallery.file.explorer.vault

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What's wrong with a password as it stands in the fields, for the line under them. */
internal enum class PasswordFault { Mismatch, TooShort }

/**
 * A new Vault password as it's being chosen: the password, its repeat, and the optional hint.
 * Not a data class, so the password can't end up in a log through `toString`.
 */
internal class PasswordEntry(val password: String = "", val repeat: String = "", val hint: String = "") {
    val isLongEnough: Boolean get() = password.length >= VaultManager.MIN_PASSWORD_LENGTH

    /** The repeat disagrees, which only counts once something has been typed into it. */
    val isMismatched: Boolean get() = repeat.isNotEmpty() && password != repeat

    /** Good enough to protect a vault: long enough, and typed the same way twice. */
    val isAcceptable: Boolean get() = isLongEnough && password == repeat

    /** The one thing to say about it under the fields. A repeat that disagrees outranks a short password. */
    val fault: PasswordFault?
        get() = when {
            isMismatched -> PasswordFault.Mismatch
            password.isNotEmpty() && !isLongEnough -> PasswordFault.TooShort
            else -> null
        }

    fun withPassword(text: String) = PasswordEntry(text, repeat, hint)
    fun withRepeat(text: String) = PasswordEntry(password, text, hint)
    fun withHint(text: String) = PasswordEntry(password, repeat, text)
}

/** The three pages of setting up a vault, in order. */
internal enum class SetupStep { Welcome, ChoosePassword, SaveRecoveryKey }

/**
 * The setup wizard: which page it's on, the password that was chosen, and the recovery key it will
 * hand over. Nothing is written anywhere until the last page's button, so leaving at any point
 * leaves no vault behind.
 *
 * It's kept in memory only. Saving it with the activity would hand the password and the key to the
 * system, and a wizard that starts over has lost nothing.
 */
@Stable
internal class VaultSetupState(val recoveryKey: String) {
    var step by mutableStateOf(SetupStep.Welcome)
        private set

    /**
     * What the password page was last left with by Continue: what it opens with the next time, and
     * what the vault is made from. Typing that's abandoned with Back never gets here.
     */
    var chosen = PasswordEntry()
        private set

    /** The password the vault will be made with. Empty until the password page has been passed. */
    val password: String get() = chosen.password

    val hint: String get() = chosen.hint

    fun showWelcome() {
        step = SetupStep.Welcome
    }

    /** Forward from the welcome page, or back from the recovery key. */
    fun showPasswordPage() {
        step = SetupStep.ChoosePassword
    }

    /**
     * Continue on the password page. Takes [entry] and moves on to the recovery key, or refuses and
     * changes nothing when [entry] isn't good enough to protect a vault.
     */
    fun choose(entry: PasswordEntry): Boolean {
        if (!entry.isAcceptable) return false
        chosen = entry
        step = SetupStep.SaveRecoveryKey
        return true
    }
}
