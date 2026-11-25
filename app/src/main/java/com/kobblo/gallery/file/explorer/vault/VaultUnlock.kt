package com.kobblo.gallery.file.explorer.vault

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The real vault on this phone, as the unlock page's rules see it. */
private class PhoneVaultDoor(private val context: Context) : VaultDoor {
    override val hint: String get() = VaultManager.getVaultPasswordHint(context)

    override val hasRecoveryKey: Boolean get() = VaultManager.hasRecoveryKey(context)

    override fun fingerprintMode(): VaultBiometricMode = VaultBiometrics.mode(context)

    override fun pauseLeftMs(): Long = VaultAttempts.lockedOutFor(context)

    override fun countWrongPassword() = VaultAttempts.recordFailure(context)

    override fun forgetWrongPasswords() = VaultAttempts.recordSuccess(context)

    override suspend fun open(password: String): Boolean =
        withContext(Dispatchers.Default) { VaultManager.unlock(context, password) }

    override suspend fun openWithFingerprint(mode: VaultBiometricMode): VaultBiometricResult {
        // The system's prompt needs an activity to stand on. Without one there's nothing to show.
        val activity = context as? FragmentActivity ?: return VaultBiometricResult.Cancelled
        return VaultBiometrics.unlock(activity, mode)
    }
}

/**
 * Asks for the vault's password, with the ways round typing it: a fingerprint when one is set up,
 * the hint when one was left, and the recovery key when the password is gone.
 */
@Composable
internal fun VaultUnlockScreen(
    onUnlocked: () -> Unit,
    onForgotPassword: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val unlock = remember { VaultUnlockState(PhoneVaultDoor(context)) }

    val haptics = LocalHaptics.current
    fun letIn() {
        haptics.tap()
        onUnlocked()
    }

    // Both ways in take their time, one deriving a key and one waiting on a finger, so neither blocks the page.
    val scope = rememberCoroutineScope()
    fun submit() {
        scope.launch(Dispatchers.Main) { if (unlock.submit()) letIn() }
    }
    fun askForFingerprint() {
        scope.launch(Dispatchers.Main) { if (unlock.useFingerprint()) letIn() }
    }

    LaunchedEffect(Unit) {
        if (unlock.promptsForFingerprintOnArrival) askForFingerprint()
    }

    VaultEntryPage(
        bar = {
            VaultPageBar(exitIcon = AppIcons.Back, exitDescription = stringResource(R.string.back), onExit = onClose)
        }
    ) {
        Spacer(Modifier.height(36.dp))
        VaultEntryHeading(
            icon = AppIcons.Lock,
            badgeSize = 72.dp,
            badgeGap = 16.dp,
            title = stringResource(R.string.vault_unlock),
            titleStyle = KobbloType.title,
            subtitle = stringResource(R.string.vault_unlock_subtitle),
            subtitleStyle = KobbloType.footnote
        )
        Spacer(Modifier.height(28.dp))

        VaultSecretField(
            value = unlock.password,
            onValueChange = unlock::type,
            label = stringResource(R.string.vault_password_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        unlock.problem?.let { problem ->
            VaultEntryFieldNote(text = problem.inWords(context), color = MaterialTheme.colorScheme.error, gap = 6.dp)
        }
        Spacer(Modifier.height(12.dp))

        if (unlock.hint.isNotBlank()) {
            PasswordHint(unlock.hint)
        }
        if (unlock.hasRecoveryKey) {
            ForgotPasswordButton(onClick = onForgotPassword)
        }
        if (unlock.offersFingerprint && context is FragmentActivity) {
            Spacer(Modifier.height(8.dp))
            QuietAction(
                icon = AppIcons.Fingerprint,
                iconSize = 18.dp,
                text = stringResource(R.string.lock_use_fingerprint),
                onClick = ::askForFingerprint
            )
        }

        Spacer(Modifier.height(32.dp))
        VaultPrimaryButton(
            text = stringResource(R.string.vault_unlock),
            enabled = unlock.canSubmit,
            working = unlock.isChecking,
            onClick = ::submit
        )
    }
}

private fun UnlockProblem.inWords(context: Context): String = when (this) {
    UnlockProblem.WrongPassword -> context.getString(R.string.vault_wrong_password)
    is UnlockProblem.Paused -> VaultAttempts.waitMessage(context, waitMs)
    UnlockProblem.FingerprintsChanged -> context.getString(R.string.vault_fingerprints_changed)
    is UnlockProblem.FingerprintFailed -> message
}

/** The hint stays folded away until it's asked for, so a glance at the screen doesn't give it up. */
@Composable
private fun ColumnScope.PasswordHint(hint: String) {
    val colors = KobbloDesign.colors
    var isShown by rememberSaveable { mutableStateOf(false) }

    QuietAction(
        icon = AppIcons.Bulb,
        iconSize = 16.dp,
        text = stringResource(if (isShown) R.string.vault_hide_hint else R.string.vault_view_hint),
        onClick = { isShown = !isShown }
    )
    AnimatedVisibility(visible = isShown) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(KobbloShapes.group)
                .background(colors.snow)
        ) {
            Column(modifier = Modifier.padding(KobbloSpace.inset)) {
                Text(text = stringResource(R.string.vault_hint), style = KobbloType.footnote, color = colors.smoke)
                Spacer(Modifier.height(2.dp))
                Text(text = hint, style = KobbloType.callout, color = colors.ink)
            }
        }
    }
}

/** A small tinted link under the password field: a glyph and a few words. */
@Composable
private fun QuietAction(icon: ImageVector, iconSize: Dp, text: String, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    TextButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.action, modifier = Modifier.size(iconSize))
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = KobbloType.footnote, color = colors.action)
    }
}
