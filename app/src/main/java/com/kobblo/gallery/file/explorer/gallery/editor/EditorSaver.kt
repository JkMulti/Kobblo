package com.kobblo.gallery.file.explorer.gallery.editor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Build
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.gallery.extensionOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Turns an edit into a file. The photo is decoded again at full size, painted, and written beside the
 * original under a temporary name, and only a finished, readable file ever takes a real name, so a
 * failed or interrupted save leaves the original alone.
 */
internal object EditorSaver {
    sealed interface Result {
        /** [reduced] is true when the photo was too large for memory and was saved smaller. */
        data class Saved(val path: String, val width: Int, val height: Int, val reduced: Boolean) : Result
        data class Failed(val reason: Reason) : Result
    }

    enum class Reason { DECODE, MEMORY, WRITE }

    /** Replacing needs a format that can be written back as itself. */
    fun canReplace(path: String): Boolean = extensionOf(path) in setOf("jpg", "jpeg", "png", "webp")

    /** Tags that describe the moment and the camera. The old thumbnail stays behind, since it would show what a brush hid. */
    private val KeptTags = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        "LensMake",
        "LensModel",
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    suspend fun save(context: Context, sourcePath: String, state: EditState, replace: Boolean): Result =
        withContext(Dispatchers.Default) {
            val source = File(sourcePath)
            val folder = source.parentFile ?: return@withContext Result.Failed(Reason.WRITE)
            val extension = extensionOf(source.name)
            val bounds = EditorDecoder.boundsOf(sourcePath)

            // Room for the photo, its output, and everything else the system is doing.
            val budget = pixelBudget(context)
            val fullWidth = bounds?.width ?: 0
            val fullHeight = bounds?.height ?: 0
            val wanted = wantedSample(state.outputScale)
            var sample = if (bounds != null) maxOf(wanted, sampleSizeFor(fullWidth, fullHeight, budget)) else wanted
            val temp = File(folder, ".${source.name}.editing")

            while (sample <= MaxSample) {
                try {
                    val decoded = decodeForSave(context, sourcePath, bounds, sample)
                        ?: return@withContext Result.Failed(Reason.DECODE)
                    if (state.strokes.isNotEmpty()) {
                        val painted = if (decoded.isMutable) decoded else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                        BrushEffects(painted).bake(painted, state.strokes)
                        return@withContext finish(context, source, folder, extension, temp, painted, state, bounds, replace, sample, wanted)
                    }
                    return@withContext finish(context, source, folder, extension, temp, decoded, state, bounds, replace, sample, wanted)
                } catch (e: OutOfMemoryError) {
                    // A bad guess at what's free. Half the width is a quarter of the memory.
                    temp.delete()
                    sample *= 2
                }
            }
            Result.Failed(Reason.MEMORY)
        }

    private suspend fun decodeForSave(context: Context, path: String, bounds: EditorDecoder.Bounds?, sample: Int): Bitmap? =
        if (bounds != null) EditorDecoder.decode(path, sample)
        else EditorDecoder.decodeThroughCoil(context, path, CoilMaxSide / sample)

