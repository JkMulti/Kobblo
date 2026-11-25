package com.kobblo.gallery.file.explorer.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

internal enum class VaultBiometricMode {
    /** A fingerprint releases the real vault key. */
    VaultKey,

    /** Plaintext from an older version, where the prompt is the only gate there has ever been. */
    PromptOnly,
    None
}

internal sealed interface VaultBiometricResult {
    data object Done : VaultBiometricResult
    data object Cancelled : VaultBiometricResult

    /** The sealed key can never open again, usually because the phone's fingerprints changed. */
    data object KeyLost : VaultBiometricResult
    class Failed(val message: String) : VaultBiometricResult
}

/**
 * Fingerprint unlock, through a third seal on the vault key made with a key that lives in Android
 * Keystore. That key is usable only straight after a strong biometric check, so the prompt is what
 * releases the vault key rather than a gate in front of it, and the wrapped key is useless off this
 * phone. Enrolling a new fingerprint destroys the Keystore key, so a fingerprint added later can't
 * open the vault. Enrolled people then just use their password and turn this on again.
 *
 * There's no device PIN fallback, even though Android allows one. A PIN is far easier to learn than
 * a vault password, often by the very people a vault is meant to keep out.
 */
internal object VaultBiometrics {
    private const val KEY_ALIAS = "kobblo_vault_biometric"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val PREF_OFFERED = "vault_biometric_offered"
    private val SLOT = "kobblo.vault.v1.biometric".toByteArray(Charsets.US_ASCII)

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(context: Context): Boolean = VaultManager.keyring(context).hasBiometricKey

    fun mode(context: Context): VaultBiometricMode = when {
        isEnabled(context) -> VaultBiometricMode.VaultKey
        isAppBiometricSettingOn(context) && VaultManager.hasOnlyPlaintextFiles(context) -> VaultBiometricMode.PromptOnly
        else -> VaultBiometricMode.None
    }

    /** People who already had biometrics on get asked once. After that it's on the security screen. */
    fun shouldOfferToEnable(context: Context): Boolean =
        VaultManager.canEncrypt &&
            isAppBiometricSettingOn(context) &&
            !isEnabled(context) &&
            !VaultManager.prefs(context).getBoolean(PREF_OFFERED, false) &&
            isAvailable(context)

    suspend fun offerToEnable(activity: FragmentActivity) {
        VaultManager.prefs(activity).edit().putBoolean(PREF_OFFERED, true).apply()
        enable(activity)
    }

    suspend fun unlock(activity: FragmentActivity, mode: VaultBiometricMode): VaultBiometricResult = when (mode) {
        VaultBiometricMode.VaultKey -> unlockWithVaultKey(activity)
        VaultBiometricMode.PromptOnly -> unlockWithPromptOnly(activity)
        VaultBiometricMode.None -> VaultBiometricResult.Cancelled
    }

