package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.HapticEvent
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import kotlin.reflect.KClass

/** The actions with a vibration of their own, under the one they give. */
private val ActionsByFeel: Map<HapticEvent, Set<KClass<out AppAction>>> = mapOf(
    HapticEvent.Delete to setOf(
        AppAction.DeleteSelectedFiles::class, AppAction.DeleteFiles::class, AppAction.PermanentlyDeleteTrashFiles::class
    ),
    HapticEvent.CopyPaste to setOf(AppAction.SetClipboard::class, AppAction.PasteClipboard::class, AppAction.MoveDragged::class),
    HapticEvent.FileOpen to setOf(AppAction.OpenFileViewer::class, AppAction.PlayAudio::class),
    HapticEvent.SettingsToggle to setOf(
        AppAction.SetShowSettingsScreen::class, AppAction.SetShowVaultScreen::class, AppAction.SetRecycleBinSettings::class,
        AppAction.ToggleHapticsMaster::class, AppAction.ToggleHapticsOption::class
    ),
    HapticEvent.Cleaner to setOf(
        AppAction.SetCleanerScreenVisible::class, AppAction.SetCleanerCategory::class,
        AppAction.SelectAllCleanerFiles::class, AppAction.ClearCleanerSelection::class
    ),
    HapticEvent.Fab to setOf(AppAction.CreateFolder::class, AppAction.CreateFile::class)
)

/** What the app does by itself. Nobody touched anything, so there's nothing to feel. */
private val Unfelt: Set<KClass<out AppAction>> = setOf(
    AppAction.Reload::class, AppAction.LoadFileDetails::class,
    AppAction.AuthSuccess::class, AppAction.AutoRequestAccess::class,
    AppAction.SetGoogleDriveAuthStatus::class, AppAction.SetMegaAuthStatus::class, AppAction.SetDropboxAuthStatus::class
)

private val FeelByAction: Map<KClass<out AppAction>, HapticEvent> =
    ActionsByFeel.flatMap { (feel, actions) -> actions.map { it to feel } }.toMap()

/** The vibration [action] gives, or null for one that gives none. Most feel like an ordinary tap. */
internal fun feelOf(action: AppAction): HapticEvent? {
    val kind = action::class
    return if (kind in Unfelt) null else FeelByAction[kind] ?: HapticEvent.General
}

/** [onAction], felt as each action is sent. */
@Composable
internal fun rememberFeltActions(haptics: HapticsController, onAction: (AppAction) -> Unit): (AppAction) -> Unit =
    remember(haptics, onAction) {
        { action ->
            feelOf(action)?.let(haptics::fire)
            onAction(action)
        }
    }
