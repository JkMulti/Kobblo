package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * What the app can do through a more privileged shell, for paths it can't reach itself. Null where there's
 * no such shell.
 */
internal fun interface PrivilegedShell {
    /** Runs a command from [ShellCommand] and says whether it succeeded. */
    fun run(command: String): Boolean
}

data class BinFailure(val path: String, val reason: FailReason)

data class BinMoveReport(val moved: List<String>, val failed: List<BinFailure>) {
    val ok: Boolean get() = failed.isEmpty()
}

data class BinRestoreReport(
    val restored: List<String>,
    /** Came back under a new name, because something else now has the old one. */
    val renamed: Int,
    /** Came back somewhere other than where it was, because that was unknown. */
    val relocated: Int,
    val failed: List<BinFailure>,
    /** Where a file went that didn't come back to where it was, so whatever followed it can follow again. */
    val landedElsewhere: List<Pair<String, String>> = emptyList()
)

data class BinDeleteReport(val deleted: Int, val failed: Int) {
    /** Where what was deleted for good used to live, for whatever was keeping track of those paths. */
    var originalPaths: List<String> = emptyList()
        internal set
}

/**
 * Moves files into and out of the Bin. Every step keeps a file findable: see [BinStore] for the records, and
 * the order here for why a kill at any point loses nothing. All of it takes one lock, because several screens
 * can be doing this at once and two writers would each drop the other's entries.
 */
