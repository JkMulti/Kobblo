package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.DarkSurfaceStyle
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ThemeMode
import com.kobblo.gallery.file.explorer.data.AppLanguages
import com.kobblo.gallery.file.explorer.tools.ToolFolderPicker
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.crash.CrashLog
import com.kobblo.gallery.file.explorer.transfer.core.DeviceName
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import com.kobblo.gallery.file.explorer.security.AppLockGate
import com.kobblo.gallery.file.explorer.security.LockDialog
import com.kobblo.gallery.file.explorer.security.LockFlow
import com.kobblo.gallery.file.explorer.security.SystemAuth
import com.kobblo.gallery.file.explorer.security.LockStore
import com.kobblo.gallery.file.explorer.security.PrivacyPrefs
import com.kobblo.gallery.file.explorer.security.findActivity
import com.kobblo.gallery.file.explorer.security.typeName
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.viewer.ViewerPrefs

/** Stored as these words, so every older install keeps its choice. */
private const val ViewerInApp = "In-app"
private const val ViewerDevice = "Device default"

/**
 * The Settings tab: everything about the app itself, from the general to the particular. Access and
 * Smart Features come first, as the two that change what the app can do at all. Then General, how it
 * looks, how it opens files, what happens to deleted ones, where Organize puts things, how it's locked,
 * Send & Receive, and last About, with the crash report when there is one.
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onShowAbout: () -> Unit
) {
    // Changing a setting is felt, with the tick that Settings has to itself, before it takes effect.
    val haptics = LocalHaptics.current
    val change = remember(onAction, haptics) {
        { setting: AppAction ->
            haptics.settingsToggle()
            onAction(setting)
        }
    }

    // A tab, so a folding large title and no Back, as on the others.
    val largeTitle = rememberLargeTitleState()
    Column(
        modifier = Modifier
            .kobbloReadableWidth()
            .nestedScroll(largeTitle.connection)
    ) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.settings_title),
            showSearch = false,
            largeTitle = largeTitle,
            modifier = Modifier.padding(horizontal = KobbloSpace.screen)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(KobbloSpace.xs))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.access_settings_row),
                    subtitle = stringResource(R.string.access_settings_row_body),
                    leading = { KobbloIconBadge(AppIcons.ShieldCheck, KobbloTint.Gray) },
                    trailing = { KobbloChevron() },
                    showDivider = com.kobblo.gallery.file.explorer.smart.SmartFeatures.isSupported,
                    onClick = { Hub.open(com.kobblo.gallery.file.explorer.ui.welcome.WelcomeRoute.Permissions) }
                )
                if (com.kobblo.gallery.file.explorer.smart.SmartFeatures.isSupported) {
                    KobbloRow(
                        title = stringResource(R.string.smart_title),
                        subtitle = stringResource(R.string.smart_settings_row_body),
                        leading = { KobbloIconBadge(AppIcons.Sparkles, KobbloTint.Purple) },
                        trailing = { KobbloChevron() },
                        onClick = { Hub.open(com.kobblo.gallery.file.explorer.ui.welcome.WelcomeRoute.SmartFeatures) }
                    )
                }
            }

            GeneralSection(state, change)
            AppearanceSection(state, change)
            ViewersSection(state, change)
            RecentlyDeletedSection(state, change)
            OrganizerSection(state, change)
            PrivacySection(state, change)
            SendSection(state, change)
            AboutSection(onShowAbout)

            Spacer(
                Modifier.height(
                    KobbloSpace.tabBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                        if (state.currentAudioFile != null) KobbloSpace.miniPlayerClearance else 0.dp
                )
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    showDivider: Boolean = false
) {
    KobbloRow(
        title = title,
        subtitle = subtitle,
        trailing = { ExpressiveSwitch(checked = checked, onCheckedChange = onChange) },
        showDivider = showDivider
    )
}

/** How big each tint's circle is, and the ring that marks the picked one. */
private val TintSwatch = 28.dp
private val TintRingGap = 2.dp
private val TintRingWidth = 2.dp

/**
 * The tints as a row of circles, each in the color it gives the app now, light or dark. The picked
 * one has a ring with an even gap, so the two circles share a center.
 */
