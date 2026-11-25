package com.kobblo.gallery.file.explorer.viewer.decoder

import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A decoded picture as packed ARGB, ready for `Bitmap.createBitmap`. */
class RasterImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** The picture's real size, which is larger than [width] when it was decoded at a reduced size. */
    val fullWidth: Int = width,
    val fullHeight: Int = height
) {
    val isSampled: Boolean get() = width != fullWidth || height != fullHeight
}

/** The file isn't what its name says, or it uses a part of the format we don't read. */
class ImageFormatException(message: String) : IOException(message)

internal fun unsupported(message: String): Nothing = throw ImageFormatException(message)

/** Reading past the end of a truncated file must fail as a format problem rather than crash. */
internal inline fun <T> checkedRead(block: () -> T): T = try {
    block()
} catch (e: IndexOutOfBoundsException) {
    throw ImageFormatException("Truncated file: ${e.message}")
} catch (e: java.nio.BufferUnderflowException) {
    throw ImageFormatException("Truncated file")
} catch (e: IllegalArgumentException) {
    throw ImageFormatException("Corrupt file: ${e.message}")
}

/**
 * Enough for any screen when zoomed, and what a normal heap can spare for one picture. It's also
 * what keeps a hostile header from allocating gigabytes.
 */
internal const val MaxDecodedPixels = 24_000_000L

/** How big a picture is wanted, so a decoder can skip pixels no one will see. */
class DecodeTarget(
    private val width: Int = Int.MAX_VALUE,
    private val height: Int = Int.MAX_VALUE,
    /** True when the picture will be cropped to fill the target, so its short side must still cover it. */
    private val fill: Boolean = false
) {
    /** The whole-number step to take through a picture, never landing below what was asked for. */
    fun stepFor(pictureWidth: Int, pictureHeight: Int): Int {
        if (pictureWidth <= 0 || pictureHeight <= 0) unsupported("Bad dimensions ${pictureWidth}x$pictureHeight")
        val acrossWidth = pictureWidth.toDouble() / width.coerceAtLeast(1)
        val acrossHeight = pictureHeight.toDouble() / height.coerceAtLeast(1)
        val ratio = if (fill) min(acrossWidth, acrossHeight) else max(acrossWidth, acrossHeight)
        var step = max(1, floor(ratio).toInt())
        while (sampledSize(pictureWidth, step).toLong() * sampledSize(pictureHeight, step) > MaxDecodedPixels) step++
        return step
    }

    companion object {
        val Original = DecodeTarget()
        fun fit(maxSide: Int) = DecodeTarget(maxSide, maxSide)
    }
}

internal fun sampledSize(size: Int, step: Int): Int = (size + step - 1) / step

/** Bytes without sign extension. */
internal fun ByteBuffer.u8(index: Int): Int = get(index).toInt() and 0xFF

internal fun opaque(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

internal fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** Decodes PackBits, used by TIFF and PSD alike. Returns how many bytes it wrote. */
internal fun unpackBits(src: ByteBuffer, start: Int, srcLength: Int, dst: ByteArray, dstStart: Int = 0, dstLength: Int = dst.size - dstStart): Int {
    var read = start
    val readEnd = start + srcLength
    var write = dstStart
    val writeEnd = dstStart + dstLength
    while (read < readEnd && write < writeEnd) {
        val header = src.get(read++).toInt()
        when {
            header >= 0 -> {
                val count = minOf(header + 1, writeEnd - write, readEnd - read)
                for (i in 0 until count) dst[write++] = src.get(read + i)
                read += header + 1
            }
            header != -128 -> {
                if (read >= readEnd) break
                val value = src.get(read++)
                val count = minOf(1 - header, writeEnd - write)
                for (i in 0 until count) dst[write++] = value
            }
        }
    }
    return write - dstStart
}
