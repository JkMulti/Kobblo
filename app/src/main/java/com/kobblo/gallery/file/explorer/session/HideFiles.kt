package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.util.Log
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.PendingHideConflict
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.ConflictResolution
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import com.kobblo.gallery.file.explorer.fileops.escapeForLike
import com.kobblo.gallery.file.explorer.suggestNonConflictingName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How far hiding a number of files has got. A file is hidden by a dot at the front of its name. */
internal data class HideRun(
    val waiting: List<FileItem>,
    val unhide: Boolean,
    val done: Int = 0,
    val failed: Int = 0
) {
    fun after(succeeded: Boolean) = if (succeeded) copy(done = done + 1) else copy(failed = failed + 1)

    companion object {
        /**
         * Unhides if everything is hidden, and hides otherwise. What's already hidden stays as it is, or a
         * mixed selection would give it a second dot.
         */
        fun over(files: List<FileItem>): HideRun {
            val unhide = files.all { it.isHidden }
            return HideRun(if (unhide) files else files.filterNot { it.isHidden }, unhide)
        }

        /** The run a conflict stopped, going on after the file it was about. */
        fun resuming(conflict: PendingHideConflict) =
            HideRun(conflict.remainingFiles, conflict.isUnhide, conflict.successCount, conflict.failedCount)
    }
}

/** Where a run stopped: at its end, or at a name that's taken. */
internal sealed interface HideStop {
    data class Finished(val run: HideRun) : HideStop
    data class Blocked(val conflict: PendingHideConflict) : HideStop
}

/**
 * Renames the files themselves.
 *
 * @param moved told of every file that now has another path.
 */
internal class HideWalk(private val moved: (from: File, to: File) -> Unit) {

    /** Goes on with [run] until it's done or can't go on without asking. */
    fun goOn(run: HideRun): HideStop {
        var now = run
        while (now.waiting.isNotEmpty()) {
            val file = now.waiting.first()
            val rest = now.copy(waiting = now.waiting.drop(1))
            val from = File(file.path)
            val folder = from.parentFile
            if (folder == null || !from.exists()) {
                now = rest.after(succeeded = false)
                continue
            }
            val to = File(folder, nameFor(file, now.unhide))
            if (to.exists()) return HideStop.Blocked(conflict(file, to, folder, rest))
            now = rest.after(rename(from, to))
        }
        return HideStop.Finished(now)
    }

    fun rename(from: File, to: File): Boolean = from.renameTo(to).also { if (it) moved(from, to) }

    private fun nameFor(file: FileItem, unhide: Boolean) = if (unhide) file.name.removePrefix(".") else "." + file.name

    private fun conflict(file: FileItem, taken: File, folder: File, rest: HideRun) = PendingHideConflict(
        file = file,
        targetName = taken.name,
        targetPath = taken.absolutePath,
        suggestedName = suggestNonConflictingName(taken.name, folder.list().orEmpty().toSet()),
        remainingFiles = rest.waiting,
        isUnhide = rest.unhide,
        successCount = rest.done,
        failedCount = rest.failed
    )
}

/**
 * Hiding and unhiding files, stopping to ask wherever the new name is taken.
 *
 * @param refresh reads the open folder again, and the recents.
 */
internal class HideFiles(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository,
    private val jobs: JobDesk,
    private val selection: Selection,
    private val refresh: () -> Unit
) {
    private val walk = HideWalk(::tellOfMove)

    fun toggle(files: List<FileItem>) {
        if (files.isEmpty() || jobs.turnsDown(files.map { it.path })) return
        val run = HideRun.over(files)
        scope.launch(Dispatchers.IO) { goOn(run) }
    }

    /** The person has said what to do about the name that was taken. */
    fun resolve(resolution: ConflictResolution) {
        val conflict = takeConflict() ?: return
        scope.launch(Dispatchers.IO) {
            goOn(HideRun.resuming(conflict).after(rename(conflict, resolution)))
        }
    }

    /** Leaves the file it was about as it is, and goes on with the rest unless [skipOnlyThis] is false. */
    fun dismiss(skipOnlyThis: Boolean) {
        val conflict = takeConflict() ?: return
        val run = HideRun.resuming(conflict)
        scope.launch(Dispatchers.IO) { goOn(if (skipOnlyThis) run else run.copy(waiting = emptyList())) }
    }

    private fun takeConflict(): PendingHideConflict? {
        val conflict = live.now.pendingHideConflict ?: return null
        live.update { it.copy(pendingHideConflict = null) }
        return conflict
    }

    private suspend fun rename(conflict: PendingHideConflict, resolution: ConflictResolution): Boolean {
        val from = File(conflict.file.path)
        val folder = from.parentFile
        if (folder == null || !from.exists()) return false
        return when (resolution) {
            is ConflictResolution.Overwrite -> {
                val to = File(conflict.targetPath)
                makeRoomAt(to) && walk.rename(from, to)
            }
            is ConflictResolution.Rename -> walk.rename(from, File(folder, resolution.newName))
        }
    }

    /** What it replaces goes to the Bin, and if that fails nothing has been lost. */
    private suspend fun makeRoomAt(place: File): Boolean =
        repository.moveToTrash(listOf(place.path)).moved.isNotEmpty() || !SafeFiles.existsAtAll(place)

    private suspend fun goOn(run: HideRun) {
        when (val stop = walk.goOn(run)) {
            is HideStop.Blocked -> live.update { it.copy(pendingHideConflict = stop.conflict) }
            is HideStop.Finished -> withContext(Dispatchers.Main) { report(stop.run) }
        }
    }

    private fun report(run: HideRun) {
        if (run.done > 0 || run.failed > 0) messages.post(resultText(run))
        if (run.done > 0) {
            refresh()
            selection.none()
        }
    }

    private fun resultText(run: HideRun): String {
        val done = words.counted(
            if (run.unhide) R.plurals.hide_result_unhidden else R.plurals.hide_result_hidden, run.done, run.done
        )
        val failed = words.counted(
            if (run.unhide) R.plurals.hide_result_unhide_failed else R.plurals.hide_result_hide_failed, run.failed, run.failed
        )
        return when {
            run.failed == 0 -> done
            run.done == 0 -> failed
            else -> words.text(R.string.app_sentences_joined, done, failed)
        }
    }

    private fun tellOfMove(from: File, to: File) {
        Locks.moved(app, from.absolutePath, to.absolutePath)
        try {
            reindex(from, to)
        } catch (e: Exception) {
            Log.w(Tag, "Couldn't tell the file index about a rename", e)
        }
    }

    /** The system's index goes by the old path until it's told of the new one, for a folder's files as well. */
    private fun reindex(from: File, to: File) {
        val inside = if (to.isDirectory) to.walkTopDown().maxDepth(ScanDepth).map { it.absolutePath }.toList() else emptyList()
        val changed = listOf(from.absolutePath, to.absolutePath) + inside
        MediaScannerConnection.scanFile(app, changed.toTypedArray(), null, null)
        // Deleting a row deletes its file. Unescaped, the `_` in `my_photos/%` matches any character, and
        // this would take everything in a neighbouring `my photos` with it.
        app.contentResolver.delete(
            MediaStore.Files.getContentUri("external"),
            "${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'",
            arrayOf(from.absolutePath, escapeForLike(from.absolutePath) + "/%")
        )
    }

    private companion object {
        const val Tag = "HideFiles"

        /** How far down a folder its files are listed one by one. */
        const val ScanDepth = 5
    }
}
