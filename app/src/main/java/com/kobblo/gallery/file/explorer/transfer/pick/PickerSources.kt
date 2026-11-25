package com.kobblo.gallery.file.explorer.transfer.pick

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.transfer.send.SendPlanner
import com.kobblo.gallery.file.explorer.transfer.send.TransferFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A row from the media library. Light, because there may be a hundred thousand of them. */
data class PickMedia(
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0
)

enum class MediaKind(val uri: Uri, val sort: String) {
    Photos(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.DATE_MODIFIED),
    Videos(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.DATE_MODIFIED),
    Music(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.TITLE)
}

/**
 * Pages through the media library a screenful at a time, so opening the picker on a huge library is
 * instant and scrolling only ever reads what's about to show.
 */
class MediaPager(private val context: Context, private val kind: MediaKind, private val query: String = "") {
    val items = mutableStateListOf<PickMedia>()
    var isLoading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    private val lock = Mutex()
    private var rowsRead = 0
    private var rowsTaken = 0

    suspend fun loadMore() = lock.withLock {
        if (endReached) return@withLock
        isLoading = true
        rowsTaken = 0
        val page = try {
            withContext(Dispatchers.IO) { fetch(rowsRead) }
        } catch (e: Exception) {
            emptyList() // A library that can't be read shows as empty rather than crashing the picker.
        }
        // Counted as rows rather than kept items, so a row with no path doesn't look like the end, or read twice as a page.
        if (rowsTaken != PAGE_SIZE) endReached = true
        rowsRead += rowsTaken
        // After the counting, since a page with a locked file in it isn't the last page.
        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
        items.addAll(page.filterNot { locks.covers(it.path) })
        isLoading = false
    }

    private fun fetch(offset: Int): List<PickMedia> {
        val isAudio = kind == MediaKind.Music
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.TITLE)
            if (isAudio) add(MediaStore.Audio.Media.ARTIST)
            if (kind != MediaKind.Photos) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(kind.sort))
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                if (isAudio) ContentResolver.QUERY_SORT_DIRECTION_ASCENDING else ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, PAGE_SIZE)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            if (query.isNotBlank()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    if (isAudio) "${MediaStore.MediaColumns.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
                    else "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                )
                val like = "%$query%"
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, if (isAudio) arrayOf(like, like) else arrayOf(like))
            }
        }
        val out = ArrayList<PickMedia>(PAGE_SIZE)
        context.contentResolver.query(kind.uri, projection, args, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val title = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val artist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val duration = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            // Before Android 11 this is the whole table however little was asked for, so the page is cut out here.
            val applied = com.kobblo.gallery.file.explorer.compat.VersionRules.providerAppliedLimit(
                android.os.Build.VERSION.SDK_INT,
                cursor.extras?.getStringArray(ContentResolver.EXTRA_HONORED_ARGS),
                ContentResolver.QUERY_ARG_LIMIT
            )
            cursor.moveToPosition(com.kobblo.gallery.file.explorer.compat.VersionRules.rowsToSkip(offset, applied) - 1)
            while (rowsTaken < PAGE_SIZE && cursor.moveToNext()) {
                rowsTaken++
                val filePath = cursor.getString(path) ?: continue
                out.add(
                    PickMedia(
                        id = cursor.getLong(id),
                        path = filePath,
                        name = cursor.getString(name) ?: File(filePath).name,
                        size = cursor.getLong(size),
                        title = cursor.getString(title),
                        artist = if (artist >= 0) cursor.getString(artist) else null,
                        durationMs = if (duration >= 0) cursor.getLong(duration) else 0
                    )
                )
            }
        }
        return out
    }

    fun contentUri(id: Long): Uri = ContentUris.withAppendedId(kind.uri, id)

    private companion object {
        const val PAGE_SIZE = 120
    }
}

/** What changed last, across everything on this phone, read from the system's index rather than the disk. */
object RecentFiles {
    // Few enough that the folders below are still on screen.
    private const val COUNT = 4

    suspend fun load(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileItem>(COUNT)
        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
        try {
            val args = Bundle().apply {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                // Hidden and half-arrived files don't count, so more than are wanted get read.
                putInt(ContentResolver.QUERY_ARG_LIMIT, COUNT * 8)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
            }
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.DATA),
                args,
                null
            )?.use { cursor ->
                while (cursor.moveToNext() && out.size < COUNT) {
                    val path = cursor.getString(0) ?: continue
                    if ("/." in path || locks.covers(path)) continue
                    val file = File(path)
                    if (file.isFile) out.add(TransferFiles.fromFile(context, file))
                }
            }
        } catch (e: Exception) {
            // Only a convenience. The folders below are still there.
        }
        out
    }
}

/** An installed app someone might want to give to a friend. */
data class PickApp(
    val packageName: String,
    val label: String,
    val sourcePath: String,
    val size: Long,
    val parts: Int,
    val info: ApplicationInfo
)

object AppSource {
    private val icons = LruCache<String, ImageBitmap>(200)

    /** Apps the person installed or updated, not the ones built into the phone. */
    suspend fun load(context: Context): List<PickApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        ).mapTo(HashSet()) { it.activityInfo.packageName }

        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName in launchable && it.packageName != context.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .mapNotNull { info ->
                val parts = SendPlanner.appParts(context, info.packageName)
                if (parts.isEmpty()) return@mapNotNull null
                PickApp(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    sourcePath = info.sourceDir,
                    size = parts.sumOf { it.length() },
                    parts = parts.size,
                    info = info
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun icon(context: Context, app: PickApp, sizePx: Int): ImageBitmap? {
        icons.get(app.packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable: Drawable = context.packageManager.getApplicationIcon(app.info)
                drawable.toBitmap(sizePx, sizePx).asImageBitmap().also { icons.put(app.packageName, it) }
            } catch (e: Exception) {
                null
            }
        }
    }
}
