package com.kobblo.gallery.file.explorer.files

import android.util.Log
import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.LocalTransfer
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import com.kobblo.gallery.file.explorer.fileops.TransferEvents
import com.kobblo.gallery.file.explorer.fileops.TransferFailure
import com.kobblo.gallery.file.explorer.fileops.TransferReport
import com.kobblo.gallery.file.explorer.fileops.reasonFor
import com.kobblo.gallery.file.explorer.files.drive.DriveTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Where a copy or a move ends up. */
internal sealed interface TransferTarget {
    class OnPhone(val folder: File) : TransferTarget

    class OnDrive(val parentId: String) : TransferTarget
}

/** One copy or move, as whoever started it wants it done. */
internal class TransferRequest(
    val move: Boolean,
    val onProgress: (Float) -> Unit = {},
    /** Holds the work while it's paused, and ends it by throwing when it's cancelled. */
    val checkPause: suspend () -> Unit = {},
    val events: TransferEvents = TransferEvents.None
)

/**
 * Copy and move between any two places. The report is what really happened, item by item, and is what the
 * person gets told. See [LocalTransfer] for the rules, which the other two routes keep as well.
 */
internal class Transfers(
    private val storage: PhoneStorage,
    private val shell: ElevatedShell,
    private val drive: DriveTransfer,
    private val index: IndexUpdates,
    private val followers: PathFollowers,
    private val onFailure: (Exception) -> Unit = { Log.e("FileRepository", "Couldn't transfer an item", it) }
) {
    /** A way to carry what the file system can't carry by itself. */
    private fun interface Route {
        fun carry(path: String, move: Boolean): TransferReport
    }

    private val throughShell = ShellTransfer(shell)

    suspend fun run(sources: List<String>, destination: String, request: TransferRequest): TransferReport {
        val target = targetAt(destination)
        val events = Following(request, followers)
        val local = localTransfer(sources, request, events)
        val plain = ArrayList<File>()

        var report = TransferReport()
        for (path in sources) {
            val route = routeFor(path, target)
            if (route == null) {
                plain += File(path)
                continue
            }
            try {
                request.checkPause()
                val step = route.carry(path, request.move)
                report += step
                // Whole, by these routes' own checks. Nothing here can be half-done by a kill and then explained.
                if (step.failed.isEmpty()) events.finished(File(path), File(step.outputs.firstOrNull() ?: path))
            } catch (_: CancellationException) {
                report = report.copy(cancelled = true)
                break
            } catch (e: Exception) {
                onFailure(e)
                report += TransferReport(failed = listOf(TransferFailure(path, reasonFor(e))))
            }
        }
        if (plain.isNotEmpty() && !report.cancelled && target is TransferTarget.OnPhone) {
            report += if (request.move) local.moveInto(plain, target.folder) else local.copyInto(plain, target.folder)
        }
        currentCoroutineContext().ensureActive()
        if (report.ok) request.onProgress(1f)
        index.announce(report.outputs + report.movedFrom)
        return report
    }

    /** Recents isn't a folder, so what's pasted there goes where new things usually land. */
    private fun targetAt(destination: String): TransferTarget {
        val onDrive = DriveLocation.of(destination)
        return when {
            // Only the account in use can be pasted into.
            onDrive != null && onDrive.account == null -> TransferTarget.OnDrive(onDrive.parentId)
            destination == Places.Recent -> TransferTarget.OnPhone(storage.downloads)
            else -> TransferTarget.OnPhone(storage.folderAt(destination))
        }
    }

    /** Null where the file system will do. It's asked for each item, as the shell can come or go in between. */
    private fun routeFor(path: String, target: TransferTarget): Route? {
        if (target is TransferTarget.OnPhone && DriveLocation.idIn(path) == null) {
            if (!shell.mustHandle(path) && !shell.mustHandle(target.folder.path)) return null
            return Route { from, move -> throughShell.carry(from, target.folder, move) }
        }
        return Route { from, move -> drive.carry(from, target, move) }
    }

    private fun localTransfer(sources: List<String>, request: TransferRequest, events: TransferEvents): LocalTransfer {
        val total = sources.filter { DriveLocation.idIn(it) == null }.sumOf { SafeFiles.sizeOf(File(it)).bytes }
        var copied = 0L
        return LocalTransfer(
            checkpoint = request.checkPause,
            onBytes = { bytes ->
                copied += bytes
                if (total > 0) request.onProgress((copied.toFloat() / total).coerceAtMost(1f))
            },
            events = events
        )
    }

    /** Whatever else is listening, a lock goes where its file goes. A copy of a locked file is locked too. */
    private class Following(private val request: TransferRequest, private val followers: PathFollowers) :
        TransferEvents by request.events {
        override fun finished(source: File, dest: File) {
            if (request.move) followers.moved(source.path, dest.path) else followers.copied(source.path, dest.path)
            request.events.finished(source, dest)
        }
    }
}

/** Copies and moves what only the elevated shell can reach. A name is free when the shell says so, not when the app can't see it. */
internal class ShellTransfer(private val shell: ElevatedShell) {

    fun carry(path: String, folder: File, move: Boolean): TransferReport {
        val name = path.substringAfterLast('/')
        if (folder.path == path || folder.path.startsWith("$path/")) return failed(path, FailReason.INTO_ITSELF)
        if (move && path.substringBeforeLast('/') == folder.path) return TransferReport(done = 1, outputs = listOf(path))
        shell.run(ShellCommand.makeFolders(folder.path))
        val freeName = SafeFiles.freeName(name) { exists(File(folder, it).path) }
        val target = File(folder, freeName).path
        val done = shell.run(if (move) ShellCommand.moveTo(path, target) else ShellCommand.copyTree(path, target))
        if (!done) {
            // Nothing had this name before, so anything there now is a partial result. Unless the
            // source has gone, in which case it's all that's left.
            if (!move || exists(path)) shell.run(ShellCommand.deleteTree(target))
            return failed(path, FailReason.DENIED)
        }
        return TransferReport(
            done = 1,
            renamed = if (freeName == name) 0 else 1,
            outputs = listOf(target),
            movedFrom = if (move) listOf(path) else emptyList()
        )
    }

    private fun exists(path: String): Boolean = shell.run(ShellCommand.exists(path))

    private fun failed(path: String, reason: FailReason) = TransferReport(failed = listOf(TransferFailure(path, reason)))
}
