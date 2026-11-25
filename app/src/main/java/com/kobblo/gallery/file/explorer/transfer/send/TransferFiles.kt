package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import android.net.Uri
import com.kobblo.gallery.file.explorer.FileItem
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Builds the file entries the rest of the app understands, for things that came from outside it. */
object TransferFiles {
    private val images = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "svg", "tif", "tiff", "ico")
    private val videos = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "ts", "m4v")
    private val audio = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma")
    private val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val documents = setOf("pdf", "doc", "docx", "txt", "json", "xls", "xlsx", "ppt", "pptx", "csv", "md", "log", "xml", "html", "htm", "srt", "lrc")
    private val apps = setOf("apk", "apks", "xapk", "aab")

    fun typeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            in images -> "image"
            in videos -> "video"
            in audio -> "audio"
            in archives -> "archive"
            in documents -> "doc"
            in apps -> "apk"
            else -> "file"
        }
    }

    fun fromFile(context: Context, file: File, displayName: String = file.name): FileItem {
        val bytes = if (file.isDirectory) 0L else file.length()
        return FileItem(
            id = file.absolutePath.hashCode() and 0x7FFFFFFF,
            path = file.absolutePath,
            name = displayName,
            type = if (file.isDirectory) "folder" else typeOf(displayName),
            kind = if (file.isDirectory) "folder" else "file",
            size = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(bytes),
            changed = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.lastModified())),
            owner = "Me",
            sizeBytes = bytes,
            lastModified = file.lastModified()
        )
    }

    /** Something shared in from another app, sent straight from there without copying it first. */
    fun fromUri(context: Context, uri: Uri, name: String, sizeBytes: Long): FileItem = FileItem(
        id = uri.toString().hashCode() and 0x7FFFFFFF,
        path = uri.toString(),
        name = name,
        type = typeOf(name),
        kind = "file",
        size = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(sizeBytes),
        changed = "",
        owner = "",
        sizeBytes = sizeBytes
    )

    /** An installed app, sent as its APK. The planner looks up its parts when sending starts. */
    fun fromApp(context: Context, packageName: String, label: String, sourcePath: String, sizeBytes: Long): FileItem {
        val safeLabel = com.kobblo.gallery.file.explorer.transfer.core.ReceivePaths.sanitizeSegment(label) ?: packageName
        return FileItem(
            id = packageName.hashCode() and 0x7FFFFFFF,
            path = sourcePath,
            name = "$safeLabel.apk",
            type = "apk",
            kind = SendPlanner.APP_KIND,
            size = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(sizeBytes),
            changed = "",
            owner = packageName,
            sizeBytes = sizeBytes
        )
    }
}
