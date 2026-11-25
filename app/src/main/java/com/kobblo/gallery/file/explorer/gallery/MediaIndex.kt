package com.kobblo.gallery.file.explorer.gallery

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.places.PlaceTable
import com.kobblo.gallery.file.explorer.viewer.metadata.ArrayByteSource
import com.kobblo.gallery.file.explorer.viewer.metadata.Iptc
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegSegments
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaFormatting
import com.kobblo.gallery.file.explorer.viewer.metadata.Mp4MetadataStripper
import com.kobblo.gallery.file.explorer.viewer.metadata.Xmp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * What's known from inside a photo: its camera, the place it was taken and the tags it carries. A library has few
 * cameras and places, so photos share these rather than holding one each, which matters at 100,000 of them.
 */
data class PhotoDetails(val camera: String?, val place: Int, val tags: List<String>) {
    val isEmpty: Boolean get() = camera == null && place < 0 && tags.isEmpty()
}

/** How far a scan has got, for saying so. */
data class IndexProgress(val done: Int, val total: Int)

/**
 * What the albums and search need beyond the feed. Sizes, dates and types come straight from the system's index in a
 * single query. Everything else needs opening the file, so it's found slowly in the background on one low priority
 * thread, saved between runs, and fills in as it arrives.
 *
 * Nothing starts this until someone opens Albums or Search, so Home never pays for it. It stops when the app leaves
 * the screen and carries on when it comes back, and a file it has already read is never read twice.
 */
