package com.kobblo.gallery.file.explorer.music

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Favorites, playlists, stats and sound settings, on the device only. A debounced write keeps a run
 * of slider moves from each touching the disk, and AtomicFile means a crash mid-write can't take the
 * user's playlists with it.
 */
class MusicStore private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val file = AtomicFile(File(musicDir(context), "library.json"))
    private val gson = Gson()
    private val loaded = CompletableDeferred<Unit>()
    private var saveJob: Job? = null

    private val _data = MutableStateFlow(MusicData())
    val data: StateFlow<MusicData> = _data.asStateFlow()

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { read() }
            if (saved != null) _data.value = saved
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    /** Changes the data. Changes made before the file has loaded wait for it rather than overwrite it. */
    fun update(change: (MusicData) -> MusicData) {
        scope.launch {
            loaded.await()
            _data.update(change)
            scheduleSave()
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SaveDelayMs)
            val snapshot = _data.value
            withContext(Dispatchers.IO) { write(snapshot) }
        }
    }

    /** Writes straight away, for when the process may be about to go. */
    fun flush() {
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        val snapshot = _data.value
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    private fun read(): MusicData? = try {
        if (file.baseFile.exists()) {
            gson.fromJson(String(file.readFully(), Charsets.UTF_8), MusicData::class.java)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the music library file", e)
        null
    }

    @Synchronized
    private fun write(snapshot: MusicData) {
        val stream = try {
            file.startWrite()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the music library file", e)
            return
        }
        try {
            stream.write(gson.toJson(snapshot).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the music library file", e)
            file.failWrite(stream)
        }
    }

    companion object {
        private const val TAG = "MusicStore"
        private const val SaveDelayMs = 800L

        @Volatile
        private var instance: MusicStore? = null

        fun get(context: Context): MusicStore =
            instance ?: synchronized(this) {
                instance ?: MusicStore(context.applicationContext).also { instance = it }
            }

        fun musicDir(context: Context): File = File(context.filesDir, "music").apply { mkdirs() }
    }
}

/** The queue as it was left, so a later launch can offer to carry on from the same spot. */
data class SavedQueue(
    val tracks: List<MusicTrack> = emptyList(),
    val index: Int = 0,
    /** Where each song belongs once shuffle is off, as positions in [tracks]. Null while unshuffled. */
    val unshuffledOrder: List<Int>? = null
)

/**
 * Remembers the queue and position. The queue only changes when the user changes it, but the position
 * changes all the time, so they are saved apart: the position to preferences, every few seconds, and
 * the possibly long queue to a file, only when it really changed.
 */
class MusicSessionStore(context: Context) {
    private val queueFile = AtomicFile(File(MusicStore.musicDir(context), "queue.json"))
    private val prefs = context.getSharedPreferences("music_session", Context.MODE_PRIVATE)
    private val gson = Gson()

    var positionMs: Long
        get() = prefs.getLong("position", 0L)
        set(value) = prefs.edit().putLong("position", value).apply()

    var currentIndex: Int
        get() = prefs.getInt("index", 0)
        set(value) = prefs.edit().putInt("index", value).apply()

    var repeatMode: Int
        get() = prefs.getInt("repeat", 0)
        set(value) = prefs.edit().putInt("repeat", value).apply()

    fun readQueue(): SavedQueue? = try {
        if (queueFile.baseFile.exists()) {
            gson.fromJson(String(queueFile.readFully(), Charsets.UTF_8), SavedQueue::class.java)
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w("MusicSession", "Couldn't read the saved queue", e)
        null
    }

    @Synchronized
    fun writeQueue(queue: SavedQueue) {
        val stream = try {
            queueFile.startWrite()
        } catch (e: Exception) {
            Log.w("MusicSession", "Couldn't open the saved queue", e)
            return
        }
        try {
            stream.write(gson.toJson(queue).toByteArray(Charsets.UTF_8))
            queueFile.finishWrite(stream)
        } catch (e: Exception) {
            Log.w("MusicSession", "Couldn't save the queue", e)
            queueFile.failWrite(stream)
        }
    }

    @Synchronized
    fun clear() {
        queueFile.delete()
        prefs.edit().clear().apply()
    }
}
