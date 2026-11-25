package com.kobblo.gallery.file.explorer.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.security.BlockCapture
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.IconTile
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val RECOVERY_KEY_TEST_TAG = "vault_recovery_key"

private const val CLIP_LABEL = "Kobblo recovery key"
private const val CLIP_LIFETIME_MS = 60_000L

// ══════════════════════════════════════════════════════════════════════════════
// SHOWING A RECOVERY KEY
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Keeps a key out of screenshots, screen recordings, and the recent apps thumbnail. It shares the window's
 * block with the rest of the Vault, so leaving a key's page doesn't lift the one the Vault still wants.
 */
@Composable
private fun SecureWindow() = BlockCapture(active = true)

private fun copyRecoveryKey(context: Context, recoveryKey: String) {
    val clip = ClipData.newPlainText(CLIP_LABEL, RecoveryKey.formatted(recoveryKey))
    // Android 13 and up hide sensitive text from the clipboard preview. Older versions ignore this.
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun clearRecoveryKeyFromClipboard(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    // Only ever clear our own clip, and read just its label so Android shows no paste notice.
    if (clipboard.primaryClipDescription?.label != CLIP_LABEL) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

/**
 * The key as one unit, with its copy action. There's deliberately no way to save it to a file or
 * send it to another app: shared storage and share targets are exactly where the old plain text
 * backup leaked from.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoveryKeyPanel(recoveryKey: String, modifier: Modifier = Modifier) {
    SecureWindow()

    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    var copies by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(copies) {
        if (copies > 0) {
            delay(CLIP_LIFETIME_MS)
            clearRecoveryKeyFromClipboard(context)
            isCopied = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { clearRecoveryKeyFromClipboard(context) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobbloShapes.group)
            .background(colors.snow)
            .padding(KobbloSpace.inset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KobbloSpace.md)
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RECOVERY_KEY_TEST_TAG)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(KobbloSpace.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(KobbloSpace.xxs),
            maxItemsInEachRow = 4
        ) {
            RecoveryKey.groups(recoveryKey).forEach { group ->
                Text(text = group, style = KobbloType.code, color = colors.ink)
            }
        }
        Button(
            onClick = {
                haptics.tap()
                copyRecoveryKey(context, recoveryKey)
                isCopied = true
                copies++
            },
            colors = ButtonDefaults.buttonColors(containerColor = colors.mist, contentColor = colors.ink)
        ) {
            Icon(
                imageVector = if (isCopied) AppIcons.Check else AppIcons.Copy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(KobbloSpace.xs))
            Text(text = stringResource(if (isCopied) R.string.vault_key_copied else R.string.vault_copy_key), style = KobbloType.headline)
        }
    }
}

/** What to do with a key that's on screen, and the plain truth about losing it. */
@Composable
private fun RecoveryKeyGuidance() {
    val colors = KobbloDesign.colors
    Text(
        text = stringResource(R.string.vault_key_guidance),
        style = KobbloType.body,
        color = colors.ink,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(KobbloSpace.xs))
    Text(
        text = stringResource(R.string.vault_key_screenshots_note),
        style = KobbloType.callout,
        color = colors.smoke,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(KobbloSpace.lg))
    SetupInfoCard(
        icon = AppIcons.Warning,
        iconTint = colors.danger,
        title = stringResource(R.string.vault_lose_both_title),
        description = stringResource(R.string.vault_key_lose_both_body)
    )
}

@Composable
private fun RecoveryKeyAcknowledgement(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KobbloShapes.md)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = KobbloSpace.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = colors.action, checkmarkColor = colors.onAction)
        )
        Spacer(Modifier.width(KobbloSpace.xxs))
        Text(
            text = stringResource(R.string.vault_key_acknowledge),
            style = KobbloType.callout,
            color = colors.ink
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// SHARED PIECES
// ══════════════════════════════════════════════════════════════════════════════

/**
 * A page's one slow vault call. Anything that takes a password derives a key from it, which takes
 * about a second, so it runs off the main thread while [isRunning] turns the page's button into a
 * spinner.
 */
@Stable
private class SlowVaultCall(private val scope: CoroutineScope) {
    var isRunning by mutableStateOf(false)
        private set

    /** Starts [call], and gives [then] its answer back on the main thread. */
    fun <T> start(call: () -> T, then: (T) -> Unit) {
        isRunning = true
        scope.launch(Dispatchers.Main) {
            val answer = withContext(Dispatchers.Default) { call() }
            isRunning = false
            then(answer)
        }
    }
}

@Composable
private fun rememberSlowVaultCall(): SlowVaultCall {
    val scope = rememberCoroutineScope()
    return remember { SlowVaultCall(scope) }
}

@Composable
internal fun VaultPrimaryButton(
    text: String,
    enabled: Boolean,
    working: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    Button(
        onClick = onClick,
        enabled = enabled && !working,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.action,
            contentColor = colors.onAction,
            disabledContainerColor = colors.action.copy(alpha = if (working) 1f else 0.35f),
            disabledContentColor = colors.onAction.copy(alpha = if (working) 1f else 0.35f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight)
    ) {
        if (working) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(
                color = colors.onAction,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(text = text, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun vaultFieldColors() = with(KobbloDesign.colors) {
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = snow,
        unfocusedContainerColor = snow,
        errorContainerColor = snow,
        focusedBorderColor = action,
        unfocusedBorderColor = silver,
        errorBorderColor = danger,
        focusedTextColor = ink,
        unfocusedTextColor = ink,
        errorTextColor = ink,
        cursorColor = action,
        errorCursorColor = danger,
        focusedLabelColor = action,
        unfocusedLabelColor = smoke,
        errorLabelColor = danger
    )
}

@Composable
private fun VaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {}
) {
    var isVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        shape = KobbloShapes.md,
        colors = vaultFieldColors(),
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { isVisible = !isVisible }) {
                Icon(
                    imageVector = if (isVisible) AppIcons.EyeOff else AppIcons.Eye,
                    contentDescription = stringResource(if (isVisible) R.string.viewer_pdf_password_hide else R.string.viewer_pdf_password_show),
                    tint = KobbloDesign.colors.smoke
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun VaultFieldMessage(text: String, color: Color) {
    Spacer(Modifier.height(KobbloSpace.xxs))
    Text(
        text = text,
        style = KobbloType.caption,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.xs)
    )
}

/** New password, retype, and hint, with the rules the setup wizard uses. */
@Composable
private fun VaultNewPasswordFields(
    password: String,
    onPasswordChange: (String) -> Unit,
    confirm: String,
    onConfirmChange: (String) -> Unit,
    hint: String,
    onHintChange: (String) -> Unit
) {
    val colors = KobbloDesign.colors
    val isTooShort = password.isNotEmpty() && password.length < VaultManager.MIN_PASSWORD_LENGTH
    val isMismatched = confirm.isNotEmpty() && password != confirm

    VaultPasswordField(value = password, onValueChange = onPasswordChange, label = stringResource(R.string.set_new_password_label))
    if (isTooShort) {
        VaultFieldMessage(
            pluralStringResource(R.plurals.vault_password_too_short, VaultManager.MIN_PASSWORD_LENGTH, VaultManager.MIN_PASSWORD_LENGTH),
            colors.smoke
        )
    }
    Spacer(Modifier.height(KobbloSpace.sm))
    VaultPasswordField(
        value = confirm,
        onValueChange = onConfirmChange,
        label = stringResource(R.string.vault_retype_password),
        isError = isMismatched
    )
    if (isMismatched) {
        VaultFieldMessage(stringResource(R.string.vault_passwords_mismatch), colors.danger)
    }
    Spacer(Modifier.height(KobbloSpace.sm))
    OutlinedTextField(
        value = hint,
        onValueChange = onHintChange,
        label = { Text(stringResource(R.string.vault_hint_label)) },
        singleLine = true,
        shape = KobbloShapes.md,
        colors = vaultFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
    VaultFieldMessage(stringResource(R.string.vault_hint_warning), colors.smoke)
}

private fun isNewPasswordValid(password: String, confirm: String) =
    password.length >= VaultManager.MIN_PASSWORD_LENGTH && password == confirm

/** The frame every recovery and security page shares. */
@Composable
internal fun VaultFlowPage(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    backDescription: String = stringResource(R.string.back),
    stepper: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .vaultPageBounds()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VaultPageBar(
            exitIcon = AppIcons.Back,
            exitDescription = backDescription,
            onExit = onBack,
            progress = stepper
        )
        Spacer(Modifier.height(KobbloSpace.lg))
        IconTile(icon = icon, bgColor = colors.snow, iconTint = colors.ink, size = 64.dp)
        Spacer(Modifier.height(KobbloSpace.md))
        Text(text = title, style = KobbloType.title, color = colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(KobbloSpace.xxs))
        Text(text = subtitle, style = KobbloType.callout, color = colors.smoke, textAlign = TextAlign.Center)
        Spacer(Modifier.height(KobbloSpace.xl))
        content()
        // The mini player floats over the end of the page while music plays, and the last button has to clear it.
        Spacer(Modifier.height(KobbloSpace.miniPlayerClearance))
    }
}

/** Moves between the steps of one flow: they're the same place, so they only cross-fade. */
/** Two steps, the first while [step] is null. The second slides in from the right, and Back slides it off again. */
@Composable
private fun <T> VaultFlowSteps(step: T?, content: @Composable (T?) -> Unit) {
    val isReduced = ExpressiveMotion.isReducedMotion()
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            when {
                isReduced -> EnterTransition.None togetherWith ExitTransition.None
                initialState == null -> slideInHorizontally(KobbloMotion.smooth()) { it } togetherWith
                    slideOutHorizontally(KobbloMotion.smooth()) { -it / 4 }
                else -> (slideInHorizontally(KobbloMotion.smooth()) { -it / 4 } togetherWith
                    slideOutHorizontally(KobbloMotion.smooth()) { it }).apply { targetContentZIndex = -1f }
            }
        },
        label = "vault_flow_step"
    ) { content(it) }
}

// ══════════════════════════════════════════════════════════════════════════════
// FORGOT PASSWORD: RECOVERY KEY, THEN A NEW PASSWORD
// ══════════════════════════════════════════════════════════════════════════════

@Composable
internal fun VaultRecoveryScreen(onRecovered: () -> Unit, onBack: () -> Unit) {
    var acceptedKey by remember { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        if (acceptedKey != null) {
            acceptedKey = null
        } else {
            onBack()
        }
    }
    BackHandler(onBack = goBack)

    VaultFlowSteps(step = acceptedKey) { key ->
        if (key == null) {
            EnterRecoveryKeyPage(onAccepted = { acceptedKey = it }, onBack = goBack)
        } else {
            ResetPasswordPage(recoveryKey = key, onDone = onRecovered, onBack = goBack)
        }
    }
}

@Composable
private fun EnterRecoveryKeyPage(onAccepted: (String) -> Unit, onBack: () -> Unit) {
    SecureWindow()

    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val checking = rememberSlowVaultCall()

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (input.isBlank() || checking.isRunning) return
        checking.start({ VaultManager.checkRecoveryKey(context, input) }) { check ->
            when (check) {
                RecoveryKeyCheck.Valid -> {
                    haptics.tap()
                    onAccepted(input)
                }
                RecoveryKeyCheck.Malformed ->
                    error = context.getString(R.string.vault_key_incomplete)
                RecoveryKeyCheck.WrongKey ->
                    error = context.getString(R.string.vault_key_wrong)
            }
        }
    }

    VaultFlowPage(
        icon = AppIcons.Key,
        title = stringResource(R.string.vault_use_key_title),
        subtitle = stringResource(R.string.vault_use_key_subtitle),
        onBack = onBack
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                error = null
            },
            label = { Text(stringResource(R.string.vault_recovery_key_label)) },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
            textStyle = KobbloType.code.copy(fontSize = KobbloType.body.fontSize),
            isError = error != null,
            minLines = 2,
            shape = KobbloShapes.md,
            colors = vaultFieldColors(),
            // The password keyboard type is what tells keyboards not to learn or suggest this.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { VaultFieldMessage(it, colors.danger) }
        Spacer(Modifier.height(KobbloSpace.lg))
        Text(
            text = stringResource(R.string.vault_key_lost_too),
            style = KobbloType.callout,
            color = colors.smoke,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(KobbloSpace.xxl))
        VaultPrimaryButton(
            text = stringResource(R.string.welcome_continue),
            enabled = input.isNotBlank(),
            working = checking.isRunning,
            onClick = ::submit
        )
    }
}

