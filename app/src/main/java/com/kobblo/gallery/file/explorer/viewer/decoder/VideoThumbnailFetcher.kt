package com.kobblo.gallery.file.explorer.viewer.decoder

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.decode.VideoFrameDecoder
import coil.size.pxOrElse
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * A still for any video file, by the cheapest route that works: the system's own thumbnail, then a
 * frame read by the platform, then a frame played by ExoPlayer for containers the platform can't open.
 * Frames that took real work are kept on disk, so a feed doesn't decode them again on every scroll.
 * When all of those fail it throws, and [PlaceholderInterceptor] draws a clean glyph in its place.
 */
internal class VideoThumbnailFetcher(private val file: File, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val requested = maxOf(
            options.size.width.pxOrElse { DefaultSide },
            options.size.height.pxOrElse { DefaultSide }
        )
        val side = Buckets.firstOrNull { requested <= it } ?: Buckets.last()
        val frameMillis = options.parameters.value<Long>(VideoFrameDecoder.VIDEO_FRAME_MICROS_KEY)?.div(1000) ?: DefaultFrameMillis
        val key = "${file.path}|${file.lastModified()}|$side|$frameMillis"

        val context = options.context
        val failureKey = file.path + file.lastModified()
        if (failures.get(failureKey) == true || withContext(Dispatchers.IO) { failureMarker(context, failureKey).exists() }) {
            failures.put(failureKey, true)
            throw IOException("No frame in ${file.name}")
        }
        val bitmap = gate.withPermit {
            cached(context, key)
                ?: fromMediaStore(context, side)
                ?: fromRetriever(side, frameMillis)?.also { store(context, key, it) }
                ?: fromPlayer(context, side, frameMillis)?.also { store(context, key, it) }
        }
        if (bitmap == null) {
            failures.put(failureKey, true)
            withContext(Dispatchers.IO) {
                try {
                    failureMarker(context, failureKey).apply { parentFile?.mkdirs() }.createNewFile()
                } catch (_: Exception) {
                }
            }
            throw IOException("No frame in ${file.name}")
        }
        return DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun fromMediaStore(context: Context, side: Int): Bitmap? = try {
        val resolver = context.contentResolver
        val id = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.path),
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        when {
            id == null -> null
            Build.VERSION.SDK_INT >= 29 -> resolver.loadThumbnail(
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                android.util.Size(side, side),
                null
            )
            else -> @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(
                resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
            )
        }
    } catch (_: Exception) {
        // The system refuses for anything it can't decode itself, which is why the rest of this exists.
        null
    }

    private fun fromRetriever(side: Int, frameMillis: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val time = frameMillis * 1000
            val frame = frameAt(retriever, time, side)
                // Asking past the end of a short clip finds nothing.
                ?: frameAt(retriever, 0L, side)
            if (frame != null) return frame
            retriever.embeddedPicture?.let { picture ->
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeByteArray(picture, 0, picture.size, options)
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, side: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun fromPlayer(context: Context, side: Int, frameMillis: Long): Bitmap? =
        // Player-side frames need a player's whole setup, so only one runs at a time.
        playerGate.withPermit { ExoFrameGrabber.grab(context, file, side, frameMillis) }

    private suspend fun cached(context: Context, key: String): Bitmap? = withContext(Dispatchers.IO) {
        val cachedFile = cacheFile(context, key)
        if (cachedFile.exists()) BitmapFactory.decodeFile(cachedFile.path) else null
    }

    private fun store(context: Context, key: String, bitmap: Bitmap) {
        try {
            val target = cacheFile(context, key)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            trimOccasionally(target.parentFile)
        } catch (_: Exception) {
            // A thumbnail not saved is only a thumbnail decoded again later.
        }
    }

    private fun cacheFile(context: Context, key: String): File =
        File(File(context.cacheDir, "video_thumbs"), "${digestOf(key)}.jpg")

    private fun failureMarker(context: Context, key: String): File =
        File(File(context.cacheDir, "video_thumbs"), "${digestOf(key)}.none")

    private fun digestOf(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun trimOccasionally(dir: File?) {
        if (dir == null || ++storedSinceTrim < TrimEvery) return
        storedSinceTrim = 0
        val files = dir.listFiles() ?: return
        if (files.size <= MaxCachedFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - MaxCachedFiles).forEach { it.delete() }
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (FileKinds.isVideo(data.name)) VideoThumbnailFetcher(data, options) else null
    }

    private companion object {
        const val DefaultSide = 1024
        const val DefaultFrameMillis = 1000L
        val Buckets = intArrayOf(384, 768, 1280)
        const val MaxCachedFiles = 4000
        const val TrimEvery = 200

        @Volatile
        var storedSinceTrim = 0

        // Decoding a frame costs a codec, and a fast scroll would otherwise ask for dozens at once.
        val gate = Semaphore(4)
        val playerGate = Semaphore(1)

        // A file that yields nothing would otherwise retry all its slow fallbacks on every scroll.
        val failures = LruCache<String, Boolean>(2048)
    }
}
