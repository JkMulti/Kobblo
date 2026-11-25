package com.kobblo.gallery.file.explorer.viewer.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.exifinterface.media.ExifInterface
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A rectangle in the picture's own pixels. */
internal data class SourceRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** The sums behind showing only the visible part of a zoomed picture at full detail. */
internal object RegionMath {
    /**
     * What part of an upright [imageWidth] by [imageHeight] picture is on screen, given the picture
     * fits a [boxWidth] by [boxHeight] screen and has been zoomed by [scale] and moved by the offsets.
     * Null when none of it is.
     */
    fun visibleRect(
        imageWidth: Float, imageHeight: Float,
        boxWidth: Float, boxHeight: Float,
        scale: Float, offsetX: Float, offsetY: Float
    ): SourceRect? {
        if (imageWidth <= 0f || imageHeight <= 0f || boxWidth <= 0f || boxHeight <= 0f || scale <= 0f) return null
        val pixelScale = minOf(boxWidth / imageWidth, boxHeight / imageHeight) * scale
        fun sourceX(x: Float) = imageWidth / 2f + (x - boxWidth / 2f - offsetX) / pixelScale
        fun sourceY(y: Float) = imageHeight / 2f + (y - boxHeight / 2f - offsetY) / pixelScale
        val left = sourceX(0f).coerceAtLeast(0f)
        val top = sourceY(0f).coerceAtLeast(0f)
        val right = sourceX(boxWidth).coerceAtMost(imageWidth)
        val bottom = sourceY(boxHeight).coerceAtMost(imageHeight)
        return if (right <= left || bottom <= top) null else SourceRect(left, top, right, bottom)
    }

    /**
     * Turns a rectangle on the upright picture into one on the file's stored pixels, which is what a
     * region decoder reads. Only the rotations are handled. Mirrored photos just stay soft when zoomed.
     */
    fun toStored(rect: SourceRect, orientation: Int, storedWidth: Float, storedHeight: Float): SourceRect? = when (orientation) {
        ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> rect
        ExifInterface.ORIENTATION_ROTATE_180 ->
            SourceRect(storedWidth - rect.right, storedHeight - rect.bottom, storedWidth - rect.left, storedHeight - rect.top)
        // Shown a quarter turn clockwise: its x axis runs down the screen and its y axis leftward.
        ExifInterface.ORIENTATION_ROTATE_90 ->
            SourceRect(rect.top, storedHeight - rect.right, rect.bottom, storedHeight - rect.left)
        ExifInterface.ORIENTATION_ROTATE_270 ->
            SourceRect(storedWidth - rect.bottom, rect.left, storedWidth - rect.top, rect.right)
        else -> null
    }

    /** The largest power of two that still leaves the region at least as sharp as the screen. */
    fun sampleSize(rectWidth: Float, rectHeight: Float, boxWidth: Float, boxHeight: Float): Int {
        val ratio = minOf(rectWidth / boxWidth, rectHeight / boxHeight)
        var sample = 1
        while (sample * 2 <= ratio) sample *= 2
        return sample
    }
}

/** Formats the platform can decode a part of, which is what makes a smaller base picture safe. */
internal fun supportsRegionDecoding(name: String): Boolean = when (FileKinds.extensionOf(name)) {
    "jpg", "jpeg", "jpe", "jfif", "png", "webp" -> true
    "heic", "heif" -> Build.VERSION.SDK_INT >= 31
    else -> false
}

private class LoadedRegion(val image: ImageBitmap, val rect: SourceRect)

