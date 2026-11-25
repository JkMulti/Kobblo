package com.kobblo.gallery.file.explorer.music

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.Options
import coil.size.pxOrElse
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What a cover is loaded from: the song it belongs to, and the album all its songs share. */
data class CoverRequest(val path: String, val mediaId: Long, val albumId: Long)

/**
 * Finds a song's cover. MediaStore keeps thumbnails on disk, so asking it first keeps a long list
 * smooth even after Coil's memory cache has moved on. The file itself and a cover image beside it
 * are for whatever MediaStore can't help with: older Androids, and files it has never scanned.
 */
private class CoverFetcher(
    private val context: Context,
    private val request: CoverRequest,
    private val options: Options
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (NoCover.containsKey(request.path)) throw IOException("No cover")
        val side = options.size.width.pxOrElse { DefaultSidePx }.coerceAtLeast(1)
        val bitmap = fromMediaStore(side) ?: fromFile(side)
        if (bitmap == null) {
            NoCover[request.path] = true
            throw IOException("No cover")
        }
        return DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun fromMediaStore(side: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || request.mediaId <= 0L) return null
        return try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, request.mediaId)
            context.contentResolver.loadThumbnail(uri, Size(side, side), null)
        } catch (_: Exception) {
            null
        }
    }

    private fun fromFile(side: Int): Bitmap? {
        val embedded = readSongTags(request.path, otherwise = null) { tags -> tags.embeddedPicture }
        if (embedded != null) return decodeSampled(side) { opts -> BitmapFactory.decodeByteArray(embedded, 0, embedded.size, opts) }
        val cover = File(request.path).parentFile?.let { findCoverFile(it) } ?: return null
        return decodeSampled(side) { opts -> BitmapFactory.decodeFile(cover.path, opts) }
    }

    private inline fun decodeSampled(side: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        return decode(BitmapFactory.Options().apply { inSampleSize = sample })
    }

    class Factory : Fetcher.Factory<CoverRequest> {
        override fun create(data: CoverRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(options.context, data, options)
    }

    companion object {
        private const val DefaultSidePx = 512

        // Most files in a big untagged folder have no cover, and looking again is not cheap.
        val NoCover = ConcurrentHashMap<String, Boolean>()
    }
}

/** Forgets which songs had no cover, for when the library has changed under us. */
internal fun forgetMissingCovers() = CoverFetcher.NoCover.clear()

private val CoverFactory = CoverFetcher.Factory()

/**
 * A song's cover, or a quiet note where there's none. Songs of one album share a cache entry, so an
 * album scrolling past decodes its cover only once.
 */
@Composable
fun MusicCover(
    track: MusicTrack?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    placeholderTint: Color = KobbloDesign.colors.smoke,
    placeholderBackground: Color = KobbloDesign.colors.mist,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val sidePx = with(LocalDensity.current) { size.roundToPx() }
    val cacheKey = track?.let { if (it.albumId > 0L && it.album.isNotBlank()) "cover:${it.albumId}:$sidePx" else "cover:${it.path}:$sidePx" }
    val request = remember(cacheKey, track?.path) {
        track?.let {
            ImageRequest.Builder(context)
                .data(CoverRequest(it.path, it.mediaId, it.albumId))
                .fetcherFactory(CoverFactory)
                .memoryCacheKey(cacheKey)
                .size(sidePx)
                .crossfade(false)
                .build()
        }
    }
    var loaded by remember(cacheKey) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(placeholderBackground),
        contentAlignment = Alignment.Center
    ) {
        if (!loaded) {
            Icon(AppIcons.Music, contentDescription = null, tint = placeholderTint, modifier = Modifier.size(size * 0.48f))
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { loaded = false }
            )
        }
    }
}
