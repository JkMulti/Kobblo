package com.kobblo.gallery.file.explorer.transfer.core

import java.io.File
import java.nio.file.Files

/** Lists a folder's files with the paths they keep on the other side. */
object FolderWalker {
    data class Entry(val file: File, val relativePath: String)

    /**
     * Every readable file under [folder], named from the folder itself: sending `Camera` gives
     * `Camera/a.jpg` and `Camera/trip/b.jpg`. Symlinks are skipped, so a link can't pull in something
     * outside what was chosen. Stops at [limit] files rather than running out of memory.
     */
    fun walk(folder: File, limit: Int = 100_000, skip: (File) -> Boolean = { false }): List<Entry> {
        val out = ArrayList<Entry>()
        val base = folder.name.ifEmpty { "folder" }
        fun visit(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (child in children) {
                if (out.size >= limit) return
                if (Files.isSymbolicLink(child.toPath()) || skip(child)) continue
                val path = "$prefix/${child.name}"
                when {
                    child.isDirectory -> visit(child, path)
                    child.isFile && child.canRead() -> out.add(Entry(child, path))
                }
            }
        }
        visit(folder, base)
        return out
    }
}
