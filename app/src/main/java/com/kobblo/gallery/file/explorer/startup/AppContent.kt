package com.kobblo.gallery.file.explorer.startup

import android.app.Activity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.MainViewModel
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.security.AppLockCover
import com.kobblo.gallery.file.explorer.ui.KobbloRoot
import com.kobblo.gallery.file.explorer.ui.LocalAudioPosition

/** Everything the main activity draws. */
@Composable
internal fun AppContent(
    activity: Activity,
    model: MainViewModel,
    storage: StorageAccessFlow,
    welcome: WelcomeStage,
    onAction: (AppAction) -> Unit
) {
    val state by model.state.collectAsState()
    val messages = remember { SnackbarHostState() }
    // Outside App Lock's cover, so what's said while the app is locked isn't missed.
    ShowMessages(model, messages)

    CompositionLocalProvider(LocalAudioPosition provides model.audioPosition) {
        // App Lock's lock lies over everything while the app is locked.
        AppLockCover(activity) {
            KobbloRoot(
                state = state,
                onActionOrig = onAction,
                snackbarHostState = messages,
                overlay = { StorageAccessAlert(storage) },
                cover = {
                    WelcomeCover(
                        stage = welcome,
                        photos = state.galleryFiles,
                        onRequestFiles = { onAction(AppAction.RequestStorageAccess) },
                        onFinished = storage::onWelcomeFinished
                    )
                }
            )
        }
    }
}

/** Shows what the view model has to say, one at a time, with an Undo for files moved by dragging them. */
@Composable
private fun ShowMessages(model: MainViewModel, messages: SnackbarHostState) {
    val undo by rememberUpdatedState(stringResource(R.string.explorer_drag_undo))
    LaunchedEffect(model) {
        model.snackbarMessage.collect { messages.showSnackbar(it, withDismissAction = true) }
    }
    LaunchedEffect(model) {
        model.dragMoveResults.collect { moved ->
            val answer = messages.showSnackbar(moved.message, undo, withDismissAction = true, SnackbarDuration.Long)
            if (answer == SnackbarResult.ActionPerformed) model.undoDragMove(moved)
        }
    }
}
