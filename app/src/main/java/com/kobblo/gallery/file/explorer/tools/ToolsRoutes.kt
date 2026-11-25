package com.kobblo.gallery.file.explorer.tools

import androidx.compose.runtime.Composable
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.tools.apps.AppManagerScreen
import com.kobblo.gallery.file.explorer.tools.documents.OpenedFileScreen
import com.kobblo.gallery.file.explorer.tools.editor.TextEditorScreen
import com.kobblo.gallery.file.explorer.tools.files.CalendarScreen
import com.kobblo.gallery.file.explorer.tools.files.CompareScreen
import com.kobblo.gallery.file.explorer.tools.files.ContactsScreen
import com.kobblo.gallery.file.explorer.tools.files.ContentSearchScreen
import com.kobblo.gallery.file.explorer.tools.files.FontPreviewScreen
import com.kobblo.gallery.file.explorer.tools.files.HexViewerScreen
import com.kobblo.gallery.file.explorer.tools.files.InspectScreen
import com.kobblo.gallery.file.explorer.tools.files.TableScreen
import com.kobblo.gallery.file.explorer.tools.rename.BatchRenameScreen
import com.kobblo.gallery.file.explorer.tools.storage.StorageAnalyzerScreen
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute

/** The full screens of this area. Open one with `Hub.open(...)`. */
sealed interface ToolsRoute : HubRoute {
    data object Apps : ToolsRoute
    data object CrashLog : ToolsRoute

    /**
     * @param edit starts in editing mode rather than reading.
     * @param forceText skips the check that the file looks like text.
     * @param goToLine scrolls to this 1-based line once the file has opened.
     */
    data class TextFile(
        val path: String,
        val edit: Boolean = false,
        val forceText: Boolean = false,
        val goToLine: Int? = null
    ) : ToolsRoute

    data class Hex(val path: String) : ToolsRoute
    data class Inspect(val path: String) : ToolsRoute
    data class OpenUnknown(val path: String) : ToolsRoute
    data class Font(val path: String) : ToolsRoute
    data class Table(val path: String) : ToolsRoute
    data class Contacts(val path: String) : ToolsRoute
    data class Calendar(val path: String) : ToolsRoute

    /** With no path it starts from the list of volumes. */
    data class Analyzer(val path: String? = null) : ToolsRoute
    data class Rename(val paths: List<String>) : ToolsRoute
    data class ContentSearch(val root: String) : ToolsRoute
    data class Compare(val first: String, val second: String? = null) : ToolsRoute
}

@Composable
fun ToolsRouteContent(route: ToolsRoute, state: AppState, onAction: (AppAction) -> Unit) {
    when (route) {
        ToolsRoute.Apps -> AppManagerScreen(onAction)
        ToolsRoute.CrashLog -> com.kobblo.gallery.file.explorer.tools.crash.CrashLogScreen()
        is ToolsRoute.TextFile -> TextEditorScreen(route.path, route.edit, route.forceText, onAction, route.goToLine)
        is ToolsRoute.Hex -> HexViewerScreen(route.path)
        is ToolsRoute.Inspect -> InspectScreen(route.path, onAction)
        is ToolsRoute.OpenUnknown -> OpenedFileScreen(route.path, onAction)
        is ToolsRoute.Font -> FontPreviewScreen(route.path)
        is ToolsRoute.Table -> TableScreen(route.path)
        is ToolsRoute.Contacts -> ContactsScreen(route.path)
        is ToolsRoute.Calendar -> CalendarScreen(route.path)
        is ToolsRoute.Analyzer -> StorageAnalyzerScreen(route.path, onAction)
        is ToolsRoute.Rename -> BatchRenameScreen(route.paths, onAction)
        is ToolsRoute.ContentSearch -> ContentSearchScreen(route.root)
        is ToolsRoute.Compare -> CompareScreen(route.first, route.second)
    }
}
