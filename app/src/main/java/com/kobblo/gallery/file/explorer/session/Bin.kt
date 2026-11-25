package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.util.Log
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import com.kobblo.gallery.file.explorer.fileops.JobSpec
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Starting long work under [FileJobs], and keeping clear of what it's working on. */
internal class JobDesk(private val words: Words, private val messages: Messages) {

    /** Starts [job], saying so if it has to wait its turn, and answers with its id. */
    fun start(job: JobSpec): Long = FileJobs.enqueueAndSay(job, messages::post)

    /** Whether a job is busy with any of [paths], which are then left alone. That's said rather than done quietly. */
    fun turnsDown(paths: Iterable<String>): Boolean {
        if (paths.none(FileJobs::isBusyWith)) return false
        messages.post(words.text(R.string.safety_job_in_use))
        return true
    }
}

/** Recently Deleted. */
internal class Bin(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository,
    private val jobs: JobDesk
) {
    fun show(visible: Boolean) {
        // Marked loading here, not once the load starts, so it never opens saying nothing's there.
        live.update { it.copy(showTrashScreen = visible, trashIsLoading = visible || it.trashIsLoading) }
        if (visible) read()
    }

    /** Reads it again if it's on screen. */
    fun refresh() {
        if (live.now.showTrashScreen) read()
    }

    fun read() {
        scope.launch(Dispatchers.IO) {
            live.update { it.copy(trashIsLoading = true) }
            val state = live.now
            val files = filesKept(retentionMillis(state.recycleBinRetentionValue, state.recycleBinRetentionUnit))
            live.update { it.copy(trashFiles = files.toImmutableList(), trashIsLoading = false) }
        }
    }

    /** What's in it. Reading it also clears out what has been there for [keptMillis]. */
    private suspend fun filesKept(keptMillis: Long): List<FileItem> = try {
        repository.getTrashFiles(keptMillis).distinctBy { it.encodedTrashName ?: "${it.path}_${it.id}" }
    } catch (e: Exception) {
        Log.e(Tag, "Couldn't read the Bin", e)
        emptyList()
    }

    fun restore(names: List<String>) {
        jobs.start(FileJobSpecs.restore(app, repository, names))
    }

    fun deleteForGood(names: List<String>) {
        jobs.start(FileJobSpecs.emptyBin(app, repository, names))
    }

    private companion object {
        const val Tag = "Bin"
    }
}

/** Every unit a build has saved, though Settings only offers days now. */
private val UnitSeconds = mapOf(
    "second" to 1L,
    "minute" to 60L,
    "hour" to 3_600L,
    "day" to 86_400L,
    "week" to 7 * 86_400L,
    "month" to 30 * 86_400L,
    "year" to 365 * 86_400L
)

/** How long a deleted file is kept. A unit that isn't known counts as weeks, which errs towards keeping. */
internal fun retentionMillis(value: Int, unit: String): Long {
    val seconds = UnitSeconds[unit.lowercase().removeSuffix("s")] ?: UnitSeconds.getValue("week")
    return value * seconds * 1_000L
}
