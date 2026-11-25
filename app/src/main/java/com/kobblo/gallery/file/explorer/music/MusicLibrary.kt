package com.kobblo.gallery.file.explorer.music

import android.content.Context
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.kobblo.gallery.file.explorer.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The library at one moment, already sorted and grouped so every view of it opens at once. */
data class MusicSnapshot(
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val artists: List<MusicArtist> = emptyList(),
    val folders: List<MusicFolder> = emptyList(),
    val byPath: Map<String, MusicTrack> = emptyMap(),
    val isLoaded: Boolean = false
) {
    fun tracksFor(paths: List<String>): List<MusicTrack> = paths.mapNotNull { byPath[it] }
    fun album(key: String): MusicAlbum? = albums.firstOrNull { it.key == key }
    fun artist(key: String): MusicArtist? = artists.firstOrNull { it.key == key }
    fun folder(path: String): MusicFolder? = folders.firstOrNull { it.path == path }
}

/**
 * Reads the device's audio from MediaStore off the main thread, keeps the result in memory, and reads
 * it again when media changes. It starts when the app does, so opening the Audio view finds it ready.
 */
class MusicLibrary private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(MusicSnapshot())
    val snapshot: StateFlow<MusicSnapshot> = _snapshot.asStateFlow()

    private var refreshJob: Job? = null
    private var observing = false

    // Raw AAC and some VBR files come out of MediaStore with no length. Finding it means opening the
    // file, so each is only ever opened once.
    private val measuredDurations = HashMap<String, Long>()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh(debounceMs = 800L)
    }

    /** Begins, or begins again now that storage access may have been granted. */
    fun start() {
        if (!observing) {
            try {
                app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
                observing = true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't watch media changes", e)
            }
        }
        refresh()
    }

    fun refresh(debounceMs: Long = 0L) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            forgetMissingCovers()
            val tracks = query()
            val sorted = sortSongs(tracks, SongSort.Title)
            val albums = groupAlbums(sorted)
            _snapshot.value = MusicSnapshot(
                tracks = sorted,
                albums = albums,
                artists = groupArtists(sorted, albums),
                folders = groupFolders(sorted),
                byPath = sorted.associateBy { it.path },
                isLoaded = true
            )
        }
    }

    /**
     * Moves a song to the app's Trash, on this scope so it finishes whatever screen asked for it has
     * since gone away. The file has already left the list by the time [onDone] runs.
     */
    fun moveToTrash(path: String, onDone: (com.kobblo.gallery.file.explorer.fileops.BinMoveReport) -> Unit) {
        scope.launch {
            val report = FileRepository(app).moveToTrash(listOf(path))
            refresh()
            withContext(Dispatchers.Main) { onDone(report) }
        }
    }

    private fun query(): List<MusicTrack> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val albumArtistColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.Audio.AudioColumns.ALBUM_ARTIST else null
        val projection = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            albumArtistColumn
        ).toTypedArray()
        // System sounds aren't music. Everything else is, even if a device files it oddly.
        val selection = "${MediaStore.Audio.Media.IS_ALARM} = 0 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0"
        val result = ArrayList<MusicTrack>()
        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(app)
        try {
            app.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val data = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val added = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val albumArtist = albumArtistColumn?.let { cursor.getColumnIndex(it) } ?: -1
                while (cursor.moveToNext()) {
                    val path = cursor.getString(data) ?: continue
                    // A row can outlive its file when something deleted it behind MediaStore's back.
                    if (!File(path).exists()) continue
                    if (locks.covers(path)) continue
                    val number = cursor.getInt(track)
                    result += MusicTrack(
                        path = path,
                        title = cursor.getString(title).orEmpty().cleanTag().let { tag ->
                            val fileBase = path.substringAfterLast('/').substringBeforeLast('.')
                            if (tag.isEmpty() || tag == fileBase) titleFromFileName(path.substringAfterLast('/')) else tag
                        },
                        artist = cursor.getString(artist).orEmpty().cleanTag(),
                        album = cursor.getString(album).orEmpty().cleanTag(),
                        albumArtist = if (albumArtist >= 0) cursor.getString(albumArtist).orEmpty().cleanTag() else "",
                        mediaId = cursor.getLong(id),
                        albumId = cursor.getLong(albumId),
                        durationMs = cursor.getLong(duration).takeIf { it > 0L } ?: measureDuration(path),
                        // MediaStore keeps the disc in the thousands of this one number.
                        trackNumber = number % 1000,
                        discNumber = number / 1000,
                        year = cursor.getInt(year),
                        dateAddedSec = cursor.getLong(added),
                        sizeBytes = cursor.getLong(size)
                    )
                }
            }
        } catch (e: SecurityException) {
            // No storage access yet. The Audio view asks for it, and start() runs again after.
            Log.i(TAG, "No access to audio yet")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the audio library", e)
        }
        // Every song list is keyed by path, and two rows for one path would crash it.
        return result.distinctBy { it.path }
    }

    private fun measureDuration(path: String): Long = synchronized(measuredDurations) {
        measuredDurations.getOrPut(path) { readDurationFromFile(path) }
    }

    companion object {
        private const val TAG = "MusicLibrary"

        @Volatile
        private var instance: MusicLibrary? = null

        fun get(context: Context): MusicLibrary =
            instance ?: synchronized(this) {
                instance ?: MusicLibrary(context).also { instance = it }
            }
    }
}

/** MediaStore writes this in place of a missing tag. */
private fun String.cleanTag(): String = if (this == "<unknown>") "" else trim()

internal fun readDurationFromFile(path: String): Long = readSongTags(path, otherwise = 0L) { tags ->
    tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
}

/**
 * Reads something out of a song's own tags. The platform's reader holds a native handle, so it is let
 * go of however [read] ends, and a file the reader can't make sense of gives [otherwise].
 */
internal fun <T> readSongTags(path: String, otherwise: T, read: (MediaMetadataRetriever) -> T): T {
    val tags = MediaMetadataRetriever()
    return try {
        tags.setDataSource(path)
        read(tags)
    } catch (_: Exception) {
        otherwise
    } finally {
        try {
            tags.release()
        } catch (_: Exception) {
        }
    }
}