class MediaIndex private constructor(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { work ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "media-index")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val cacheFile = File(app.filesDir, "gallery/details.bin")

    private val _facts = MutableStateFlow<Map<Int, MediaFacts>>(emptyMap())
    val facts: StateFlow<Map<Int, MediaFacts>> = _facts.asStateFlow()

    private val _flags = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val flags: StateFlow<Map<Int, Int>> = _flags.asStateFlow()

    private val _details = MutableStateFlow<Map<Int, PhotoDetails>>(emptyMap())
    val details: StateFlow<Map<Int, PhotoDetails>> = _details.asStateFlow()

    private val _progress = MutableStateFlow<IndexProgress?>(null)
    val progress: StateFlow<IndexProgress?> = _progress.asStateFlow()

    private var refreshJob: Job? = null

    /** The feed a scan was asked for, kept only until that scan has got through it. */
    private var unfinished: List<FileItem>? = null
    private var lastFingerprint = 0L
    private var lastVersion = 0

    /**
     * What was read is kept under this. Photos read before photo locations were allowed had theirs
     * blanked out by the system, so allowing them reads everything once more, and nothing else changes.
     */
    private fun cacheVersion(): Int =
        if (com.kobblo.gallery.file.explorer.compat.PhotoLocations.allowed(app)) PlaceTable.Version + LocationsAllowedVersion else PlaceTable.Version

    /** Whatever made this is on screen, and the counting below takes it from there. */
    private var startedActivities = 1

    init {
        File(app.filesDir, "gallery/probe.bin").delete()
        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) unfinished?.let { files ->
                    lastFingerprint = 0L
                    refresh(files)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    refreshJob?.cancel()
                    _progress.value = null
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Called when the feed changes while albums or search are in use. A newer feed replaces an older run. A star
     * makes a new list with nothing in it that matters here, and rereading would make everything on screen recount.
     */
    fun refresh(files: List<FileItem>) {
        val fingerprint = fingerprintOf(files)
        val version = cacheVersion()
        if (fingerprint == lastFingerprint && version == lastVersion && (unfinished == null || refreshJob?.isActive == true)) return
        lastFingerprint = fingerprint
        lastVersion = version
        refreshJob?.cancel()
        unfinished = files
        refreshJob = scope.launch {
            _facts.value = readFacts()
            scan(files, version)
            unfinished = null
        }
    }

    private fun fingerprintOf(files: List<FileItem>): Long {
        var hash = 1125899906842597L
        for (file in files) {
            hash = 31 * hash + file.id
            hash = 31 * hash + file.sizeBytes
            hash = 31 * hash + (if (file.isLocked) 1 else 0)
        }
        return hash
    }

    /** Reads the index straight away, for work that doesn't go through the albums. Call off the main thread. */
    fun readFactsNow(): Map<Int, MediaFacts> = readFacts()

    private fun readFacts(): Map<Int, MediaFacts> {
        val result = HashMap<Int, MediaFacts>()
        readTable(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, result)
        readTable(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, result)
        return result
    }

    private fun readTable(uri: Uri, into: MutableMap<Int, MediaFacts>) {
        val hasOrientation = com.kobblo.gallery.file.explorer.compat.VersionRules.mediaTableHasOrientation(
            android.os.Build.VERSION.SDK_INT,
            isVideoTable = uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        val projection = listOfNotNull(
            BaseColumns._ID,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION.takeIf { hasOrientation },
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.ImageColumns.DATE_TAKEN
        ).toTypedArray()
        try {
            app.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(BaseColumns._ID)
                val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val orientation = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
                val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val taken = c.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
                while (c.moveToNext()) {
                    val turned = orientation >= 0 && c.getInt(orientation).let { it == 90 || it == 270 }
                    val w = c.getInt(width)
                    val h = c.getInt(height)
                    into[c.getLong(id).toInt()] = MediaFacts(
                        width = if (turned) h else w,
                        height = if (turned) w else h,
                        addedMillis = c.getLong(added) * 1000L,
                        mime = c.getString(mime),
                        takenMillis = c.getLong(taken)
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaIndex", "Couldn't read the media index", e)
        }
    }

    private suspend fun scan(files: List<FileItem>, version: Int) {
        val cache = IndexCache.read(cacheFile, version)
        val shared = HashMap<PhotoDetails, PhotoDetails>()
        val foundFlags = HashMap<Int, Int>()
        val foundDetails = HashMap<Int, PhotoDetails>()
        for ((id, entry) in cache) {
            if (entry.flags != 0) foundFlags[id] = entry.flags
            entry.details?.let { foundDetails[id] = shared.getOrPut(it) { it } }
        }
        _flags.value = HashMap(foundFlags)
        _details.value = HashMap(foundDetails)

        // Photos first and newest first, which is what someone is likeliest to look for. A video needs far more
        // work per file than a photo, and few carry anything to find, so they wait their turn.
        val pending = files.filter { file ->
            if (file.isLocked || !IndexableMedia.canRead(file)) return@filter false
            val known = cache[file.id]
            known == null || known.sizeBytes != file.sizeBytes
        }.sortedBy { if (it.type == "video") 1 else 0 }
        if (pending.isEmpty()) return

        val head = ByteArray(HeadBytes)
        val tail = ByteArray(TailBytes)
        var done = 0
        var unsaved = false
        var unpublished = false
        var lastPublish = System.currentTimeMillis()
        var lastSave = lastPublish
        var batchStart = System.nanoTime()
        _progress.value = IndexProgress(0, pending.size)
        try {
            for (file in pending) {
                val read = readFile(file, head, tail)
                val details = read.details?.let { shared.getOrPut(it) { it } }
                cache[file.id] = IndexEntry(file.sizeBytes, read.flags, details)
                unsaved = true
                if (read.flags != 0) {
                    foundFlags[file.id] = read.flags
                    unpublished = true
                }
                if (details != null) {
                    foundDetails[file.id] = details
                    unpublished = true
                }
                done++
                if (done % ProgressEvery == 0) _progress.value = IndexProgress(done, pending.size)

                // Each publish makes whatever's on screen count again, so a library full of finds can't ask for it per file.
                val now = System.currentTimeMillis()
                if (unpublished && now - lastPublish >= PublishEveryMillis) {
                    _flags.value = HashMap(foundFlags)
                    _details.value = HashMap(foundDetails)
                    unpublished = false
                    lastPublish = now
                }
                if (now - lastSave >= SaveEveryMillis) {
                    saveCache(cache, version)
                    unsaved = false
                    lastSave = System.currentTimeMillis()
                }
                // Half of the time spent reading is spent resting, so a long first pass stays easy on the battery
                // and on whatever else is reading from storage.
                if (done % PaceEvery == 0) {
                    delay((System.nanoTime() - batchStart) / 2_000_000L)
                    batchStart = System.nanoTime()
                }
                yield()
            }
        } finally {
            _progress.value = null
            if (unsaved) saveCache(cache, version)
            if (unpublished) {
                _flags.value = HashMap(foundFlags)
                _details.value = HashMap(foundDetails)
            }
        }
    }

    private fun readFile(file: FileItem, head: ByteArray, tail: ByteArray): IndexEntry = try {
        if (file.type == "video") {
            IndexEntry(file.sizeBytes, 0, videoDetails(file))
        } else {
            RandomAccessFile(file.path, "r").use { handle ->
                val length = handle.length()
                val headLength = handle.read(head, 0, minOf(length, head.size.toLong()).toInt()).coerceAtLeast(0)
                var tailLength = 0
                if (length > head.size) {
                    val want = minOf(length - head.size, tail.size.toLong()).toInt()
                    handle.seek(length - want)
                    tailLength = handle.read(tail, 0, want).coerceAtLeast(0)
                }
                val flags = if (MediaProbe.isProbeable(file.name)) MediaProbe.inspect(head, headLength, tail, tailLength) else 0
                IndexEntry(file.sizeBytes, flags, photoDetails(file, head, headLength))
            }
        }
    } catch (e: Exception) {
        IndexEntry(file.sizeBytes, 0, null)
    } catch (e: OutOfMemoryError) {
        // One absurd file shouldn't end a scan of everything else.
        IndexEntry(file.sizeBytes, 0, null)
    }

    private fun photoDetails(file: FileItem, head: ByteArray, headLength: Int): PhotoDetails? {
        val isJpeg = headLength > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        // A JPEG's metadata is all at the front, and those bytes are in hand already. Anything else, like a HEIC
        // which keeps it in a box of its own, has to be read properly.
        val exif = try {
            if (isJpeg) ExifInterface(ByteArrayInputStream(head, 0, headLength)) else ExifInterface(file.path)
        } catch (_: Exception) {
            null
        }
        val camera = MediaFormatting.cameraName(exif?.getAttribute(ExifInterface.TAG_MAKE), exif?.getAttribute(ExifInterface.TAG_MODEL))
            ?.take(IndexCache.MaxTextLength)
        val place = exif?.latLong?.let { (latitude, longitude) -> placeOf(latitude, longitude) } ?: -1
        val tags = if (isJpeg) jpegTags(head, headLength) else emptyList()
        return PhotoDetails(camera, place, tags).takeUnless { it.isEmpty }
    }

    private fun jpegTags(head: ByteArray, headLength: Int): List<String> {
        val source = ArrayByteSource(head, headLength)
        val segments = JpegSegments.read(source)
        val tags = LinkedHashSet<String>()
        JpegSegments.xmp(source, segments)?.let { tags.addAll(Xmp.keywords(it)) }
        JpegSegments.iptc(source, segments)?.let { block ->
            Iptc.parse(block).firstOrNull { it.first == "Keywords" }?.second?.split(", ")?.let(tags::addAll)
        }
        return tags.filter { it.isNotBlank() && it.length <= IndexCache.MaxTextLength }.take(MaxTagsPerPhoto)
    }

    private fun videoDetails(file: FileItem): PhotoDetails? {
        val retriever = MediaMetadataRetriever()
        val location = try {
            retriever.setDataSource(file.path)
            MediaFormatting.parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                ?: RandomAccessFile(file.path, "r").use { handle ->
                    if (Mp4MetadataStripper.isMp4(handle)) MediaFormatting.parseIso6709(Mp4MetadataStripper.findKeyedLocation(handle)) else null
                }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        val place = location?.let { (latitude, longitude) -> placeOf(latitude, longitude) } ?: -1
        return if (place >= 0) PhotoDetails(null, place, emptyList()) else null
    }

    /** A location blanked by the system, or never set, reads as exactly 0, 0, which is open sea. */
    private fun placeOf(latitude: Double, longitude: Double): Int =
        if (latitude == 0.0 && longitude == 0.0) -1 else PlaceTable.get(app).nearest(latitude, longitude)

    private fun saveCache(cache: Map<Int, IndexEntry>, version: Int) {
        try {
            IndexCache.write(cacheFile, version, cache)
        } catch (e: Exception) {
            android.util.Log.e("MediaIndex", "Couldn't save what was read", e)
        }
    }

    companion object {
        /** XMP sits in the first segments of a file, after an EXIF block of up to 64 KB. */
        private const val HeadBytes = 128 * 1024

        /** Added to the places version while photo locations are allowed. */
        private const val LocationsAllowedVersion = 1000
        private const val TailBytes = 32 * 1024
        private const val ProgressEvery = 64
        private const val PaceEvery = 32
        private const val PublishEveryMillis = 4_000L
        private const val SaveEveryMillis = 20_000L
        private const val MaxTagsPerPhoto = 32

        @Volatile private var instance: MediaIndex? = null

        fun get(context: Context): MediaIndex =
            instance ?: synchronized(this) {
                instance ?: MediaIndex(context).also { instance = it }
            }
    }
}

/** Which files are worth opening. A plain PNG or WebP from a screenshot or a web page has nothing in it to find. */
internal object IndexableMedia {
    private val Photos = setOf("jpg", "jpeg", "jpe", "jfif", "heic", "heif", "avif", "dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw", "tif", "tiff")
    private val Videos = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mkv", "webm")

    fun canRead(file: FileItem): Boolean = when (file.type) {
        "image" -> extensionOf(file.name) in Photos
        "video" -> extensionOf(file.name) in Videos
        else -> false
    }
}
