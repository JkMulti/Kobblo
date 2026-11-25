package com.kobblo.gallery.file.explorer.viewer.decoder

import android.content.Context
import android.net.Uri
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.Decoder
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.decode.VideoFrameDecoder
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.DocumentThumbnailFetcher
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File

/**
 * The app's one image loader, which knows every format the viewer handles. Everything that draws a
 * file should go through it, by leaving `imageLoader` out of `AsyncImage`.
 */
object ViewerImages {
    /** Set on a request that wants movement. Tiles leave it out, so a feed of GIFs stays still and cheap. */
    const val Animate = "viewer.animate"

    /** Which page of a multi-page file to decode. */
    const val Page = "viewer.page"

    fun buildLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            // First, so an encrypted Vault file is drawn from its sealed bytes and never read as it is.
            add(com.kobblo.gallery.file.explorer.vault.VaultThumbnailFetcher.Factory())
            add(VideoThumbnailFetcher.Factory())
            add(DocumentThumbnailFetcher.Factory())
            add(DocumentThumbnailFetcher.UriFactory())
            add(StillUnlessAsked(if (Build.VERSION.SDK_INT >= 28) ImageDecoderDecoder.Factory() else GifDecoder.Factory()))
            add(ApngDecoder.Factory())
            add(SvgDecoder.Factory())
            add(RasterDecoder.Factory())
            add(RawDecoder.Factory())
            // A content:// video has no file name, so it reaches this by its MIME type instead.
            add(VideoFrameDecoder.Factory())
            add(PlaceholderInterceptor())
        }
        .crossfade(true)
        .build()

    /** What Coil should load for a file: a path, or a `content://` stream handed over by another app. */
    fun model(file: FileItem): Any =
        if (file.path.startsWith("content://")) Uri.parse(file.path) else File(file.path)

    /** A video's still while its player starts, beginning from the tile it was opened from. */
    fun posterRequest(context: Context, file: FileItem, longSidePx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(model(file))
            .size(longSidePx)
            .apply { tileInMemory(context, file)?.let { placeholderMemoryCacheKey(it) } }
            .build()

    /** Home's grid keys its tiles by the system's id and everywhere else keys them by path. */
    private fun tileInMemory(context: Context, file: FileItem): MemoryCache.Key? {
        val keys = Coil.imageLoader(context).memoryCache?.keys ?: return null
        val gridPrefix = "gallery:${file.id}:"
        val filePrefix = "${file.path}:"
        return keys.firstOrNull { it.key.startsWith(gridPrefix) || it.key.startsWith(filePrefix) }
    }

    /** The longest side a picture is decoded to when there's no way to come back for more detail. */
    private const val MaxDecodeSide = 4096

    /** The request for the viewer's main picture, animated and at the page asked for. */
    fun fullRequest(context: Context, file: FileItem, page: Int): ImageRequest {
        val isVector = FileKinds.extensionOf(file.name) == "svg"
        // Where the part on screen can be decoded later, the picture only needs to fill the screen.
        // A vector drawn much larger than that makes everyone wait for sharpness few would zoom to see.
        val metrics = context.resources.displayMetrics
        val fitsScreen = isVector || (!file.path.startsWith("content://") && supportsRegionDecoding(file.name))
        val longSide = if (fitsScreen) maxOf(metrics.widthPixels, metrics.heightPixels) else MaxDecodeSide
        return ImageRequest.Builder(context)
            .data(model(file))
            .size(longSide)
            // A vector has no size of its own to stop at, so it draws to whatever is asked for.
            .precision(if (isVector) Precision.EXACT else Precision.INEXACT)
            .setParameter(Animate, true, memoryCacheKey = "1")
            .setParameter(Page, page, memoryCacheKey = page.toString())
            .apply {
                // The tile this was opened from is already in memory, so it shows at once while
                // the full picture decodes. This is what makes opening a photo feel instant.
                if (page == 0) tileInMemory(context, file)?.let { placeholderMemoryCacheKey(it) }
            }
            .build()
    }
}

/** Keeps a decoder away from every request that didn't ask for movement. */
private class StillUnlessAsked(private val delegate: Decoder.Factory) : Decoder.Factory {
    override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? =
        if (options.parameters.value<Boolean>(ViewerImages.Animate) == true) delegate.create(result, options, imageLoader) else null
}
