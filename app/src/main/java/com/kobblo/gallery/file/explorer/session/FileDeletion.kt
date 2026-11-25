package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.BinFailure
import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import com.kobblo.gallery.file.explorer.removePaths
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Deleting files, to Recently Deleted or for good.
 *
 * @param reload reads the open location again and measures the storage.
 */
internal class FileDeletion(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository,
    private val jobs: JobDesk,
    private val drive: DriveAccounts,
    private val cleaner: Cleaner,
    private val reload: () -> Unit
) {
    fun deleteChosen() {
        val state = live.now
        // Off this thread, since Select All in a large library is a lot of rows to look up.
        scope.launch(Dispatchers.Default) {
            // Everything selectable is in the list on screen, so an id can only mean a file from there.
            val byId = HashMap<Int, FileItem>(state.files.size * 2)
            for (file in state.files) byId.putIfAbsent(file.id, file)
            delete(state.selectedFiles.mapNotNull(byId::get))
        }
    }

    /**
     * Deletes exactly these. A viewer or a menu has the file it means right here, and its id may mean a
     * different file in the list behind it, so it must not go through the selection.
     */
    fun delete(files: List<FileItem>) {
        if (files.isEmpty()) return
        live.update { it.choosingNothing() }
        scope.launch(Dispatchers.IO) { deleteNow(files) }
    }

    private suspend fun deleteNow(files: List<FileItem>) {
        val state = live.now
        val plan = DeletePlan.of(
            files,
            inDrive = state.location == CloudService.Drive.key,
            fromCleaner = Places.isCategory(state.location),
            binEnabled = state.isRecycleBinEnabled
        )
        var refusedByDrive = false
        for (file in plan.inDrive) if (drive.delete(file) != null) refusedByDrive = true
        if (refusedByDrive) messages.post(words.text(R.string.app_drive_delete_failed))
        if (plan.onlyDrive) return reload()
        jobs.start(
            FileJobSpecs.delete(
                app,
                repository,
                toBin = plan.toBin,
                forGood = plan.forGood,
                emptied = plan.emptied,
                refused = plan.refused,
                doneMessage = if (plan.toBin.isEmpty()) null else movedToBin(state.recycleBinRetentionValue, state.recycleBinRetentionUnit),
                onFinished = ::forget
            )
        )
    }

    /** Settings only saves days now. Older versions may have saved another unit, which stays as it was. */
    private fun movedToBin(keptFor: Int, unit: String): String =
        if (unit.equals("Days", ignoreCase = true)) words.counted(R.plurals.app_bin_moved_days, keptFor, keptFor)
        else words.text(R.string.app_bin_moved_for, keptFor, unit.lowercase())

    /** On whichever screen is left. A new one will have read all of this fresh. */
    private fun forget(removed: List<String>) {
        val gone = removed.toSet()
        if (gone.isNotEmpty()) cleaner.outdate()
        live.update { it.without(gone) }
    }
}

/** This state with the files at [gone] taken out of every list. */
internal fun AppState.without(gone: Set<String>): AppState {
    fun List<FileItem>.left() = filterNot { it.path in gone }.toImmutableList()
    return copy(
        files = files.left(),
        folderCache = folderCache.mapValues { it.value.left() }.toPersistentMap(),
        cleanerData = cleanerData?.removePaths(gone)
    )
}

/** What's to happen to each of the files someone asked to delete. */
internal class DeletePlan(
    val inDrive: List<FileItem>,
    val toBin: List<String>,
    val forGood: List<String>,
    /** Empty folders, which just go: there's nothing in them to get back, and a Bin full of them helps nobody. */
    val emptied: List<String>,
    /** Turned down, because they aren't what they were listed as any more. */
    val refused: List<BinFailure>
) {
    val onlyDrive: Boolean get() = toBin.isEmpty() && forGood.isEmpty() && emptied.isEmpty() && refused.isEmpty()

    private enum class Fate { Drive, Refuse, Empty, Bin, Erase }

    companion object {
        /**
         * @param inDrive whether Drive's top folder is open, where every row is Drive's.
         * @param fromCleaner whether these are from one of the Cleaner's lists, which can be older than
         * what's on the disk now.
         */
        fun of(files: List<FileItem>, inDrive: Boolean, fromCleaner: Boolean, binEnabled: Boolean): DeletePlan {
            val byFate = files.groupBy { file ->
                when {
                    inDrive || CloudService.Drive.isFolder(file.path) -> Fate.Drive
                    lostItsOriginal(file) -> Fate.Refuse
                    fromCleaner && file.isEmptyFolder && hasFilled(file) -> Fate.Refuse
                    fromCleaner && file.type == "folder" && isEmpty(file) -> Fate.Empty
                    binEnabled -> Fate.Bin
                    else -> Fate.Erase
                }
            }
            fun paths(fate: Fate) = byFate[fate].orEmpty().map { it.path }
            return DeletePlan(
                inDrive = byFate[Fate.Drive].orEmpty(),
                toBin = paths(Fate.Bin),
                forGood = paths(Fate.Erase),
                emptied = paths(Fate.Empty),
                refused = paths(Fate.Refuse).map { BinFailure(it, FailReason.NOT_POSSIBLE) }
            )
        }

        /** A duplicate whose other copy has gone is the only one left. */
        private fun lostItsOriginal(file: FileItem) = file.duplicateOf != null && !File(file.duplicateOf).exists()

        private fun hasFilled(file: FileItem) = File(file.path).list()?.isNotEmpty() == true

        private fun isEmpty(file: FileItem) = File(file.path).list()?.isEmpty() == true
    }
}
