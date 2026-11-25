package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Deletes files on the phone for good, by whichever route is allowed to. */
internal class FileDeletion(
    private val storage: PhoneStorage,
    private val shell: ElevatedShell,
    private val index: IndexUpdates,
    private val followers: PathFollowers
) {
    /** Whether it's gone. For a large folder this takes a while. */
    fun delete(path: String): Boolean {
        val gone = remove(path)
        index.rescan(path)
        return gone
    }

    /**
     * Deletes many and says which went. The index hears in one go at the end, because a scan for each path is
     * a queue of thousands of trips to the system's process, still running long after this is done.
     */
    suspend fun deleteAll(paths: List<String>, shouldStop: () -> Boolean, onProgress: (Int) -> Unit): List<String> {
        val job = currentCoroutineContext()
        val gone = ArrayList<String>(paths.size)
        for ((position, path) in paths.withIndex()) {
            job.ensureActive()
            if (shouldStop()) break
            if (remove(path)) gone += path
            onProgress(position + 1)
        }
        gone.forEach(followers::forget)
        index.gone(gone)
        return gone
    }

    private fun remove(path: String): Boolean {
        if (isTopOfStorage(path)) return false
        if (shell.mustHandle(path)) return shell.run(ShellCommand.deleteTree(path))
        val file = File(path)
        return SafeFiles.deleteTree(file) || removeByOtherRoute(file)
    }

    /** Nothing on screen offers these. Nothing should be able to reach them by accident either. */
    private fun isTopOfStorage(path: String): Boolean =
        path.trimEnd('/').let { it in TopsOfStorage || it == storage.root.path }

    /** The system's index can delete what the file API won't, and so can the shell. */
    fun removeByOtherRoute(file: File): Boolean {
        index.deleteThrough(file.path)
        if (SafeFiles.existsAtAll(file) && shell.isReady) shell.run(ShellCommand.deleteTree(file.path))
        return !SafeFiles.existsAtAll(file)
    }

    private companion object {
        val TopsOfStorage = setOf("", "/storage", "/storage/emulated")
    }
}
