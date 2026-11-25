package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.text.DateFormat
import java.util.Date

/**
 * Pieces a folder's list together out of the system's index, which holds files by their whole path. What's
 * directly in the folder is a row of its own, and a folder inside it shows itself by the files below it.
 *
 * Whichever row speaks of a name first settles what that name is.
 */
internal class IndexedFolder(path: String, private val dates: DateFormat) {
    private val base = if (path.endsWith("/")) path else "$path/"
    private val children = LinkedHashMap<String, FileItem>()

    /** Picks rows under this folder, for the index's own search. It can let others through as well. */
    val pattern: String = "$base%"

    fun add(fullPath: String, displayName: String?, bytes: Long, modifiedSeconds: Long) {
        // The search takes an underscore in the folder's name for any character at all.
        val below = fullPath.removePrefix(base)
        if (below.isEmpty() || below.length == fullPath.length) return
        val isFolder = '/' in below
        val name = if (isFolder) below.substringBefore('/') else displayName ?: below
        if (name in children) return
        val type = if (isFolder) "folder" else FileKinds.typeOf(name)
        children[name] = FileItem(
            id = FirstId + children.size,
            path = base + name,
            name = name,
            type = type,
            kind = listKindOf(type),
            size = if (isFolder) "" else FileSizes.label(bytes),
            changed = if (modifiedSeconds > 0) dates.format(Date(modifiedSeconds * 1000)) else "",
            owner = LocalOwner,
            sizeBytes = if (isFolder) 0L else bytes,
            lastModified = modifiedSeconds * 1000
        )
    }

    fun rows(): List<FileItem> = children.values.sortedWith(compareBy({ it.type != "folder" }, { it.name.lowercase() }))

    private companion object {
        const val FirstId = 30000
    }
}