internal class RecycleBin(
    private val store: BinStore,
    private val shell: PrivilegedShell? = null,
    private val isRestricted: (String) -> Boolean = { false },
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** A last try at a source that wouldn't delete, by whatever other route the platform has. */
    private val removeStubborn: (File) -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Long = { Random.nextLong() },
    /** Fails between volumes, which is what sends a file on a card the long way round. */
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) {
    private val dir get() = store.dir

    /** [shouldStop] ends it between batches. What's moved by then is saved and reported, and the rest never left. */
    suspend fun moveIn(paths: List<String>, shouldStop: () -> Boolean = { false }, onProgress: (Int) -> Unit = {}): BinMoveReport = lock.withLock {
        val failed = ArrayList<BinFailure>()
        if (paths.isEmpty()) return@withLock BinMoveReport(emptyList(), failed)
        val entries = if (prepareFolder()) store.load()?.entries else null
        if (entries == null) return@withLock BinMoveReport(emptyList(), paths.map { BinFailure(it, FailReason.DENIED) })

        // Lowercase, since the folder can't tell apart names that base64 can.
        val taken = HashSet<String>((entries.size + paths.size) * 2)
        dir.list()?.forEach { taken.add(it.lowercase()) }
        val planned = ArrayList<BinEntry>(paths.size)
        for (path in paths) {
            val source = File(path)
            val attributes = SafeFiles.attributesOf(source)
            // A restricted path can't be looked at from here. The shell says if it's really there.
            if (attributes == null && !(isRestricted(path) && shell != null)) {
                failed += BinFailure(path, FailReason.MISSING)
                continue
            }
            // Selected through a file manager's view of hidden folders, this could be the Bin itself.
            if (attributes?.isDirectory == true && SafeFiles.isSameOrInside(source, dir)) {
                failed += BinFailure(path, FailReason.INTO_ITSELF)
                continue
            }
            val name = BinNames.allocate(path, random) { it.lowercase() in taken }
            taken.add(name.lowercase())
            planned += BinEntry(
                encodedName = name,
                originalPath = path,
                deletedAt = now(),
                originalName = source.name,
                isDirectory = attributes?.isDirectory == true,
                sizeBytes = if (attributes == null || attributes.isDirectory) -1L else attributes.size()
            )
        }
        try {
            store.writeJournal(planned)
        } catch (e: IOException) {
            // No record means no move. A file without one is exactly what this is here to prevent.
            val reason = reasonFor(e)
            return@withLock BinMoveReport(emptyList(), failed + planned.map { BinFailure(it.originalPath!!, reason) })
        }

        val moved = ArrayList<String>(planned.size)
        var done = failed.size
        // A batch at a time, side by side, because almost all of this is waiting on the system to rename each
        // file. It's safe here as it wouldn't be for a restore: every file already has its own name. If this
        // is cancelled partway, the journal already holds everything, and the next look at the Bin settles it.
        for (batch in planned.chunked(MOVE_BATCH)) {
            currentCoroutineContext().ensureActive()
            if (shouldStop()) break
            val reasons = coroutineScope {
                batch.map { entry ->
                    async(moving) {
                        try {
                            moveOneIn(entry.originalPath!!, entry)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            reasonFor(e)
                        }
                    }
                }.awaitAll()
            }
            for ((entry, reason) in batch.zip(reasons)) {
                if (reason == null) {
                    entries[entry.encodedName] = entry
                    moved += entry.originalPath!!
                } else {
                    failed += BinFailure(entry.originalPath!!, reason)
                }
            }
            done += batch.size
            onProgress(done)
        }
        saveQuietly(entries.values)
        BinMoveReport(moved, failed)
    }

    private suspend fun moveOneIn(path: String, entry: BinEntry): FailReason? {
        val source = File(path)
        val target = store.fileOf(entry)
        if (isRestricted(path) && shell != null) {
            if (!shell.run(ShellCommand.copyTree(path, target.path))) {
                SafeFiles.deleteTree(target)
                return FailReason.DENIED
            }
            return if (shell.run(ShellCommand.deleteTree(path))) null else {
                // The original's still there, so the copy has no reason to be.
                SafeFiles.deleteTree(target)
                FailReason.DENIED
            }
        }
        if (SafeFiles.attributesOf(source) == null) return FailReason.MISSING
        if (rename(source, target)) return null

        // A card, or another volume. It goes under a temporary name until it's whole and checked, because
        // once it has its real name the journal will say it's in the Bin.
        val size = SafeFiles.sizeOf(source)
        if (size.unreadable) return FailReason.DENIED
        if (freeSpace(dir) in 1 until size.bytes) return FailReason.NO_SPACE
        val part = SafeFiles.partFileIn(dir)
        try {
            TreeCopier(verify = true).copy(source, part)
            if (!part.renameTo(target)) return FailReason.UNKNOWN
        } catch (e: Exception) {
            SafeFiles.deleteTree(part)
            throw e
        }
        if (SafeFiles.deleteTree(source) || removeStubborn(source)) return null
        // None of it went, so the copy has no reason to be there. If only some did, the copy in the Bin is
        // now the only whole one, and it stays.
        if (SafeFiles.sizeOf(source).files < size.files) return null
        SafeFiles.deleteTree(target)
        return FailReason.DENIED
    }

    private fun prepareFolder(): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        return try {
            File(dir, BinStore.NO_MEDIA).let { it.exists() || it.createNewFile() }
        } catch (_: IOException) {
            false
        }
    }

    /** A failed save can't undo what's already happened, and the journal still holds what the list doesn't. */
    private fun saveQuietly(entries: Collection<BinEntry>) {
        try {
            store.save(entries)
        } catch (_: IOException) {
        }
    }

    /**
     * Puts files back. Nothing already at the original path is touched: the file comes back beside it with a
     * number. A file with no known home goes to [fallbackDir], which the report says.
     */
    suspend fun restore(
        names: Collection<String>,
        fallbackDir: File,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): BinRestoreReport = lock.withLock {
        val entries = store.load()?.entries
            ?: return@withLock BinRestoreReport(emptyList(), 0, 0, names.map { BinFailure(it, FailReason.DENIED) })
        val restored = ArrayList<String>()
        val failed = ArrayList<BinFailure>()
        val elsewhere = ArrayList<Pair<String, String>>()
        var renamed = 0
        var relocated = 0
        var done = 0
        try {
            for (name in names.toSet()) {
                currentCoroutineContext().ensureActive()
                if (shouldStop()) break
                val entry = entries[name] ?: continue
                try {
                    val target = targetFor(entry, fallbackDir)
                    restoreOne(entry, target)
                    entries.remove(name)
                    restored += target.path
                    if (entry.originalPath == null) {
                        relocated++
                    } else if (target.path != entry.originalPath) {
                        renamed++
                        elsewhere += entry.originalPath to target.path
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += BinFailure(entry.originalPath ?: name, reasonFor(e))
                }
                onProgress(++done)
            }
        } finally {
            withContext(NonCancellable) { saveQuietly(entries.values) }
        }
        BinRestoreReport(restored, renamed, relocated, failed, elsewhere)
    }

    private fun targetFor(entry: BinEntry, fallbackDir: File): File {
        val wanted = entry.originalPath?.let(::File) ?: File(fallbackDir, entry.originalName)
        val folder = wanted.parentFile ?: throw FileOpException(FailReason.NOT_POSSIBLE, wanted.path)
        val elevated = shell?.takeIf { isRestricted(wanted.path) }
        val name = SafeFiles.freeName(wanted.name, entry.isDirectory) { candidate ->
            val path = File(folder, candidate)
            elevated?.run(ShellCommand.exists(path.path)) ?: SafeFiles.existsAtAll(path)
        }
        return File(folder, name)
    }

    private suspend fun restoreOne(entry: BinEntry, target: File) {
        val source = store.fileOf(entry)
        val folder = target.parentFile ?: throw FileOpException(FailReason.NOT_POSSIBLE, target.path)
        if (isRestricted(target.path) && shell != null) {
            val copied = shell.run(ShellCommand.makeFolders(folder.path)) && shell.run(ShellCommand.copyTree(source.path, target.path))
            if (!copied) {
                shell.run(ShellCommand.deleteTree(target.path))
                throw FileOpException(FailReason.DENIED, target.path)
            }
            SafeFiles.deleteTree(source)
            return
        }
        if (!folder.isDirectory && !folder.mkdirs()) throw FileOpException(FailReason.DENIED, "Couldn't recreate ${folder.path}")
        if (rename(source, target)) return
        if (freeSpace(folder) in 1 until SafeFiles.sizeOf(source).bytes) throw FileOpException(FailReason.NO_SPACE, target.path)
        try {
            TreeCopier(verify = true).copy(source, target)
        } catch (e: Exception) {
            SafeFiles.deleteTree(target)
            throw e
        }
        SafeFiles.deleteTree(source)
    }

    /** Deletes for good. Anything that won't go stays listed, rather than vanishing from view and staying on the disk. */
    suspend fun deleteForever(
        names: Collection<String>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): BinDeleteReport = lock.withLock {
        val entries = store.load()?.entries ?: return@withLock BinDeleteReport(0, names.size)
        var deleted = 0
        var failed = 0
        var done = 0
        val originals = ArrayList<String>()
        try {
            for (name in names.toSet()) {
                currentCoroutineContext().ensureActive()
                if (shouldStop()) break
                val entry = entries[name] ?: continue
                if (SafeFiles.deleteTree(store.fileOf(entry))) {
                    entries.remove(name)
                    deleted++
                    entry.originalPath?.let(originals::add)
                } else {
                    failed++
                }
                onProgress(++done)
            }
        } finally {
            withContext(NonCancellable) { saveQuietly(entries.values) }
        }
        BinDeleteReport(deleted, failed).apply { originalPaths = originals }
    }

    /**
     * What to show, after clearing what's past its time. An [expiryMillis] of zero or less means never: a
     * kept period of 0 would otherwise delete the whole Bin as soon as it opened.
     */
    suspend fun list(expiryMillis: Long): List<BinEntry> = lock.withLock {
        val contents = store.load() ?: return@withLock emptyList()
        val entries = contents.entries
        val time = now()
        var changed = contents.changed
        if (expiryMillis > 0L) {
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (time - entry.deletedAt > expiryMillis && SafeFiles.deleteTree(store.fileOf(entry))) {
                    iterator.remove()
                    changed = true
                }
            }
        }
        // Dates given to found files have to stick, or their time would start again at every look.
        if (changed) saveQuietly(entries.values)
        entries.values.toList()
    }

    private companion object {
        val lock = Mutex()
        val moving = Dispatchers.IO.limitedParallelism(4)
        const val MOVE_BATCH = 32
    }
}
