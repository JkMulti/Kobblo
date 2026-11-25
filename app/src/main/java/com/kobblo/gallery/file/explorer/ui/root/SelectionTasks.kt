package com.kobblo.gallery.file.explorer.ui.root

import android.content.Context
import androidx.compose.runtime.Composable
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GalleryScope
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSelectionBar
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import java.io.File

/** What's selected, as the bars at the foot of the screen need to know it. */
internal class Selection(val files: List<FileItem>, selecting: Boolean) {
    /** The apps' packages in it. */
    val apps: List<FileItem> = packagesIn(files)

    /** Whether it can be installed, which takes there being nothing in it but apps. */
    val isInstallable: Boolean = selecting && apps.isNotEmpty() && apps.size == files.size

    val isHidden: Boolean = allHidden(files)

    /** The one archive that's selected, if that's all that is. */
    val archive: FileItem? = files.singleOrNull()?.takeIf { ArchiveFormats.isArchiveName(it.name) }
}

/** What the selection bar's buttons do with what's selected. */
internal class SelectionTasks(
    private val selection: Selection,
    private val root: RootMemory,
    private val state: AppState,
    private val context: Context,
    private val haptics: HapticsController,
    private val onPasteInto: (folderName: String) -> Unit,
    private val onAction: (AppAction) -> Unit
) {
    fun clear() = felt(AppAction.ClearSelection)

    fun selectAll() = felt(AppAction.SelectAll(GalleryScope.selectableIds(state.location)))

    fun selectNone() = felt(AppAction.SelectNone)

    fun select(ids: Set<Int>) = onAction(AppAction.SelectAll(ids.toList()))

    fun copy() = felt(AppAction.SetClipboard("copy"))

    fun cut() = felt(AppAction.SetClipboard("cut"))

    fun delete() = felt { root.deleting.askAboutSelection() }

    fun compress() = felt { if (selection.files.isNotEmpty()) root.archives.pack(selection.files) }

    /** Puts the selection away and asks about the one archive that was in it. */
    fun extract(archive: FileItem) = felt {
        onAction(AppAction.ClearSelection)
        root.archives.offer(archive)
    }

    /** Several files on the phone get the screen made for renaming them. Those anywhere else get the sheet. */
    fun rename() = felt {
        val paths = selection.files.map { it.path }
        if (paths.isNotEmpty() && paths.all { it.startsWith("/") }) Hub.open(ToolsRoute.Rename(paths))
        else onAction(AppAction.SetShowBatchRenameDialog(true))
    }

    fun share() = felt {
        val links = state.selectionAsPicked().map { context.lend(File(it.path)) }
        if (links.isNotEmpty()) context.shareAll(links, context.getString(R.string.view_share_files))
    }

    fun sendNearby() = felt {
        onAction(AppAction.NearbyShareAction.StageFiles(selection.files))
        onAction(AppAction.ClearSelection)
        onAction(AppAction.SetLocation("send"))
    }

    /** Pastes here, remembering where here is so the paste can go on saying it after the person has moved on. */
    fun paste() = felt {
        onPasteInto(state.currentFolderName ?: context.getString(R.string.view_current_folder))
        onAction(AppAction.PasteClipboard(state.location))
        onAction(AppAction.ClearSelection)
    }

    fun toggleHide() = felt {
        if (selection.files.isEmpty()) return@felt
        val done = root.hiding.toggle(selection.files, explained = state.hasSeenHideWarning, onAction)
        if (done) onAction(AppAction.ClearSelection)
    }

    fun install() = felt { root.installs.toggleCard(selection.apps) }

    private fun felt(action: AppAction) {
        haptics.tap()
        onAction(action)
    }

    private inline fun felt(task: () -> Unit) {
        haptics.tap()
        task()
    }
}

/**
 * The bar over a selection. On Home, Album's sheet already offers both moving and copying, and More
 * whatever else can be done with many photos.
 */
@Composable
internal fun SelectionBar(selection: Selection, tasks: SelectionTasks, state: AppState) {
    val onHome = state.location == "gallery"
    ExpressiveSelectionBar(
        selectedCount = state.selectedFiles.size,
        selectable = GalleryScope.viewerFiles(state.location, state.files),
        selected = state.selectedFiles,
        onSelect = tasks::select,
        onSelectAll = tasks::selectAll,
        onSelectNone = tasks::selectNone,
        onClearSelection = tasks::clear,
        onAddToAlbum = if (onHome) ({ GalleryScope.requestAlbumPick(move = true) }) else null,
        onMore = if (onHome) ({ GalleryScope.showBulkActions = true }) else null,
        onCopy = if (onHome) null else tasks::copy,
        onCut = if (onHome) null else tasks::cut,
        onPaste = if (state.clipboardPaths.isEmpty()) null else tasks::paste,
        onShare = tasks::share,
        onSendNearby = tasks::sendNearby,
        onBatchRename = tasks::rename,
        onArchive = tasks::compress,
        onExtract = selection.archive?.let { archive -> { tasks.extract(archive) } },
        onToggleHide = tasks::toggleHide, isAllHidden = selection.isHidden,
        onInstallApks = if (selection.isInstallable) tasks::install else null, isSingleApk = selection.apps.size == 1,
        onDelete = tasks::delete
    )
}
