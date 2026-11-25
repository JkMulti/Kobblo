package com.kobblo.gallery.file.explorer

import androidx.compose.runtime.Immutable

/** What a folder's list can be sorted by. */
enum class SortMode { ALPHABETICAL, DATE, SIZE, KIND }

/**
 * One row of any list of files: a folder's, the gallery's, a server's or a cloud's. It carries the text a
 * row shows ready made, because a long list can't afford to write a date or a size while it scrolls.
 */
@Immutable
data class FileItem(
    /** Tells rows apart within one list, and is what a selection is a set of. */
    val id: Int,
    /** An absolute path on the phone, or an address that starts with its storage, such as `drive_id:`. */
    val path: String,
    /** As the row shows it. For something in the Bin, the name it had before. */
    val name: String,
    /** "folder", or a word for the kind of file, most often the one `FileKinds.typeOf` gives. It picks the icon and the viewer. */
    val type: String,
    /** A coarser word than [type], which the filters go by. Servers and the Bin write their own here. */
    val kind: String,
    /** The text for how big it is: a size, or how many things are in a folder. */
    val size: String,
    /** The text for when it last changed. */
    val changed: String,
    /** Who it belongs to, where a storage has more than one owner. */
    val owner: String,
    /** It's a favorite. */
    val isPinned: Boolean = false,
    /** A lock on it, or on a folder it's in, keeps it closed. */
    val isLocked: Boolean = false,
    val isEmptyFolder: Boolean = false,
    /** Zero for a folder. */
    val sizeBytes: Long = 0,
    /** In milliseconds. What a list sorts by date on, so in Recents it's when the file was last used. */
    val lastModified: Long = 0,
    /** Where something in the Bin came from, if that's known. */
    val originalPath: String? = null,
    /** The name something in the Bin has there. */
    val encodedTrashName: String? = null,
    /** How long a video plays, as text. */
    val duration: String? = null,
    /** Where a cloud keeps a picture of it. */
    val thumbnailLink: String? = null,
    /** How many things are in a folder, not counting hidden ones. */
    val folderItemCount: Int? = null,
    /** The file this one was proven identical to, for the Cleaner's duplicates. It has to still be there to delete this. */
    val duplicateOf: String? = null
) {
    val isHidden: Boolean get() = name.startsWith(".")
}

/**
 * A file's id in a folder's list, made from its path. A selection is a set of these, and the list can be read
 * again at any moment, so numbering by position would let one new file shift a selection onto different files.
 */
internal fun stableFileId(path: String, used: MutableSet<Int>): Int {
    var id = path.hashCode() and Int.MAX_VALUE
    while (!used.add(id)) id = (id + 1) and Int.MAX_VALUE
    return id
}

/**
 * A name nothing in [existingNames] has, in the app's usual `photo (1).jpg` form. It always adds a number, so
 * it's for when [originalName] is already known to be taken.
 */
fun suggestNonConflictingName(originalName: String, existingNames: Set<String>): String {
    // The dot that hides a file doesn't start an extension, and neither does one right after it.
    val stemStart = if (originalName.startsWith('.')) 1 else 0
    val dot = originalName.lastIndexOf('.')
    val cut = if (dot > stemStart) dot else originalName.length
    val stem = originalName.take(cut)
    val extension = originalName.drop(cut)
    return generateSequence(1, Int::inc)
        .map { number -> "$stem ($number)$extension" }
        .first { it !in existingNames }
}
