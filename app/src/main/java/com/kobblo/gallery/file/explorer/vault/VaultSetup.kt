package com.kobblo.gallery.file.explorer.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

private const val SetupSteps = 3

/**
 * Makes a vault in three pages: what a vault is and what it can't do, choosing its password, and
 * the recovery key. The pages slide the way the wizard is moving.
 */
@Composable
internal fun VaultSetupWizard(onSetupCompleted: () -> Unit, onClose: () -> Unit) {
    val setup = remember { VaultSetupState(RecoveryKey.generate()) }

    AnimatedContent(
        targetState = setup.step,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally { width -> direction * width } + fadeIn())
                .togetherWith(slideOutHorizontally { width -> -direction * width } + fadeOut())
        },
        label = "vault_setup_step"
    ) { step ->
        when (step) {
            SetupStep.Welcome -> SetupWelcomePage(onContinue = setup::showPasswordPage, onClose = onClose)
            SetupStep.ChoosePassword -> SetupPasswordPage(setup)
            SetupStep.SaveRecoveryKey -> VaultSetupStepThree(setup = setup, onFinish = onSetupCompleted)
        }
    }
}

@Composable
private fun SetupProgress(step: SetupStep) {
    StepperIndicator(currentStep = step.ordinal + 1, totalSteps = SetupSteps)
}

/** Page 1: what's about to be made, and the four things to understand before relying on it. */
@Composable
private fun SetupWelcomePage(onContinue: () -> Unit, onClose: () -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current

    VaultEntryPage(
        bar = {
            VaultPageBar(
                exitIcon = AppIcons.Close,
                exitDescription = stringResource(R.string.vault_cancel_setup),
                onExit = onClose,
                progress = { SetupProgress(SetupStep.Welcome) }
            )
        }
    ) {
        Spacer(Modifier.height(24.dp))
        VaultEntryHeading(
            icon = AppIcons.Shield,
            badgeSize = 80.dp,
            badgeGap = 16.dp,
            title = stringResource(R.string.vault_welcome_title),
            titleStyle = KobbloType.largeTitle,
            subtitle = stringResource(R.string.vault_welcome_subtitle),
            subtitleStyle = KobbloType.callout
        )
        Spacer(Modifier.height(24.dp))

        SetupInfoCard(
            icon = AppIcons.CloudOff,
            iconTint = colors.ink,
            title = stringResource(R.string.vault_setup_offline_title),
            description = stringResource(R.string.vault_setup_offline_body)
        )
        Spacer(Modifier.height(BetweenCards))
        SetupInfoCard(
            icon = AppIcons.KeyOff,
            iconTint = colors.ink,
            title = stringResource(R.string.vault_setup_no_reset_title),
            description = stringResource(R.string.vault_setup_no_reset_body)
        )
        Spacer(Modifier.height(BetweenCards))
        // The one that costs everything if it's skimmed, so it alone is in the warning color.
        SetupInfoCard(
            icon = AppIcons.Warning,
            iconTint = colors.danger,
            title = stringResource(R.string.vault_lose_both_title),
            description = stringResource(R.string.vault_setup_lose_both_body)
        )
        Spacer(Modifier.height(BetweenCards))
        SetupInfoCard(
            icon = AppIcons.Phone,
            iconTint = colors.ink,
            title = stringResource(R.string.vault_setup_this_phone_title),
            description = stringResource(R.string.vault_setup_this_phone_body)
        )

        Spacer(Modifier.height(BeforeButton))
        VaultEntryButton(
            text = stringResource(R.string.vault_setup_password_action),
            onClick = {
                haptics.tap()
                onContinue()
            }
        )
    }
}

/** Page 2: the password, its repeat, and a hint for the day it won't come to mind. */
@Composable
private fun SetupPasswordPage(setup: VaultSetupState) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    // What's typed stays on this page until Continue takes it: leaving with Back forgets it.
    var entry by remember { mutableStateOf(setup.chosen) }
    val minLength = VaultManager.MIN_PASSWORD_LENGTH
    // Passwords are kept out of what the keyboard learns and suggests.
    val secretKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

    VaultEntryPage(
        bar = {
            VaultPageBar(
                exitIcon = AppIcons.Back,
                exitDescription = stringResource(R.string.vault_previous_step),
                onExit = setup::showWelcome,
                progress = { SetupProgress(SetupStep.ChoosePassword) }
            )
        }
    ) {
        Spacer(Modifier.height(20.dp))
        VaultEntryHeading(
            icon = AppIcons.Lock,
            badgeSize = 64.dp,
            badgeGap = 12.dp,
            title = stringResource(R.string.vault_create_password_title),
            titleStyle = KobbloType.title,
            subtitle = stringResource(R.string.vault_create_password_subtitle),
            subtitleStyle = KobbloType.footnote
        )
        Spacer(Modifier.height(24.dp))

        VaultSecretField(
            value = entry.password,
            onValueChange = { entry = entry.withPassword(it) },
            label = stringResource(R.string.vault_password_label),
            placeholder = pluralStringResource(R.plurals.vault_password_placeholder, minLength, minLength),
            leadingIcon = AppIcons.Lock,
            keyboardOptions = secretKeyboard
        )
        Spacer(Modifier.height(BetweenFields))
        VaultSecretField(
            value = entry.repeat,
            onValueChange = { entry = entry.withRepeat(it) },
            label = stringResource(R.string.vault_retype_password),
            placeholder = stringResource(R.string.vault_retype_placeholder),
            leadingIcon = AppIcons.LockClock,
            isWrong = entry.isMismatched,
            keyboardOptions = secretKeyboard
        )
        when (entry.fault) {
            PasswordFault.Mismatch -> VaultEntryFieldNote(
                text = stringResource(R.string.vault_passwords_mismatch),
                color = MaterialTheme.colorScheme.error,
                gap = 4.dp
            )
            PasswordFault.TooShort -> VaultEntryFieldNote(
                text = pluralStringResource(R.plurals.vault_password_too_short, minLength, minLength),
                color = colors.smoke,
                gap = 4.dp
            )
            null -> Unit
        }
        Spacer(Modifier.height(BetweenFields))
        VaultEntryField(
            value = entry.hint,
            onValueChange = { entry = entry.withHint(it) },
            label = stringResource(R.string.vault_hint_label),
            placeholder = stringResource(R.string.vault_hint_placeholder),
            leadingIcon = AppIcons.Bulb
        )
        VaultEntryFieldNote(text = stringResource(R.string.vault_hint_note), color = colors.smoke, gap = 6.dp)

        Spacer(Modifier.height(BeforeButton))
        VaultEntryButton(
            text = stringResource(R.string.vault_continue_to_key),
            enabled = entry.isAcceptable,
            onClick = {
                if (entry.isAcceptable) {
                    haptics.tap()
                    setup.choose(entry)
                }
            }
        )
    }
}

private val BetweenCards = 12.dp
private val BetweenFields = 12.dp
private val BeforeButton = 32.dp
