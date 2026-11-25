package com.kobblo.gallery.file.explorer.fileops

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.archive.ArchiveCreateConfig
import com.kobblo.gallery.file.explorer.archive.ArchiveCreateManager
import java.io.File

/** What a stopped or finished delete took away, for screens holding a copy of the list. */
internal data class DeleteOutcome(val removed: List<String>)

/**
 * The long jobs the app has, each with its words. The rules about the files themselves stay where they were, in
 * [LocalTransfer], [RecycleBin] and the archive classes: this only runs them under [FileJobs] and reports.
 */
internal object FileJobSpecs {
    private fun folderOf(context: Context, location: String): File? = when {
        location == "drive" || location.startsWith("drive_id:") || location.startsWith("drive:") -> null
        location == "home" -> Environment.getExternalStorageDirectory()
        location == "recent" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else -> File(location)
    }

    private fun nameOf(context: Context, folder: File?): String = when {
        folder == null -> context.getString(R.string.safety_job_cloud)
        folder.path == Environment.getExternalStorageDirectory().path -> context.getString(R.string.safety_job_internal_storage)
        else -> folder.name
    }

    /** For someone who wasn't watching, who has only this to go on: which ones, not just how many. */
    private fun withNames(context: Context, text: String, paths: List<String>): String {
        val names = paths.take(SHOWN_NAMES).joinToString(", ") { it.substringAfterLast('/') }
        val list = if (paths.size > SHOWN_NAMES) {
            context.getString(R.string.safety_job_failed_names_more, names, paths.size - SHOWN_NAMES)
        } else {
            context.getString(R.string.safety_job_failed_names, names)
        }
        return "$text $list"
    }

    private const val SHOWN_NAMES = 3

    private class Journaling(private val control: JobControl) : TransferEvents {
        override fun started(source: File, dest: File) {
            control.journal.started(source.path, dest.path)
            control.setItem(source.name)
        }

        override fun verified(source: File, dest: File) = control.journal.verified(source.path, dest.path)

        override fun finished(source: File, dest: File) = control.journal.finished(source.path, dest.path)

        override fun rolledBack(source: File, dest: File) = control.journal.rolledBack(source.path, dest.path)
    }

    fun transfer(
        context: Context,
        repository: FileRepository,
        paths: List<String>,
        destLocation: String,
        move: Boolean,
        onReport: (TransferReport?) -> Unit = {}
    ): JobSpec {
        val folder = folderOf(context, destLocation)
        val name = nameOf(context, folder)
        val kind = if (move) JobKind.MOVE else JobKind.COPY
        val stoppedTitle = context.getString(if (move) R.string.safety_job_move_stopped else R.string.safety_job_copy_stopped, name)
        return JobSpec(
            kind = kind,
            surface = JobSurface.PASTE_PILL,
            title = context.getString(if (move) R.string.safety_job_moving_to else R.string.safety_job_copying_to, name),
            target = name,
            plan = JobPlan(kind, name, folder?.path, paths),
            onStopped = {
                val nothing = context.getString(R.string.safety_job_nothing_changed)
                JobResult(JobOutcome.STOPPED, stoppedTitle, nothing, nothing, folder?.path)
            },
            onFailed = { error ->
                val text = FileOpText.reason(context, reasonFor(error))
                JobResult(
                    JobOutcome.PROBLEM,
                    context.getString(if (move) R.string.safety_job_move_problem else R.string.safety_job_copy_problem, name),
                    text,
                    text,
                    folder?.path
                )
            },
            onDone = { result -> onReport(result.payload as? TransferReport) },
            work = { control ->
                val events = Journaling(control)
                val report = if (move) {
                    repository.moveFiles(paths, destLocation, control::setFraction, control::checkpoint, events)
                } else {
                    repository.copyFiles(paths, destLocation, control::setFraction, control::checkpoint, events)
                }
                transferResult(context, report, move, name, folder?.path, stoppedTitle)
            }
        )
    }

