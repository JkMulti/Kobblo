package com.kobblo.gallery.file.explorer.explorer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import java.io.File

/**
 * The file explorer's sheets that outlive the screen or menu that opened them. Choosing Open With closes
 * the file's menu, for one, so the sheet can't belong to the menu.
 */
object ExplorerUi {
    internal sealed interface Sheet {
        data class OpenWith(val file: FileItem) : Sheet
        data class GoToPath(val from: String) : Sheet
    }

    internal var sheet by mutableStateOf<Sheet?>(null)

    /** A file that should be scrolled to and picked out once its folder has loaded. */
    var revealPath by mutableStateOf<String?>(null)
        private set

    fun openWith(file: FileItem) {
        sheet = Sheet.OpenWith(file)
    }

    fun goToPath(from: String) {
        sheet = Sheet.GoToPath(from)
    }

    /** Opens the folder a file is in, with the file in view. */
    fun showInFolder(path: String, onAction: (AppAction) -> Unit) {
        val folder = File(path).parentFile ?: return
        revealPath = path
        onAction(AppAction.SetQuery(""))
        onAction(AppAction.SetLocation(folder.absolutePath, folder.name))
    }

    fun clearReveal() {
        revealPath = null
    }

    fun copyPath(context: Context, path: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.explorer_path), path))
        // From Android 13 the system shows what was copied by itself.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, R.string.explorer_path_copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/** Draws whichever of [ExplorerUi]'s sheets is open. It sits above the tabs and the Hub's screens. */
@Composable
fun ExplorerRuntime(state: AppState, onAction: (AppAction) -> Unit) {
    when (val sheet = ExplorerUi.sheet) {
        is ExplorerUi.Sheet.OpenWith -> OpenWithSheet(sheet.file, onDismiss = { ExplorerUi.sheet = null })
        is ExplorerUi.Sheet.GoToPath -> GoToPathSheet(sheet.from, onAction, onDismiss = { ExplorerUi.sheet = null })
        null -> Unit
    }
}
