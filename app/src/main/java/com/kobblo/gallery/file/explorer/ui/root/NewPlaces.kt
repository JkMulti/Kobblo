package com.kobblo.gallery.file.explorer.ui.root

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.FtpConnectionsDialog
import com.kobblo.gallery.file.explorer.ui.NextcloudConnectionsDialog
import com.kobblo.gallery.file.explorer.ui.SftpConnectionsDialog
import com.kobblo.gallery.file.explorer.ui.SmbConnectionsDialog
import com.kobblo.gallery.file.explorer.ui.WebDavConnectionsDialog
import com.kobblo.gallery.file.explorer.ui.expressive.NewCloudConnectionSheet
import com.kobblo.gallery.file.explorer.ui.rememberDrivePickerLauncher

/** A kind of server reached by its address. Each has a form of its own. */
internal enum class ServerKind { Smb, Ftp, Sftp, WebDav, Nextcloud }

/** Which step of adding a place is on screen. */
@Stable
internal class PlaceForms {
    /** The list of kinds of place to add. */
    var choosing by mutableStateOf(false)

    var server by mutableStateOf<ServerKind?>(null)
    var dropbox by mutableStateOf(false)
}

/** Google's own screens: signing in, choosing files in Drive, and agreeing to something Drive asks for later. */
internal class GoogleScreens(
    private val context: Context,
    val pickFiles: () -> Unit,
    private val signInScreen: ActivityResultLauncher<Intent>,
    private val consentScreen: ActivityResultLauncher<Intent>
) {
    /** Asks which account to use, even when one is signed in already, since this is how a second one is added. */
    fun signIn() {
        val wanted = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE))
            .build()
        val google = GoogleSignIn.getClient(context, wanted)
        google.signOut().addOnCompleteListener { signInScreen.launch(google.signInIntent) }
    }

    fun askConsent(question: Intent) = consentScreen.launch(question)
}

/**
 * The screens, ready to open. They're made ready whether or not they're wanted: an answer can come back
 * after the app was closed behind one of them, and has to find the launcher that asked.
 */
@Composable
internal fun rememberGoogleScreens(forms: PlaceForms, onAction: (AppAction) -> Unit): GoogleScreens {
    val context = LocalContext.current
    val pickFiles = rememberDrivePickerLauncher { granted, picked ->
        if (granted && !picked.isNullOrEmpty()) {
            onAction(AppAction.SetDrivePickedIds(picked))
            onAction(AppAction.Reload)
        }
    }
    val signInScreen = rememberLauncherForActivityResult(StartActivityForResult()) { answer ->
        signedIn(answer.data, context, forms, onAction)
    }
    val consentScreen = rememberLauncherForActivityResult(StartActivityForResult()) { answer ->
        onAction(AppAction.ClearRecoverableAuthIntent)
        if (answer.resultCode == Activity.RESULT_OK) onAction(AppAction.Reload)
    }
    return remember(context, pickFiles, signInScreen, consentScreen) {
        GoogleScreens(context, pickFiles, signInScreen, consentScreen)
    }
}

/** Takes in Google's answer to signing in, and goes to the account that was chosen. One with no address can't be gone to. */
private fun signedIn(answer: Intent?, context: Context, forms: PlaceForms, onAction: (AppAction) -> Unit) {
    val account = runCatching { GoogleSignIn.getSignedInAccountFromIntent(answer).getResult(ApiException::class.java) }
    val email = account.getOrNull()?.email
    val problem = account.exceptionOrNull()
    if (email == null && problem == null) return

    forms.choosing = false
    if (email != null) {
        onAction(AppAction.SetGoogleDriveAuthStatus(isAuthenticated = true, email))
        onAction(AppAction.SwitchToStorage(storageId = "drive_$email", defaultLocation = "drive:$email"))
    } else {
        Log.w("GoogleScreens", "Couldn't sign in to Google", problem)
        onAction(AppAction.SetErrorMessage(context.getString(R.string.polish_google_login_failed)))
    }
}

/** The kinds of place that can be added. */
@Composable
internal fun NewPlaceSheet(forms: PlaceForms, google: GoogleScreens, onAction: (AppAction) -> Unit) {
    if (!forms.choosing) return
    fun formFor(kind: ServerKind): () -> Unit = { forms.server = kind }
    NewCloudConnectionSheet(
        onDismissRequest = { forms.choosing = false },
        onConnectGoogleDrive = google::signIn,
        onConnectMega = { onAction(AppAction.SetShowMegaPopup(true)) },
        onConnectDropbox = { forms.dropbox = true },
        onConnectNextcloud = formFor(ServerKind.Nextcloud),
        onConnectWebDav = formFor(ServerKind.WebDav),
        onConnectSftp = formFor(ServerKind.Sftp),
        onConnectFtp = formFor(ServerKind.Ftp),
        onConnectSmb = formFor(ServerKind.Smb)
    )
}

/** The form for the kind of server that was chosen. */
@Composable
internal fun ServerForm(forms: PlaceForms, state: AppState, onAction: (AppAction) -> Unit) {
    val close = { forms.server = null }
    when (forms.server) {
        ServerKind.Smb -> SmbConnectionsDialog(state.smbState, onAction, close)
        ServerKind.Ftp -> FtpConnectionsDialog(state.ftpState, onAction, close)
        ServerKind.Sftp -> SftpConnectionsDialog(state.sftpState, onAction, close)
        ServerKind.WebDav -> WebDavConnectionsDialog(state.webDavState, onAction, close)
        ServerKind.Nextcloud -> NextcloudConnectionsDialog(state.webDavState, onAction, close)
        null -> Unit
    }
}
