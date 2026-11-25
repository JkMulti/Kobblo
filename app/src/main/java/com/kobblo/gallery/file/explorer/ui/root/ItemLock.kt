package com.kobblo.gallery.file.explorer.ui.root

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.kobblo.gallery.file.explorer.security.BlockCapture
import com.kobblo.gallery.file.explorer.security.LockDialog
import com.kobblo.gallery.file.explorer.security.LockFlow
import com.kobblo.gallery.file.explorer.security.PrivacyPrefs

/**
 * Asks for Kobblo's lock, full screen, when something to do with a locked item waits on it. With no lock
 * set yet, the dialog asks for one to be chosen, then carries on with what was asked.
 */
@Composable
internal fun ItemLock(state: AppState, onAction: (AppAction) -> Unit) {
    val reason = state.authRequestReason ?: return
    val path = state.authRequestPath ?: return
    val name = state.authRequestFolderName ?: path.substringAfterLast('/')
    val locking = reason == AuthReason.LOCK_FILE
    LockDialog(
        flow = LockFlow.Unlock,
        lockedName = name,
        heading = stringResource(reason.heading),
        // Not locked yet when locking it, so it's named rather than called locked.
        subtitle = name.takeIf { locking },
        // The one moment someone decides to rely on this, so it's when they should hear what it does.
        note = if (locking) stringResource(R.string.safety_lock_what_it_is) else null,
        onDone = {
            onAction(AppAction.AuthSuccess(reason, path, fileId = state.authRequestFileId, folderName = state.authRequestFolderName))
        },
        onCancel = { onAction(AppAction.CancelAuth) }
    )
}

@get:StringRes
private val AuthReason.heading: Int
    get() = when (this) {
        AuthReason.OPEN_FILE -> R.string.lock_reason_open
        AuthReason.LOCK_FILE -> R.string.lock_reason_lock
        AuthReason.UNLOCK_FILE -> R.string.lock_reason_unlock
        AuthReason.CONFIRM_ACTION -> R.string.safety_lock_confirm_title
    }

/** Keeps the Vault and locked items out of screenshots, unless Settings allows them. */
@Composable
internal fun CaptureGuard(state: AppState) {
    val context = LocalContext.current
    // Only Settings can change it, so it's read again whenever Settings opens or closes.
    val allowed = remember(state.showSettingsScreen) { PrivacyPrefs.screenshotsAllowed(context) }
    val private = state.showVaultScreen ||
        state.viewingFile?.isLocked == true ||
        (state.location.startsWith("/") && Locks.covers(context, state.location))
    BlockCapture(private && !allowed)
}
