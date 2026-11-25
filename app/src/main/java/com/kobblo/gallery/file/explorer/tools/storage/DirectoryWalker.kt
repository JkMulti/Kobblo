package com.kobblo.gallery.file.explorer.tools.storage

import java.io.File
import java.nio.file.Files

/** What a directory holds, all the way down. */
data class DirectorySize(
    val bytes: Long,
    val files: Int,
    val folders: Int
) {
    operator fun plus(other: DirectorySize) =
        DirectorySize(bytes + other.bytes, files + other.files, folders + other.folders)

    companion object {
        val Zero = DirectorySize(0, 0, 0)
    }
}

/**
 * Adds up a directory recursively. Written without Android types so it can be tested on the JVM.
 *
 * Symbolic links aren't followed: one pointing back up a tree would never finish, and what it points
 * to is counted where it really lives.
 */
object DirectoryWalker {

    /**
     * @param checkCancelled called once per directory, and expected to throw to stop the walk.
     * @param onDirectory receives every directory's own total as the walk finishes it, deepest
     * first, which is what lets a cache answer for folders inside this one afterwards.
     */
    fun measure(
        root: File,
        checkCancelled: () -> Unit = {},
        onDirectory: (File, DirectorySize) -> Unit = { _, _ -> }
    ): DirectorySize {
        checkCancelled()
        val children = root.listFiles() ?: return DirectorySize.Zero.also { onDirectory(root, it) }
        var total = DirectorySize.Zero
        for (child in children) {
            total += if (child.isDirectory) {
                if (isSymlink(child)) DirectorySize.Zero
                else measure(child, checkCancelled, onDirectory) + DirectorySize(0, 0, 1)
            } else {
                DirectorySize(child.length(), 1, 0)
            }
        }
        onDirectory(root, total)
        return total
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        false
    }
}
