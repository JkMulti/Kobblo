package com.kobblo.gallery.file.explorer

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One of the Cleaner's categories: how much it takes up, and its files where those are kept as a list. */
data class CleanerCategoryData(
    /** In English, as screens have words of their own for it. */
    val categoryName: String,
    val files: ImmutableList<FileItem>,
    /** Of all its files, whether or not they're in [files]. */
    val totalSizeBytes: Long
)

/**
 * What a walk of the phone's storage found. Only [emptyFolders] and [duplicates] hold their files, since those
 * are the two that take a walk to find; the rest are totals.
 */
data class CleanerData(
    val documents: CleanerCategoryData,
    val images: CleanerCategoryData,
    val videos: CleanerCategoryData,
    val audio: CleanerCategoryData,
    val apps: CleanerCategoryData,
    /** Folders with nothing at all in them, hidden or not. */
    val emptyFolders: CleanerCategoryData,
    /** The copies that can go. The file each was proven identical to isn't among them. */
    val duplicates: CleanerCategoryData,
    /** Used space that no category accounts for. */
    val otherBytes: Long,
    val totalStorageBytes: Long,
    val freeStorageBytes: Long,
    val archives: CleanerCategoryData = totalOnly("Archives"),
    /** Everything directly in the Downloads folder that isn't hidden. */
    val downloadsCount: Int = 0,
    val downloadsSizeBytes: Long = 0L,
    val largeFilesSizeBytes: Long = 0L,
    val downloads: CleanerCategoryData = totalOnly("Downloads"),
    val large: CleanerCategoryData = totalOnly("Large")
) {
    internal companion object {
        /** A category that's only a number here. Its files are listed when someone opens it. */
        fun totalOnly(name: String, bytes: Long = 0L) = CleanerCategoryData(name, persistentListOf(), bytes)
    }
}

/**
 * Takes deleted files out straight away. Only duplicates and empty folders are held as lists, so the other
 * totals catch up when the Cleaner next opens, which walks the disk again.
 */
fun CleanerData.removePaths(deletedPaths: Set<String>): CleanerData {
    if (deletedPaths.isEmpty()) return this
    val remaining = duplicates.without(deletedPaths)
    val freed = duplicates.totalSizeBytes - remaining.totalSizeBytes
    return copy(
        duplicates = remaining,
        emptyFolders = emptyFolders.without(deletedPaths),
        freeStorageBytes = minOf(freeStorageBytes + freed, totalStorageBytes),
        otherBytes = maxOf(otherBytes - freed, 0L)
    )
}

private fun CleanerCategoryData.without(paths: Set<String>): CleanerCategoryData {
    val kept = files.filterNot { it.path in paths }.toImmutableList()
    return copy(files = kept, totalSizeBytes = kept.sumOf { it.sizeBytes })
}