@Composable
private fun ResetPasswordPage(recoveryKey: String, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val saving = rememberSlowVaultCall()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    VaultFlowPage(
        icon = AppIcons.Lock,
        title = stringResource(R.string.vault_new_password_title),
        subtitle = stringResource(R.string.vault_new_password_subtitle),
        onBack = onBack
    ) {
        VaultNewPasswordFields(
            password = password,
            onPasswordChange = { password = it },
            confirm = confirm,
            onConfirmChange = { confirm = it },
            hint = hint,
            onHintChange = { hint = it }
        )
        Spacer(Modifier.height(KobbloSpace.xxl))
        VaultPrimaryButton(
            text = stringResource(R.string.vault_save_and_open),
            enabled = isNewPasswordValid(password, confirm),
            working = saving.isRunning,
            onClick = {
                saving.start({ VaultManager.resetPassword(context, recoveryKey, password, hint) }) { saved ->
                    if (saved) {
                        haptics.tap()
                        Toast.makeText(context, context.getString(R.string.settings_lock_password_changed), Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        Toast.makeText(context, context.getString(R.string.vault_new_password_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// INSIDE THE VAULT: PASSWORD AND RECOVERY KEY
// ══════════════════════════════════════════════════════════════════════════════

/**
 * A row that leads somewhere in the vault's settings. [isWarning] draws its glyph in the danger
 * color, for something left undone that puts what's in the vault at risk.
 */
@Composable
private fun VaultSecurityRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isWarning: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val iconTint = if (isWarning) colors.danger else colors.ink
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobbloShapes.md)
            .clickable {
                haptics.tap()
                onClick()
            }
            .padding(vertical = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon = icon, bgColor = colors.snow, iconTint = iconTint, size = 44.dp)
        Spacer(Modifier.width(KobbloSpace.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = KobbloType.headline, color = colors.ink)
            Text(text = subtitle, style = KobbloType.callout, color = colors.smoke)
        }
        Spacer(Modifier.width(KobbloSpace.xs))
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = colors.smoke,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The way into [VaultSecurityScreen] from the vault's top bar. */
@Composable
internal fun VaultSecurityButton(onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton(
        icon = AppIcons.Key,
        description = stringResource(R.string.vault_security_title),
        onClick = {
            haptics.tap()
            onClick()
        }
    )
}

/** What's still short of fully protected, and the way to fix it. */
@Composable
internal fun VaultNoticeRow(
    notice: VaultNotice,
    onEnterPassword: () -> Unit,
    onRetryEncryption: () -> Unit,
    onChangePassword: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (notice) {
        VaultNotice.NoKeyYet -> VaultSecurityRow(
            icon = com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Warning,
            isWarning = true,
            title = stringResource(R.string.vault_notice_no_key_title),
            subtitle = stringResource(R.string.vault_notice_no_key_body),
            onClick = onEnterPassword,
            modifier = modifier
        )
        VaultNotice.NotEnoughSpace -> VaultSecurityRow(
            icon = com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Warning,
            isWarning = true,
            title = stringResource(R.string.vault_notice_no_space_title),
            subtitle = stringResource(R.string.vault_notice_no_space_body),
            onClick = onRetryEncryption,
            modifier = modifier
        )
        VaultNotice.ShortPassword -> VaultSecurityRow(
            icon = com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Lock,
            isWarning = true,
            title = stringResource(R.string.vault_notice_short_password_title),
            subtitle = pluralStringResource(
                R.plurals.vault_notice_short_password_body,
                VaultManager.MIN_PASSWORD_LENGTH,
                VaultManager.MIN_PASSWORD_LENGTH
            ),
            onClick = onChangePassword,
            modifier = modifier
        )
    }
}

@Composable
private fun VaultFingerprintRow() {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val coroutineScope = rememberCoroutineScope()
    var isOn by remember { mutableStateOf(VaultBiometrics.isEnabled(context)) }

    if (!VaultManager.canEncrypt || (!isOn && !VaultBiometrics.isAvailable(context))) return

    Spacer(Modifier.height(KobbloSpace.xs))
    VaultSecurityRow(
        icon = com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Fingerprint,
        title = stringResource(if (isOn) R.string.vault_fingerprint_off_title else R.string.vault_fingerprint_on_title),
        subtitle = stringResource(if (isOn) R.string.vault_fingerprint_off_body else R.string.vault_fingerprint_on_body),
        onClick = {
            if (isOn) {
                VaultBiometrics.disable(context)
                isOn = false
            } else {
                coroutineScope.launch(Dispatchers.Main) {
                    val result = VaultBiometrics.enable(activity)
                    isOn = VaultBiometrics.isEnabled(context)
                    if (result is VaultBiometricResult.Failed) {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )
}

/** A vault from before recovery keys has no way back from a forgotten password, so it asks for one. */
@Composable
internal fun AddRecoveryKeyPrompt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    VaultSecurityRow(
        icon = AppIcons.Key,
        isWarning = true,
        title = stringResource(R.string.vault_add_key_title),
        subtitle = stringResource(R.string.vault_add_key_body),
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
internal fun ForgotPasswordButton(onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    TextButton(onClick = onClick) {
        Icon(
            imageVector = AppIcons.Key,
            contentDescription = null,
            tint = colors.action,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.vault_forgot_password),
            style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
            color = colors.action
        )
    }
}

@Composable
private fun VaultSecurityScreen(
    onRecoveryKey: () -> Unit,
    onChangePassword: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hasRecoveryKey = remember { VaultManager.hasRecoveryKey(context) }
    BackHandler(onBack = onBack)

    VaultFlowPage(
        icon = AppIcons.Shield,
        title = stringResource(R.string.vault_security_title),
        subtitle = stringResource(if (hasRecoveryKey) R.string.vault_security_has_key else R.string.vault_security_no_key),
        onBack = onBack
    ) {
        VaultSecurityRow(
            icon = AppIcons.Key,
            isWarning = !hasRecoveryKey,
            title = stringResource(if (hasRecoveryKey) R.string.vault_replace_key else R.string.vault_add_key_title),
            subtitle = stringResource(if (hasRecoveryKey) R.string.vault_replace_key_body else R.string.vault_add_key_body),
            onClick = onRecoveryKey
        )
        Spacer(Modifier.height(KobbloSpace.xs))
        VaultSecurityRow(
            icon = AppIcons.Lock,
            title = stringResource(R.string.vault_change_password),
            subtitle = stringResource(R.string.vault_change_password_body),
            onClick = onChangePassword
        )
        VaultFingerprintRow()
    }
}

/** Adds a recovery key to a vault that doesn't have one, or replaces the one it has. */
@Composable
private fun VaultRecoveryKeyScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val isReplacing = remember { VaultManager.hasRecoveryKey(context) }
    var confirmedPassword by remember { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        if (confirmedPassword != null) {
            confirmedPassword = null
        } else {
            onBack()
        }
    }
    BackHandler(onBack = goBack)

    VaultFlowSteps(step = confirmedPassword) { password ->
        if (password == null) {
            ConfirmPasswordPage(
                isReplacing = isReplacing,
                onConfirmed = { confirmedPassword = it },
                onBack = goBack
            )
        } else {
            NewRecoveryKeyPage(password = password, onDone = onDone, onBack = goBack)
        }
    }
}

@Composable
private fun ConfirmPasswordPage(
    isReplacing: Boolean,
    onConfirmed: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val checking = rememberSlowVaultCall()

    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (password.isEmpty() || checking.isRunning) return
        checking.start({ VaultManager.verifyPassword(context, password) }) { isCorrect ->
            if (isCorrect) onConfirmed(password) else error = context.getString(R.string.vault_wrong_password)
        }
    }

    VaultFlowPage(
        icon = AppIcons.Key,
        title = stringResource(if (isReplacing) R.string.vault_replace_key_title else R.string.vault_add_key_title),
        subtitle = stringResource(if (isReplacing) R.string.vault_replace_key_subtitle else R.string.vault_add_key_subtitle),
        onBack = onBack
    ) {
        VaultPasswordField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = stringResource(R.string.vault_password_label),
            isError = error != null,
            imeAction = ImeAction.Done,
            onDone = ::submit
        )
        error?.let { VaultFieldMessage(it, colors.danger) }

        // A vault with no recovery key was made by an earlier version, which offered a text backup.
        if (!isReplacing) {
            Spacer(Modifier.height(KobbloSpace.xl))
            SetupInfoCard(
                icon = AppIcons.Warning,
                iconTint = colors.danger,
                title = stringResource(R.string.vault_backup_file_title),
                description = stringResource(R.string.vault_backup_file_body, "kobblo_vault_password_backup.txt")
            )
        }

        Spacer(Modifier.height(KobbloSpace.xxl))
        VaultPrimaryButton(
            text = stringResource(R.string.welcome_continue),
            enabled = password.isNotEmpty(),
            working = checking.isRunning,
            onClick = ::submit
        )
    }
}

@Composable
private fun NewRecoveryKeyPage(password: String, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val recoveryKey = remember { RecoveryKey.generate() }
    SaveRecoveryKeyPage(
        recoveryKey = recoveryKey,
        finishText = stringResource(R.string.vault_finish),
        savedMessage = stringResource(R.string.vault_key_saved),
        failedMessage = stringResource(R.string.vault_key_save_failed),
        save = { VaultManager.setRecoveryKey(context, password, recoveryKey) },
        onDone = onDone,
        onBack = onBack
    )
}

/**
 * Page 3 of the setup wizard, which shows the key [setup] made when it began. Nothing is committed
 * until the last button, and Back returns to the password with the same key waiting.
 */
@Composable
internal fun VaultSetupStepThree(setup: VaultSetupState, onFinish: () -> Unit) {
    val context = LocalContext.current
    SaveRecoveryKeyPage(
        recoveryKey = setup.recoveryKey,
        finishText = stringResource(R.string.vault_finish_and_open),
        savedMessage = stringResource(R.string.vault_ready),
        failedMessage = stringResource(R.string.vault_setup_failed),
        save = { VaultManager.setupVault(context, setup.password, setup.hint, setup.recoveryKey) },
        onDone = onFinish,
        onBack = setup::showPasswordPage,
        backDescription = stringResource(R.string.vault_previous_step),
        stepper = { StepperIndicator(currentStep = 3, totalSteps = 3) }
    )
}

@Composable
private fun SaveRecoveryKeyPage(
    recoveryKey: String,
    finishText: String,
    savedMessage: String,
    failedMessage: String,
    save: () -> Boolean,
    onDone: () -> Unit,
    onBack: () -> Unit,
    backDescription: String = stringResource(R.string.back),
    stepper: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val saving = rememberSlowVaultCall()

    var isAcknowledged by remember { mutableStateOf(false) }

    VaultFlowPage(
        icon = AppIcons.Key,
        title = stringResource(R.string.vault_save_key_title),
        subtitle = stringResource(R.string.vault_save_key_subtitle),
        onBack = onBack,
        backDescription = backDescription,
        stepper = stepper
    ) {
        RecoveryKeyPanel(recoveryKey = recoveryKey)
        Spacer(Modifier.height(KobbloSpace.md))
        RecoveryKeyGuidance()
        Spacer(Modifier.height(KobbloSpace.md))
        RecoveryKeyAcknowledgement(checked = isAcknowledged, onCheckedChange = { isAcknowledged = it })
        Spacer(Modifier.height(KobbloSpace.xl))
        VaultPrimaryButton(
            text = finishText,
            enabled = isAcknowledged,
            working = saving.isRunning,
            onClick = {
                haptics.tap()
                saving.start(save) { saved ->
                    if (saved) {
                        Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun VaultChangePasswordScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val changing = rememberSlowVaultCall()
    BackHandler(onBack = onBack)

    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf(VaultManager.getVaultPasswordHint(context)) }
    var error by remember { mutableStateOf<String?>(null) }

    VaultFlowPage(
        icon = AppIcons.Lock,
        title = stringResource(R.string.vault_change_password),
        subtitle = stringResource(R.string.vault_change_password_subtitle),
        onBack = onBack
    ) {
        VaultPasswordField(
            value = current,
            onValueChange = {
                current = it
                error = null
            },
            label = stringResource(R.string.old_password_label),
            isError = error != null
        )
        error?.let { VaultFieldMessage(it, colors.danger) }
        Spacer(Modifier.height(KobbloSpace.sm))
        VaultNewPasswordFields(
            password = password,
            onPasswordChange = { password = it },
            confirm = confirm,
            onConfirmChange = { confirm = it },
            hint = hint,
            onHintChange = { hint = it }
        )
        Spacer(Modifier.height(KobbloSpace.xxl))
        VaultPrimaryButton(
            text = stringResource(R.string.vault_change_password),
            enabled = current.isNotEmpty() && isNewPasswordValid(password, confirm),
            working = changing.isRunning,
            onClick = {
                changing.start({ VaultManager.changePassword(context, current, password, hint) }) { changed ->
                    if (changed) {
                        haptics.tap()
                        Toast.makeText(context, context.getString(R.string.settings_lock_password_changed), Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        error = context.getString(R.string.vault_wrong_password)
                    }
                }
            }
        )
    }
}

private enum class VaultSecurityRoute { Overview, RecoveryKey, ChangePassword }

private val VaultSecurityRoute?.depth: Int
    get() = when (this) {
        null -> 0
        VaultSecurityRoute.Overview -> 1
        VaultSecurityRoute.RecoveryKey, VaultSecurityRoute.ChangePassword -> 2
    }

/**
 * The vault's browser and the screens reached from it. They go deeper the same way the rest of the
 * app does. The browser is the only screen that can lock the vault, so nothing here needs a reset.
 */
@Composable
internal fun VaultSecurityHost(
    browser: @Composable (onOpenSecurity: () -> Unit, onAddRecoveryKey: () -> Unit) -> Unit
) {
    val routes = remember { mutableStateListOf<VaultSecurityRoute>() }
    val pop: () -> Unit = { routes.removeLastOrNull() }
    val isReduced = ExpressiveMotion.isReducedMotion()
    val motion = KobbloDesign.motion

    AnimatedContent(
        targetState = routes.lastOrNull(),
        transitionSpec = {
            when {
                isReduced -> EnterTransition.None togetherWith ExitTransition.None
                targetState.depth > initialState.depth ->
                    (slideInHorizontally(motion.smooth()) { it } + fadeIn(motion.fadeIn()))
                        .togetherWith(
                            slideOutHorizontally(motion.smooth()) { -it / 4 } +
                                fadeOut(tween(motion.Slow, easing = motion.EaseOut), targetAlpha = 0.5f)
                        )
                        .apply { targetContentZIndex = 1f }
                else ->
                    (slideInHorizontally(motion.smooth()) { -it / 4 } +
                        fadeIn(tween(motion.Default, easing = motion.EaseOut), initialAlpha = 0.5f))
                        .togetherWith(slideOutHorizontally(motion.smooth()) { it } + fadeOut(motion.fadeOut()))
                        .apply { targetContentZIndex = -1f }
            }
        },
        label = "vault_security_depth"
    ) { route ->
        when (route) {
            null -> browser(
                { routes.add(VaultSecurityRoute.Overview) },
                { routes.add(VaultSecurityRoute.RecoveryKey) }
            )
            VaultSecurityRoute.Overview -> VaultSecurityScreen(
                onRecoveryKey = { routes.add(VaultSecurityRoute.RecoveryKey) },
                onChangePassword = { routes.add(VaultSecurityRoute.ChangePassword) },
                onBack = pop
            )
            VaultSecurityRoute.RecoveryKey -> VaultRecoveryKeyScreen(onDone = pop, onBack = pop)
            VaultSecurityRoute.ChangePassword -> VaultChangePasswordScreen(onDone = pop, onBack = pop)
        }
    }
}
