package com.kobblo.gallery.file.explorer.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.util.Log
import androidx.annotation.RequiresApi
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.IndexConditions
import com.kobblo.gallery.file.explorer.LARGE_FILE_BYTES
import com.kobblo.gallery.file.explorer.StorageCategory
import com.kobblo.gallery.file.explorer.StorageTally
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Android's index of files, MediaStore. */
internal class SystemIndex(private val context: Context) : IndexUpdates, IndexQueries {
    private val resolver: ContentResolver get() = context.contentResolver
    private val everyFile: Uri get() = MediaStore.Files.getContentUri("external")

    override fun announce(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            scan(withContents(paths, AnnounceLimit))
        } catch (e: Exception) {
            Log.w(Tag, "Couldn't tell the file index about a change", e)
        }
    }

    override fun rescan(path: String) {
        try {
            scan(listOf(path))
        } catch (_: Exception) {
        }
    }

    private fun scan(paths: List<String>) = MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)

    /**
     * These are whole paths, so nothing else can match. They go in chunks, because one call per file is a trip
     * to the system's process each.
     */
    override fun forget(paths: List<String>) {
        for (chunk in paths.chunked(DeleteChunk)) {
            try {
                val marks = chunk.joinToString(",") { "?" }
                resolver.delete(everyFile, "${MediaColumns.DATA} IN ($marks)", chunk.toTypedArray())
            } catch (e: Exception) {
                Log.w(Tag, "Couldn't update the file index", e)
            }
        }
    }

    override fun deleteThrough(path: String) {
        try {
            resolver.delete(everyFile, "${MediaColumns.DATA} = ?", arrayOf(path))
        } catch (_: Exception) {
        }
    }

    override suspend fun forEachFile(condition: String, visit: (IndexedFile) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val job = currentCoroutineContext()
        val rows = filesMatching(condition) ?: return false
        rows.use {
            while (rows.moveToNext()) {
                if (rows.position % CheckEvery == 0) job.ensureActive()
                fileIn(rows)?.let(visit)
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun filesMatching(condition: String): Cursor? {
        val arguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, condition)
            // A file written straight to storage can stay "pending" until something scans it.
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return try {
            resolver.query(primaryFiles(), FileColumns, arguments, null)
        } catch (e: Exception) {
            Log.e(Tag, "Couldn't read the file index", e)
            null
        }
    }

    /** Null for a row without a path, or one the scanner hasn't reached whose file isn't there. */
    private fun fileIn(row: Cursor): IndexedFile? {
        val path = row.getString(1) ?: return null
        val id = row.getLong(0).toInt()
        val mediaType = row.getInt(4)
        if (!row.isNull(2) && !row.isNull(3)) {
            return IndexedFile(id, path, row.getLong(2), row.getLong(3) * 1000L, mediaType, scanned = true)
        }
        val file = File(path)
        return if (file.isFile) IndexedFile(id, path, file.length(), file.lastModified(), mediaType, scanned = false) else null
    }

    /**
     * The index adds up photos, videos and music by itself. Everything else has to go by its name, but a
     * large library is nearly all media, so those rows are few. So are rows the scanner hasn't reached.
     */
    override suspend fun tally(): StorageTally? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val tally = StorageTally()
            if (addMediaSums(tally) && addLargeSum(tally) && addNamedFiles(tally)) tally else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(Tag, "The file index wouldn't add up sizes", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun addMediaSums(tally: StorageTally): Boolean {
        val grouped = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, IndexConditions.Visible)
            putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, arrayOf(MediaType))
        }
        val sums = resolver.query(primaryFiles(), arrayOf(MediaType, SizeSum), grouped, null) ?: return false
        sums.use {
            while (sums.moveToNext()) {
                StorageCategory.ofMediaType(sums.getInt(0))?.let { tally.add(it, sums.getLong(1)) }
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun addLargeSum(tally: StorageTally): Boolean {
        val sum = resolver.query(primaryFiles(), arrayOf(SizeSum), IndexConditions.Large, null, null) ?: return false
        sum.use { if (sum.moveToFirst()) tally.large = sum.getLong(0) }
        return true
    }

    private suspend fun addNamedFiles(tally: StorageTally): Boolean = forEachFile(IndexConditions.NamedOrUnscanned) { file ->
        StorageCategory.ofRow(file.mediaType, file.path)?.let { tally.add(it, file.size) }
        // Sums only reach rows that have a size, so the large total still needs these.
        if (!file.scanned && file.size >= LARGE_FILE_BYTES) tally.large += file.size
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun primaryFiles(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun folder(path: String): List<FileItem> = try {
        val folder = IndexedFolder(path, DisplayDates.date())
        resolver.query(everyFile, FolderColumns, "${MediaColumns.DATA} LIKE ?", arrayOf(folder.pattern), null)?.use { rows ->
            while (rows.moveToNext()) folder.add(rows.getString(0), rows.getString(1), rows.getLong(2), rows.getLong(3))
        }
        folder.rows()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private companion object {
        const val Tag = "FileRepository"

        /** The most paths the index hears about in one go. The rest it finds on its own next scan. */
        const val AnnounceLimit = 5000

        /** Comfortably under SQLite's limit on the values in one statement, on every version. */
        const val DeleteChunk = 400

        /** How many rows to read between looks at whether anyone still wants them. */
        const val CheckEvery = 1024

        const val MediaType = "media_type"
        const val SizeSum = "SUM(_size)"

        val FileColumns = arrayOf(BaseColumns._ID, MediaColumns.DATA, MediaColumns.SIZE, MediaColumns.DATE_MODIFIED, MediaType)
        val FolderColumns = arrayOf(MediaColumns.DATA, MediaColumns.DISPLAY_NAME, MediaColumns.SIZE, MediaColumns.DATE_MODIFIED)
    }
}
