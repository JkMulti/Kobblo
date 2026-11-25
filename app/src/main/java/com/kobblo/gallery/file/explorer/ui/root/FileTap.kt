package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import androidx.compose.ui.focus.FocusManager
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.explorer.ExplorerOpenWith
import com.kobblo.gallery.file.explorer.gallery.PhotoFlight
import com.kobblo.gallery.file.explorer.isCloudPath
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import kotlinx.coroutines.CoroutineScope

/** What a tap on a file in a list comes to, before how its kind opens is asked. */
internal enum class FileTap { Unlock, Select, Open }

/** A lock comes before everything, so a locked file can't be selected either. */
internal fun fileTap(file: FileItem, selecting: Boolean): FileTap = when {
    file.isLocked -> FileTap.Unlock
    selecting -> FileTap.Select
    else -> FileTap.Open
}

/** Answers taps on the files in the lists the root shows. */
internal class FileTaps(
    private val context: Context,
    private val focus: FocusManager,
    private val scope: CoroutineScope,
    private val archives: ArchiveDesk,
    private val reducedMotion: Boolean,
    private val state: AppState,
    private val onAction: (AppAction) -> Unit
) {
    private val opener = FileOpener(context, state, focus, onAction)

    fun on(file: FileItem) = when (fileTap(file, state.isSelectionMode)) {
        FileTap.Unlock -> onAction(AppAction.RequestAuth(AuthReason.OPEN_FILE, file.path, fileId = file.id, folderName = file.name))
        FileTap.Select -> onAction(AppAction.ToggleSelection(file.id))
        FileTap.Open -> open(file)
    }

    private fun open(file: FileItem) {
        val isFolder = file.type == "folder"
        if (file.path.startsWith("/")) onAction(AppAction.LogRecentAction(file.path, if (isFolder) "Visited" else "Opened"))
        when {
            isFolder -> opener.goInto(file)
            // Someone chose another app for this kind of file, and it has it now.
            ExplorerOpenWith.openWithDefault(context, file) -> Unit
            ArchiveFormats.isArchiveName(file.name) -> offerArchive(file)
            else -> {
                // A photo or video grows out of whatever thumbnail it was tapped in, a tile, a row or a card,
                // if the viewer really opens it.
                val flies = file.type == "image" || file.type == "video"
                if (flies && !reducedMotion) PhotoFlight.requestFor(scope, file.path)
                opener.open(file)
            }
        }
    }

    /** Asks what to do with an archive, once it's on the phone. */
    private fun offerArchive(file: FileItem) {
        if (!isCloudPath(file.path)) {
            archives.offer(file)
            return
        }
        val copy = downloadedCopy(context.cacheDir, file)
        if (copy.length() > 0L) archives.offer(file.copy(path = copy.absolutePath)) else onAction(AppAction.DownloadAndOpenFile(file))
    }
}
