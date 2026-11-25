package com.kobblo.gallery.file.explorer.archive

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.ExtractJob
import com.kobblo.gallery.file.explorer.fileops.ExtractOutcome
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.FinishedJob
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.RunningJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the screens show about an extraction. The work is a job in [FileJobs], which outlives this, so a screen made
 * again partway only has to look and the pill is back.
 */
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {

    private val shown = MutableStateFlow<ArchiveProgress?>(null)

    /** Null when there's nothing to show: no extraction asked for, or the last one's ending already dismissed. */
    val archiveProgress: StateFlow<ArchiveProgress?> = shown.asStateFlow()

    private val nameShown = MutableStateFlow<String?>(null)
    val currentArchiveName: StateFlow<String?> = nameShown.asStateFlow()

    /** The archive last asked for and where it's going, kept so a password can be tried on the same one. */
    private var asked: Pair<Uri, Uri>? = null
    private var jobId: Long? = null
    private var dismissal: Job? = null
    private var shownAt = 0L

    init {
        viewModelScope.launch {
            FileJobs.state.collect { jobs -> jobs.running?.let(::follow) }
        }
        viewModelScope.launch {
            FileJobs.finished.collect { finished -> conclude(finished) }
        }
    }

    private fun follow(job: RunningJob) {
        if (job.kind != JobKind.EXTRACT) return
        dismissal?.cancel()
        jobId = job.id
        val percent = ExtractionDisplay.percentOf(job.fraction)
        val now = SystemClock.elapsedRealtime()
        // This is read at the top of the whole screen, so it only changes when there's something to see.
        if (ExtractionDisplay.isWorthShowing(shown.value, percent, sinceShownMillis = now - shownAt)) {
            shownAt = now
            shown.value = ArchiveProgress.Running(percent, 100, job.item.orEmpty())
        }
    }

    private fun conclude(finished: FinishedJob) {
        if (finished.kind != JobKind.EXTRACT) return
        val ending = ExtractionDisplay.endingFor(finished.result.payload as? ExtractOutcome)
        shown.value = ending
        // Success needs no answer, so it takes itself off the screen. The others wait to be read.
        if (ending is ArchiveProgress.Complete) dismissSoon()
    }

    private fun dismissSoon() {
        dismissal?.cancel()
        dismissal = viewModelScope.launch {
            delay(SHOW_DONE_MILLIS)
            shown.value = null
        }
    }

    fun extract(source: Uri, dest: Uri, initialPassword: String? = null, archiveName: String? = null) {
        asked = source to dest
        shown.value = null
        val name = archiveName ?: source.lastPathSegment?.substringAfterLast('/') ?: namelessArchive()
        nameShown.value = name
        enqueue(source, dest, initialPassword, name)
    }

    /**
     * Tries [password] on the archive last asked for, and starts over with it if it's right. A wrong one
     * asks again, saying so.
     */
    fun submitPassword(password: String) {
        val (source, dest) = asked ?: return
        viewModelScope.launch {
            try {
                if (ArchiveHandlerFactory.create(getApplication(), source).testPassword(source, password)) {
                    shown.value = null
                    enqueue(source, dest, password, nameShown.value ?: namelessArchive())
                } else {
                    shown.value = ArchiveProgress.NeedsPassword(attemptFailed = true)
                }
            } catch (e: Exception) {
                Log.w("ArchiveViewModel", "Couldn't test the password", e)
                shown.value = ArchiveProgress.Failed(getApplication<Application>().getString(R.string.polish_extract_failed))
            }
        }
    }

    fun resetState() {
        dismissal?.cancel()
        shown.value = null
        nameShown.value = null
    }

    fun cancelExtraction() {
        dismissal?.cancel()
        jobId?.let(FileJobs::cancel)
        // Stays up while it stops, unless there's nothing running behind this, such as a password being asked for.
        if (FileJobs.state.value.running?.id != jobId) shown.value = null
    }

    /** Whether [source] is a RAR, going by its contents. False for anything that can't be told. */
    suspend fun detectIsRar(source: Uri): Boolean = try {
        ArchiveHandlerFactory.create(getApplication(), source) is RarArchiveHandler
    } catch (e: Exception) {
        false
    }

    private fun enqueue(source: Uri, dest: Uri, password: String?, archiveName: String) {
        jobId = FileJobs.enqueueAndSay(ExtractJob.spec(getApplication(), source, dest, password, archiveName)) { message ->
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    /** What an archive is called when nothing gives it a name. */
    private fun namelessArchive(): String = getApplication<Application>().getString(R.string.archive_kind)

    private companion object {
        const val SHOW_DONE_MILLIS = 2500L
    }
}

/** What a job's state comes to on screen. Apart from the view model so it can be checked without one. */
internal object ExtractionDisplay {
    /** A percent that hasn't moved is still shown again this often, so the name of the file being unpacked keeps up. */
    const val REFRESH_MILLIS = 400L

    /** A pill counts files or percent as it comes, and only the fraction means the same for all formats. */
    fun percentOf(fraction: Float?): Int = if (fraction == null) 0 else (fraction * 100).toInt()

    /** Whether progress at [percent] should replace what's [showing], last changed [sinceShownMillis] ago. */
    fun isWorthShowing(showing: ArchiveProgress?, percent: Int, sinceShownMillis: Long): Boolean {
        val running = showing as? ArchiveProgress.Running ?: return true
        return running.filesDone != percent || sinceShownMillis >= REFRESH_MILLIS
    }

    /** Null is a job the person stopped. There's nothing to add to that. */
    fun endingFor(outcome: ExtractOutcome?): ArchiveProgress? = when (outcome) {
        is ExtractOutcome.Complete -> ArchiveProgress.Complete(outcome.folder)
        is ExtractOutcome.NeedsPassword -> ArchiveProgress.NeedsPassword(outcome.attemptFailed)
        is ExtractOutcome.Failed -> ArchiveProgress.Failed(outcome.reason)
        null -> null
    }
}
