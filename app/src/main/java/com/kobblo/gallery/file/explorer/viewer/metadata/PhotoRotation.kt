package com.kobblo.gallery.file.explorer.viewer.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import androidx.exifinterface.media.ExifInterface
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.isOnSharedStorage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Turning a JPEG for good, by its orientation tag. The picture's data isn't touched, so nothing is lost to encoding
 * again, and turning it back is exactly as good as never having turned it. The tag is changed on a copy, which only
 * replaces the photo once it's on the disk and has been checked.
 */
object PhotoRotation {
    private val Jpegs = setOf("jpg", "jpeg", "jpe", "jfif")

    /** What else a photo can be turned by. Other formats would have to be encoded again, which is the editor's job. */
    fun canRotate(file: FileItem): Boolean =
        file.type == "image" && file.isOnSharedStorage && FileKinds.extensionOf(file.name) in Jpegs

    /** Blocks on the disk, so call it off the main thread. Throws if the photo couldn't be turned, and then it's as it was. */
    fun rotate(context: Context, path: String, degrees: Int) {
        val source = File(path)
        val temp = File(source.parentFile, ".${source.name}.turning")
        try {
            source.copyTo(temp, overwrite = true)
            val exif = ExifInterface(temp)
            val before = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            // A photo with no such tag reads as "undefined" here, and rotating undefined leaves it undefined.
            if (before == ExifInterface.ORIENTATION_UNDEFINED) {
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            }
            exif.rotate(degrees)
            exif.saveAttributes()
            val after = ExifInterface(temp).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            if (after == before || !stillTheSamePhoto(temp, source)) throw IOException("The turned copy didn't check out")
            // On the disk before it replaces anything, so a dead battery can't leave an empty photo.
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            // A second on, rather than the same date: the system's scanner skips a file that looks unchanged, and
            // then its thumbnail would stay the old way round. Its place in the feed barely moves.
            temp.setLastModified(source.lastModified() + 1_000L)
            if (!temp.renameTo(source)) throw IOException("Couldn't swap in the turned copy")
        } finally {
            temp.delete()
        }
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), null, null)
    }

    private fun stillTheSamePhoto(candidate: File, original: File): Boolean {
        if (candidate.length() <= 0L) return false
        val before = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, before)
        val after = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(candidate.path, after)
        return after.outWidth > 0 && after.outWidth == before.outWidth && after.outHeight == before.outHeight
    }
}
