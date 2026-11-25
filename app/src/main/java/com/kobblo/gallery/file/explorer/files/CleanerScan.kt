package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.CleanerCategoryData
import com.kobblo.gallery.file.explorer.CleanerData
import com.kobblo.gallery.file.explorer.CleanerData.Companion.totalOnly
import com.kobblo.gallery.file.explorer.DuplicateCandidates
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.StorageTally
import com.kobblo.gallery.file.explorer.fileops.DuplicateFinder
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Walks the disk for the Cleaner, which needs what the system's index can't say: which folders are
 * empty, and which files are the same. It keeps only running totals and, for files that could have a
 * twin, a path and a size. A row per file for the whole of storage ran the app out of memory at 100,000
 * files, so the list behind each category is read when that category is opened.
 *
 * @param canDelete whether a file can be deleted at all. One that can't is never offered as a duplicate.
 */
internal class CleanerScan(
    private val storage: PhoneStorage,
    private val marks: () -> PathMarks,
    private val canDelete: (path: String) -> Boolean
) {
    /** Comparing duplicates can take far longer than the walk, so [onWalked] gets everything else first. */
    suspend fun run(onWalked: (CleanerData) -> Unit): CleanerData {
        val finds = Finds(CategoryRows(marks()))
        try {
            storage.walk(onFile = { path, size, _ -> finds.addFile(path, size) }, onEmptyFolder = finds::addEmptyFolder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever was found by then still counts.
            e.printStackTrace()
        }
        val walked = summary(finds)
        onWalked(walked)
        return walked.copy(duplicates = duplicates(finds))
    }

    /** What the walk has come across, and the ids of the rows made for it. */
    private class Finds(val rows: CategoryRows) {
        val tally = StorageTally()
        val candidates = DuplicateCandidates()
        val emptyFolders = ArrayList<FileItem>()
        private var lastId = FirstId - 1

        fun nextId(): Int = ++lastId

        fun addFile(path: String, size: Long) {
            tally.add(path, size)
            if (size > SmallestTwin) candidates.add(path, size)
        }

        fun addEmptyFolder(path: String, modified: Long) {
            emptyFolders += rows.folder(nextId(), path, modified, isEmpty = true)
        }
    }

    private fun summary(finds: Finds): CleanerData {
        val tally = finds.tally
        val downloads = storage.downloadEntries()
        val downloadBytes = downloads.sumOf { if (it.isFile) it.length() else 0L }
        val space = storage.space
        return CleanerData(
            documents = totalOnly("Documents", tally.documents),
            images = totalOnly("Images", tally.images),
            videos = totalOnly("Videos", tally.videos),
            audio = totalOnly("Audio", tally.audio),
            apps = totalOnly("Apps", tally.apps),
            emptyFolders = CleanerCategoryData("Empty folders", finds.emptyFolders.toImmutableList(), 0L),
            duplicates = totalOnly("Duplicates"),
            otherBytes = tally.otherBytes(space.usedBytes),
            totalStorageBytes = space.totalBytes,
            freeStorageBytes = space.freeBytes,
            archives = totalOnly("Archives", tally.archives),
            downloadsCount = downloads.size,
            downloadsSizeBytes = downloadBytes,
            largeFilesSizeBytes = tally.large,
            downloads = totalOnly("Downloads", downloadBytes),
            large = totalOnly("Large", tally.large)
        )
    }

    /** Only what has been proven byte for byte to be the same as a file that stays. */
    private suspend fun duplicates(finds: Finds): CleanerCategoryData {
        val job = currentCoroutineContext()
        val extras = ArrayList<FileItem>()
        finds.candidates.forEachSharedSize { size, paths ->
            job.ensureActive()
            for (same in DuplicateFinder.provenGroups(paths, size) { job.ensureActive() }) {
                val (kept, others) = split(same)
                others.mapTo(extras) { finds.rows.file(finds.nextId(), it.path, size, it.modified, duplicateOf = kept.path) }
            }
        }
        val largestFirst = extras.sortedByDescending { it.sizeBytes }
        return CleanerCategoryData("Duplicates", largestFirst.toImmutableList(), extras.sumOf { it.sizeBytes })
    }

    private class Twin(val path: String, val deletable: Boolean, val modified: Long)

    /**
     * Which of some identical files is kept, and which can go. One that can't be deleted is the one kept, and
     * otherwise the oldest is.
     */
    private fun split(paths: List<String>): Pair<Twin, List<Twin>> {
        val twins = paths
            .map { Twin(it, canDelete(it), File(it).lastModified()) }
            .sortedWith(compareBy({ it.deletable }, { it.modified }))
        return twins.first() to twins.drop(1).filter { it.deletable }
    }

    private companion object {
        const val FirstId = 100000

        /** Up to this many bytes, files are often alike without being copies, and deleting them gains nothing. */
        const val SmallestTwin = 1024L
    }
}