    suspend fun enable(activity: FragmentActivity): VaultBiometricResult {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKeystoreKey()) }
        } catch (e: GeneralSecurityException) {
            return failed(activity, e)
        } catch (e: ProviderException) {
            return failed(activity, e)
        }
        val prompt = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_fingerprint_prompt_title))
            .setSubtitle(activity.getString(R.string.vault_fingerprint_prompt_body))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.lock_not_now))
            .setConfirmationRequired(false)
            .build()

        return when (val outcome = authenticate(activity, prompt, cipher)) {
            is AuthOutcome.Success -> try {
                // Keystore refuses to touch the key at all until the check has passed, even for AAD.
                cipher.updateAAD(SLOT)
                val sealed = VaultManager.withVaultKey(cipher::doFinal)
                    ?: return VaultBiometricResult.Failed(activity.getString(R.string.vault_is_locked))
                if (VaultManager.keyring(activity).saveBiometricKey(KeystoreSealedKey(cipher.iv, sealed))) {
                    VaultBiometricResult.Done
                } else {
                    VaultBiometricResult.Failed(activity.getString(R.string.vault_fingerprint_save_failed))
                }
            } catch (e: GeneralSecurityException) {
                failed(activity, e)
            } catch (e: ProviderException) {
                failed(activity, e)
            }
            AuthOutcome.Cancelled -> VaultBiometricResult.Cancelled
            is AuthOutcome.Error -> VaultBiometricResult.Failed(outcome.message)
        }
    }

    fun disable(context: Context) {
        VaultManager.keyring(context).removeBiometricKey()
        try {
            keystore().deleteEntry(KEY_ALIAS)
        } catch (e: GeneralSecurityException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private suspend fun unlockWithVaultKey(activity: FragmentActivity): VaultBiometricResult {
        val sealed = VaultManager.keyring(activity).readBiometricKey() ?: return lost(activity)
        val cipher = try {
            val key = keystore().getKey(KEY_ALIAS, null) as? SecretKey ?: return lost(activity)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return lost(activity)
        } catch (e: GeneralSecurityException) {
            return failed(activity, e)
        } catch (e: IOException) {
            return failed(activity, e)
        } catch (e: ProviderException) {
            return failed(activity, e)
        }
        val prompt = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_unlock))
            .setSubtitle(activity.getString(R.string.vault_fingerprint_unlock_subtitle))
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(activity.getString(R.string.vault_use_password))
            .setConfirmationRequired(false)
            .build()

        return when (val outcome = authenticate(activity, prompt, cipher)) {
            is AuthOutcome.Success -> try {
                cipher.updateAAD(SLOT)
                VaultManager.openSession(activity, cipher.doFinal(sealed.ciphertext))
                VaultBiometricResult.Done
            } catch (_: AEADBadTagException) {
                lost(activity)
            } catch (e: GeneralSecurityException) {
                failed(activity, e)
            } catch (e: ProviderException) {
                failed(activity, e)
            }
            AuthOutcome.Cancelled -> VaultBiometricResult.Cancelled
            is AuthOutcome.Error -> VaultBiometricResult.Failed(outcome.message)
        }
    }

    private suspend fun unlockWithPromptOnly(activity: FragmentActivity): VaultBiometricResult {
        // Android 9 and 10 can't combine a device PIN with strong biometrics.
        val biometric = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Authenticators.BIOMETRIC_STRONG
        } else {
            Authenticators.BIOMETRIC_WEAK
        }
        val prompt = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_unlock))
            .setSubtitle(activity.getString(R.string.vault_credential_unlock_subtitle))
            .setAllowedAuthenticators(biometric or Authenticators.DEVICE_CREDENTIAL)
            .build()

        return when (val outcome = authenticate(activity, prompt, cipher = null)) {
            is AuthOutcome.Success -> {
                VaultManager.openSessionWithoutKey()
                VaultBiometricResult.Done
            }
            AuthOutcome.Cancelled -> VaultBiometricResult.Cancelled
            is AuthOutcome.Error -> VaultBiometricResult.Failed(outcome.message)
        }
    }

    private fun newKeystoreKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec.build()) }
            .generateKey()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun isAppBiometricSettingOn(context: Context): Boolean =
        context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).getBoolean("lock_biometric_enabled", false)

    private fun lost(context: Context): VaultBiometricResult {
        disable(context)
        return VaultBiometricResult.KeyLost
    }

    private fun failed(context: Context, cause: Exception): VaultBiometricResult {
        cause.printStackTrace()
        return VaultBiometricResult.Failed(context.getString(R.string.vault_fingerprint_failed))
    }

    private sealed interface AuthOutcome {
        data object Success : AuthOutcome
        data object Cancelled : AuthOutcome
        class Error(val message: String) : AuthOutcome
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        info: BiometricPrompt.PromptInfo,
        cipher: Cipher?
    ): AuthOutcome = suspendCancellableCoroutine { continuation ->
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(AuthOutcome.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val outcome = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> AuthOutcome.Cancelled
                    else -> AuthOutcome.Error(errString.toString())
                }
                if (continuation.isActive) continuation.resume(outcome)
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        if (cipher != null) {
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } else {
            prompt.authenticate(info)
        }
    }
}
