package com.kobblo.gallery.file.explorer.ui.expressive

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.tools.storage.rememberFolderSize
import java.util.Date

// The line under a name in a list of files: how big a file is and when it changed, or what a folder
// holds. A listing arrives with some of this already put into words, and the rest is worked out here.

private const val Dot = " · "

fun formatFileSize(bytes: Long): String = FileSizes.label(bytes)

/** A file's size and the day and time it changed, as much of the two as is known. */
fun formatFileMeta(file: FileItem): String =
    listOf(fileSizeText(file), changedText(file)).filter { it.isNotEmpty() }.joinToString(Dot)

/** Just the day, for a row with no room for the time. */
fun formatFileDate(file: FileItem): String =
    if (file.lastModified > 0) DisplayDates.date().format(Date(file.lastModified)) else file.changed

/** When a file changed: worked out from its timestamp where it has one, else as its listing worded it. */
private fun changedText(file: FileItem): String =
    if (file.lastModified > 0) DisplayDates.dateDotTime(file.lastModified) else file.changed

/** What a listing writes where a size belongs when it has none to give. */
private val SizePlaceholders = setOf("--", "Unknown")

/** Words that mark a listing's size text as a count of what's inside, such as "3 files" or "12 items". */
private val CountWords = listOf("file", "item")

/**
 * A file's size in words. The listing's own text is used when it really is a size. When it is a
 * count or a placeholder instead, the size comes from the bytes, and is left out if those aren't known.
 */
internal fun fileSizeText(file: FileItem): String {
    val listed = file.size
    val listedIsSize = listed.isNotEmpty() && listed !in SizePlaceholders && CountWords.none { it in listed }
    return when {
        listedIsSize -> listed
        file.sizeBytes > 0L -> formatFileSize(file.sizeBytes)
        else -> ""
    }
}

/** What a folder says under its name, before it is put into words. */
internal sealed interface FolderNote {
    /** How many things are in it. Items, not files: the folders inside it count too. */
    data class Items(val count: Int) : FolderNote

    /** Text its listing already supplied. */
    data class Listed(val text: String) : FolderNote

    /** Nothing is known of it but that it is a folder. */
    data object Unknown : FolderNote
}

/**
 * The note for a folder on the phone's own storage. A listing that has counted and sized the folder
 * itself ("4 files · 2 MB") is kept whole. Otherwise the count is used, then whatever the listing wrote
 * in its place, and a folder nothing is known about reads as empty.
 */
internal fun deviceFolderNote(file: FileItem): FolderNote {
    val listed = file.size
    val count = file.folderItemCount
    return when {
        "file" in listed && "·" in listed -> FolderNote.Listed(listed)
        count != null -> FolderNote.Items(count)
        listed.isNotEmpty() && !listed.equals("folder", ignoreCase = true) -> FolderNote.Listed(listed)
        else -> FolderNote.Items(0)
    }
}

/**
 * The note for a folder kept anywhere else. Few services count what a folder holds, so when there is
 * no count it says when the folder changed, and failing that only what it is.
 */
internal fun remoteFolderNote(file: FileItem): FolderNote {
    val count = file.folderItemCount
    return when {
        count != null -> FolderNote.Items(count)
        file.changed.isNotEmpty() && file.changed != "Unknown" -> FolderNote.Listed(file.changed)
        else -> FolderNote.Unknown
    }
}

private fun FolderNote.inWords(resources: Resources): String = when (this) {
    is FolderNote.Items -> resources.getQuantityString(R.plurals.items_count, count, count)
    is FolderNote.Listed -> text
    FolderNote.Unknown -> resources.getString(R.string.tools_inspect_folder)
}

/**
 * The line under a folder's name. On the phone's storage it can end with the folder's size, which is
 * measured off the main thread, remembered, and dropped as soon as the row scrolls away. Icon view
 * shows only the count, so it passes [withSize] as false and nothing is measured.
 */
@Composable
fun rememberFolderMeta(file: FileItem, withSize: Boolean = true): String {
    val resources = LocalContext.current.resources
    val onDevice = file.path.startsWith("/")
    if (!onDevice) return remoteFolderNote(file).inWords(resources)
    val note = deviceFolderNote(file).inWords(resources)
    if (!withSize) return note
    val measured by rememberFolderSize(file.path)
    return measured?.let { note + Dot + formatFileSize(it.bytes) } ?: note
}
