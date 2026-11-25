package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.fileops.BinDeleteReport
import com.kobblo.gallery.file.explorer.fileops.BinEntry
import com.kobblo.gallery.file.explorer.fileops.BinMoveReport
import com.kobblo.gallery.file.explorer.fileops.BinRestoreReport
import com.kobblo.gallery.file.explorer.fileops.BinStore
import com.kobblo.gallery.file.explorer.fileops.RecycleBin
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Date

/**
 * The Bin as the rest of the app uses it: what [RecycleBin] does with the files, and telling the index and the
 * locks about it.
 *
 * @param removeStubborn a last try at deleting an original that wouldn't go.
 */
internal class Bin(
    val folder: File,
    private val storage: PhoneStorage,
    private val shell: ElevatedShell,
    private val index: IndexUpdates,
    private val followers: PathFollowers,
    private val removeStubborn: (File) -> Boolean
) {
    /** Made anew each time, as whether there's a shell can have changed. */
    private fun files() = RecycleBin(
        store = BinStore(folder),
        shell = shell.takeIf { it.isReady },
        isRestricted = ::isRestrictedPath,
        removeStubborn = removeStubborn
    )

    /** Where something in the Bin is, by the name it has there. */
    fun pathOf(encodedName: String): String = File(folder, encodedName).path

    suspend fun moveIn(paths: List<String>, shouldStop: () -> Boolean, onProgress: (Int) -> Unit): BinMoveReport {
        val report = files().moveIn(paths, shouldStop, onProgress)
        index.gone(report.moved)
        return report
    }

    suspend fun restore(names: List<String>, shouldStop: () -> Boolean, onProgress: (Int) -> Unit): BinRestoreReport {
        val report = files().restore(names, storage.downloads, shouldStop, onProgress)
        for ((from, to) in report.landedElsewhere) followers.moved(from, to)
        index.announce(report.restored)
        return report
    }

    suspend fun deleteForever(names: List<String>, shouldStop: () -> Boolean, onProgress: (Int) -> Unit): BinDeleteReport {
        val report = files().deleteForever(names, shouldStop, onProgress)
        report.originalPaths.forEach(followers::forget)
        return report
    }

    /** What's in there, once whatever is past its time has been cleared out. */
    suspend fun list(expiryMillis: Long, marks: PathMarks): List<FileItem> {
        val dates = DisplayDates.dateTime()
        val usedIds = HashSet<Int>()
        return files().list(expiryMillis).map { entry -> row(entry, idFor(entry, usedIds), dates.format(Date(entry.deletedAt)), marks) }
    }

    private fun idFor(entry: BinEntry, used: MutableSet<Int>): Int =
        generateSequence(entry.encodedName.hashCode(), Int::inc).first(used::add)

    private fun row(entry: BinEntry, id: Int, deleted: String, marks: PathMarks): FileItem {
        val file = File(folder, entry.encodedName)
        val type = if (entry.isDirectory) "folder" else FileKinds.typeOf(entry.originalName)
        val bytes = if (entry.sizeBytes < 0L) file.length() else entry.sizeBytes
        return FileItem(
            id = id,
            path = file.absolutePath,
            name = entry.originalName,
            type = type,
            kind = type,
            size = if (entry.isDirectory) "" else FileSizes.label(bytes),
            changed = deleted,
            owner = "me",
            // It kept its lock when it came here, so it's as private here as anywhere.
            isLocked = entry.originalPath?.let(marks::isLocked) == true,
            isEmptyFolder = entry.isDirectory && file.list()?.isEmpty() == true,
            sizeBytes = bytes,
            lastModified = entry.deletedAt,
            originalPath = entry.originalPath,
            encodedTrashName = entry.encodedName
        )
    }
}
