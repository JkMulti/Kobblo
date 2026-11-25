package com.kobblo.gallery.file.explorer.viewer.metadata

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Taking metadata out of a photo or video. Everything here works on a new file. Replacing an original
 * only happens once a clean copy has been written and checked, and only when the user confirmed it.
 */
object MetadataActions {
    private val Jpegs = setOf("jpg", "jpeg", "jpe", "jfif")
    private val Mp4s = setOf("mp4", "m4v", "mov", "3gp", "3g2")
    private val Stills = setOf("png", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "tga", "psd", "ppm", "pgm", "pbm", "dng", "cr2", "nef", "arw")

    /** True when this file's metadata is something we know how to edit. */
    fun canEdit(file: FileItem): Boolean =
        !file.path.startsWith("content://") && FileKinds.extensionOf(file.name) in Jpegs + Mp4s

    /**
     * Whether a clean copy can be made to send. Beyond the formats edited above, a still picture can go out as its
     * pixels alone. A moving one would be flattened, which isn't what anyone asking to share it expects.
     */
    fun canShareClean(file: FileItem): Boolean {
        if (file.path.startsWith("content://")) return false
        val extension = FileKinds.extensionOf(file.name)
        if (extension in Jpegs || extension in Mp4s) return true
        return file.type == "image" && extension in Stills && !looksAnimated(file.path)
    }

    private fun looksAnimated(path: String): Boolean = try {
        val head = ByteArray(4096)
        val read = File(path).inputStream().use { it.read(head) }.coerceAtLeast(0)
        // WebP says so in its ANIM chunk, and an animated PNG in its acTL.
        val text = String(head, 0, read, Charsets.ISO_8859_1)
        text.contains("ANIM") || text.contains("acTL")
    } catch (_: Exception) {
        true
    }

    /** Writes a copy with the location gone beside the original, and returns it. */
    suspend fun saveCopyWithoutLocation(context: Context, file: FileItem): File = withContext(Dispatchers.IO) {
        val source = File(file.path)
        val target = uniqueSibling(source, "_no_location")
        try {
            writeWithoutLocation(source, target)
            target.setLastModified(source.lastModified())
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        scan(context, target)
        target
    }

    /** Removes the location from the file itself, by swapping in a checked clean copy. */
    suspend fun removeLocationInPlace(context: Context, file: FileItem) = withContext(Dispatchers.IO) {
        val source = File(file.path)
        val temp = File(source.parentFile, ".${source.name}.cleaning")
        try {
            writeWithoutLocation(source, temp)
            if (!stillReadable(temp, source)) throw IOException("Clean copy didn't check out")
            // On the disk before it replaces anything, so a dead battery can't leave an empty photo.
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            temp.setLastModified(source.lastModified())
            if (!temp.renameTo(source)) throw IOException("Couldn't swap in the clean copy")
        } finally {
            temp.delete()
        }
        scan(context, source)
    }

    /** Makes a metadata-free copy in the cache for one share. An earlier one has been sent by now. */
    suspend fun cleanCopyForSharing(context: Context, file: FileItem): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared_clean")
        directory.deleteRecursively()
        directory.mkdirs()
        writeClean(context, file, directory)
    }

    /** The same for many, leaving out whatever can't be cleaned. Nothing goes out with its metadata by mistake. */
    suspend fun cleanCopiesForSharing(context: Context, files: List<FileItem>): List<File> = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared_clean")
        directory.deleteRecursively()
        directory.mkdirs()
        files.filter(::canShareClean).mapNotNull { file ->
            try {
                writeClean(context, file, directory)
            } catch (e: Exception) {
                android.util.Log.e("MetadataActions", "Couldn't clean a copy", e)
                null
            }
        }
    }

    private suspend fun writeClean(context: Context, file: FileItem, directory: File): File {
        val source = File(file.path)
        val extension = FileKinds.extensionOf(file.name)
        // Two files of one name can come from two albums.
        fun free(name: String) = File(directory, com.kobblo.gallery.file.explorer.gallery.freeFileName(name) { File(directory, it).exists() })
        when (extension) {
            in Jpegs -> {
                val target = free(source.name)
                source.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output -> JpegMetadataRewriter.removeAll(input, output) }
                }
                return target
            }
            in Mp4s -> {
                val target = free(source.name)
                source.copyTo(target, overwrite = true)
                RandomAccessFile(target, "rw").use { handle ->
                    if (!Mp4MetadataStripper.isMp4(handle)) throw IOException("Not an MP4")
                    Mp4MetadataStripper.removeAll(handle)
                }
                return target
            }
            in Stills -> {
                val bitmap = com.kobblo.gallery.file.explorer.gallery.BulkWork.decodeFitting(context.applicationContext, file.path, 0)
                    ?: throw IOException("Couldn't read it")
                try {
                    val keepsAlpha = extension == "png" || extension == "webp"
                    val target = free(if (keepsAlpha) source.name else "${source.nameWithoutExtension}.jpg")
                    val format = when {
                        extension == "png" -> android.graphics.Bitmap.CompressFormat.PNG
                        extension != "webp" -> android.graphics.Bitmap.CompressFormat.JPEG
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                        else -> @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP
                    }
                    target.outputStream().buffered().use { output ->
                        if (!bitmap.compress(format, if (extension == "png") 100 else 95, output)) throw IOException("Couldn't encode it")
                    }
                    return target
                } finally {
                    bitmap.recycle()
                }
            }
            else -> throw IOException("Unsupported format")
        }
    }

    fun share(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeWithoutLocation(source: File, target: File) {
        when (FileKinds.extensionOf(source.name)) {
            in Jpegs -> source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> JpegMetadataRewriter.removeLocation(input, output) }
            }
            in Mp4s -> {
                source.copyTo(target, overwrite = true)
                RandomAccessFile(target, "rw").use { handle ->
                    if (!Mp4MetadataStripper.isMp4(handle)) throw IOException("Not an MP4")
                    Mp4MetadataStripper.removeLocation(handle)
                }
            }
            else -> throw IOException("Unsupported format")
        }
    }

    /** A bad rewrite must never replace the only copy of someone's photo. */
    private fun stillReadable(candidate: File, original: File): Boolean {
        if (candidate.length() <= 0L) return false
        if (FileKinds.extensionOf(original.name) in Mp4s) return candidate.length() == original.length()
        val before = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, before)
        val after = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(candidate.path, after)
        return after.outWidth > 0 && after.outWidth == before.outWidth && after.outHeight == before.outHeight
    }

    private fun uniqueSibling(source: File, suffix: String): File {
        val base = source.nameWithoutExtension
        val extension = source.extension
        var candidate = File(source.parentFile, "$base$suffix.$extension")
        var count = 2
        while (candidate.exists()) {
            candidate = File(source.parentFile, "$base$suffix $count.$extension")
            count++
        }
        return candidate
    }

    private fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.path), null, null)
    }
}
