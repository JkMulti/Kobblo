package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.StorageTally
import com.kobblo.gallery.file.explorer.compat.StorageWalk
import com.kobblo.gallery.file.explorer.fileops.LockedPaths
import java.io.File
import java.util.Collections

/** A phone whose storage is a folder, and whose card, if it has one, is another. */
internal class TestStorage(
    override val root: File,
    override val cardPath: String? = null,
    override val space: StorageSpace = StorageSpace(1_000_000L, 400_000L)
) : PhoneStorage {
    override val downloads: File get() = File(root, "Download")

    override val busyFolders: List<File> get() = listOf(downloads, root)

    override suspend fun walk(
        onFile: (path: String, size: Long, modifiedMillis: Long) -> Unit,
        onEmptyFolder: (path: String, modifiedMillis: Long) -> Unit
    ) = StorageWalk.walk(root.toPath(), checkCancelled = {}, onFile, onEmptyFolder)
}

/** The computer's own shell in place of Shizuku's. It runs the commands for real, so use it on temporary folders. */
internal class TestShell(override var isReady: Boolean = true) : ElevatedShell {
    val commands: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Commands that fail without being run. */
    var refuses: (String) -> Boolean = { false }

    private fun start(command: String): Process {
        check(isReady) { "Nothing should use a shell that isn't there" }
        commands += command
        return ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
    }

    override fun run(command: String): Boolean = when {
        refuses(command) -> {
            commands += command
            false
        }
        else -> start("( $command ) >/dev/null 2>&1").waitFor() == 0
    }

    override fun linesOf(command: String): List<String>? = start(command).let { process ->
        process.inputStream.bufferedReader().readLines().also { process.waitFor() }
    }

    override fun runForOutput(command: String): Pair<Boolean, String> = start(command).let { process ->
        val said = process.inputStream.bufferedReader().readText()
        (process.waitFor() == 0) to said
    }
}

/** Remembers what the index was told. */
internal class TestIndex(var onDelete: (String) -> Unit = {}) : IndexUpdates, IndexQueries {
    val announced = ArrayList<String>()
    val forgotten = ArrayList<String>()
    val rescanned = ArrayList<String>()
    val deletedThrough = ArrayList<String>()

    /** What's in the index, or null for a phone whose index can't be trusted. */
    var files: List<IndexedFile>? = null
    var conditions = ArrayList<String>()

    override fun announce(paths: List<String>) {
        announced += paths
    }

    override fun rescan(path: String) {
        rescanned += path
    }

    override fun forget(paths: List<String>) {
        forgotten += paths
    }

    override fun deleteThrough(path: String) {
        deletedThrough += path
        onDelete(path)
    }

    override suspend fun forEachFile(condition: String, visit: (IndexedFile) -> Unit): Boolean {
        conditions += condition
        files?.forEach(visit) ?: return false
        return true
    }

    override suspend fun tally(): StorageTally? = null

    override fun folder(path: String): List<FileItem> = emptyList()
}

/** Remembers what the locks and favorites were told, in one list, as "moved a to b". */
internal class TestFollowers : PathFollowers {
    val heard = ArrayList<String>()

    override fun moved(from: String, to: String) {
        heard += "moved $from to $to"
    }

    override fun copied(from: String, to: String) {
        heard += "copied $from to $to"
    }

    override fun forget(path: String) {
        heard += "forgot $path"
    }
}

internal fun marks(favorites: Set<String> = emptySet(), locks: Set<String> = emptySet()) = PathMarks(favorites, LockedPaths(locks))

internal fun testRows() = LocalRows { count -> "$count items" }

/** Makes a file with its folders, and returns it. */
internal fun File.with(name: String, text: String = "contents of $name"): File =
    resolve(name).also { it.parentFile?.mkdirs(); it.writeText(text) }
