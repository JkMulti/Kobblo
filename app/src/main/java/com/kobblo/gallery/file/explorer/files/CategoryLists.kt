package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.CategoryTotals
import com.kobblo.gallery.file.explorer.CleanerData
import com.kobblo.gallery.file.explorer.DayLabels
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.IndexConditions
import com.kobblo.gallery.file.explorer.LARGE_FILE_BYTES
import com.kobblo.gallery.file.explorer.StorageCategory
import com.kobblo.gallery.file.explorer.StorageTally
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Locale

/** What one of the Browse tab's or the Cleaner's categories lists, by the key its screen is opened with. */
internal sealed interface CategoryList {
    /** Found by walking the disk, so it's kept from the Cleaner's last walk. */
    data object Duplicates : CategoryList
    data object EmptyFolders : CategoryList
    data object Downloads : CategoryList

    /** Files picked one by one, out of everything there is. */
    sealed interface Picked : CategoryList {
        /** Picks these files' rows out of the system's index. The scanner may not have measured some of them yet. */
        val indexCondition: String

        /** Whether a file the index knows belongs here. */
        fun holds(file: IndexedFile): Boolean

        /** Whether a file on the disk belongs here. */
        fun holds(path: String, size: Long): Boolean
    }

    data class Of(val category: StorageCategory) : Picked {
        override val indexCondition get() = category.indexSelection()
        override fun holds(file: IndexedFile) = StorageCategory.ofRow(file.mediaType, file.path) == category
        override fun holds(path: String, size: Long) = StorageCategory.of(path) == category
    }

    data object Large : Picked {
        override val indexCondition get() = IndexConditions.LargeOrUnscanned
        override fun holds(file: IndexedFile) = file.size >= LARGE_FILE_BYTES
        override fun holds(path: String, size: Long) = size >= LARGE_FILE_BYTES
    }

    companion object {
        /** Null for a key that stands for no category. */
        fun of(key: String): CategoryList? = when (key.lowercase(Locale.ROOT)) {
            "duplicates", "duplicate" -> Duplicates
            "empty folders", "empty_folders" -> EmptyFolders
            "download", "downloads" -> Downloads
            "doc", "docs", "documents" -> Of(StorageCategory.Documents)
            "image", "images" -> Of(StorageCategory.Images)
            "video", "videos" -> Of(StorageCategory.Videos)
            "audio", "music" -> Of(StorageCategory.Audio)
            "apk", "app", "apps" -> Of(StorageCategory.Apps)
            "archive", "archives" -> Of(StorageCategory.Archives)
            "large", "largefiles", "large files", "other files", "other" -> Large
            else -> null
        }

        /** Whether listing it takes a walk of the disk, rather than a question to the system's index. */
        fun takesAWalk(key: String): Boolean = of(key).let { it == Duplicates || it == EmptyFolders }
    }
}

/**
 * The files and the totals of the categories. Only the rows of the category that's opened are ever built, so
 * opening Videos in a library of 100,000 photos costs what the videos cost.
 */
internal class CategoryLists(
    private val index: IndexQueries,
    private val storage: PhoneStorage,
    private val marks: () -> PathMarks
) {
    /** [scan] is only read for the two lists that take a walk of the disk to find. */
    suspend fun files(key: String, scan: CleanerData?): List<FileItem> = when (val list = CategoryList.of(key)) {
        null -> emptyList()
        // These were found by walking the disk moments ago, and checking each again took 15 s at 100,000 rows.
        CategoryList.Duplicates -> scan?.duplicates?.files.orEmpty()
        CategoryList.EmptyFolders -> scan?.emptyFolders?.files.orEmpty()
        CategoryList.Downloads -> downloads()
        is CategoryList.Picked -> picked(list)
    }

    private suspend fun picked(list: CategoryList.Picked): List<FileItem> {
        val rows = CategoryRows(marks())
        val items = ArrayList<FileItem>()
        val indexed = index.forEachFile(list.indexCondition) { file ->
            if (list.holds(file)) items += rows.file(file.id, file.path, file.size, file.modifiedMillis)
        }
        if (indexed) return items
        storage.walk(onFile = { path, size, modified ->
            if (list.holds(path, size)) items += rows.file(FirstWalkedId + items.size, path, size, modified)
        })
        return items
    }

    private fun downloads(): List<FileItem> {
        val rows = CategoryRows(marks())
        return storage.downloadEntries()
            .mapIndexed { position, entry -> rows.download(FirstWalkedId + position, entry) }
            .sortedByDescending { it.lastModified }
    }

    /**
     * What the Browse tab shows beside each category. The system's index can add these up by itself in a
     * fraction of the time a walk of the disk takes, so it's cheap enough to ask each time Browse opens.
     */
    suspend fun totals(): CategoryTotals {
        val tally = index.tally() ?: walkedTally()
        return CategoryTotals(
            imageBytes = tally.images,
            videoBytes = tally.videos,
            audioBytes = tally.audio,
            docBytes = tally.documents,
            appBytes = tally.apps,
            archiveBytes = tally.archives,
            largeBytes = tally.large,
            otherBytes = tally.otherBytes(storage.space.usedBytes),
            downloadsCount = storage.downloadEntries().size
        )
    }

    private suspend fun walkedTally(): StorageTally {
        val tally = StorageTally()
        storage.walk(onFile = { path, size, _ -> tally.add(path, size) })
        return tally
    }

    private companion object {
        /** Away from the index's own ids, which these lists have where there's an index. */
        const val FirstWalkedId = 300000
    }
}

/** Builds the rows of the categories and the Cleaner, which show plain files and don't go by kind. */
internal class CategoryRows(private val marks: PathMarks) {
    private val dates = DayLabels()

    fun file(id: Int, path: String, size: Long, modified: Long, duplicateOf: String? = null): FileItem {
        val name = path.substringAfterLast('/')
        // Only a .ts needs its bytes looked at, which is why this takes a file for just that.
        val type = if (FileKinds.extensionOf(name) == "ts") FileKinds.typeOf(File(path)) else FileKinds.typeOf(name)
        return FileItem(
            id, path, name, type, "file", FileSizes.label(size), dates.labelFor(modified), LocalOwner,
            isPinned = marks.isFavorite(path),
            isLocked = marks.isLocked(path),
            sizeBytes = size,
            lastModified = modified,
            duplicateOf = duplicateOf
        )
    }

    fun folder(id: Int, path: String, modified: Long, isEmpty: Boolean) = FileItem(
        id, path, path.substringAfterLast('/'), "folder", "folder", "--", dates.labelFor(modified), LocalOwner,
        isPinned = marks.isFavorite(path),
        isLocked = marks.isLocked(path),
        isEmptyFolder = isEmpty,
        lastModified = modified
    )

    fun download(id: Int, entry: File): FileItem {
        val path = entry.absolutePath
        val modified = entry.lastModified()
        if (entry.isDirectory) return folder(id, path, modified, isEmpty = entry.list()?.isEmpty() == true)
        return file(id, path, if (entry.isFile) entry.length() else 0L, modified)
    }
}
