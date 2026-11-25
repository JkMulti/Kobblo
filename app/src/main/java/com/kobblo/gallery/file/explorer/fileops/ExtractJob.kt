package com.kobblo.gallery.file.explorer.fileops

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.archive.ArchiveHandlerFactory
import com.kobblo.gallery.file.explorer.archive.ArchiveProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** How an extraction ended, for the screen that asked. */
internal sealed class ExtractOutcome {
    data class Complete(val folder: String) : ExtractOutcome()
    data class NeedsPassword(val attemptFailed: Boolean) : ExtractOutcome()
    data class Failed(val reason: String) : ExtractOutcome()
}

/**
 * Unpacks an archive as one of [FileJobs]' jobs. Half an extraction must never look like a whole one, so into an
 * empty folder it goes straight in and the folder goes if it fails. Into one with things in it, it's unpacked to a
 * hidden folder alongside them first and only joins them once it's whole, under new names where one is taken, so
 * nothing already there can be written over and a cancel or a kill has nothing to pick apart.
 */
internal object ExtractJob {
    fun spec(context: Context, source: Uri, dest: Uri, password: String?, archiveName: String, onDone: (ExtractOutcome?) -> Unit = {}): JobSpec {
        val localDest = dest.takeIf { it.scheme != "content" }?.path
        val sourcePath = if (source.scheme == "file" || source.scheme == null) source.path else source.toString()
        return JobSpec(
            kind = JobKind.EXTRACT,
            surface = JobSurface.EXTRACT_PILL,
            title = context.getString(R.string.safety_job_extracting, archiveName),
            target = archiveName,
            plan = JobPlan(JobKind.EXTRACT, archiveName, localDest, listOfNotNull(sourcePath?.takeIf { it.startsWith("/") })),
            writes = listOfNotNull(localDest),
            onStopped = {
                val text = context.getString(R.string.safety_job_extract_stopped_detail)
                JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_extract_stopped, archiveName), text, null, localDest)
            },
            onFailed = { error ->
                val reason = FileOpText.reason(context, reasonFor(error)).replaceFirstChar { it.uppercase() }
                JobResult(
                    JobOutcome.PROBLEM,
                    context.getString(R.string.safety_job_extract_problem, archiveName),
                    reason,
                    null,
                    localDest,
                    ExtractOutcome.Failed(reason)
                )
            },
            onDone = { result -> onDone(result.payload as? ExtractOutcome) },
            work = { control -> run(context, control, source, dest, password, archiveName) }
        )
    }

    private suspend fun run(
        context: Context,
        control: JobControl,
        source: Uri,
        dest: Uri,
        password: String?,
        archiveName: String
    ): JobResult {
        val isSaf = dest.scheme == "content"
        val localDest = if (isSaf) null else dest.path?.let(::File)
        fun failed(reason: String) = reason.replaceFirstChar { it.uppercase() }.let { sentence ->
            JobResult(
                JobOutcome.PROBLEM,
                context.getString(R.string.safety_job_extract_problem, archiveName),
                sentence,
                null,
                localDest?.path,
                ExtractOutcome.Failed(sentence)
            )
        }
        fun needsPassword(attemptFailed: Boolean) = JobResult(
            JobOutcome.PROBLEM,
            context.getString(R.string.safety_job_extract_needs_password, archiveName),
            null,
            null,
            localDest?.path,
            ExtractOutcome.NeedsPassword(attemptFailed)
        )

        val startedEmpty = localDest != null && localDest.list().isNullOrEmpty()
        val cacheFolder = if (isSaf) File(context.cacheDir, "extract_${System.currentTimeMillis()}") else null
        val staging = if (localDest != null && !startedEmpty) SafeFiles.partFileIn(localDest) else null
        val target = cacheFolder ?: staging ?: localDest ?: return failed(context.getString(R.string.safety_reason_not_possible))
        val sourcePath = if (source.scheme == "file" || source.scheme == null) source.path.orEmpty() else source.toString()
        control.journal.unpacking(sourcePath, localDest?.path ?: target.path, startedEmpty, staging?.path)

        var finished = false
        try {
            val handler = try {
                ArchiveHandlerFactory.create(context, source)
            } catch (e: Exception) {
                return failed(context.getString(R.string.safety_job_extract_prepare_failed))
            }
            control.checkpoint()
            // Before anything's written, and here rather than in the screen that asked, since for a RAR finding
            // out means unpacking the lot. A password that came with the request has already been tried.
            if (password == null) {
                val encrypted = try {
                    handler.isEncrypted(source) || handler.listEntries(source).any { it.isEncrypted }
                } catch (e: Exception) {
                    val message = e.message.orEmpty()
                    if (message.contains("password", ignoreCase = true) || message.contains("encrypt", ignoreCase = true) || message.contains("decrypter", ignoreCase = true)) {
                        true
                    } else {
                        return failed(context.getString(R.string.safety_job_extract_prepare_failed))
                    }
                }
                if (encrypted) return needsPassword(false)
            }
            target.mkdirs()

            var outcome: ArchiveProgress? = null
            // A handler's own stopping is by coroutine cancel, so that's how a stop reaches it.
            val stopped = stopWith(control) {
                handler.extract(source, Uri.fromFile(target), password).collect { progress ->
                    when (progress) {
                        is ArchiveProgress.Running -> {
                            // Some formats count files and zip counts percent, so only the fraction means the same for all.
                            if (progress.filesTotal > 0) control.setFraction(progress.filesDone.toFloat() / progress.filesTotal)
                            control.setItem(progress.currentEntryName.substringAfterLast('/'))
                        }
                        else -> outcome = progress
                    }
                }
            }
            if (stopped) throw CancellationException("Stopped")

            return when (val ended = outcome) {
                is ArchiveProgress.Complete -> {
                    val output = when {
                        isSaf -> {
                            copyToTree(context, target, dest, archiveName)
                            dest.toString()
                        }
                        staging != null -> {
                            join(staging, localDest!!, control)
                            localDest.path
                        }
                        else -> ended.outputDir
                    }
                    if (!isSaf) scanAll(context, if (staging != null) localDest!! else File(output))
                    finished = true
                    val title = context.getString(R.string.safety_job_extracted, archiveName)
                    JobResult(JobOutcome.DONE, title, null, null, output.takeIf { it.startsWith("/") }, ExtractOutcome.Complete(output))
                }
                is ArchiveProgress.NeedsPassword -> needsPassword(ended.attemptFailed)
                is ArchiveProgress.Failed -> failed(ended.reason)
                else -> failed(context.getString(R.string.safety_reason_unknown))
            }
        } finally {
            // Cleaned the same way for a stop, a failure or a wrong password. The archive itself isn't touched, so
            // nothing is lost by this.
            cacheFolder?.let { SafeFiles.deleteTree(it) }
            staging?.let { SafeFiles.deleteTree(it) }
            if (!finished && startedEmpty && localDest != null) SafeFiles.deleteTree(localDest)
        }
    }

    /** Runs [block], cancelling it if a stop is asked for meanwhile. Says whether that's how it ended. */
    private suspend fun stopWith(control: JobControl, block: suspend () -> Unit): Boolean = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            while (!control.stopRequested) delay(STOP_POLL_MILLIS)
            work.cancel()
        }
        try {
            work.await()
            false
        } catch (e: CancellationException) {
            if (!control.stopRequested) throw e
            true
        } finally {
            watcher.cancel()
        }
    }

    /** Moves what was unpacked in beside what's there. A rename, so this is quick and can't half-write anything. */
    private fun join(staging: File, into: File, control: JobControl) {
        for (child in staging.listFiles().orEmpty()) {
            val attributes = SafeFiles.attributesOf(child) ?: continue
            val name = SafeFiles.freeName(child.name, attributes.isDirectory) { SafeFiles.existsAtAll(File(into, it)) }
            val placed = File(into, name)
            control.journal.joined(placed.path)
            if (!child.renameTo(placed)) throw IOException("Couldn't finish ${child.name}")
        }
    }

    private fun scanAll(context: Context, folder: File) {
        try {
            val files = folder.walkTopDown().filter { it.isFile }.take(SCAN_LIMIT).map { it.absolutePath }.toList()
            if (files.isNotEmpty()) MediaScannerConnection.scanFile(context, files.toTypedArray(), null, null)
        } catch (e: Exception) {
            android.util.Log.w("ExtractJob", "Couldn't tell the file index about what was extracted", e)
        }
    }

    private fun copyToTree(context: Context, from: File, tree: Uri, archiveName: String) {
        val docTree = DocumentFile.fromTreeUri(context, tree) ?: return
        // Its own new folder, so nothing already in the chosen one can be written over.
        val wanted = archiveName.substringBeforeLast(".").ifEmpty { "Extracted" }
        val folderName = SafeFiles.freeName(wanted, isFolder = true) { docTree.findFile(it) != null }
        val folder = docTree.createDirectory(folderName) ?: throw IOException("Couldn't create a folder there")
        try {
            from.listFiles()?.forEach { copyToDocument(context, it, folder) }
        } catch (e: Exception) {
            folder.delete()
            throw e
        }
    }

    private fun copyToDocument(context: Context, source: File, into: DocumentFile) {
        if (source.isDirectory) {
            val folder = into.findFile(source.name) ?: into.createDirectory(source.name)
                ?: throw IOException("Couldn't create ${source.name}")
            source.listFiles()?.forEach { copyToDocument(context, it, folder) }
        } else {
            val document = into.createFile("*/*", source.name) ?: throw IOException("Couldn't create ${source.name}")
            val out = context.contentResolver.openOutputStream(document.uri) ?: throw IOException("Couldn't write ${source.name}")
            out.use { stream -> source.inputStream().use { input -> input.copyTo(stream) } }
        }
    }

    private const val STOP_POLL_MILLIS = 100L
    private const val SCAN_LIMIT = 5000
}
