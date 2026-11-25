package com.kobblo.gallery.file.explorer.viewer.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Scale
import coil.size.pxOrElse
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

private fun ImageSource.head(bytes: Long): ByteArray {
    val peek = source().peek()
    val buffer = Buffer()
    var left = bytes
    while (left > 0) {
        val read = peek.read(buffer, left)
        if (read < 0) break
        left -= read
    }
    return buffer.readByteArray()
}

private fun ImageSource.fileName(): String? = fileOrNull()?.name

/** Maps the file rather than reading it, so a huge picture doesn't have to fit on the heap first. */
private fun mapFile(file: File): ByteBuffer {
    if (file.length() > Int.MAX_VALUE) throw ImageFormatException("File too large")
    return RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
}

private fun Options.decodeTarget(): DecodeTarget = DecodeTarget(
    size.width.pxOrElse { Int.MAX_VALUE },
    size.height.pxOrElse { Int.MAX_VALUE },
    fill = scale == Scale.FILL
)

/** Hands one of the pure-Kotlin decoders its file and turns what comes back into a bitmap. */
internal class RasterDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val format: Format
) : Decoder {
    enum class Format { Tiff, Psd, Tga, Pnm }

    override suspend fun decode(): DecodeResult = runInterruptible {
        val data = mapFile(source.file().toFile())
        val page = options.parameters.value<Int>(ViewerImages.Page) ?: 0
        val target = options.decodeTarget()
        val image = when (format) {
            Format.Tiff -> TiffDecoder.decode(data, page, target)
            Format.Psd -> PsdDecoder.decode(data, target)
            Format.Tga -> TgaDecoder.decode(data, target)
            Format.Pnm -> PnmDecoder.decode(data, target)
        }
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        DecodeResult(BitmapDrawable(options.context.resources, bitmap), image.isSampled)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val name = result.source.fileName()
            // Most RAW files are TIFFs underneath, but showing one that way would find only a thumbnail.
            if (name != null && FileKinds.isRaw(name)) return null
            val head = result.source.head(32)
            val isTga = (name != null && FileKinds.extensionOf(name) == "tga") || result.mimeType == "image/x-tga"
            val format = when {
                TiffDecoder.isTiff(head) -> Format.Tiff
                PsdDecoder.isPsd(head) -> Format.Psd
                PnmDecoder.isPnm(head) -> Format.Pnm
                isTga && TgaDecoder.looksLikeTga(head) -> Format.Tga
                else -> return null
            }
            return RasterDecoder(result.source, options, format)
        }
    }
}

/**
 * Plays an APNG, which the platform would stop on its first frame. Frames are composed ahead of
 * time, within a budget. Past that it declines, and the first frame is what shows.
 */
internal class ApngDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible {
        val file = source.file().toFile()
        if (file.length() > MaxFileBytes) throw ImageFormatException("APNG too large")
        val animation = ApngParser.parse(file.readBytes()) ?: return@runInterruptible stillFrame(file)
        if (animation.width.toLong() * animation.height * 4 * animation.frames.size > MaxAnimationBytes) {
            return@runInterruptible stillFrame(file)
        }
        DecodeResult(compose(animation), isSampled = false)
    }

    private fun stillFrame(file: File): DecodeResult {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("Unreadable PNG")
        return DecodeResult(BitmapDrawable(options.context.resources, bitmap), isSampled = false)
    }

    private fun compose(animation: ApngParser.Animation): AnimationDrawable {
        val resources = options.context.resources
        val canvasBitmap = Bitmap.createBitmap(animation.width, animation.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val result = AnimationDrawable()
        for (frame in animation.frames) {
            val rect = RectF(
                frame.x.toFloat(), frame.y.toFloat(),
                (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat()
            )
            val before = if (frame.dispose == ApngParser.DisposePrevious) {
                Bitmap.createBitmap(canvasBitmap, frame.x, frame.y, frame.width, frame.height)
            } else null
            if (frame.blend == ApngParser.BlendSource) canvas.drawRect(rect, clear)
            BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size)?.let {
                canvas.drawBitmap(it, frame.x.toFloat(), frame.y.toFloat(), null)
                it.recycle()
            }
            result.addFrame(BitmapDrawable(resources, canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)), frame.delayMillis)
            when (frame.dispose) {
                ApngParser.DisposeBackground -> canvas.drawRect(rect, clear)
                ApngParser.DisposePrevious -> before?.let {
                    canvas.drawRect(rect, clear)
                    canvas.drawBitmap(it, frame.x.toFloat(), frame.y.toFloat(), null)
                    it.recycle()
                }
            }
        }
        canvasBitmap.recycle()
        result.isOneShot = animation.plays == 1
        return result
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (options.parameters.value<Boolean>(ViewerImages.Animate) != true) return null
            if (!ApngParser.isAnimated(result.source.head(HeadBytes))) return null
            return ApngDecoder(result.source, options)
        }
    }

    private companion object {
        /** acTL has to come before the image data, and in practice comes right after the header. */
        const val HeadBytes = 64L * 1024
        const val MaxFileBytes = 64L * 1024 * 1024
        const val MaxAnimationBytes = 192L * 1024 * 1024
    }
}

/**
 * Camera RAW through the platform where it can, and otherwise the JPEG preview nearly every RAW file
 * carries inside. Android's decoder reads DNG properly and finds previews in several others, so it
 * goes first.
 */
internal class RawDecoder(private val source: ImageSource, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult = runInterruptible {
        val file = source.file().toFile()
        val bitmap = platformDecode(file) ?: previewDecode(file) ?: tiffDecode(file) ?: throw IOException("No picture in this RAW file")
        DecodeResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true)
    }

    private fun platformDecode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) })
    } catch (_: Exception) {
        null
    }

    private fun previewDecode(file: File): Bitmap? {
        val data = mapFile(file)
        val range = EmbeddedJpeg.findLargest(data) ?: return null
        val jpeg = ByteArray(range.length)
        data.position(range.offset)
        data.get(jpeg)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        val decoded = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        ) ?: return null
        // The preview doesn't know how the camera was held. The RAW file's own tags do.
        val degrees = try { ExifInterface(file).rotationDegrees } catch (_: Exception) { 0 }
        if (degrees == 0) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun tiffDecode(file: File): Bitmap? = try {
        val image = TiffDecoder.decode(mapFile(file), page = 0, target = options.decodeTarget())
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    } catch (_: ImageFormatException) {
        null
    }

    private fun sampleSize(width: Int, height: Int): Int {
        val step = options.decodeTarget().stepFor(width, height)
        var sample = 1
        while (sample * 2 <= step) sample *= 2
        return max(1, sample)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val name = result.source.fileName() ?: return null
            return if (FileKinds.isRaw(name)) RawDecoder(result.source, options) else null
        }
    }
}

/** The number of pages a file offers. Only TIFF has any to page through. */
internal fun pageCountOf(file: File): Int = try {
    if (FileKinds.extensionOf(file.name) in setOf("tif", "tiff")) {
        val data = mapFile(file)
        val head = ByteArray(4).also { data.duplicate().get(it) }
        if (TiffDecoder.isTiff(head)) TiffDecoder.pageCount(data) else 1
    } else 1
} catch (_: Exception) {
    1
}