    private fun transferResult(
        context: Context,
        report: TransferReport,
        move: Boolean,
        name: String,
        folder: String?,
        stoppedTitle: String
    ): JobResult {
        val problem = FileOpText.transferProblem(context, report, move)
        return when {
            report.cancelled -> JobResult(JobOutcome.STOPPED, stoppedTitle, problem, problem, folder, report)
            report.failed.isNotEmpty() -> JobResult(
                JobOutcome.PROBLEM,
                context.getString(if (move) R.string.safety_job_move_problem else R.string.safety_job_copy_problem, name),
                withNames(context, problem, report.failed.map { it.path }),
                problem,
                folder,
                report
            )
            else -> {
                val title = context.resources.getQuantityString(
                    if (move) R.plurals.safety_job_moved else R.plurals.safety_job_copied,
                    report.done,
                    report.done,
                    name
                )
                JobResult(
                    JobOutcome.DONE,
                    title,
                    problem.ifBlank { null },
                    if (problem.isBlank()) title else context.getString(R.string.app_sentences_joined, title, problem),
                    folder,
                    report
                )
            }
        }
    }

    /**
     * @param toBin what goes to Recently Deleted, or [forGood] if the Bin is off. [emptied] are empty folders from
     * the Cleaner, which just go. [refused] were turned down before getting here, and are reported the same way.
     */
    fun delete(
        context: Context,
        repository: FileRepository,
        toBin: List<String>,
        forGood: List<String>,
        emptied: List<String>,
        refused: List<BinFailure>,
        doneMessage: String?,
        onFinished: (List<String>) -> Unit = {}
    ): JobSpec {
        val all = emptied + toBin + forGood
        val kind = if (forGood.isEmpty() && emptied.isEmpty()) JobKind.TRASH else JobKind.DELETE
        val folder = all.firstOrNull()?.substringBeforeLast('/', "")?.ifEmpty { null }
        return JobSpec(
            kind = kind,
            surface = JobSurface.BAR,
            title = context.getString(R.string.safety_deleting),
            total = all.size,
            plan = JobPlan(kind, "", folder, all),
            writes = emptyList(),
            onStopped = {
                val text = context.getString(R.string.safety_job_nothing_changed)
                JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_delete_stopped), text, text, folder)
            },
            onFailed = { error ->
                val text = FileOpText.reason(context, reasonFor(error))
                JobResult(JobOutcome.PROBLEM, context.getString(R.string.safety_job_delete_problem), text, text, folder)
            },
            onDone = { result -> onFinished((result.payload as? DeleteOutcome)?.removed.orEmpty()) },
            work = { control ->
                val removed = ArrayList<String>(all.size)
                val failed = ArrayList(refused)
                var dealtWith = 0
                for (path in emptied) {
                    if (control.stopRequested) break
                    // Refuses anything with a file in it, so what arrived since it was listed is safe.
                    if (File(path).delete()) removed += path else failed += BinFailure(path, FailReason.DENIED)
                    control.setCount(++dealtWith)
                }
                val before = dealtWith
                if (toBin.isNotEmpty() && !control.stopRequested) {
                    val report = repository.moveToTrash(toBin, { control.stopRequested }) { control.setCount(before + it) }
                    removed += report.moved
                    failed += report.failed
                }
                if (forGood.isNotEmpty() && !control.stopRequested) {
                    var reached = 0
                    val gone = repository.deleteAllRestricted(forGood, { control.stopRequested }) { count ->
                        reached = count
                        control.setCount(before + count)
                    }
                    removed += gone
                    val goneSet = gone.toSet()
                    forGood.take(reached).filterNot { it in goneSet }.forEach { failed += BinFailure(it, FailReason.DENIED) }
                }
                val outcome = DeleteOutcome(removed)
                when {
                    control.stopRequested -> {
                        val text = context.getString(R.string.safety_job_delete_partly, removed.size, all.size)
                        JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_delete_stopped), text, text, folder, outcome)
                    }
                    failed.isNotEmpty() -> {
                        val text = FileOpText.deleteFailed(context, removed.size, failed)
                        JobResult(
                            JobOutcome.PROBLEM,
                            context.getString(R.string.safety_job_delete_problem),
                            withNames(context, text, failed.map { it.path }),
                            text,
                            folder,
                            outcome
                        )
                    }
                    else -> {
                        val title = context.resources.getQuantityString(
                            if (kind == JobKind.TRASH) R.plurals.safety_job_trashed else R.plurals.safety_job_deleted,
                            removed.size,
                            removed.size
                        )
                        JobResult(JobOutcome.DONE, title, null, doneMessage, folder, outcome)
                    }
                }
            }
        )
    }

    fun restore(context: Context, repository: FileRepository, names: List<String>): JobSpec {
        val fallback = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        return JobSpec(
            kind = JobKind.RESTORE,
            surface = JobSurface.BAR,
            title = context.getString(R.string.safety_restoring),
            total = names.size,
            plan = JobPlan(JobKind.RESTORE, "", null, names.map(repository::binPathOf)),
            writes = emptyList(),
            onStopped = {
                val text = context.getString(R.string.safety_job_nothing_changed)
                JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_restore_stopped), text, text)
            },
            onFailed = { error ->
                val text = FileOpText.reason(context, reasonFor(error))
                JobResult(JobOutcome.PROBLEM, context.getString(R.string.safety_job_restore_problem), text, text)
            },
            work = { control ->
                val report = repository.restoreFromTrash(names, { control.stopRequested }) { control.setCount(it) }
                val text = FileOpText.restored(context, report).ifBlank { null }
                val folder = report.restored.firstOrNull()?.substringBeforeLast('/', "")?.ifEmpty { null } ?: fallback
                when {
                    control.stopRequested -> {
                        val partly = context.getString(R.string.safety_job_restore_partly, report.restored.size, names.size)
                        JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_restore_stopped), partly, partly, folder)
                    }
                    report.failed.isNotEmpty() -> JobResult(
                        JobOutcome.PROBLEM,
                        context.getString(R.string.safety_job_restore_problem),
                        withNames(context, text.orEmpty(), report.failed.map { it.path }),
                        text,
                        folder
                    )
                    else -> JobResult(JobOutcome.DONE, text ?: context.getString(R.string.safety_job_restore_done), null, text, folder)
                }
            }
        )
    }

    fun emptyBin(context: Context, repository: FileRepository, names: List<String>): JobSpec = JobSpec(
        kind = JobKind.EMPTY_BIN,
        surface = JobSurface.BAR,
        title = context.getString(R.string.safety_deleting),
        total = names.size,
        plan = JobPlan(JobKind.EMPTY_BIN, "", null, names.map(repository::binPathOf)),
        writes = emptyList(),
        onStopped = {
            val text = context.getString(R.string.safety_job_nothing_changed)
            JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_delete_stopped), text, text)
        },
        onFailed = { error ->
            val text = FileOpText.reason(context, reasonFor(error))
            JobResult(JobOutcome.PROBLEM, context.getString(R.string.safety_job_delete_problem), text, text)
        },
        work = { control ->
            val report = repository.permanentlyDeleteTrash(names, { control.stopRequested }) { control.setCount(it) }
            when {
                control.stopRequested -> {
                    val text = context.getString(R.string.safety_job_empty_partly, report.deleted, names.size)
                    JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_delete_stopped), text, text)
                }
                report.failed > 0 -> {
                    val text = FileOpText.binDeleteFailed(context, report)
                    JobResult(JobOutcome.PROBLEM, context.getString(R.string.safety_job_delete_problem), text, text)
                }
                else -> JobResult(
                    JobOutcome.DONE,
                    context.resources.getQuantityString(R.plurals.safety_job_deleted, report.deleted, report.deleted)
                )
            }
        }
    )

    /** Downloads sorted into folders. It's a move like any other, with several places to go. */
    fun organize(
        context: Context,
        repository: FileRepository,
        groups: List<Pair<List<String>, String>>
    ): JobSpec {
        val label = context.getString(R.string.safety_job_organizing)
        val sources = groups.flatMap { it.first }
        return JobSpec(
            kind = JobKind.ORGANIZE,
            surface = JobSurface.BAR,
            title = label,
            total = sources.size,
            plan = JobPlan(JobKind.ORGANIZE, label, null, sources),
            writes = groups.map { it.second },
            onStopped = {
                val text = context.getString(R.string.safety_job_nothing_changed)
                JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_organize_stopped), text, text)
            },
            onFailed = { error ->
                val reason = FileOpText.reason(context, reasonFor(error)).replaceFirstChar { it.uppercase() }
                JobResult(
                    JobOutcome.PROBLEM,
                    context.getString(R.string.safety_job_organize_problem),
                    reason,
                    context.getString(R.string.app_sentence_with_reason, context.getString(R.string.polish_organize_failed), reason)
                )
            },
            work = { control ->
                var report = TransferReport()
                var done = 0
                val events = Journaling(control)
                for ((paths, dest) in groups) {
                    if (paths.isEmpty()) continue
                    File(dest).mkdirs()
                    report += repository.moveFiles(paths, dest, {}, control::checkpoint, events)
                    done += paths.size
                    control.setCount(done)
                    if (report.cancelled) break
                }
                val problem = FileOpText.transferProblem(context, report, move = true)
                val organized = context.getString(R.string.safety_job_organized)
                when {
                    report.cancelled -> JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_organize_stopped), problem, problem)
                    report.failed.isNotEmpty() -> JobResult(
                        JobOutcome.PROBLEM,
                        context.getString(R.string.safety_job_organize_problem),
                        withNames(context, problem, report.failed.map { it.path }),
                        problem
                    )
                    else -> JobResult(JobOutcome.DONE, organized, problem.ifBlank { null }, if (problem.isBlank()) organized else context.getString(R.string.app_sentences_joined, organized, problem))
                }
            }
        )
    }

    /**
     * Files going into the Vault. What's being added stays out of the notification and out of the note: what's
     * written down says only that this happened, because the Vault's whole point is that nothing else knows.
     */
    fun vaultImport(
        context: Context,
        count: Int,
        import: (shouldStop: () -> Boolean) -> Int,
        onFinished: (Int) -> Unit
    ): JobSpec {
        val title = context.getString(R.string.safety_job_vault_adding)
        return JobSpec(
            kind = JobKind.VAULT,
            surface = JobSurface.OWN,
            title = title,
            plan = JobPlan(JobKind.VAULT, ""),
            reads = emptyList(),
            writes = emptyList(),
            onStopped = { JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_vault_stopped)) },
            onFailed = { JobResult(JobOutcome.PROBLEM, context.getString(R.string.safety_job_vault_problem)) },
            onDone = { result -> onFinished(result.payload as? Int ?: 0) },
            work = { control ->
                val added = import { control.stopRequested }
                JobResult(
                    if (control.stopRequested) JobOutcome.STOPPED else JobOutcome.DONE,
                    context.resources.getQuantityString(R.plurals.safety_job_vault_added, added, added),
                    payload = added
                )
            }
        )
    }

    fun compress(
        context: Context,
        sources: List<File>,
        destination: File,
        config: ArchiveCreateConfig,
        watched: Boolean
    ): JobSpec {
        val name = destination.name
        val folder = destination.parent
        return JobSpec(
            kind = JobKind.COMPRESS,
            // The dialog stops everything else, which is fine when it's what was just asked for and not when
            // it opens by itself out of a queue.
            surface = if (watched) JobSurface.COMPRESS_DIALOG else JobSurface.BAR,
            title = context.getString(R.string.safety_job_compressing, name),
            target = name,
            plan = JobPlan(JobKind.COMPRESS, name, folder, sources.map { it.path }),
            onStopped = {
                val text = context.getString(R.string.safety_job_no_archive)
                JobResult(JobOutcome.STOPPED, context.getString(R.string.safety_job_compress_stopped, name), text, text, folder)
            },
            onFailed = { error ->
                val text = FileOpText.reason(context, reasonFor(error))
                JobResult(
                    JobOutcome.PROBLEM,
                    context.getString(R.string.safety_job_compress_problem, name),
                    text.replaceFirstChar { it.uppercase() },
                    context.getString(R.string.app_title_with_reason, context.getString(R.string.safety_job_compress_problem, name), text),
                    folder
                )
            },
            work = { control ->
                val made = ArchiveCreateManager.createArchive(
                    sourceFiles = sources,
                    destinationFile = destination,
                    config = config,
                    onProgress = { percent, item ->
                        control.setFraction(percent / 100f)
                        control.setItem(if (item == ArchiveCreateManager.DoneItem) context.getString(R.string.done_action) else item)
                    },
                    isCancelled = { control.stopRequested },
                    onBuilding = { part, final -> control.journal.building(part.path, final.path) }
                )
                try {
                    MediaScannerConnection.scanFile(context, arrayOf(made.path), null, null)
                } catch (_: Exception) {
                }
                JobResult(JobOutcome.DONE, context.getString(R.string.safety_job_compressed, made.name), null, null, made.parent, made)
            }
        )
    }
}
