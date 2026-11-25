package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.fileops.LockGate
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.kobblo.gallery.file.explorer.music.MusicLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * What the lock screen is asked to check for.
 *
 * @param row the file's id, when it's in the list on screen.
 * @param name what to call it once it opens, when that isn't its file's name.
 */
internal class LockQuestion(val reason: AuthReason, val path: String, val row: Int?, val name: String?)

/** This state with the lock screen asking [question], or asking nothing. */
internal fun AppState.asking(question: LockQuestion?): AppState = copy(
    authRequestReason = question?.reason,
    authRequestPath = question?.path,
    authRequestFileId = question?.row,
    authRequestFolderName = question?.name
)

/**
 * Locking files and folders, and what happens once the lock has been entered. The lock screen is what checks
 * it; this is only told.
 *
 * @param open opens a location under a name.
 * @param reload reads the open location again.
 */
internal class ItemLocks(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository,
    private val open: (path: String, name: String) -> Unit,
    private val reload: () -> Unit
) {
    fun ask(question: LockQuestion) = live.update { it.asking(question) }

    fun cancel() {
        LockGate.cancelled()
        live.update { it.asking(null) }
    }

    /** The lock was entered, or newly set, for [question]. The lock screen stays until this has been dealt with. */
    fun entered(question: LockQuestion) {
        scope.launch {
            when (question.reason) {
                AuthReason.LOCK_FILE -> lock(question.path)
                AuthReason.UNLOCK_FILE -> unlock(question.path)
                AuthReason.CONFIRM_ACTION -> LockGate.confirmed()
                AuthReason.OPEN_FILE -> openLocked(question.path, question.row, question.name)
            }
            cancel()
        }
    }

    private suspend fun lock(path: String) {
        setLocked(path, true)
        // Says what it did, and no more than that. It's the only place anyone is told.
        messages.show(words.text(R.string.safety_lock_done))
    }

    private suspend fun unlock(path: String) {
        if (path !in repository.getLockedFiles()) {
            // Its lock is on a folder above it, and only that can be taken off.
            messages.show(words.text(R.string.safety_lock_in_folder))
            return
        }
        setLocked(path, false)
        messages.show(words.text(R.string.polish_unlocked))
    }

    private fun setLocked(path: String, locked: Boolean) {
        repository.setLocked(path, locked)
        reload()
        MusicLibrary.get(app).refresh()
    }

    private fun openLocked(path: String, row: Int?, name: String?) {
        val listed = row?.let { id -> live.now.files.firstOrNull { it.id == id } }
        val file = File(path)
        when {
            listed?.type == "folder" || file.isDirectory -> {
                Locks.openAllOver(app, path)
                open(path, name ?: listed?.name ?: file.name)
            }
            listed != null -> offer(listed)
            file.exists() -> offer(unlisted(file, path, name))
            // Not on the phone, so it can only be somewhere that's opened by its location.
            name != null -> open(path, name)
        }
    }

    private fun offer(file: FileItem) = live.update { it.copy(unlockedFileToOpen = file) }

    /** A locked file reached from outside the list on screen. */
    private fun unlisted(file: File, path: String, name: String?) = FileItem(
        id = path.hashCode(),
        path = path,
        name = name ?: file.name,
        type = "file",
        kind = "file",
        size = FileSizes.label(file.length()),
        changed = "",
        owner = ""
    )

    fun opened() = live.update { it.copy(unlockedFileToOpen = null) }
}
