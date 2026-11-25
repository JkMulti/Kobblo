package com.kobblo.gallery.file.explorer.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.focus.FocusManager
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.OpenElsewhere
import com.kobblo.gallery.file.explorer.ui.root.FileOpener
import com.kobblo.gallery.file.explorer.ui.root.handOverMime
import com.kobblo.gallery.file.explorer.ui.root.lend
import com.kobblo.gallery.file.explorer.ui.root.viewIntent
import java.io.File

/** Opens [file] the way a tap on it in a list does, from anywhere in the app. */
fun handleFileOpen(file: FileItem, context: Context, state: AppState, focusManager: FocusManager, onAction: (AppAction) -> Unit) {
    FileOpener(context, state, focusManager, onAction).open(file)
}

/**
 * Hands [file] to another app, by the system's chooser with this app left out: Other App means another app.
 * If nothing else can open it, [inThisApp] is what happens instead, since a tap shouldn't just end.
 * [defaultMime] is what to call it where its name doesn't say, and [onShowToast] hears about a failure
 * in place of the system's own toast.
 */
fun openFileInExternalApp(
    context: Context,
    file: FileItem,
    defaultMime: String,
    onShowToast: ((String) -> Unit)? = null,
    inThisApp: (() -> Unit)? = null
) {
    try {
        val opening = viewIntent(context.lend(File(file.path)), handOverMime(file.name, otherwise = defaultMime))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (inThisApp != null && !OpenElsewhere.anyoneElse(context, opening)) {
            inThisApp()
        } else {
            context.startActivity(OpenElsewhere.chooser(context, opening, context.getString(R.string.view_open_with_chooser)))
        }
    } catch (e: Exception) {
        Log.w("OpenFile", "Couldn't hand a file to another app", e)
        val problem = context.getString(R.string.no_app_found)
        onShowToast?.invoke(problem) ?: Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
    }
}
