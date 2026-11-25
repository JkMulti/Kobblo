package com.kobblo.gallery.file.explorer

import androidx.compose.runtime.Immutable
import com.kobblo.gallery.file.explorer.viewer.FileKinds

/** At or above this a file counts as large. */
internal const val LARGE_FILE_BYTES = 50L * 1024L * 1024L

/**
 * Which category a file's name puts it in. A file in none of these can still count as large.
 *
 * @property mediaType what the system's media_type column holds for it, where it has a number for it.
 */
internal enum class StorageCategory(private val mediaType: Int?) {
    Documents(null), Images(1), Videos(3), Audio(2), Apps(null), Archives(null);

    private val listed: Set<String>
        get() = when (this) {
            Documents -> FileKinds.Docs
            Images -> FileKinds.Images
            Videos -> FileKinds.Videos
            Audio -> FileKinds.Audio
            Apps -> FileKinds.Apps
            Archives -> FileKinds.Archives
        }

    /** Its extensions, less any that a category before it claims, the way [of] decides. */
    val extensions: Set<String>
        get() = entries.takeWhile { it != this }.fold(listed) { left, earlier -> left - earlier.listed }

    /**
     * This category's rows in the system's index. Comparing a name against every extension is what's
     * slow at 100,000 rows, so photos, videos and music go by the system's own column first. A format it
     * doesn't know, like TGA, is still found by name. The extensions are our own, never from outside.
     */
    fun indexSelection(): String {
        val byName = "${IndexConditions.NotMedia} AND (" + extensions.joinToString(" OR ") { "_data LIKE '%.$it'" } + ")"
        return "${IndexConditions.Visible} AND (" + (if (mediaType != null) "media_type = $mediaType OR ($byName)" else byName) + ")"
    }

    companion object {
        /** The category of the system's media_type column, for the three it has an opinion on. */
        fun ofMediaType(mediaType: Int): StorageCategory? = when (mediaType) {
            Images.mediaType -> Images
            Audio.mediaType -> Audio
            Videos.mediaType -> Videos
            else -> null
        }

        /** Where a row of the index belongs: what the system read in the file if it can say, or else the name. */
        fun ofRow(mediaType: Int, path: String): StorageCategory? = ofMediaType(mediaType) ?: of(path)

        /** Takes a name or a whole path, since only what's after the last dot matters. */
        fun of(name: String): StorageCategory? {
            val extension = FileKinds.extensionOf(name)
            return entries.firstOrNull { extension in it.listed }
        }
    }
}

/** Conditions that pick rows of the system's index of files. */
internal object IndexConditions {
    /**
     * The rows that a walk of the disk would count: files only, nothing hidden, and nothing under
     * Android/data or Android/obb. 12289 is the system's format code for a folder, which isn't public, and a
     * row the scanner hasn't reached yet has no format at all.
     */
    const val Visible = "format IS NOT 12289 AND _data NOT LIKE '%/.%' " +
        "AND _data NOT LIKE '%/Android/data%' AND _data NOT LIKE '%/Android/obb%'"

    const val Large = "$Visible AND _size >= $LARGE_FILE_BYTES"

    /** Rows the system hasn't called a photo, video or music: the only ones that have to go by their name. */
    const val NotMedia = "(media_type IS NULL OR media_type NOT IN (1, 2, 3))"

    const val NamedOrUnscanned = "$Visible AND ($NotMedia OR _size IS NULL)"

    const val LargeOrUnscanned = "$Visible AND (_size >= $LARGE_FILE_BYTES OR _size IS NULL)"
}

/** Adds files up by category, so Browse and the Cleaner can't count them differently. */
internal class StorageTally {
    private val bytes = LongArray(StorageCategory.entries.size)
    val documents get() = bytes[StorageCategory.Documents.ordinal]
    val images get() = bytes[StorageCategory.Images.ordinal]
    val videos get() = bytes[StorageCategory.Videos.ordinal]
    val audio get() = bytes[StorageCategory.Audio.ordinal]
    val apps get() = bytes[StorageCategory.Apps.ordinal]
    val archives get() = bytes[StorageCategory.Archives.ordinal]
    var large = 0L

    fun add(name: String, size: Long) {
        StorageCategory.of(name)?.let { add(it, size) }
        if (size >= LARGE_FILE_BYTES) large += size
    }

    fun add(category: StorageCategory, size: Long) {
        bytes[category.ordinal] += size
    }

    /** What's left of the used space once the known categories are taken out. */
    fun otherBytes(usedBytes: Long): Long = (usedBytes - bytes.sum()).coerceAtLeast(0L)
}

/** The numbers beside the Browse tab's categories. */
@Immutable
data class CategoryTotals(
    val imageBytes: Long,
    val videoBytes: Long,
    val audioBytes: Long,
    val docBytes: Long,
    val appBytes: Long,
    val archiveBytes: Long,
    val largeBytes: Long,
    val otherBytes: Long,
    val downloadsCount: Int
)
