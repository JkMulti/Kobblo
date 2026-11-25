package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class TransferFailure(val path: String, val reason: FailReason)

/**
 * Hears each top-level item as it goes. It's only ever told what happened: nothing here can change how an item
 * is copied, and the outcome is still all in the report. It's what a kill leaves a record of.
 */
internal interface TransferEvents {
    fun started(source: File, dest: File) {}

    /** The copy is whole and read back, and the original is about to go. */
    fun verified(source: File, dest: File) {}

    fun finished(source: File, dest: File) {}

    /** It failed, and what it had made has been removed. */
    fun rolledBack(source: File, dest: File) {}

    object None : TransferEvents
}

/**
 * How a paste went, so what the person is told is what happened. [outputs] are the finished files and
 * folders under their final names, for the index to hear about.
 */
data class TransferReport(
    val done: Int = 0,
    /** Arrived under a new name because the one they had was taken. */
    val renamed: Int = 0,
    val failed: List<TransferFailure> = emptyList(),
    /** Links inside a folder that couldn't be recreated where the folder went. */
    val linksSkipped: Int = 0,
    val cancelled: Boolean = false,
    val outputs: List<String> = emptyList(),
    /** What a move took away, for the index to forget. */
    val movedFrom: List<String> = emptyList()
) {
    val ok: Boolean get() = failed.isEmpty() && !cancelled

    operator fun plus(other: TransferReport) = TransferReport(
        done + other.done,
        renamed + other.renamed,
        failed + other.failed,
        linksSkipped + other.linksSkipped,
        cancelled || other.cancelled,
        outputs + other.outputs,
        movedFrom + other.movedFrom
    )
}

/**
 * Copies and moves between folders the app can reach itself. The rules live here so they can't differ
 * from one caller to the next:
 *
 * - Nothing is overwritten. A name that's taken gets a number, the way the gallery's albums already work.
 * - A folder can't go into itself, and a file put in its own folder is either a copy with a new name or,
 *   for a move, nothing at all.
 * - A move only removes the source after a copy has been read back and matched.
 * - If anything goes wrong the partial result goes and the source stays.
 * - A cancel removes the item in progress. A copy also removes what it finished, which can only be files it
 *   made, but a finished move stays moved, since that's now the only copy.
 */