    private suspend fun finish(
        context: Context,
        source: File,
        folder: File,
        extension: String,
        temp: File,
        decoded: Bitmap,
        state: EditState,
        bounds: EditorDecoder.Bounds?,
        replace: Boolean,
        sample: Int,
        wantedSample: Int
    ): Result {
        // A photo decoded at a smaller size needs less enlarging to reach the asked-for one.
        val fullWidth = bounds?.width ?: decoded.width
        val renderScale = (state.outputScale * fullWidth / decoded.width.toFloat()).coerceAtMost(1f)
        // The rendered bitmap always claims alpha, so only the photo itself can say whether it has any.
        val hasAlpha = decoded.hasAlpha()
        val output = EditorRender.render(decoded, state, renderScale)
        decoded.recycle()
        val format = formatFor(extension, hasAlpha)
        val written = try {
            FileOutputStream(temp).use { stream ->
                output.compress(format.compress, format.quality, stream).also {
                    stream.flush()
                    stream.fd.sync()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PhotoEditor", "Couldn't write the edited photo", e)
            false
        }
        val width = output.width
        val height = output.height
        output.recycle()
        if (!written) {
            temp.delete()
            return Result.Failed(Reason.WRITE)
        }
        // A file that can't be read back must not replace anything.
        val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.path, check)
        if (check.outWidth != width || check.outHeight != height) {
            temp.delete()
            return Result.Failed(Reason.WRITE)
        }
        copyExif(source.path, temp.path, width, height)

        val target = if (replace && canReplace(source.path)) {
            val keptDate = source.lastModified()
            val repository = FileRepository(context.applicationContext)
            val binned = withContext(Dispatchers.IO) {
                // The original isn't gone, only moved to Recently Deleted.
                repository.moveToTrash(listOf(source.path)).moved.isNotEmpty()
            }
            if (!binned || source.exists()) {
                temp.delete()
                return Result.Failed(Reason.WRITE)
            }
            if (!temp.renameTo(source)) {
                // A kill right here would leave the original safe in the Bin. Failing here must not leave it
                // there with the person told the save failed, so it goes straight back.
                temp.delete()
                withContext(Dispatchers.IO) {
                    repository.getTrashFiles(0L)
                        .filter { it.originalPath == source.path }
                        .maxByOrNull { it.lastModified }
                        ?.encodedTrashName
                        ?.let { repository.restoreFromTrash(listOf(it)) }
                }
                return Result.Failed(Reason.WRITE)
            }
            // Just past the old date: the feed keeps the photo in its place, and anything caching by date sees a new file.
            source.setLastModified(keptDate + 1000L)
            source
        } else {
            val name = editedFileName(source.name, format.extension) { File(folder, it).exists() }
            val copy = File(folder, name)
            if (!temp.renameTo(copy)) {
                temp.delete()
                return Result.Failed(Reason.WRITE)
            }
            copy
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.path), null, null)
        return Result.Saved(target.path, width, height, reduced = sample > wantedSample)
    }

    private class OutputFormat(val compress: Bitmap.CompressFormat, val quality: Int, val extension: String)

    private fun formatFor(extension: String, hasAlpha: Boolean): OutputFormat = when (extension) {
        "png" -> OutputFormat(Bitmap.CompressFormat.PNG, 100, "png")
        "webp" -> {
            val webp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            OutputFormat(webp, 95, "webp")
        }
        "jpeg" -> OutputFormat(Bitmap.CompressFormat.JPEG, 95, "jpeg")
        "jpg" -> OutputFormat(Bitmap.CompressFormat.JPEG, 95, "jpg")
        // Everything else can be read but not written, so a copy goes to the nearest common format.
        else -> if (hasAlpha) OutputFormat(Bitmap.CompressFormat.PNG, 100, "png") else OutputFormat(Bitmap.CompressFormat.JPEG, 95, "jpg")
    }

    internal fun copyExif(from: String, to: String, width: Int, height: Int) {
        try {
            val old = ExifInterface(from)
            val new = ExifInterface(to)
            for (tag in KeptTags) old.getAttribute(tag)?.let { new.setAttribute(tag, it) }
            // The pixels are upright now, whatever the original's flag said.
            new.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            new.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            new.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            new.saveAttributes()
        } catch (e: Exception) {
            // Losing the tags isn't worth losing the save.
            android.util.Log.w("PhotoEditor", "Couldn't keep the photo's details")
        }
    }

    /** Asking for half the size or less means the decoder can do most of the shrinking, which also looks better. */
    private fun wantedSample(outputScale: Float): Int = when {
        outputScale <= 0.25f -> 4
        outputScale <= 0.5f -> 2
        else -> 1
    }

    private fun pixelBudget(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        // Two full-size copies at four bytes a pixel, in about half of what's free.
        return (memory.availMem / 2 / BytesPerPixelPair).coerceIn(MinPixels, MaxPixels)
    }

    private const val BytesPerPixelPair = 8L
    private const val MinPixels = 4_000_000L
    private const val MaxPixels = 200_000_000L
    private const val MaxSample = 32
    private const val CoilMaxSide = 8192
}
