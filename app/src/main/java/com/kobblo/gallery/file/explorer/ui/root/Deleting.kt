package com.kobblo.gallery.file.explorer.ui.root

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet

/** What someone is being asked whether to delete. */
internal sealed interface Doomed {
    /** One file, whether or not it's selected. */
    data class One(val file: FileItem) : Doomed

    /** Whatever is selected. */
    data object Selection : Doomed
}

/** The question of whether to delete something, while it's on screen. */
@Stable
internal class DeleteQuestion {
    var about by mutableStateOf<Doomed?>(null)
        private set

    fun ask(file: FileItem) {
        about = Doomed.One(file)
    }

    fun askAboutSelection() {
        about = Doomed.Selection
    }

    fun settle() {
        about = null
    }
}

/** What the question calls the things it's about. */
internal sealed interface DeleteTitle {
    data class Named(val name: String) : DeleteTitle
    data class Counted(val count: Int) : DeleteTitle

    /** One thing is selected, but the list no longer has it to name. */
    data object OneItem : DeleteTitle

    data object SelectedItems : DeleteTitle
}

/**
 * The title for deleting [files] out of [selectedCount] selected. The two can differ while a list is
 * being read again, and then what was selected is what counts.
 */
internal fun deleteTitle(about: Doomed, files: List<FileItem>, selectedCount: Int): DeleteTitle = when {
    about is Doomed.One -> DeleteTitle.Named(about.file.name)
    files.size == 1 -> DeleteTitle.Named(files.single().name)
    selectedCount == 1 -> DeleteTitle.OneItem
    selectedCount > 1 -> DeleteTitle.Counted(selectedCount)
    else -> DeleteTitle.SelectedItems
}

/** What will become of the things deleted. */
internal enum class DeleteNote(@StringRes val text: Int) {
    GoneForGood(R.string.delete_warning_undone),
    EmptyFolders(R.string.view_delete_empty_folders_note),
    ItGoesToBin(R.string.view_delete_moves_to_bin),
    TheyGoToBin(R.string.view_delete_they_move_to_bin)
}

/**
 * Folders go to Recently Deleted like anything else and come back whole. The exception is the Cleaner's
 * empty folders, which have nothing to restore and would only fill it.
 */
internal fun deleteNote(location: String, binOn: Boolean, files: List<FileItem>): DeleteNote = when {
    !binOn || location == "drive" -> DeleteNote.GoneForGood
    location.startsWith("category/") && files.isNotEmpty() && files.all { it.isEmptyFolder } -> DeleteNote.EmptyFolders
    files.size == 1 -> DeleteNote.ItGoesToBin
    else -> DeleteNote.TheyGoToBin
}

@Composable
private fun DeleteTitle.inWords(): String = when (this) {
    is DeleteTitle.Named -> stringResource(R.string.view_delete_named_title, name)
    is DeleteTitle.Counted -> pluralStringResource(R.plurals.view_delete_items_title, count, count)
    DeleteTitle.OneItem -> stringResource(R.string.view_delete_selected_item)
    DeleteTitle.SelectedItems -> stringResource(R.string.view_delete_selected_items)
}

/** Asks before anything is deleted, saying whether it can be got back. */
@Composable
internal fun DeleteSheet(question: DeleteQuestion, state: AppState, onAction: (AppAction) -> Unit) {
    val about = question.about ?: return
    val haptics = LocalHaptics.current
    val files = remember(about, state.files, state.selectedFiles) {
        when (about) {
            is Doomed.One -> listOf(about.file)
            Doomed.Selection -> state.files.filter { it.id in state.selectedFiles }
        }
    }
    ExpressiveConfirmationSheet(
        title = deleteTitle(about, files, state.selectedFiles.size).inWords(),
        subtitle = stringResource(deleteNote(state.location, state.isRecycleBinEnabled, files).text),
        onConfirm = {
            haptics.delete()
            onAction(
                when (about) {
                    is Doomed.One -> AppAction.DeleteFiles(listOf(about.file))
                    Doomed.Selection -> AppAction.DeleteSelectedFiles
                }
            )
            question.settle()
        },
        onDismissRequest = {
            haptics.tap()
            question.settle()
        }
    )
}
