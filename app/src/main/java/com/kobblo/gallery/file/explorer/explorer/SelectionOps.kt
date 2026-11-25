package com.kobblo.gallery.file.explorer.explorer

import com.kobblo.gallery.file.explorer.FileItem

/** The kinds a selection can be made by, in the order the sheet lists them. */
enum class SelectKind(val type: String) {
    Folders("folder"),
    Photos("image"),
    Videos("video"),
    Audio("audio"),
    Documents("doc"),
    Archives("archive"),
    Apps("apk"),
    Other("file")
}

/**
 * What the selection sheet can do to a list. Locked files stay out, because choosing one by hand asks
 * for the password first, and a sweep shouldn't be a way around that.
 */
object SelectionOps {
    fun invert(files: List<FileItem>, selected: Set<Int>): Set<Int> {
        val result = HashSet<Int>()
        for (file in files) {
            if (!file.isLocked && file.id !in selected) result.add(file.id)
        }
        return result
    }

    fun allSelectable(files: List<FileItem>): Set<Int> {
        val result = HashSet<Int>(files.size * 2)
        for (file in files) if (!file.isLocked) result.add(file.id)
        return result
    }

    /** Adds to what's already chosen, so picking photos and then videos gets both. */
    fun addKind(files: List<FileItem>, selected: Set<Int>, kind: SelectKind): Set<Int> {
        val result = HashSet(selected)
        for (file in files) if (!file.isLocked && kindOf(file) == kind) result.add(file.id)
        return result
    }

    /** What the chosen files add up to. A folder's size takes a walk to find, so folders count for nothing here. */
    fun totalBytes(files: List<FileItem>, selected: Set<Int>): Long {
        var total = 0L
        for (file in files) if (file.id in selected) total += file.sizeBytes
        return total
    }

    /** How many of each kind [files] holds, so the sheet only offers what's there. */
    fun kindCounts(files: List<FileItem>): Map<SelectKind, Int> {
        val counts = IntArray(SelectKind.entries.size)
        for (file in files) if (!file.isLocked) counts[kindOf(file).ordinal]++
        return SelectKind.entries.filter { counts[it.ordinal] > 0 }.associateWith { counts[it.ordinal] }
    }

    private fun kindOf(file: FileItem): SelectKind = when (file.type) {
        "folder" -> SelectKind.Folders
        "image" -> SelectKind.Photos
        "video" -> SelectKind.Videos
        "audio" -> SelectKind.Audio
        "doc", "document" -> SelectKind.Documents
        "archive" -> SelectKind.Archives
        "apk" -> SelectKind.Apps
        else -> SelectKind.Other
    }
}
