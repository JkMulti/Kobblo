package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.fileops.RenameProblem
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import com.kobblo.gallery.file.explorer.fileops.isUsableFileName
import java.io.File

/** Makes new files and folders on the phone, and renames what's there. Nothing that exists is ever replaced. */
internal class FileEdits(
    private val storage: PhoneStorage,
    private val shell: ElevatedShell,
    private val followers: PathFollowers
) {
    /** Whether there's a new folder called [name] at [location]. */
    fun createFolder(location: String, name: String): Boolean {
        val parent = parentAt(location)
        if (shell.mustHandle(parent)) return shell.run(ShellCommand.makeFolders(parent.removeSuffix("/") + "/" + name))
        val folder = File(parent, name)
        return !folder.exists() && folder.mkdirs()
    }

    /** Whether there's a new, empty file called [name] at [location]. */
    fun createFile(location: String, name: String): Boolean = try {
        // It says no by itself when the name is taken.
        File(parentAt(location), name).createNewFile()
    } catch (_: Exception) {
        false
    }

    private fun parentAt(location: String): String = when {
        location == Places.Home -> storage.root.absolutePath
        Places.isCard(location) -> storage.cardPath ?: location
        else -> location
    }

    /** Null when it's done. A plain rename replaces whatever already has the name, so that's checked for first. */
    fun rename(path: String, newName: String): RenameProblem? {
        if (!isUsableFileName(newName)) return RenameProblem.BAD_NAME
        val file = File(path)
        val target = File(file.parentFile ?: return RenameProblem.FAILED, newName)
        return if (shell.mustHandle(path)) renameInShell(path, target.path) else renameHere(file, target)
    }

    private fun renameInShell(from: String, to: String): RenameProblem? = when {
        shell.run(ShellCommand.exists(to)) -> RenameProblem.NAME_TAKEN
        shell.run(ShellCommand.moveTo(from, to)) -> null
        else -> RenameProblem.FAILED
    }

    private fun renameHere(file: File, target: File): RenameProblem? {
        if (!SafeFiles.existsAtAll(file)) return RenameProblem.FAILED
        // Shared storage ignores case, so changing only that finds the file itself at the new name.
        if (SafeFiles.existsAtAll(target) && !SafeFiles.sameFile(file, target)) return RenameProblem.NAME_TAKEN
        if (!file.renameTo(target)) return RenameProblem.FAILED
        followers.moved(file.path, target.path)
        return null
    }
}
