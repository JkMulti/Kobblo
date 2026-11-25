package com.kobblo.gallery.file.explorer.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * What a tile asks Coil for. The system's id lets it use the system's thumbnail cache.
 *
 * @param forViewer also leaves the picture where the viewer looks for it. Not for a passing use, which would push things
 * out of memory that a tile needs, and replace a sharper picture with this one.
 */
internal data class GalleryThumb(val id: Long, val path: String, val isVideo: Boolean, val sizePx: Int, val forViewer: Boolean = true)

/**
 * Serves tiles from the system's thumbnail cache. Decoding a 12 MP photo for a tile takes tens of
 * milliseconds and a video frame far longer, while the cached thumbnail takes a few, which is what
 * keeps a fast scroll through a large library from outrunning the pictures.
 *
 * Anything the system can't thumbnail goes on through the normal pipeline as a file, so every format
 * the app's own decoders know still gets a picture.
 */
internal class GalleryThumbFetcher(
    private val thumb: GalleryThumb,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thumb.id > 0) {
            val fromSystem = try {
                loadSystemThumbnail()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fromSystem != null) return fromSystem
        }
        val next = imageLoader.components.newFetcher(File(thumb.path), options, imageLoader)
        return next?.first?.fetch()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun loadSystemThumbnail(): FetchResult {
        val collection = if (thumb.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(collection, thumb.id)
        val signal = CancellationSignal()
        val handle = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
        try {
            val bitmap = runInterruptible {
                context.contentResolver.loadThumbnail(uri, Size(thumb.sizePx, thumb.sizePx), signal)
            }
            // The viewer shows the tile it was opened from while the full picture decodes, and looks it
            // up by path and date. Tiles have keys of their own, so it needs telling about this one too.
            if (thumb.forViewer) {
                imageLoader.memoryCache?.set(
                    MemoryCache.Key("${thumb.path}:${File(thumb.path).lastModified()}"),
                    MemoryCache.Value(bitmap)
                )
            }
            return DrawableResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                // Not marked as sampled: each size has its own cache key, so a thumbnail a little
                // smaller than asked for must count as a hit rather than being fetched again.
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } finally {
            handle.dispose()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<GalleryThumb> {
        override fun create(data: GalleryThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            GalleryThumbFetcher(data, options, imageLoader, context.applicationContext)
    }
}