@Composable
private fun TintPicker(selected: KobbloAccent, onSelect: (KobbloAccent) -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    Row(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        KobbloAccent.entries.forEach { accent ->
            val picked = accent == selected
            val tint = accent.color(colors.isDark)
            val ring by animateFloatAsState(
                targetValue = if (picked) 1f else 0f,
                animationSpec = KobbloMotion.state(),
                label = "tint_ring"
            )
            val name = stringResource(accent.label)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .selectable(
                        selected = picked,
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!picked) {
                            haptics.tap()
                            onSelect(accent)
                        }
                    }
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(TintSwatch + (TintRingGap + TintRingWidth) * 2)
                        .graphicsLayer {
                            alpha = ring
                            scaleX = 0.85f + 0.15f * ring
                            scaleY = 0.85f + 0.15f * ring
                        }
                        .border(TintRingWidth, tint, CapsuleShape)
                )
                Box(
                    modifier = Modifier
                        .size(TintSwatch)
                        .clip(CapsuleShape)
                        .background(tint)
                )
            }
        }
    }
}

@Composable
private fun AppearanceSection(state: AppState, onAction: (AppAction) -> Unit) {
    KobbloGroupHeader(stringResource(R.string.settings_appearance))
    KobbloGroup {
        val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
        KobbloRow(
            title = stringResource(R.string.theme_mode_label),
            below = {
                Spacer(Modifier.height(KobbloSpace.xs))
                KobbloSegmentedControl(
                    options = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    selectedIndex = modes.indexOf(state.themeMode).coerceAtLeast(0),
                    onSelect = { onAction(AppAction.SetThemeMode(modes[it])) }
                )
            },
            showDivider = true
        )
        // Only dark has a black to deepen, so the choice appears when it can do something. The colors on
        // screen say whether this is dark, which covers following the system as well as choosing it here.
        if (KobbloDesign.colors.isDark) {
            SwitchRow(
                title = stringResource(R.string.amoled_black),
                subtitle = stringResource(R.string.amoled_black_subtitle),
                checked = state.darkSurfaceStyle == DarkSurfaceStyle.AMOLED,
                onChange = { onAction(AppAction.SetDarkSurfaceStyle(if (it) DarkSurfaceStyle.AMOLED else DarkSurfaceStyle.STANDARD)) },
                showDivider = true
            )
        }
        KobbloRow(
            title = stringResource(R.string.settings_tint),
            below = {
                Spacer(Modifier.height(KobbloSpace.xs))
                TintPicker(selected = state.tint, onSelect = { onAction(AppAction.SetTint(it)) })
            },
            showDivider = true
        )
        val columns = listOf(2, 3, 4)
        KobbloRow(
            title = stringResource(R.string.settings_grid_columns),
            subtitle = stringResource(R.string.settings_grid_columns_desc),
            trailing = {
                KobbloSegmentedControl(
                    options = columns.map { it.toString() },
                    selectedIndex = columns.indexOf(state.gridColumns).coerceAtLeast(0),
                    onSelect = { if (columns[it] != state.gridColumns) onAction(AppAction.SetGridColumns(columns[it])) },
                    modifier = Modifier.width(132.dp)
                )
            },
            showDivider = true
        )
        SwitchRow(
            title = stringResource(R.string.settings_hidden_files),
            subtitle = stringResource(R.string.settings_hidden_files_desc),
            checked = state.showHiddenFiles,
            onChange = { onAction(AppAction.SetShowHiddenFiles(it)) }
        )
    }
}

