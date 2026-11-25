package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.StorageTally
import java.io.File

/** What the system's index of files has to hear about what the app did to them. */
internal interface IndexUpdates {
    /** Something came, went or changed here. */
    fun announce(paths: List<String>)

    /** Just this path, whatever is inside it. */
    fun rescan(path: String)

    /** These files have gone. */
    fun forget(paths: List<String>)

    /** Has the system delete a file the app couldn't. It may be refused too, so look whether it's still there. */
    fun deleteThrough(path: String)

    /** For files that have left where they were. A few are announced as well, which older versions of Android need. */
    fun gone(paths: List<String>) {
        forget(paths)
        if (paths.size <= AnnounceDirectly) announce(paths)
    }

    private companion object {
        const val AnnounceDirectly = 200
    }
}

/** A file out of the system's index. One the scanner hasn't reached yet has its size and date from the disk. */
internal class IndexedFile(
    val id: Int,
    val path: String,
    val size: Long,
    val modifiedMillis: Long,
    val mediaType: Int,
    val scanned: Boolean
)

/** What the system's index of files can be asked. */
internal interface IndexQueries {
    /**
     * Visits the files [condition] picks, or returns false if there's no index that can be trusted. From
     * Android 11 every write goes through the index, and reading it is far cheaper than touching each file.
     * Before that a file can be on disk long before the index hears of it.
     */
    suspend fun forEachFile(condition: String, visit: (IndexedFile) -> Unit): Boolean

    /** How much each category takes up by the index's own sums, or null where they can't be had. */
    suspend fun tally(): StorageTally?

    /** The rows of a folder the app can't open, as far as the index knows them. */
    fun folder(path: String): List<FileItem>
}

/** How far into a folder its contents are announced with it. */
private const val AnnounceDepth = 8

/** [paths] and what's in those that are folders, up to [limit] in all. */
internal fun withContents(paths: List<String>, limit: Int): List<String> {
    val all = LinkedHashSet(paths)
    for (path in paths) {
        if (all.size >= limit) break
        val folder = File(path)
        if (folder.isDirectory) folder.walkTopDown().maxDepth(AnnounceDepth).take(limit).mapTo(all) { it.path }
    }
    return all.take(limit)
}
