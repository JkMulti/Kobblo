package com.kobblo.gallery.file.explorer.compat

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * A walk of storage the way the Cleaner counts it: nothing hidden, and nothing under Android/data or Android/obb.
 * One stat per entry, and nothing about a file is kept here. Android 8 to 10 have no trustworthy index, so there
 * this is what every category total and list comes from. It's only file paths, so it can be tested off the device.
 */
internal object StorageWalk {
    fun walk(
        root: Path,
        checkCancelled: () -> Unit,
        onFile: (path: String, size: Long, modifiedMillis: Long) -> Unit,
        onEmptyFolder: (path: String, modifiedMillis: Long) -> Unit
    ) {
        class OpenFolder(val modified: Long) { var entries = 0 }
        val open = ArrayDeque<OpenFolder>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                open.lastOrNull()?.let { it.entries++ }
                if (dir != root) {
                    val path = dir.toString()
                    if (dir.fileName.toString().startsWith(".") || path.contains("/Android/data") || path.contains("/Android/obb")) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                }
                open.addLast(OpenFolder(attrs.lastModifiedTime().toMillis()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                open.lastOrNull()?.let { it.entries++ }
                if (attrs.isRegularFile && !file.fileName.toString().startsWith(".")) {
                    onFile(file.toString(), attrs.size(), attrs.lastModifiedTime().toMillis())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Something unreadable is still something in its parent, so that parent isn't empty.
                open.lastOrNull()?.let { it.entries++ }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val folder = open.removeLast()
                if (exc == null && folder.entries == 0 && dir != root) onEmptyFolder(dir.toString(), folder.modified)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
