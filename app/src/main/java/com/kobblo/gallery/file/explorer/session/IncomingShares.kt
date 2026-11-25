package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.share.IncomingSharePrompt
import com.kobblo.gallery.file.explorer.data.share.SharedIncomingFile
import com.kobblo.gallery.file.explorer.fileops.JobControl
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.JobOutcome
import com.kobblo.gallery.file.explorer.fileops.JobPlan
import com.kobblo.gallery.file.explorer.fileops.JobResult
import com.kobblo.gallery.file.explorer.fileops.JobSpec
import com.kobblo.gallery.file.explorer.fileops.JobSurface
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Files another app shares into this one: asking where they should go, and saving them there.
 *
 * @param showSaved opens the folder they were saved to, under its name.
 */
internal class IncomingShares(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val jobs: JobDesk,
    private val showSaved: (folder: String, name: String) -> Unit
) {
    fun offer(uris: List<Uri>, declaredType: String?) {
        scope.launch(Dispatchers.IO) {
            val files = uris.mapNotNull { describe(it, declaredType) }.filter { it.name.isNotBlank() }
            if (files.isEmpty()) {
                messages.post(words.text(R.string.app_share_unreadable))
                return@launch
            }
            val prompt = IncomingSharePrompt(files = files, selectedDestination = usualFolder())
            live.update { it.copy(viewingFile = null, incomingSharePrompt = prompt) }
        }
    }

    private fun describe(uri: Uri, declaredType: String?): SharedIncomingFile? = try {
        SharedIncomingFile.fromUri(app, uri, declaredType)
    } catch (e: Exception) {
        Log.e(Tag, "Failed to read an incoming share: ${e.javaClass.simpleName}")
        null
    }

    private fun usualFolder(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = if (downloads.exists() || downloads.mkdirs()) downloads else Environment.getExternalStorageDirectory()
        return folder.absolutePath
    }

    fun chooseFolder(path: String) =
        live.update { it.copy(incomingSharePrompt = it.incomingSharePrompt?.copy(selectedDestination = path)) }

    fun dismiss() = live.update { it.copy(incomingSharePrompt = null) }

    fun save(path: String) {
        val prompt = live.now.incomingSharePrompt ?: return
        if (prompt.isSaving) return
        live.update { it.copy(incomingSharePrompt = prompt.copy(isSaving = true)) }
        val folder = File(path)
        val name = folder.name.ifEmpty { words.text(R.string.app_share_storage) }
        val saving = ShareSaving(app, words, prompt.files, folder, name)
        jobs.start(saving.job { finished(saving, folder, name) })
    }

    /** A screen made since the job began doesn't have this prompt, and has nothing of it to clear. */
    private fun finished(saving: ShareSaving, folder: File, name: String) {
        dismiss()
        if (saving.savedAny) scope.launch { showSaved(folder.absolutePath, name) }
    }

    private companion object {
        const val Tag = "IncomingShares"
    }
}

/** One lot of shared files on its way into [folder], called [folderName]. */
private class ShareSaving(
    private val app: Application,
    private val words: Words,
    private val files: List<SharedIncomingFile>,
    private val folder: File,
    private val folderName: String
) {
    private val saved = ArrayList<File>()
    private var failed = 0

    val savedAny: Boolean get() = saved.isNotEmpty()

    fun job(onDone: () -> Unit) = JobSpec(
        kind = JobKind.COPY,
        surface = JobSurface.BAR,
        title = words.text(R.string.safety_job_copying_to, folderName),
        target = folderName,
        total = files.size,
        plan = JobPlan(JobKind.COPY, folderName, folder.absolutePath, files.map { it.uri.toString() }),
        // They're another app's to read from, and nothing here could rename them meanwhile.
        reads = emptyList(),
        // What's said at the end is the same whether it ran to the end or was stopped partway.
        onStopped = ::result,
        onFailed = { result() },
        onDone = { onDone() },
        work = ::saveAll
    )

    private fun result(): JobResult {
        val allWell = saved.isNotEmpty() && failed == 0
        val message = when {
            saved.isEmpty() -> words.text(R.string.polish_files_not_saved)
            saved.size == 1 -> words.text(R.string.app_share_saved_one, saved.single().name, folderName)
            failed > 0 -> words.counted(R.plurals.app_share_saved_some, files.size, saved.size, files.size, folderName)
            else -> words.counted(R.plurals.app_share_saved_all, saved.size, saved.size, folderName)
        }
        return JobResult(if (allWell) JobOutcome.DONE else JobOutcome.PROBLEM, message, null, message, folder.absolutePath)
    }

    private suspend fun saveAll(control: JobControl): JobResult {
        folder.mkdirs()
        for (file in files) {
            control.checkpoint()
            try {
                save(file, control)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(Tag, "Error saving file ${file.name}", e)
                failed++
            }
        }
        return result()
    }

    private fun save(file: SharedIncomingFile, control: JobControl) {
        val target = File(folder, SafeFiles.freeName(file.name) { File(folder, it).exists() })
        control.setItem(file.name)
        control.journal.started(file.uri.toString(), target.path)
        copy(file, target)
        MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), arrayOf(file.mimeType ?: "*/*"), null)
        saved += target
        control.journal.finished(file.uri.toString(), target.path)
        control.setCount(saved.size + failed)
    }

    /** Under a temporary name, so a share that dies halfway doesn't leave half a file looking whole. */
    private fun copy(file: SharedIncomingFile, target: File) {
        val source = app.contentResolver.openInputStream(file.uri) ?: throw IOException("Nothing to read for ${file.name}")
        val part = SafeFiles.partFileIn(folder)
        try {
            source.use { from -> part.outputStream().use { to -> from.copyTo(to) } }
            if (!part.renameTo(target)) throw IOException("Couldn't finish ${file.name}")
        } finally {
            part.delete()
        }
    }

    private companion object {
        const val Tag = "IncomingShares"
    }
}
