package com.kobblo.gallery.file.explorer.vault

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.kobblo.gallery.file.explorer.ui.DocumentThumbnailFetcher
import com.kobblo.gallery.file.explorer.ui.expressive.fileCategoryForName
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lets Coil draw thumbnails of encrypted files, through the same `File` model as any other file.
 * Images and videos stream straight from the ciphertext and never touch the disk in plaintext. Coil
 * only writes its disk cache for network images, and its memory cache is cleared when the vault
 * locks. Document thumbnails need a real file, so they get a copy that lasts as long as the render.
 */
internal class VaultThumbnailFetcher(
    private val file: File,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val store = VaultManager.fileStore() ?: throw IOException("Vault is locked")
        val summary = store.summarize(file) ?: throw IOException("Unreadable file")
        val name = summary.info.name
        return when (fileCategoryForName(name)) {
            "images" -> SourceResult(
                source = ImageSource(store.open(file).source().buffer(), options.context),
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")),
                dataSource = DataSource.DISK
            )
            "videos" -> videoFrame(store)
            else -> document(name, summary.sizeBytes)
        }
    }

    private fun videoFrame(store: VaultStore): FetchResult {
        store.openSeekable(file).use { channel ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ChannelDataSource(channel))
                val frame = retriever.getFrameAtTime(VIDEO_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IOException("No frame to show")
                return DrawableResult(
                    drawable = BitmapDrawable(options.context.resources, frame.scaledToThumbnail()),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            } finally {
                retriever.release()
            }
        }
    }

    private suspend fun document(name: String, sizeBytes: Long): FetchResult {
        if (sizeBytes > MAX_DOCUMENT_BYTES) throw IOException("Too large to preview")
        val copy = VaultManager.decryptToCache(options.context, file, name) ?: throw IOException("Couldn't read file")
        try {
            return DocumentThumbnailFetcher(copy, options).fetch() ?: throw IOException("No preview for this type")
        } finally {
            VaultManager.discardDecryptedCopy(copy.path)
        }
    }

    private fun Bitmap.scaledToThumbnail(): Bitmap {
        val scale = THUMBNAIL_PX.toFloat() / max(width, height)
        if (scale >= 1f) return this
        return Bitmap.createScaledBitmap(this, (width * scale).roundToInt(), (height * scale).roundToInt(), true)
    }

    private class ChannelDataSource(private val channel: SeekableByteChannel) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            synchronized(channel) {
                channel.position(position)
                channel.read(ByteBuffer.wrap(buffer, offset, size))
            }

        override fun getSize(): Long = synchronized(channel) { channel.size() }

        // The fetcher owns the channel.
        override fun close() = Unit
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (VaultManager.isVaultCiphertext(options.context, data)) VaultThumbnailFetcher(data, options) else null
    }

    private companion object {
        const val VIDEO_FRAME_US = 1_000_000L
        const val THUMBNAIL_PX = 256
        const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
    }
}
