package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileDetails
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.ExplorerUi
import com.kobblo.gallery.file.explorer.fileops.LockGate
import com.kobblo.gallery.file.explorer.fileops.OpenElsewhere
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.expressive.FileInfoBottomSheet
import java.io.File
import java.util.Locale

/** The file whose sheet is open, and what has been read about it. */
@Stable
internal class FileDetailsDesk {
    var file by mutableStateOf<FileItem?>(null)
        private set
    var read by mutableStateOf<FileDetails?>(null)
        private set

    fun open(file: FileItem) {
        this.file = file
    }

    fun close() {
        file = null
    }

    /** Reads what there is to know about the open file, and forgets it once the sheet has closed. */
    fun load(onAction: (AppAction) -> Unit) {
        val open = file
        if (open == null) read = null else onAction(AppAction.LoadFileDetails(open.path) { read = it })
    }
}

/** Keeps [desk] told about whichever file is open in it. */
@Composable
internal fun FileDetailsReader(desk: FileDetailsDesk, onAction: (AppAction) -> Unit) {
    LaunchedEffect(desk.file) { desk.load(onAction) }
}

/** How much of [name] is the name and not its extension, for picking out to type over. */
internal fun nameEnd(name: String): Int = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

/** The sheet of everything that can be done with one file. */
@Composable
internal fun FileDetailsSheet(root: RootMemory, state: AppState, onAction: (AppAction) -> Unit) {
    val file = root.details.file ?: return
    var newName by remember { mutableStateOf<TextFieldValue?>(null) }
    newName?.let { typed ->
        RenameDialog(
            typed = typed,
            onType = { newName = it },
            onSave = {
                if (typed.text.isNotBlank()) onAction(AppAction.RenameFile(file.path, typed.text.trim()))
                newName = null
                root.details.close()
            },
            onDismiss = { newName = null }
        )
    }

    val tasks = FileTasks(file, root, state, LocalContext.current, LocalHaptics.current, onAction)
    FileInfoBottomSheet(
        file = file,
        fileDetails = root.details.read,
        onDismissRequest = root.details::close,
        onSelect = tasks::select,
        onToggleFavorite = tasks::toggleFavorite,
        onShare = tasks::share,
        onSendNearby = tasks::sendNearby,
        // Ready to type over the name, keeping its extension, with the keyboard already up.
        onRename = { tasks.unlocked { newName = TextFieldValue(file.name, TextRange(0, nameEnd(file.name))) } },
        onToggleHide = tasks::toggleHide,
        onDelete = tasks::delete,
        onToggleLock = tasks::toggleLock,
        onCopy = tasks.onPhone { clip("copy") },
        onMove = tasks.onPhone { clip("cut") },
        onCompress = tasks.onPhone { compress() },
        onExtract = if (ArchiveFormats.isArchiveName(file.name)) tasks::extract else null,
        onShowInFolder = if (tasks.isAwayFromFolder) tasks::showInFolder else null,
        onOpenWith = if (file.type == "folder") null else tasks::openWith
    )
}

@Composable
private fun RenameDialog(typed: TextFieldValue, onType: (TextFieldValue) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    NamePrompt(title = stringResource(R.string.dialog_rename), onSave = onSave, onDismiss = onDismiss) { field ->
        KobbloTextField(typed, onType, stringResource(R.string.dialog_rename_placeholder), field)
    }
}

/** What the sheet's rows do with [file]. Nearly all of them close the sheet on their way to doing it. */
private class FileTasks(
    private val file: FileItem,
    private val root: RootMemory,
    private val state: AppState,
    private val context: Context,
    private val haptics: HapticsController,
    private val onAction: (AppAction) -> Unit
) {
    private val isOnPhone = file.path.startsWith("/")

    /** Whether the file is being looked at somewhere other than among its neighbours. */
    val isAwayFromFolder: Boolean get() = isOnPhone && File(file.path).parent != state.location

    /** A row for something only files on the phone can do, or null for any other file. */
    fun onPhone(task: FileTasks.() -> Unit): (() -> Unit)? = if (isOnPhone) ({ task() }) else null

    fun select() {
        root.details.close()
        onAction(AppAction.ToggleSelection(file.id))
    }

    fun toggleFavorite() = closing { onAction(AppAction.TogglePin(file.path)) }

    fun share() = closing { context.share(context.lend(file.path), context.getString(R.string.view_share_named, file.name)) }

    fun sendNearby() = closing {
        onAction(AppAction.NearbyShareAction.StageFiles(listOf(file)))
        onAction(AppAction.SetLocation("send"))
    }

    fun toggleHide() = unlocked {
        root.details.close()
        root.hiding.toggle(listOf(file), explained = state.hasSeenHideWarning, onAction)
    }

    fun delete() = unlocked {
        root.deleting.ask(file)
        root.details.close()
    }

    fun toggleLock() = closing {
        requestLock(if (file.isLocked) AuthReason.UNLOCK_FILE else AuthReason.LOCK_FILE)
    }

    /** Goes by path, not by selecting the file, since a locked one can't be selected. Its lock goes with it. */
    fun clip(how: String) = unlocked {
        root.details.close()
        onAction(AppAction.ClipFile(file.path, how))
    }

    fun compress() = closing { root.archives.pack(listOf(file)) }

    fun extract() = closing { root.archives.offer(file) }

    fun showInFolder() = closing { ExplorerUi.showInFolder(file.path, onAction) }

    fun openWith() = closing { if (isOnPhone) ExplorerUi.openWith(file) else openRemoteElsewhere() }

    /** Does [task] with the file open to it. Changing a locked item takes the same password as looking at it. */
    fun unlocked(task: () -> Unit) {
        haptics.tap()
        if (file.isLocked) {
            LockGate.ask(task)
            requestLock(AuthReason.CONFIRM_ACTION)
        } else {
            task()
        }
    }

    private fun closing(task: () -> Unit) {
        haptics.tap()
        root.details.close()
        task()
    }

    private fun requestLock(reason: AuthReason) =
        onAction(AppAction.RequestAuth(reason, file.path, fileId = file.id, folderName = file.name))

    private fun openRemoteElsewhere() {
        try {
            val extension = file.name.substringAfterLast('.', "").lowercase(Locale.getDefault())
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: mimeForExtension(extension)
            val opening = viewIntent(context.lend(file.path), mime)
            context.startActivity(OpenElsewhere.chooser(context, opening, context.getString(R.string.view_open_with_chooser)))
        } catch (e: Exception) {
            onAction(AppAction.ShowToast(context.getString(R.string.view_no_app_to_open)))
        }
    }
}