/** The app's language, and whether it answers touches with a tap. */
@Composable
private fun GeneralSection(state: AppState, onAction: (AppAction) -> Unit) {
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    val isDefault = state.currentLanguage == AppLanguages.DEVICE_DEFAULT_TAG || state.currentLanguage.isBlank()
    val current = if (isDefault) {
        stringResource(R.string.settings_language_default)
    } else {
        AppLanguages.SUPPORTED_LANGUAGES.find { it.tag.equals(state.currentLanguage, ignoreCase = true) }?.nativeName ?: state.currentLanguage
    }
    KobbloGroupHeader(stringResource(R.string.settings_general))
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.settings_language),
            subtitle = current,
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { showLanguages = true }
        )
        SwitchRow(
            title = stringResource(R.string.settings_vibration),
            subtitle = stringResource(R.string.settings_vibration_desc),
            checked = state.haptics.masterEnabled,
            onChange = { onAction(AppAction.ToggleHapticsMaster(it)) }
        )
    }
    if (showLanguages) {
        LanguageSheet(state, isDefault, onAction, onDismiss = { showLanguages = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(state: AppState, isDefault: Boolean, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        // The one list in Settings longer than a phone is tall, so the sheet scrolls, title and all.
        val scrolling = Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
        Column(modifier = scrolling.padding(bottom = KobbloSpace.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.settings_language),
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = KobbloDesign.colors.ink,
                modifier = Modifier.padding(vertical = KobbloSpace.xs)
            )
            KobbloGroup {
                fun pick(tag: String) {
                    onDismiss()
                    onAction(AppAction.SetLanguage(tag))
                }
                LanguageRow(
                    name = stringResource(R.string.settings_language_default),
                    gloss = null,
                    selected = isDefault,
                    showDivider = AppLanguages.SUPPORTED_LANGUAGES.isNotEmpty(),
                    onClick = { pick(AppLanguages.DEVICE_DEFAULT_TAG) }
                )
                AppLanguages.SUPPORTED_LANGUAGES.forEachIndexed { index, language ->
                    LanguageRow(
                        name = language.nativeName,
                        gloss = language.englishGloss,
                        selected = language.tag.equals(state.currentLanguage, ignoreCase = true),
                        showDivider = index < AppLanguages.SUPPORTED_LANGUAGES.lastIndex,
                        onClick = { pick(language.tag) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(name: String, gloss: String?, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = name,
        subtitle = gloss,
        trailing = {
            if (selected) {
                Icon(AppIcons.Check, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(20.dp))
            }
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

/** Something in Privacy & Security waiting on the lock: what the lock screen is up for, and what then. */
private class LockAsk(val flow: LockFlow, val then: () -> Unit)

/**
 * Kobblo's lock and what hangs off it: fingerprint, App Lock and when it locks, whether
 * the Vault and locked items can be captured, and the Vault's place in Tools. Anything that weakens the
 * lock asks for it first: turning fingerprint on, or App Lock off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySection(state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val store = remember(context) { LockStore.get(context) }
    var lockType by remember { mutableStateOf(store.type) }
    var biometrics by remember { mutableStateOf(store.biometrics) }
    var appLock by remember { mutableStateOf(AppLockGate.isOn(context)) }
    var minutes by remember { mutableStateOf(AppLockGate.minutes(context)) }
    var screenshots by remember { mutableStateOf(PrivacyPrefs.screenshotsAllowed(context)) }
    var vaultMinutes by remember { mutableStateOf(com.kobblo.gallery.file.explorer.vault.VaultLockAfter.minutes(context)) }
    var asking by remember { mutableStateOf<LockAsk?>(null) }
    var choosingMinutes by rememberSaveable { mutableStateOf(false) }
    var choosingVaultMinutes by rememberSaveable { mutableStateOf(false) }
    val biometricsAvailable = activity != null && SystemAuth.canUseBiometrics(activity)

    fun refresh() {
        lockType = store.type
        biometrics = store.biometrics
        appLock = AppLockGate.isOn(context)
        activity?.let { AppLockGate.settingsChanged(it) }
    }

    fun minutesLabel(value: Int): String =
        if (value == 0) context.getString(R.string.settings_lock_immediately)
        else context.resources.getQuantityString(R.plurals.settings_lock_minutes, value, value)

    KobbloGroupHeader(stringResource(R.string.settings_privacy))
    KobbloGroup {
        KobbloRow(
            title = stringResource(if (lockType != null) R.string.settings_lock_change else R.string.settings_lock_set_up),
            subtitle = lockType?.let { stringResource(typeName(it)) },
            leading = { KobbloIconBadge(AppIcons.LockFill, KobbloTint.Blue) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { asking = LockAsk(if (lockType != null) LockFlow.Change else LockFlow.Setup) { refresh() } }
        )
        if (lockType != null && biometricsAvailable) {
            SwitchRow(
                title = stringResource(R.string.enable_fingerprint_access),
                subtitle = stringResource(R.string.settings_biometrics_desc),
                checked = biometrics,
                onChange = { on ->
                    if (on) {
                        asking = LockAsk(LockFlow.Confirm) {
                            store.setBiometrics(true)
                            refresh()
                        }
                    } else {
                        store.setBiometrics(false)
                        refresh()
                    }
                },
                showDivider = true
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_app_lock),
            subtitle = stringResource(R.string.settings_app_lock_desc),
            checked = appLock && lockType != null,
            onChange = { on ->
                when {
                    // With no lock yet, choosing one comes first.
                    on && lockType == null -> asking = LockAsk(LockFlow.Setup) {
                        AppLockGate.setOn(context, true)
                        refresh()
                    }
                    on -> {
                        AppLockGate.setOn(context, true)
                        refresh()
                    }
                    // Off weakens the lock, so it asks for it.
                    else -> asking = LockAsk(LockFlow.Confirm) {
                        AppLockGate.setOn(context, false)
                        refresh()
                    }
                }
            },
            showDivider = true
        )
        if (appLock && lockType != null) {
            KobbloRow(
                title = stringResource(R.string.settings_lock_after),
                trailing = {
                    Text(
                        text = minutesLabel(minutes),
                        style = KobbloType.callout,
                        color = KobbloDesign.colors.smoke,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    KobbloChevron()
                },
                showDivider = true,
                onClick = { choosingMinutes = true }
            )
        }
        SwitchRow(
            title = stringResource(R.string.settings_allow_screenshots),
            subtitle = stringResource(R.string.settings_allow_screenshots_desc),
            checked = screenshots,
            onChange = {
                screenshots = it
                PrivacyPrefs.setScreenshotsAllowed(context, it)
            },
            showDivider = true
        )
        SwitchRow(
            title = stringResource(R.string.settings_show_vault),
            subtitle = stringResource(R.string.settings_show_vault_desc),
            checked = state.isVaultAlwaysShown,
            onChange = { onAction(AppAction.SetVaultAlwaysShown(it)) },
            showDivider = true
        )
        // The Vault's own lock: how long it stays open in the background. Screen-off locks it regardless.
        KobbloRow(
            title = stringResource(R.string.settings_vault_lock_after),
            subtitle = stringResource(R.string.settings_vault_lock_after_desc),
            trailing = {
                Text(
                    text = minutesLabel(vaultMinutes),
                    style = KobbloType.callout,
                    color = KobbloDesign.colors.smoke,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp)
                )
                KobbloChevron()
            },
            onClick = { choosingVaultMinutes = true }
        )
    }
    KobbloGroupFooter(stringResource(R.string.settings_lock_footer))

    asking?.let { ask ->
        LockDialog(
            flow = ask.flow,
            lockedName = stringResource(R.string.app_name_brand),
            onDone = {
                asking = null
                ask.then()
            },
            onCancel = {
                asking = null
                refresh()
            }
        )
    }

    if (choosingMinutes) {
        MinutesSheet(
            title = stringResource(R.string.settings_lock_after),
            choices = AppLockGate.MinuteChoices,
            selected = minutes,
            label = ::minutesLabel,
            onDismiss = { choosingMinutes = false },
            onPick = {
                choosingMinutes = false
                minutes = it
                AppLockGate.setMinutes(context, it)
            }
        )
    }
    if (choosingVaultMinutes) {
        MinutesSheet(
            title = stringResource(R.string.settings_vault_lock_after),
            choices = com.kobblo.gallery.file.explorer.vault.VaultLockAfter.MinuteChoices,
            selected = vaultMinutes,
            label = ::minutesLabel,
            onDismiss = { choosingVaultMinutes = false },
            onPick = {
                choosingVaultMinutes = false
                vaultMinutes = it
                com.kobblo.gallery.file.explorer.vault.VaultLockAfter.setMinutes(context, it)
            }
        )
    }
}

/** A choice of minutes in a sheet, as Lock After and Lock Vault After offer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MinutesSheet(
    title: String,
    choices: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = KobbloSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = KobbloDesign.colors.ink,
                modifier = Modifier.padding(vertical = KobbloSpace.xs)
            )
            KobbloGroup {
                choices.forEachIndexed { index, value ->
                    LanguageRow(
                        name = label(value),
                        gloss = null,
                        selected = value == selected,
                        showDivider = index < choices.lastIndex,
                        onClick = { onPick(value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewersSection(state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    var videoViewer by remember { mutableStateOf(ViewerPrefs.videoViewer(context)) }
    KobbloGroupHeader(stringResource(R.string.settings_open_with))
    KobbloGroup {
        ViewerRow(stringResource(R.string.settings_open_text), state.viewerTextPdf, showDivider = true) {
            onAction(AppAction.SetViewerPreference("Text/PDF", it))
        }
        ViewerRow(stringResource(R.string.settings_open_music), state.viewerMusic, showDivider = true) {
            onAction(AppAction.SetViewerPreference("Music", it))
        }
        ViewerRow(stringResource(R.string.settings_open_images), state.viewerImage, showDivider = true) {
            onAction(AppAction.SetViewerPreference("Image", it))
        }
        ViewerRow(stringResource(R.string.settings_open_videos), videoViewer) {
            videoViewer = it
            ViewerPrefs.setVideoViewer(context, it)
        }
    }
}

@Composable
private fun ViewerRow(title: String, value: String, showDivider: Boolean = false, onChange: (String) -> Unit) {
    KobbloRow(
        title = title,
        trailing = {
            KobbloSegmentedControl(
                options = listOf(stringResource(R.string.settings_open_in_app), stringResource(R.string.settings_open_other)),
                selectedIndex = if (value == ViewerDevice) 1 else 0,
                onSelect = { onChange(if (it == 1) ViewerDevice else ViewerInApp) },
                modifier = Modifier.width(176.dp)
            )
        },
        showDivider = showDivider
    )
}

@Composable
private fun OrganizerSection(state: AppState, onAction: (AppAction) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val folders = listOf(
        Triple("Docs", stringResource(R.string.cat_docs), state.orgDestDocs),
        Triple("Images", stringResource(R.string.settings_open_images), state.orgDestImages),
        Triple("Apks", stringResource(R.string.cat_apps), state.orgDestApks),
        Triple("Music", stringResource(R.string.settings_open_music), state.orgDestMusic),
        Triple("Videos", stringResource(R.string.settings_open_videos), state.orgDestVideos)
    )
    KobbloGroupHeader(stringResource(R.string.settings_organizer))
    KobbloGroup {
        folders.forEachIndexed { index, (category, label, path) ->
            KobbloRow(
                title = label,
                subtitle = path,
                trailing = { KobbloChevron() },
                showDivider = index < folders.lastIndex,
                onClick = { picking = category }
            )
        }
    }
    KobbloGroupFooter(stringResource(R.string.settings_organizer_footer))
    picking?.let { category ->
        ToolFolderPicker(
            title = stringResource(R.string.settings_organizer),
            confirmLabel = stringResource(R.string.settings_choose_folder),
            initialPath = folders.first { it.first == category }.third.takeIf { java.io.File(it).isDirectory }
                ?: android.os.Environment.getExternalStorageDirectory().path,
            onDismiss = { picking = null },
            onPick = {
                onAction(AppAction.SetOrganiserPath(category, it))
                picking = null
            }
        )
    }
}

@Composable
private fun SendSection(state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    var editingName by rememberSaveable { mutableStateOf(false) }
    var pickingFolder by rememberSaveable { mutableStateOf(false) }
    val deviceName = state.nearbyDeviceName.ifEmpty { DeviceName.default(context) }

    KobbloGroupHeader(stringResource(R.string.settings_nearby_share))
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.settings_send_name),
            subtitle = deviceName,
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { editingName = true }
        )
        KobbloRow(
            title = stringResource(R.string.settings_send_folder),
            subtitle = state.nearbyReceivePath,
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { pickingFolder = true }
        )
        SwitchRow(
            title = stringResource(R.string.settings_send_ask),
            subtitle = stringResource(if (state.nearbyAskBeforeReceiving) R.string.settings_send_ask_on else R.string.settings_send_ask_off),
            checked = state.nearbyAskBeforeReceiving,
            onChange = { onAction(AppAction.NearbyShareAction.SetAskBeforeReceiving(it)) }
        )
    }

    if (editingName) {
        var name by rememberSaveable { mutableStateOf(deviceName) }
        GradientAlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text(stringResource(R.string.settings_send_name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KobbloSpace.xs)) {
                    Text(stringResource(R.string.settings_send_name_desc))
                    KobbloTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(R.string.settings_send_name)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onAction(AppAction.NearbyShareAction.SetDeviceName(name.trim()))
                        editingName = false
                    }
                ) { Text(stringResource(R.string.save_action)) }
            },
            dismissButton = {
                TextButton(onClick = { editingName = false }) { Text(stringResource(R.string.cancel), color = KobbloDesign.colors.smoke) }
            }
        )
    }
    if (pickingFolder) {
        ToolFolderPicker(
            title = stringResource(R.string.settings_send_folder),
            confirmLabel = stringResource(R.string.settings_choose_folder),
            initialPath = state.nearbyReceivePath.takeIf { java.io.File(it).isDirectory }
                ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
            onDismiss = { pickingFolder = false },
            onPick = {
                onAction(AppAction.NearbyShareAction.SetReceivePath(it))
                pickingFolder = false
            }
        )
    }
}

/** Whether deleting moves files to Recently Deleted, and how long they wait there before they're gone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentlyDeletedSection(state: AppState, onAction: (AppAction) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val enabled = state.isRecycleBinEnabled
    val value = state.recycleBinRetentionValue
    val unit = state.recycleBinRetentionUnit
    val keptFor = if (unit.equals("Days", ignoreCase = true)) {
        pluralStringResource(R.plurals.settings_keep_days, value, value)
    } else {
        retentionDurationText(value, unit)
    }
    KobbloGroupHeader(stringResource(R.string.settings_trash_bin))
    KobbloGroup {
        SwitchRow(
            title = stringResource(R.string.enable_bin),
            subtitle = null,
            checked = enabled,
            onChange = { onAction(AppAction.SetRecycleBinSettings(it, value, unit)) },
            showDivider = enabled
        )
        if (enabled) {
            KobbloRow(
                title = stringResource(R.string.settings_keep_for),
                trailing = {
                    Text(
                        text = keptFor,
                        style = KobbloType.callout,
                        color = KobbloDesign.colors.smoke,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    KobbloChevron()
                },
                onClick = { choosing = true }
            )
        }
    }
    KobbloGroupFooter(stringResource(if (enabled) R.string.settings_trash_footer_on else R.string.settings_trash_footer_off))

    if (choosing) {
        val choices = (KeepForChoices + if (unit.equals("Days", ignoreCase = true)) listOf(value) else emptyList()).distinct().sorted()
        KobbloSheet(
            onDismissRequest = { choosing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = KobbloSpace.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_keep_for),
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                    color = KobbloDesign.colors.ink,
                    modifier = Modifier.padding(vertical = KobbloSpace.xs)
                )
                KobbloGroup {
                    choices.forEachIndexed { index, days ->
                        LanguageRow(
                            name = pluralStringResource(R.plurals.settings_keep_days, days, days),
                            gloss = null,
                            selected = unit.equals("Days", ignoreCase = true) && days == value,
                            showDivider = index < choices.lastIndex,
                            onClick = {
                                choosing = false
                                onAction(AppAction.SetRecycleBinSettings(true, days, "Days"))
                            }
                        )
                    }
                }
            }
        }
    }
}

/** How long Recently Deleted can keep files: a day, a week, two, a month, two months or three. */
private val KeepForChoices = listOf(1, 7, 14, 30, 60, 90)

/** The app itself, and the last crash report when there is one to read. */
@Composable
private fun AboutSection(onShowAbout: () -> Unit) {
    val context = LocalContext.current
    val hasCrashLog = remember(Hub.depth) { CrashLog.exists(context) }
    KobbloGroupHeader(stringResource(R.string.about_nav))
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.settings_about_app),
            subtitle = stringResource(R.string.settings_version, com.kobblo.gallery.file.explorer.BuildConfig.VERSION_NAME),
            leading = { com.kobblo.gallery.file.explorer.ui.design.BrandMark(size = 32.dp) },
            trailing = { KobbloChevron() },
            showDivider = hasCrashLog,
            onClick = onShowAbout
        )
        if (hasCrashLog) {
            KobbloRow(
                title = stringResource(R.string.crash_last_title),
                subtitle = stringResource(R.string.crash_last_subtitle),
                leading = { KobbloIconBadge(AppIcons.Alert, KobbloTint.Red) },
                trailing = { KobbloChevron() },
                onClick = { Hub.open(ToolsRoute.CrashLog) }
            )
        }
    }
}
