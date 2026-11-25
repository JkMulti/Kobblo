package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.RecentActions
import com.kobblo.gallery.file.explorer.fileops.FileOpText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Making files and folders, and renaming them.
 *
 * @param load reads a location.
 * @param reopen goes to the open location again.
 */
internal class FileNames(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val repository: FileRepository,
    private val jobs: JobDesk,
    private val selection: Selection,
    private val load: (String) -> Unit,
    private val reopen: () -> Unit
) {
    fun createFolder(name: String) = create(name, repository::createFolder)

    fun createFile(name: String) = create(name, repository::createFile)

    private fun create(name: String, make: suspend (folder: String, name: String) -> Boolean) {
        scope.launch {
            val folder = live.now.location
            if (make(folder, name)) load(folder) else messages.post(words.text(R.string.safety_create_failed, name))
        }
    }

    /** Renames one file, which the viewer may be showing. */
    fun rename(path: String, newName: String) {
        if (jobs.turnsDown(listOf(path))) return
        scope.launch {
            val problem = repository.renameChecked(path, newName)
            if (problem != null) {
                messages.post(FileOpText.renameFailed(app, problem, newName))
                return@launch
            }
            val newPath = beside(path, newName)
            repository.logRecentAction(newPath, RecentActions.renamedFrom(File(path).name))
            // Renamed from the viewer, it's still the photo being looked at.
            live.update {
                val viewed = it.viewingFile
                if (viewed?.path == path) it.copy(viewingFile = viewed.copy(path = newPath, name = newName)) else it
            }
            reopen()
        }
    }

    fun showRenaming(show: Boolean) = live.update { it.copy(showBatchRenameDialog = show) }

    /** Gives everything chosen the same name with a number each, in the order of the names they had. */
    fun renameChosen(naming: NumberedNames) {
        scope.launch {
            val files = selection.files().sortedBy { it.name }
            if (jobs.turnsDown(files.map { it.path })) return@launch
            val allDone = files.withIndex().map { (index, file) -> renameTo(file, naming.nameAt(index)) }.all { it }
            selection.none()
            showRenaming(false)
            reopen()
            if (!allDone) messages.post(words.text(R.string.app_rename_some_failed))
        }
    }

    private suspend fun renameTo(file: FileItem, newName: String): Boolean {
        if (!repository.renameFile(file.path, newName)) return false
        repository.logRecentAction(beside(file.path, newName), RecentActions.renamedFrom(file.name))
        return true
    }

    private fun beside(path: String, name: String) = File(path).parentFile?.absolutePath + "/" + name
}

/**
 * The names a batch rename gives, one after another.
 *
 * @param style how the number is written, as the sheet names it: `None`, `(1)` or `0001`. Any other is
 * written plainly.
 */
internal class NumberedNames(
    private val baseName: String,
    private val extension: String,
    private val padding: Int,
    private val startNumber: Int,
    private val isPrefix: Boolean,
    private val style: String
) {
    fun nameAt(index: Int): String {
        val name = numbered(startNumber + index)
        val ending = if (extension.isEmpty()) "" else "." + extension.removePrefix(".")
        return (name + ending).trim()
    }

    private fun numbered(number: Int): String {
        if (style == "None") return baseName
        val digits = number.toString().padStart(padding.coerceAtLeast(0), '0')
        val label = if (style == "(1)") "($digits)" else digits
        val joint = if (style == "0001") "_" else " "
        return if (isPrefix) label + joint + baseName else baseName + joint + label
    }
}
