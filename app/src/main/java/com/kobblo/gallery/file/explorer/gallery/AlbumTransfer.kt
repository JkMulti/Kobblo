package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import android.media.MediaScannerConnection
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.JobControl
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.JobOutcome
import com.kobblo.gallery.file.explorer.fileops.JobPlan
import com.kobblo.gallery.file.explorer.fileops.JobResult
import com.kobblo.gallery.file.explorer.fileops.JobSpec
import com.kobblo.gallery.file.explorer.fileops.JobSurface
import com.kobblo.gallery.file.explorer.fileops.Locks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Moves and copies media between albums. Unlike a plain paste it never overwrites: a name already
 * taken in the album gets a number. Files keep their dates, favorites and locks follow them, and the
 * system's index hears about every change, so the feed updates by itself.
 */
object AlbumTransfer {
    data class Progress(val done: Int, val total: Int, val moving: Boolean, val albumName: String)

    private var jobId: Long? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    fun cancel() {
        jobId?.let(FileJobs::cancel)
    }

    /** Reports how many files arrived, and how many couldn't be moved or copied. */
    fun start(
        context: Context,
        files: List<FileItem>,
        album: File,
        move: Boolean,
        onDone: suspend (done: Int, failed: Int) -> Unit
    ) {
        val app = context.applicationContext
        val kind = if (move) JobKind.MOVE else JobKind.COPY
        val stopped = {
            JobResult(
                JobOutcome.STOPPED,
                app.getString(if (move) R.string.safety_job_move_stopped else R.string.safety_job_copy_stopped, album.name),
                null,
                null,
                album.path
            )
        }
        jobId = FileJobs.enqueueAndSay(
            JobSpec(
                kind = kind,
                surface = JobSurface.OWN,
                title = app.getString(if (move) R.string.safety_job_moving_to else R.string.safety_job_copying_to, album.name),
                target = album.name,
                total = files.size,
                plan = JobPlan(kind, album.name, album.path, files.map { it.path }),
                onStopped = stopped,
                onFailed = { error ->
                    val text = error.message ?: app.getString(R.string.safety_reason_unknown)
                    JobResult(
                        JobOutcome.PROBLEM,
                        app.getString(if (move) R.string.safety_job_move_problem else R.string.safety_job_copy_problem, album.name),
                        text,
                        text,
                        album.path
                    )
                },
                work = { control -> run(app, files, album, move, control, onDone) }
            )
        ) { message -> android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show() }
    }

    private suspend fun run(
        app: Context,
        files: List<FileItem>,
        album: File,
        move: Boolean,
        control: JobControl,
        onDone: suspend (done: Int, failed: Int) -> Unit
    ): JobResult {
        var done = 0
        var failed = 0
        val touched = ArrayList<String>()
        _progress.value = Progress(0, files.size, move, album.name)
        try {
            album.mkdirs()
            val taken = HashSet(album.list().orEmpty().asList())
            for (file in files) {
                control.checkpoint()
                val source = File(file.path)
                if (source.parentFile == album) {
                    // Already there, so there's nothing to do and nothing to report.
                    done++
                } else {
                    val target = File(album, freeFileName(source.name) { it in taken })
                    control.setItem(source.name)
                    control.journal.started(source.path, target.path)
                    try {
                        transfer(source, target, move, control)
                        taken.add(target.name)
                        touched.add(target.path)
                        if (move) {
                            touched.add(source.path)
                            Locks.moved(app, source.path, target.path)
                        } else {
                            Locks.copied(app, source.path, target.path)
                        }
                        done++
                        control.journal.finished(source.path, target.path)
                    } catch (e: IOException) {
                        android.util.Log.e("AlbumTransfer", "Couldn't transfer a file", e)
                        target.takeIf { !move || source.exists() }?.delete()
                        control.journal.rolledBack(source.path, target.path)
                        failed++
                    }
                }
                control.setCount(done + failed)
                _progress.value = Progress(done + failed, files.size, move, album.name)
            }
        } finally {
            if (touched.isNotEmpty()) {
                MediaScannerConnection.scanFile(app, touched.toTypedArray(), null, null)
            }
            _progress.value = null
        }
        withContext(Dispatchers.Main) { onDone(done, failed) }
        val title = app.resources.getQuantityString(
            if (move) R.plurals.safety_job_moved else R.plurals.safety_job_copied,
            done,
            done,
            album.name
        )
        return JobResult(if (failed == 0) JobOutcome.DONE else JobOutcome.PROBLEM, title, null, null, album.path)
    }

    private suspend fun transfer(source: File, target: File, move: Boolean, control: JobControl) {
        if (!source.exists()) throw IOException("Missing source")
        if (move && source.renameTo(target)) return
        // Whole and checked under a temporary name before it takes this one, and read back if the
        // original is about to go.
        com.kobblo.gallery.file.explorer.fileops.TreeCopier(verify = move, checkpoint = control::checkpoint).copy(source, target)
        if (move) control.journal.verified(source.path, target.path)
        if (move && !source.delete()) {
            target.delete()
            throw IOException("Couldn't remove the original")
        }
    }

    /**
     * Renames an album's folder. The system's index follows a folder renamed through the normal path
     * on recent Android, and the scan covers older versions.
     */
    suspend fun renameAlbum(context: Context, from: File, to: File, files: List<FileItem>): Boolean =
        withContext(Dispatchers.IO) {
            if (to.exists() || !from.renameTo(to)) return@withContext false
            Locks.moved(context.applicationContext, from.path, to.path)
            val paths = ArrayList<String>(files.size * 2)
            for (file in files) {
                val moved = File(to, File(file.path).name).path
                paths.add(file.path)
                paths.add(moved)
            }
            if (paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null, null)
            }
            true
        }
}
