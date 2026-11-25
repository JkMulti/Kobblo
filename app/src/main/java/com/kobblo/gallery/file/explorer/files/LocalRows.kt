package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.DayLabels
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.explorer.FastStat
import com.kobblo.gallery.file.explorer.explorer.NaturalOrder
import com.kobblo.gallery.file.explorer.stableFileId
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.stream.Collectors
import java.util.stream.IntStream

/** Who every file on the phone belongs to. */
internal const val LocalOwner = "Me"

/** The coarser word a folder's list, Recents and Drive give a row of [type]. */
internal fun listKindOf(type: String): String = when (type) {
    "image", "video", "audio" -> "media"
    "folder", "archive", "doc" -> type
    else -> "file"
}

/** The ids of one folder's list. */
internal class ListIds {
    private val used = HashSet<Int>()

    fun of(path: String): Int = stableFileId(path, used)
}

/**
 * Builds the rows for files and folders on the phone.
 *
 * @param itemCount the words for how many things are in a folder.
 */
internal class LocalRows(private val itemCount: (Int) -> String) {
    // A long folder's rows are made on several threads, and a date format is only good for one.
    private val dayLabels = ThreadLocal.withInitial { DayLabels() }

    /** The rows of a folder holding [files], in the order a folder starts in before its own sort is applied. */
    fun listed(files: Array<File>, marks: PathMarks): List<FileItem> {
        val ids = ListIds()
        val rows = if (files.size < ShareFrom) {
            files.map { row(it, ids.of(it.absolutePath), marks) }
        } else {
            // A row's item count means listing that folder too, which took 400 ms of a folder holding 1,000
            // others. Nearly all of that is waiting on the disk, so it's shared out.
            val idOf = IntArray(files.size) { ids.of(files[it].absolutePath) }
            IntStream.range(0, files.size).parallel()
                .mapToObj { row(files[it], idOf[it], marks) }
                .collect(Collectors.toList())
        }
        return rows.sortedWith(FavoritesThenFolders)
    }

    /** It looks at the disk once, where asking for each fact in turn took four looks, most of a second for 10,000 files. */
    fun row(file: File, id: Int, marks: PathMarks): FileItem {
        val path = file.absolutePath
        val stat = FastStat.of(file)
        val modified = stat?.modifiedMillis ?: file.lastModified()
        val changed = dayLabels.get()!!.labelFor(modified)
        if (stat?.isDirectory ?: file.isDirectory) {
            val visible = file.list().orEmpty().count { !it.startsWith('.') }
            return FileItem(
                id, path, file.name, "folder", listKindOf("folder"), itemCount(visible), changed, LocalOwner,
                isPinned = marks.isFavorite(path),
                isLocked = marks.isLocked(path),
                isEmptyFolder = visible == 0,
                lastModified = modified,
                folderItemCount = visible
            )
        }
        val type = FileKinds.typeOf(file)
        val bytes = stat?.size ?: file.length()
        return FileItem(
            id, path, file.name, type, listKindOf(type), FileSizes.label(bytes), changed, LocalOwner,
            isPinned = marks.isFavorite(path),
            isLocked = marks.isLocked(path),
            sizeBytes = bytes,
            lastModified = modified
        )
    }

    private companion object {
        /** Below this many entries, a folder's listing isn't worth sharing between threads. */
        const val ShareFrom = 256

        val FavoritesThenFolders: Comparator<FileItem> = compareByDescending<FileItem> { it.isPinned }
            .thenByDescending { it.type == "folder" }
            .thenBy(NaturalOrder) { it.name }
    }
}
