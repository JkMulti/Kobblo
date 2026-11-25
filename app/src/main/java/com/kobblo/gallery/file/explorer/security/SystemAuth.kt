package com.kobblo.gallery.file.explorer.security

import android.app.Activity
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kobblo.gallery.file.explorer.vault.BiometricAuth

/**
 * Android's own authentication, as Kobblo's lock uses it.
 *
 * Fingerprint or face is only ever a shortcut in front of Kobblo's PIN, pattern or password, and only
 * strong biometrics count: the phone's screen lock never opens Kobblo, or anyone who knows the phone's
 * PIN could. The screen lock is used for one thing, vouching for someone who forgot Kobblo's lock.
 */
object SystemAuth {

    /** A strong fingerprint or face is enrolled, so it can stand in for the lock. */
    fun canUseBiometrics(context: Context): Boolean = status(context, BIOMETRIC_STRONG)

    /** The phone has a screen lock (or biometrics) that can vouch for a reset. */
    fun hasScreenLock(context: Context): Boolean = status(context, BiometricAuth.allowed)

    /**
     * Asks for fingerprint or face. [declineLabel] is the button that goes back to the lock, such as
     * "Use PIN"; pressing it, or any error, calls [onDeclined].
     */
    fun askBiometric(
        activity: Activity,
        title: String,
        declineLabel: String,
        onPassed: () -> Unit,
        onDeclined: () -> Unit = {}
    ) {
        val request = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(declineLabel)
            .build()
        show(activity, request, onPassed, onDeclined)
    }

    /** Asks for the phone's screen lock, to vouch for resetting a forgotten Kobblo lock. */
    fun askScreenLock(activity: Activity, title: String, subtitle: String, onPassed: () -> Unit) {
        val request = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricAuth.allowed)
            .build()
        show(activity, request, onPassed, onEnded = {})
    }

    private fun status(context: Context, authenticators: Int): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun show(activity: Activity, request: BiometricPrompt.PromptInfo, onPassed: () -> Unit, onEnded: () -> Unit) {
        // The prompt needs a FragmentActivity to attach to; without one there's nothing to ask with.
        val host = activity as? FragmentActivity
        if (host == null) {
            onEnded()
            return
        }
        val listener = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onPassed()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onEnded()
        }
        BiometricPrompt(host, ContextCompat.getMainExecutor(host), listener).authenticate(request)
    }
}