/** Facts about the file that stay the same however it's zoomed. */
private class RegionSource(
    val decoder: BitmapRegionDecoder,
    val orientation: Int,
    val storedWidth: Int,
    val storedHeight: Int
) {
    private val isSideways = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270
    val uprightWidth = if (isSideways) storedHeight else storedWidth
    val uprightHeight = if (isSideways) storedWidth else storedHeight
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

private fun openRegionSource(path: String): RegionSource? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val orientation = try {
        ExifInterface(File(path)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val decoder = if (Build.VERSION.SDK_INT >= 31) {
        BitmapRegionDecoder.newInstance(path)
    } else {
        @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(path, false)
    }
    if (bounds.outWidth > 0 && bounds.outHeight > 0) RegionSource(decoder, orientation, bounds.outWidth, bounds.outHeight) else null
} catch (_: Exception) {
    null
}

/**
 * Draws the part of a zoomed photo that's on screen at the file's full detail, over the screen-sized
 * picture beneath it. Holding one region rather than a grid of tiles keeps memory at about one
 * screenful however large the photo is, and the picture below stands in while a finger is moving.
 */
@Composable
internal fun SharpRegionLayer(
    path: String,
    scale: Float,
    offset: Offset,
    boxSize: IntSize,
    modifier: Modifier = Modifier
) {
    var source by remember(path) { mutableStateOf<RegionSource?>(null) }
    var region by remember(path) { mutableStateOf<LoadedRegion?>(null) }
    val isZoomedIn = scale > ZoomedInScale

    LaunchedEffect(path, isZoomedIn) {
        if (isZoomedIn && source == null) source = withContext(Dispatchers.IO) { openRegionSource(path) }
        if (!isZoomedIn) region = null
    }
    DisposableEffect(path) {
        onDispose { source?.decoder?.recycle() }
    }

    LaunchedEffect(source, scale, offset, boxSize) {
        val opened = source ?: return@LaunchedEffect
        if (!isZoomedIn || boxSize == IntSize.Zero) return@LaunchedEffect
        // Wait for the fingers to settle, so a pan isn't spent decoding places already left behind.
        delay(SettleMillis)
        val boxWidth = boxSize.width.toFloat()
        val boxHeight = boxSize.height.toFloat()
        val rect = RegionMath.visibleRect(
            opened.uprightWidth.toFloat(), opened.uprightHeight.toFloat(),
            boxWidth, boxHeight, scale, offset.x, offset.y
        ) ?: return@LaunchedEffect
        val stored = RegionMath.toStored(rect, opened.orientation, opened.storedWidth.toFloat(), opened.storedHeight.toFloat())
            ?: return@LaunchedEffect
        val sample = RegionMath.sampleSize(rect.width, rect.height, boxWidth, boxHeight)
        val loaded = withContext(Dispatchers.IO) {
            try {
                val bounds = Rect(
                    stored.left.toInt().coerceAtLeast(0),
                    stored.top.toInt().coerceAtLeast(0),
                    stored.right.roundToInt().coerceAtMost(opened.storedWidth),
                    stored.bottom.roundToInt().coerceAtMost(opened.storedHeight)
                )
                if (bounds.isEmpty) return@withContext null
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = opened.decoder.decodeRegion(bounds, options) ?: return@withContext null
                val upright = if (opened.degrees == 0) decoded else {
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(opened.degrees.toFloat()) }, true)
                        .also { if (it !== decoded) decoded.recycle() }
                }
                LoadedRegion(upright.asImageBitmap(), rect)
            } catch (_: Exception) {
                null
            }
        }
        if (isActive && loaded != null) region = loaded
    }

    val shown = region
    val opened = source
    if (shown != null && opened != null && isZoomedIn) {
        Canvas(modifier.fillMaxSize()) {
            val pixelScale = minOf(size.width / opened.uprightWidth, size.height / opened.uprightHeight) * scale
            val left = size.width / 2f + offset.x + pixelScale * (shown.rect.left - opened.uprightWidth / 2f)
            val top = size.height / 2f + offset.y + pixelScale * (shown.rect.top - opened.uprightHeight / 2f)
            drawImage(
                image = shown.image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(
                    max(1, (shown.rect.width * pixelScale).roundToInt()),
                    max(1, (shown.rect.height * pixelScale).roundToInt())
                )
            )
        }
    }
}

private const val ZoomedInScale = 1.15f
private const val SettleMillis = 180L
