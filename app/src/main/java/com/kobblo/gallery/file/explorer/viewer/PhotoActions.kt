package com.kobblo.gallery.file.explorer.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.print.PrintHelper
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.freeFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The smaller things a viewer can do with what's on screen. None of them changes the original. */
object PhotoActions {
    /** The picture itself rather than its path, for pasting into a chat or a document. */
    fun copyImage(context: Context, file: FileItem) {
        val clip = ClipData.newUri(context.contentResolver, file.name, viewerUriFor(context, file))
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        confirmCopied(context, R.string.viewer_copied_image)
    }

    fun copyText(context: Context, label: String, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        confirmCopied(context, R.string.viewer_copied_details)
    }

    // From Android 13 the system shows what was copied, and a second message would only be noise.
    private fun confirmCopied(context: Context, message: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** Offers a photo to whatever on this phone takes one: a contact's picture, a profile, a wallpaper app. */
    fun setAs(context: Context, file: FileItem) {
        val mime = FileKinds.mimeTypeOf(file.name)
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(viewerUriFor(context, file), mime)
            putExtra("mimeType", mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.viewer_set_as)))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.viewer_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** Hands a photo to the system's print dialog, which brings its own preview and choice of printer. */
    fun print(context: Context, file: FileItem) {
        try {
            PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(file.name, viewerUriFor(context, file))
        } catch (_: Exception) {
            Toast.makeText(context, R.string.viewer_cant_print, Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves the frame at [positionMs] as a new photo beside the video, and says what it's called. */
    suspend fun saveFrame(context: Context, file: FileItem, positionMs: Long): File = withContext(Dispatchers.IO) {
        val source = File(file.path)
        val folder = source.parentFile ?: throw IOException("No folder")
        val retriever = MediaMetadataRetriever()
        val frame: Bitmap = try {
            retriever.setDataSource(file.path)
            retriever.getFrameAtTime(TimeUnit.MILLISECONDS.toMicros(positionMs), MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw IOException("No frame there")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        val taken = folder.list().orEmpty().toHashSet()
        val target = File(folder, freeFileName(frameName(source.nameWithoutExtension, positionMs)) { it in taken })
        val temp = File(folder, ".${target.name}.saving")
        try {
            temp.outputStream().use { output ->
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw IOException("Couldn't encode the frame")
            }
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("Couldn't save the frame")
        } finally {
            temp.delete()
            frame.recycle()
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.path), null, null)
        target
    }

    /** `clip_frame_1m05s.jpg` for a frame at 1:05. */
    internal fun frameName(base: String, positionMs: Long): String {
        val seconds = positionMs / 1000
        val time = if (seconds >= 60) "%dm%02ds".format(Locale.ROOT, seconds / 60, seconds % 60) else "${seconds}s"
        return "${base}_frame_$time.jpg"
    }

    /** Opens a coordinate in whatever maps app is here. Nothing is sent unless that app sends it. */
    fun mapsIntent(latitude: Double, longitude: Double): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude"))
}
