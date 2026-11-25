package com.kobblo.gallery.file.explorer.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.gallery.ScanRequests
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.MediaStoreQueries
import java.io.File
import java.util.Date
import java.util.TimeZone

/** A row of the system's tables of media. Dates are zero until the scanner has read the file. */
internal class MediaRow(
    val id: Int,
    val path: String,
    val name: String?,
    val bytes: Long,
    val takenMillis: Long,
    val modifiedSeconds: Long,
    val addedSeconds: Long,
    val playsMillis: Long
)

/** Reads the Home tab's feed out of the system's tables. */
internal class GalleryReader(private val context: Context, private val choices: SavedChoices) {

    /** Every photo and video, newest first. */
    fun everything(showHidden: Boolean): List<FileItem> = read(showHidden, limit = 0).first

    /** The newest [limit], and whether that was everything there is. */
    fun newest(showHidden: Boolean, limit: Int): Pair<List<FileItem>, Boolean> = read(showHidden, limit)

    /** A [limit] of zero means there's none. */
    private fun read(showHidden: Boolean, limit: Int): Pair<List<FileItem>, Boolean> {
        val feed = GalleryFeedRows(showHidden, choices.marksInLists())
        var complete = true
        for (table in tables()) if (!read(table, limit, feed)) complete = false
        askForScan(feed.unscanned)
        val items = feed.newestFirst()
        return (if (!complete && items.size > limit) items.subList(0, limit) else items) to complete
    }

    /** One of the system's tables. [type] is null where the rows are of every kind. */
    private class Table(val uri: Uri, val type: String?, val playsColumn: String? = null, val selection: String? = null)

    private fun tables() = listOf(
        Table(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image"),
        Table(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", playsColumn = MediaStore.Video.VideoColumns.DURATION),
        // Formats the system has no idea about, like TGA or APNG, are only plain files to it.
        Table(MediaStore.Files.getContentUri("external"), type = null, selection = MediaStoreQueries.unrecognizedMedia())
    )

    /** Returns whether all of the table was read. Without a limit nothing is waiting to hear that it wasn't. */
    private fun read(table: Table, limit: Int, feed: GalleryFeedRows): Boolean = try {
        query(table, limit)?.use { rows ->
            val plays = table.playsColumn?.let(rows::getColumnIndex) ?: -1
            while (rows.moveToNext() && (limit == 0 || rows.position < limit)) {
                mediaRow(rows, plays)?.let { feed.add(it, table.type) }
            }
            // Older versions can ignore the limit, and then there's simply more that wasn't read.
            limit == 0 || rows.count < limit
        } ?: true
    } catch (e: Exception) {
        Log.e("GalleryMedia", "Couldn't read ${table.type} media", e)
        limit == 0
    }

    private fun query(table: Table, limit: Int): Cursor? {
        val columns = if (table.playsColumn == null) Columns else Columns + table.playsColumn
        val resolver = context.contentResolver
        if (limit == 0) return resolver.query(table.uri, columns, table.selection, null, null)
        val arguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, table.selection)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NewestFirst)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        return resolver.query(table.uri, columns, arguments, null)
    }

    private fun mediaRow(row: Cursor, plays: Int): MediaRow? = MediaRow(
        // The system's id is unique across photos and videos, and stable.
        id = row.getLong(0).toInt(),
        path = row.getString(1) ?: return null,
        name = row.getString(2),
        bytes = row.getLong(3),
        takenMillis = row.getLong(4),
        modifiedSeconds = row.getLong(5),
        addedSeconds = row.getLong(6),
        playsMillis = if (plays >= 0) row.getLong(plays) else 0L
    )

    private fun askForScan(unscanned: List<String>) {
        if (unscanned.isEmpty()) return
        val paths = ScanRequests.nextToScan(unscanned)
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    private companion object {
        /** The order the feed itself sorts into, so that the first rows really are its first. */
        const val NewestFirst = "COALESCE(NULLIF(datetaken, 0), date_modified * 1000, date_added * 1000) DESC"

        val Columns = arrayOf(
            BaseColumns._ID,
            MediaColumns.DATA,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.SIZE,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.DATE_ADDED
        )
    }
}

/** Turns rows of media into the feed's items, and leaves out what has no place in it. */
internal class GalleryFeedRows(private val showHidden: Boolean, private val marks: PathMarks) {
    private val items = ArrayList<FileItem>()

    // The grid keys tiles by path, and two rows for one path can exist for a moment during a move.
    private val seen = HashSet<String>()

    // A library has far fewer days than photos, and formatting a date or a size is slow enough to
    // matter at 100,000 rows, so each text is made once and shared.
    private val dayLabels = HashMap<Long, String>()
    private val dates = DisplayDates.date()
    private val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis())

    /** Files the scanner hasn't read, which it should be asked to. */
    val unscanned = ArrayList<String>()

    /** Takes a row of a table that only holds [type], or of one that holds anything when that's null. */
    fun add(row: MediaRow, type: String?) {
        val path = row.path
        // Hidden folders include the trash, which must never appear here.
        if (path.contains("/.kobblo_trash") || (!showHidden && path.contains("/."))) return
        val name = row.name ?: path.substringAfterLast('/')
        val itemType = type ?: FileKinds.typeOf(name)
        // The system calls every .ts a video, and TypeScript isn't one.
        if (itemType == "video" && FileKinds.typeOf(File(path)) != "video") return
        if (!seen.add(path)) return
        val moment = momentOf(row)
        items += FileItem(
            id = row.id,
            path = path,
            name = name,
            type = itemType,
            kind = "media",
            size = FileSizes.label(row.bytes),
            changed = dayLabels.getOrPut((moment + offset) / DayMillis) { dates.format(Date(moment)) },
            owner = LocalOwner,
            isPinned = marks.isFavorite(path),
            isLocked = marks.isLocked(path),
            sizeBytes = row.bytes,
            lastModified = moment,
            duration = PlayingTime.short(row.playsMillis)
        )
    }

    /**
     * A file written straight to storage has a row before the scanner has read it, with no dates yet. It
     * shouldn't look like 1970 in the meantime.
     */
    private fun momentOf(row: MediaRow): Long {
        if (row.takenMillis > 0L) return row.takenMillis
        if (row.modifiedSeconds > 0L) return row.modifiedSeconds * 1000L
        unscanned += row.path
        return row.addedSeconds * 1000L
    }

    fun newestFirst(): List<FileItem> = items.apply { sortByDescending { it.lastModified } }

    private companion object {
        const val DayMillis = 24L * 60 * 60 * 1000
    }
}
