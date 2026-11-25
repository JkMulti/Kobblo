package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.explorer.drag.DragMoved
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.FinishedJob
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.TransferReport
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val Cut = "cut"

/**
 * Copying and moving files: the clipboard, and what a drag lets go of.
 *
 * @param refresh reads again whatever a finished job may have changed.
 */
internal class FileTransfers(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val messages: Messages,
    private val repository: FileRepository,
    private val jobs: JobDesk,
    private val selection: Selection,
    private val bin: Bin,
    private val refresh: () -> Unit
) {
    /** Jobs started by a drag, whose result can offer Undo. Only touched on the main thread. */
    private val dragged = HashSet<Long>()
    private val draggedResults = MutableSharedFlow<DragMoved>()

    /** Moves made by a drag that can be put back. */
    val undoable: SharedFlow<DragMoved> = draggedResults.asSharedFlow()

    /** Puts what's chosen on the clipboard, to be copied or cut. */
    fun clipSelection(action: String) = clip(selection.files().map { it.path }, action)

    /** For one file picked from its menu, which needn't be in the list on screen or selectable. */
    fun clipFile(path: String, action: String) = clip(listOf(path), action)

    private fun clip(paths: List<String>, action: String) = live.update {
        it.choosingNothing().copy(clipboardPaths = paths.toImmutableList(), clipboardAction = action)
    }

    fun clearClipboard() = live.update { it.copy(clipboardPaths = persistentListOf(), clipboardAction = null) }

    fun paste(destination: String) {
        val state = live.now
        val action = state.clipboardAction ?: return
        if (state.clipboardPaths.isEmpty()) return
        // The job has its own list now, so the clipboard is free to be used again while this runs or waits.
        clearClipboard()
        start(state.clipboardPaths, destination, action)
    }

    /**
     * Cut and Paste in one movement, and the same job as far as anyone can tell: queued, never overwriting,
     * with the same notification and Cancel. It leaves the clipboard alone, since nothing was copied to it.
     */
    fun moveDragged(paths: List<String>, destination: String) {
        if (paths.isEmpty()) return
        selection.none()
        dragged += start(paths, destination, Cut)
    }

    /** Puts a drag's move back, which is just more moves, by the same job. */
    fun undo(moved: DragMoved) = moved.back.forEach { (folder, paths) -> start(paths, folder, Cut) }

    private fun start(paths: List<String>, destination: String, action: String): Long = jobs.start(
        FileJobSpecs.transfer(
            app, repository, paths, destination,
            move = action == Cut,
            onReport = { report -> live.update { it.keepingUnfinished(report, paths, action) } }
        )
    )

    fun togglePause() = FileJobs.togglePause()

    fun cancelRunning() = FileJobs.cancelRunning()

    /**
     * The jobs belong to the process, not to this, so one started before this existed can still finish while
     * it does. Whatever screen is here when one ends is the one that reports it and refreshes.
     */
    fun observeJobs() {
        scope.launch { FileJobs.finished.collect(::finished) }
    }

    private suspend fun finished(job: FinishedJob) {
        val moved = if (dragged.remove(job.id)) DragMoved.from(job.result) else null
        if (moved != null) {
            draggedResults.emit(moved)
        } else {
            job.result.message?.takeIf { it.isNotBlank() }?.let(messages::post)
        }
        if (job.kind in ChangeTheBin) bin.refresh()
        refresh()
    }

    private companion object {
        val ChangeTheBin = setOf(JobKind.TRASH, JobKind.RESTORE, JobKind.EMPTY_BIN)
    }
}

/**
 * This state after a transfer of [paths] that may not have gone all the way. What failed or never started
 * stays on the clipboard to try again, and a stopped move leaves off what has already gone. Unless something
 * else has been copied since: that's the newer wish. No [report] at all means it never got to begin.
 */
internal fun AppState.keepingUnfinished(report: TransferReport?, paths: List<String>, action: String): AppState {
    if (report != null && report.ok) return this
    if (clipboardPaths.isNotEmpty()) return this
    val left = if (report == null || report.cancelled) {
        val moved = report?.movedFrom.orEmpty().toSet()
        paths.filterNot { it in moved }
    } else {
        report.failed.map { it.path }
    }
    return copy(clipboardPaths = left.toImmutableList(), clipboardAction = if (left.isEmpty()) null else action)
}
