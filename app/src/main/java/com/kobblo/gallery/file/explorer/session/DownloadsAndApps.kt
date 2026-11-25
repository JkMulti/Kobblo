package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.os.Environment
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.DownloadsOrganizer
import com.kobblo.gallery.file.explorer.data.core.DownloadsOrganizer.Group
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Organize Downloads: finding what there is to sort into folders, and sorting it once someone has said yes. */
internal class DownloadsSorting(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository,
    private val jobs: JobDesk
) {
    /** Offers what's in Downloads now. The row for this is on the list of locations, so it goes by no open folder. */
    fun propose() {
        scope.launch(Dispatchers.IO) {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            val sortable = repository.getFiles(downloads).filter(::isSortable)
            if (sortable.isEmpty()) messages.show(words.text(R.string.app_organize_nothing)) else offer(sortable)
        }
    }

    private fun isSortable(file: FileItem) = file.type != "folder" && !file.isEmptyFolder && !file.isHidden &&
        DownloadsOrganizer.groupOf(file.name) != null

    private fun offer(files: List<FileItem>) = live.update { it.copy(organisePendingFiles = files) }

    fun cancel() = offer(emptyList())

    fun confirm() {
        val state = live.now
        if (state.organisePendingFiles.isEmpty()) return
        cancel()
        val byGroup = state.organisePendingFiles.groupBy({ DownloadsOrganizer.groupOf(it.name) }, { it.path })
        val moves = Group.entries.map { group -> byGroup[group].orEmpty() to state.folderFor(group) }
        jobs.start(FileJobSpecs.organize(app, repository, moves))
    }

    private fun AppState.folderFor(group: Group): String = when (group) {
        Group.Docs -> orgDestDocs
        Group.Images -> orgDestImages
        Group.Apps -> orgDestApks
        Group.Music -> orgDestMusic
        Group.Videos -> orgDestVideos
    }
}

/** Installing apps without the system's own screens, which takes Shizuku. */
internal class AppInstalls(
    private val scope: CoroutineScope,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository
) {
    /** @param replacing removes the installed app first, for one that can't be installed over. */
    fun install(path: String, downgrade: Boolean, replacing: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (replacing) {
                messages.show(words.text(R.string.app_install_uninstalling_old))
                removeInstalled(path)
            }
            messages.show(words.text(R.string.tools_bundle_installing))
            val installed = installed(path, downgrade)
            messages.show(words.text(if (installed) R.string.app_install_done else R.string.app_install_failed))
        }
    }

    fun installAll(paths: List<String>, downgrade: Boolean, silent: Boolean, replacing: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (!silent && !downgrade) {
                messages.show(words.text(R.string.app_install_batch_needs_shizuku))
                return@launch
            }
            messages.show(words.text(R.string.tools_bundle_installing))
            val done = paths.count { path ->
                if (replacing) removeInstalled(path)
                installed(path, downgrade)
            }
            messages.show(words.counted(R.plurals.app_install_batch_count, paths.size, done, paths.size))
        }
    }

    private fun removeInstalled(path: String) {
        repository.getPackageNameFromApk(path)?.let(repository::uninstallApkSilent)
    }

    private fun installed(path: String, downgrade: Boolean): Boolean = repository.installApkSilent(path, downgrade).first
}
