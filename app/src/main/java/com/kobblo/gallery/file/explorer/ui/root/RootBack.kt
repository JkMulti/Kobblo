package com.kobblo.gallery.file.explorer.ui.root

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import kotlinx.coroutines.CancellationException

/**
 * What Back does when no page, viewer or sheet has a use for it. The handlers further down answer first:
 * a gesture only leads out of a place once there's no selection or search there to put away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RootBack(page: RootPage, trail: PageTrail, state: AppState, onAction: (AppAction) -> Unit) {
    val focus = LocalFocusManager.current
    // With the keyboard up, Back is the keyboard's.
    val keyboardUp = WindowInsets.isImeVisible
    BackHandler(enabled = state.query.isNotEmpty() && !keyboardUp) {
        focus.clearFocus()
        onAction(AppAction.SetQuery(""))
    }
    BackHandler(enabled = state.selectedFiles.isNotEmpty()) { onAction(AppAction.ClearSelection) }

    // From Home there's nowhere further back, so there Back leaves the app.
    val leadsOut = !page.ownsBack && state.location != "gallery"
    PredictiveBackHandler(enabled = leadsOut && state.selectedFiles.isEmpty() && state.query.isEmpty()) { gesture ->
        try {
            gesture.collect { trail.pull = it.progress }
            val phoneRoot = Environment.getExternalStorageDirectory().absolutePath
            trail.take(backFrom(state.location, trail.cleanerWaiting, phoneRoot), onAction)
        } catch (_: CancellationException) {
            // Let go before it was far enough, so nothing happens.
        } finally {
            trail.pull = 0f
        }
    }
}