internal class LocalTransfer(
    private val checkpoint: suspend () -> Unit = {},
    private val onBytes: (Long) -> Unit = {},
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val openInput: (File) -> InputStream = { java.io.FileInputStream(it) },
    private val openOutput: (File) -> OutputStream = { java.io.FileOutputStream(it) },
    /** Fails between volumes, which is the whole reason a move can need to copy. */
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    private val events: TransferEvents = TransferEvents.None
) {
    suspend fun copyInto(sources: List<File>, destDir: File): TransferReport {
        ensureFolder(destDir)?.let { reason ->
            return TransferReport(failed = sources.map { TransferFailure(it.path, reason) })
        }
        val created = ArrayList<File>()
        var report = TransferReport()
        val copier = copier(verify = false)
        for (source in sources) {
            try {
                checkpoint()
                val step = copyOne(source, destDir, copier, created)
                report += step
            } catch (e: CancellationException) {
                created.forEach { SafeFiles.deleteTree(it) }
                return TransferReport(cancelled = true, linksSkipped = copier.linksSkipped)
            } catch (e: Exception) {
                report += TransferReport(failed = listOf(TransferFailure(source.path, reasonFor(e))))
            }
        }
        return report.copy(linksSkipped = copier.linksSkipped)
    }

    private suspend fun copyOne(source: File, destDir: File, copier: TreeCopier, created: MutableList<File>): TransferReport {
        val attributes = SafeFiles.attributesOf(source) ?: throw FileOpException(FailReason.MISSING, source.path)
        if (attributes.isDirectory && SafeFiles.isSameOrInside(source, destDir)) {
            throw FileOpException(FailReason.INTO_ITSELF, source.path)
        }
        val size = SafeFiles.sizeOf(source)
        requireRoom(destDir, size.bytes)
        val name = SafeFiles.freeName(source.name, attributes.isDirectory) { SafeFiles.existsAtAll(File(destDir, it)) }
        val dest = File(destDir, name)
        created += dest
        events.started(source, dest)
        try {
            copier.copy(source, dest)
        } catch (e: Exception) {
            // Nothing was at this name before, so all of it is this copy's own.
            SafeFiles.deleteTree(dest)
            created -= dest
            events.rolledBack(source, dest)
            throw e
        }
        events.finished(source, dest)
        return TransferReport(done = 1, renamed = if (name != source.name) 1 else 0, outputs = listOf(dest.path))
    }

    suspend fun moveInto(sources: List<File>, destDir: File): TransferReport {
        ensureFolder(destDir)?.let { reason ->
            return TransferReport(failed = sources.map { TransferFailure(it.path, reason) })
        }
        var report = TransferReport()
        val copier = copier(verify = true)
        for (source in sources) {
            try {
                checkpoint()
                report += moveOne(source, destDir, copier)
            } catch (e: CancellationException) {
                return report.copy(cancelled = true, linksSkipped = copier.linksSkipped)
            } catch (e: Exception) {
                report += TransferReport(failed = listOf(TransferFailure(source.path, reasonFor(e))))
            }
        }
        return report.copy(linksSkipped = copier.linksSkipped)
    }

    private suspend fun moveOne(source: File, destDir: File, copier: TreeCopier): TransferReport {
        val attributes = SafeFiles.attributesOf(source) ?: throw FileOpException(FailReason.MISSING, source.path)
        // Already where it's going. Saying so as a success leaves nothing for the person to do.
        if (source.parentFile?.let { SafeFiles.sameFile(it, destDir) } == true) {
            return TransferReport(done = 1, outputs = listOf(source.path))
        }
        if (attributes.isDirectory && SafeFiles.isSameOrInside(source, destDir)) {
            throw FileOpException(FailReason.INTO_ITSELF, source.path)
        }
        val name = SafeFiles.freeName(source.name, attributes.isDirectory) { SafeFiles.existsAtAll(File(destDir, it)) }
        val dest = File(destDir, name)
        val done = TransferReport(
            done = 1,
            renamed = if (name != source.name) 1 else 0,
            outputs = listOf(dest.path),
            movedFrom = listOf(source.path)
        )
        val size = SafeFiles.sizeOf(source)
        events.started(source, dest)
        if (rename(source, dest)) {
            onBytes(size.bytes)
            events.finished(source, dest)
            return done
        }
        // Another volume, or a rename the file system refused, which leaves copying.
        try {
            if (size.unreadable) throw FileOpException(FailReason.DENIED, "Part of ${source.name} can't be read")
            requireRoom(destDir, size.bytes)
            copier.copy(source, dest)
        } catch (e: Exception) {
            SafeFiles.deleteTree(dest)
            events.rolledBack(source, dest)
            throw e
        }
        // From here the copy is good and is kept whatever happens to the original.
        events.verified(source, dest)
        val gone = SafeFiles.deleteTree(source)
        events.finished(source, dest)
        if (!gone) {
            return done.copy(failed = listOf(TransferFailure(source.path, FailReason.DENIED)), movedFrom = emptyList())
        }
        return done
    }

    private fun copier(verify: Boolean) = TreeCopier(verify, checkpoint, onBytes, openInput, openOutput)

    private fun ensureFolder(folder: File): FailReason? = when {
        folder.isDirectory -> {
            SafeFiles.clearStaleParts(folder)
            null
        }
        folder.mkdirs() -> null
        else -> FailReason.DENIED
    }

    /** Stops before writing a byte, rather than filling the disk to find out. Zero means the system doesn't know. */
    private fun requireRoom(destDir: File, needed: Long) {
        val free = freeSpace(destDir)
        if (free in 1 until needed) throw FileOpException(FailReason.NO_SPACE, "Needs $needed bytes, $free free")
    }
}
