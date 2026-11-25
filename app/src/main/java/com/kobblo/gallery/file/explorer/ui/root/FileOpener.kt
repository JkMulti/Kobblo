package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.focus.FocusManager
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.ExplorerOpenWith
import com.kobblo.gallery.file.explorer.isCloudPath
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.ui.openFileInExternalApp
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.ViewerPrefs
import java.io.File

/**
 * Opens a file as things stand in [state]: in the viewer its kind has, in the app the person has chosen
 * for it, or on the screen that works out what it is. A folder is gone into, and a cloud file is
 * downloaded first. Whatever had the keyboard gives it up along the way, unless something only plays.
 */
internal class FileOpener(
    private val context: Context,
    private val state: AppState,
    private val focus: FocusManager,
    private val onAction: (AppAction) -> Unit
) {
    fun open(file: FileItem) {
        when {
            file.type == "folder" -> goInto(file)
            isCloudPath(file.path) -> openDownloaded(file)
            else -> openHere(file)
        }
    }

    /** Goes into a folder. One found by searching takes the search with it. */
    fun goInto(folder: FileItem) {
        if (state.query.isNotEmpty()) {
            focus.clearFocus()
            onAction(AppAction.SetQuery(""))
        }
        onAction(AppAction.SetLocation(folder.path, folder.name))
    }

    private fun openDownloaded(file: FileItem) {
        val copy = downloadedCopy(context.cacheDir, file)
        if (copy.length() > 0L) {
            val type = if (FileKinds.extensionOf(copy.name) == "pdf") "document" else file.type
            open(file.copy(path = copy.absolutePath, type = type))
            return
        }
        // An empty one is a download that never got anywhere.
        copy.delete()
        onAction(AppAction.DownloadAndOpenFile(file))
    }

    private fun openHere(file: FileItem) {
        val onPhone = file.path.startsWith("/")
        if (onPhone) {
            onAction(AppAction.LogRecentAction(file.path, "Opened"))
            if (takenElsewhere(file)) return
        }
        when (openKind(file.name, file.type)) {
            OpenKind.Photo -> view(file, "image/*".takeIf { state.viewerImage == ViewerPrefs.DeviceDefault })
            OpenKind.Document -> view(file, documentMime(file.name).takeIf { state.viewerTextPdf == ViewerPrefs.DeviceDefault })
            OpenKind.Video -> watch(file)
            OpenKind.Song -> play(file)
            OpenKind.App -> install(file)
            // Nothing in the app claims it, so what it holds decides what happens next.
            OpenKind.Unknown -> if (onPhone) ToolsFiles.openUnknown(file) else openFileInExternalApp(context, file, "*/*")
        }
    }

    /** True once the app chosen for this kind of file, or a screen among the tools, has it. */
    private fun takenElsewhere(file: FileItem): Boolean {
        if (ExplorerOpenWith.openWithDefault(context, file)) return true
        if (state.viewerTextPdf == ViewerPrefs.DeviceDefault || !ToolsFiles.tryOpen(file)) return false
        // The results stay behind what opens over them, so Back returns to them.
        focus.clearFocus()
        return true
    }

    /** Shows a photo or a document, in another app if its kind opens there, which is when there's an [elsewhere]. */
    private fun view(file: FileItem, elsewhere: String?) {
        focus.clearFocus()
        if (elsewhere != null) {
            handOver(file, elsewhere, instead = AppAction.ViewFile(file))
            return
        }
        onAction(AppAction.ViewFile(file))
        if (state.query.isNotEmpty()) onAction(AppAction.SetQuery(""))
    }

    /** Shows a video. A search it was found by stays as it was, for coming back to. */
    private fun watch(file: FileItem) {
        focus.clearFocus()
        val watching = AppAction.ViewFile(file)
        if (ViewerPrefs.videoViewer(context) == ViewerPrefs.DeviceDefault) {
            handOver(file, FileKinds.mimeTypeOf(file.name), instead = watching)
        } else {
            onAction(watching)
        }
    }

    private fun play(file: FileItem) {
        val playing = AppAction.PlayAudio(file)
        if (state.viewerMusic == ViewerPrefs.DeviceDefault) handOver(file, "audio/*", instead = playing) else onAction(playing)
    }

    /** Gives [file] to another app. Where there's none to give it to, says so and does [instead]. */
    private fun handOver(file: FileItem, mime: String, instead: AppAction) {
        openFileInExternalApp(context, file, mime, inThisApp = {
            onAction(AppAction.ShowToast(context.getString(R.string.safety_lock_no_app)))
            onAction(instead)
        })
    }

    /** Hands an app's package to the system's installer. */
    private fun install(file: FileItem) {
        try {
            val installing = viewIntent(context.lend(File(file.path)), PackageMime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(installing)
        } catch (e: Exception) {
            Log.w("FileOpener", "Nothing would install an app", e)
            onAction(AppAction.ShowToast(context.getString(R.string.polish_cant_open_app_package)))
        }
    }
}
