package com.kobblo.gallery.file.explorer.vault

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Why the Vault is still locked after a try, for the line under the password field. */
internal sealed interface UnlockProblem {
    data object WrongPassword : UnlockProblem

    /** Too many wrong passwords in a row. Nothing more is tried until [waitMs] has passed. */
    data class Paused(val waitMs: Long) : UnlockProblem

    /** The phone's fingerprints changed, which destroyed the key a fingerprint released. */
    data object FingerprintsChanged : UnlockProblem

    /** A fingerprint prompt that failed, in the system's own words. */
    data class FingerprintFailed(val message: String) : UnlockProblem
}

/**
 * A locked vault as its unlock page needs it. The page's rules are written against this, so they
 * can be tested without a phone, a keystore or a second of key derivation.
 */
internal interface VaultDoor {
    /** The hint its owner left, or blank. */
    val hint: String

    val hasRecoveryKey: Boolean

    /** What a fingerprint can do for this vault as things stand. */
    fun fingerprintMode(): VaultBiometricMode

    /** How long the pause earned by wrong passwords still has to run, in milliseconds. 0 when there is none. */
    fun pauseLeftMs(): Long

    fun countWrongPassword()

    /** The vault opened, by whatever means: the run of wrong passwords is over. */
    fun forgetWrongPasswords()

    /** Tries [password]. True when the vault is now open. Slow, and does its waiting off the main thread. */
    suspend fun open(password: String): Boolean

    /** Shows the fingerprint prompt for [mode] and reports how it ended. */
    suspend fun openWithFingerprint(mode: VaultBiometricMode): VaultBiometricResult
}

/**
 * The unlock page: the password being typed, what went wrong last time, and whether a fingerprint is
 * on offer. Every fifth wrong password in a row earns a pause, longer each round, during which a
 * password isn't even tried. A fingerprint is: it's the phone that rations those.
 */
@Stable
internal class VaultUnlockState(private val door: VaultDoor) {
    val hint: String = door.hint
    val hasRecoveryKey: Boolean = door.hasRecoveryKey

    var fingerprintMode by mutableStateOf(door.fingerprintMode())
        private set

    var password by mutableStateOf("")
        private set

    var problem by mutableStateOf<UnlockProblem?>(null)
        private set

    /** A password is being checked, which takes about a second. */
    var isChecking by mutableStateOf(false)
        private set

    val canSubmit: Boolean get() = password.isNotEmpty()

    val offersFingerprint: Boolean get() = fingerprintMode != VaultBiometricMode.None

    /** A fingerprint that releases the real key is asked for as soon as the page appears. */
    val promptsForFingerprintOnArrival: Boolean get() = fingerprintMode == VaultBiometricMode.VaultKey

    fun type(text: String) {
        password = text
        problem = null
    }

    /** Tries the typed password. True when the vault opened; otherwise [problem] says why not. */
    suspend fun submit(): Boolean {
        if (password.isEmpty() || isChecking) return false
        val pause = door.pauseLeftMs()
        if (pause > 0) {
            problem = UnlockProblem.Paused(pause)
            return false
        }

        isChecking = true
        val opened = door.open(password)
        isChecking = false

        if (opened) {
            door.forgetWrongPasswords()
            return true
        }
        door.countWrongPassword()
        // This one may have been the fifth, and then the news is the pause rather than the mistake.
        val earned = door.pauseLeftMs()
        problem = if (earned > 0) UnlockProblem.Paused(earned) else UnlockProblem.WrongPassword
        return false
    }

    /** Asks for a fingerprint. True when the vault opened. Backing out of the prompt changes nothing. */
    suspend fun useFingerprint(): Boolean = when (val result = door.openWithFingerprint(fingerprintMode)) {
        VaultBiometricResult.Done -> {
            door.forgetWrongPasswords()
            true
        }
        VaultBiometricResult.KeyLost -> {
            // The door has dropped the dead key by now, so it may have nothing left to offer.
            fingerprintMode = door.fingerprintMode()
            problem = UnlockProblem.FingerprintsChanged
            false
        }
        is VaultBiometricResult.Failed -> {
            problem = UnlockProblem.FingerprintFailed(result.message)
            false
        }
        VaultBiometricResult.Cancelled -> false
    }
}
