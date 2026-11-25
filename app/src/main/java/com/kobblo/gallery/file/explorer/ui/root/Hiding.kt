package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.ConflictResolution
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme

/** Whether [files] are all hidden already, so that hiding them would really be showing them. */
internal fun allHidden(files: List<FileItem>): Boolean = files.isNotEmpty() && files.all { it.isHidden }

/** Hiding files and showing them again, and the files held back while what hiding does is explained. */
@Stable
internal class Hiding {
    /** Files to hide once it has been explained, or null when there's nothing to explain. */
    var heldBack by mutableStateOf<List<FileItem>?>(null)
        private set

    /**
     * Hides [files], or shows them if they're all hidden. The first time anything is hidden they're held
     * back instead, until the person has read what it does. True if it went ahead.
     */
    fun toggle(files: List<FileItem>, explained: Boolean, onAction: (AppAction) -> Unit): Boolean {
        if (!explained && !allHidden(files)) {
            heldBack = files
            return false
        }
        onAction(AppAction.ToggleHideFiles(files))
        return true
    }

    fun drop() {
        heldBack = null
    }
}

/** What hiding does, said once, before the first files are hidden. */
@Composable
internal fun HideExplanation(hiding: Hiding, state: AppState, onAction: (AppAction) -> Unit) {
    val files = hiding.heldBack ?: return
    GradientAlertDialog(
        onDismissRequest = hiding::drop,
        title = { Text(stringResource(R.string.view_hiding_files_title)) },
        text = { Text(stringResource(R.string.view_hiding_files_body)) },
        confirmButton = {
            Answer(stringResource(R.string.view_got_it), ExpressiveTheme.colors.accent, strong = true) {
                hiding.drop()
                onAction(AppAction.SetHasSeenHideWarning(true))
                onAction(AppAction.ToggleHideFiles(files))
                if (state.isSelectionMode) onAction(AppAction.ClearSelection)
            }
        },
        dismissButton = { Answer(stringResource(R.string.cancel), ExpressiveTheme.colors.muted, onClick = hiding::drop) }
    )
}

/** Asks what to do when the name a file would get by being hidden or shown is another file's. */
@Composable
internal fun HideConflict(state: AppState, onAction: (AppAction) -> Unit) {
    val conflict = state.pendingHideConflict ?: return
    val colors = ExpressiveTheme.colors
    fun settle(how: ConflictResolution) = onAction(AppAction.ResolveHideConflict(how))
    GradientAlertDialog(
        onDismissRequest = { onAction(AppAction.DismissHideConflict) },
        title = { Text(stringResource(R.string.view_item_exists_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.view_item_exists_body, conflict.targetName))
                Text(stringResource(R.string.view_item_exists_question, conflict.suggestedName))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Answer(stringResource(R.string.rename_action), colors.accent, strong = true) {
                    settle(ConflictResolution.Rename(conflict.suggestedName))
                }
                Answer(stringResource(R.string.view_overwrite), colors.danger, strong = true) { settle(ConflictResolution.Overwrite) }
            }
        },
        dismissButton = {
            Answer(stringResource(R.string.view_skip), colors.muted) { onAction(AppAction.DismissHideConflict) }
        }
    )
}
