package com.kobblo.gallery.file.explorer.ui.root

import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.CloudLoginBottomSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet

/** A cloud service signed in to with an email and a password, and what its locations start with. */
private enum class CloudService(
    val title: String,
    val locations: String,
    val category: String,
    val registerUrl: String,
    val recoveryUrl: String
) {
    Mega("Mega", "mega", "archives", "https://mega.io/register", "https://mega.io/recovery"),
    Dropbox("Dropbox", "dropbox", "videos", "https://dropbox.com/register", "https://www.dropbox.com/forgot")
}

/** MEGA's sheet. The view model opens it and closes it, since only it knows whether signing in worked. */
@Composable
internal fun MegaAccount(state: AppState, onAction: (AppAction) -> Unit) {
    if (!state.showMegaPopup) return
    val close = { onAction(AppAction.SetShowMegaPopup(false)) }
    AccountSheet(
        service = CloudService.Mega,
        signedIn = state.isMegaAuthenticated,
        email = state.megaAccountEmail,
        problem = state.megaLoginError,
        onSignIn = { email, password -> onAction(AppAction.SetMegaAuthStatus(true, email, password)) },
        onSignOut = {
            onAction(AppAction.SetMegaAuthStatus(false, null, null))
            close()
            leave(CloudService.Mega, state, onAction)
        },
        onDismiss = close
    )
}

/** Dropbox's sheet, opened from the list of places to add. */
@Composable
internal fun DropboxAccount(forms: PlaceForms, state: AppState, onAction: (AppAction) -> Unit) {
    if (!forms.dropbox) return
    val close = { forms.dropbox = false }
    AccountSheet(
        service = CloudService.Dropbox,
        signedIn = state.isDropboxAuthenticated,
        email = state.dropboxAccountEmail,
        problem = null,
        onSignIn = { email, _ ->
            onAction(AppAction.SetDropboxAuthStatus(true, email))
            close()
        },
        onSignOut = {
            onAction(AppAction.SetDropboxAuthStatus(false, null))
            close()
            leave(CloudService.Dropbox, state, onAction)
        },
        onDismiss = close
    )
}

/** Someone signed out is taken from the account they can no longer see to the phone's own storage. */
private fun leave(service: CloudService, state: AppState, onAction: (AppAction) -> Unit) {
    if (!state.location.startsWith(service.locations)) return
    onAction(AppAction.SetLocation(Environment.getExternalStorageDirectory().absolutePath))
}

/** Signing in to a service, or, once signed in, out of it. */
@Composable
private fun AccountSheet(
    service: CloudService,
    signedIn: Boolean,
    email: String?,
    problem: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    if (signedIn) {
        ExpressiveConfirmationSheet(
            title = stringResource(R.string.logout_prompt, service.title),
            subtitle = email ?: stringResource(R.string.view_connected_account),
            confirmLabel = stringResource(R.string.logout_action),
            onConfirm = onSignOut,
            onDismissRequest = onDismiss
        )
    } else {
        CloudLoginBottomSheet(
            providerName = service.title, providerIcon = AppIcons.Cloud, providerCategory = service.category,
            registerUrl = service.registerUrl, recoveryUrl = service.recoveryUrl,
            errorMessage = problem,
            onLogin = onSignIn,
            onDismissRequest = onDismiss
        )
    }
}
