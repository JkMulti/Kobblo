package com.kobblo.gallery.file.explorer.vault

import android.os.Build
import androidx.biometric.BiometricManager.Authenticators

/**
 * What a fingerprint prompt accepts. The library throws on strong biometrics with a PIN before
 * Android 11, and minSdk is 26, so older phones ask for the weaker pairing it does allow.
 */
object BiometricAuth {
    val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        } else {
            Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        }
}
