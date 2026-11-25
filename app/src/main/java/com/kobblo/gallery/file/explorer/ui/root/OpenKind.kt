package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File

// What a file is when it's opened, what to call it when another app is asked to open it, and where a
// downloaded copy of it is kept. Nothing here touches Android, so it can all be tested as it is.

/** What opening a file comes to. */
internal enum class OpenKind { Photo, Document, Song, Video, App, Unknown }

/** Extensions read in the document viewer whatever the listing says their type is. */
private val ReadableExtensions = setOf(
    "pdf", "docx", "doc",
    "txt", "md", "log", "csv", "json", "xml", "html", "htm", "css",
    "kt", "java", "py", "js", "ts", "sh",
    "yaml", "yml", "ini", "conf", "properties"
)

/** Whether a file is one the document viewer reads, by its name or by its type. */
internal fun readsAsDocument(name: String, type: String): Boolean =
    type == "doc" || type == "document" || FileKinds.extensionOf(name) in ReadableExtensions

/**
 * How [name] opens, given the type its listing gave it. A listing's photo is always a photo, and its
 * video always a video: a `.ts` can be a transport stream as well as TypeScript, and the type has
 * already settled which.
 */
internal fun openKind(name: String, type: String): OpenKind = when {
    type == "image" -> OpenKind.Photo
    type == "video" -> OpenKind.Video
    readsAsDocument(name, type) -> OpenKind.Document
    type == "audio" -> OpenKind.Song
    FileKinds.extensionOf(name) == "apk" -> OpenKind.App
    else -> OpenKind.Unknown
}

private const val PlainText = "text/plain"
private const val Pdf = "application/pdf"
private const val Word = "application/msword"
private const val WordXml = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
internal const val PackageMime = "application/vnd.android.package-archive"

/** Text that another app does better with under its own name than as plain text. */
private val MarkupMimes = mapOf("html" to "text/html", "htm" to "text/html", "xml" to "text/xml", "json" to "application/json")

/** What a document is called when it's handed to another app and nothing more is known of it. */
internal fun documentMime(name: String): String = when (val extension = FileKinds.extensionOf(name)) {
    "pdf" -> Pdf
    "docx" -> WordXml
    "doc" -> Word
    else -> MarkupMimes[extension] ?: PlainText
}

/** Names that say for certain what a file is, whatever whoever is opening it took it for. */
private val CertainMimes = MarkupMimes + mapOf("pdf" to Pdf, "csv" to "text/csv") +
    listOf("txt", "md", "log", "kt", "java", "py").associateWith { PlainText }

/** The type to hand another app along with a file: what its name says, or [otherwise] where that isn't telling. */
internal fun handOverMime(name: String, otherwise: String): String =
    CertainMimes[FileKinds.extensionOf(name)] ?: otherwise

/** Types Android's own table doesn't always have. */
private val UncommonMimes = mapOf(
    "pdf" to Pdf, "docx" to WordXml, "doc" to Word,
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xls" to "application/vnd.ms-excel",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "ppt" to "application/vnd.ms-powerpoint",
    "apk" to PackageMime,
    "zip" to "application/zip", "rar" to "application/x-rar-compressed", "7z" to "application/x-7z-compressed",
    "tar" to "application/x-tar", "gz" to "application/gzip"
) + listOf("txt", "log", "md", "csv", "xml", "json", "kt", "java", "py", "sh").associateWith { PlainText }

/** The type of a file with this lower-case [extension], for when the system has no answer. */
internal fun mimeForExtension(extension: String): String = UncommonMimes[extension] ?: when (extension) {
    in FileKinds.Audio -> "audio/*"
    in FileKinds.Videos -> "video/*"
    in FileKinds.Images -> "image/*"
    else -> "*/*"
}

private val UnsafeInFileNames = Regex("[^a-zA-Z0-9._-]")

/**
 * Where the copy of a cloud file is kept once it has been downloaded to be opened. Its id comes first,
 * because two files in different folders can share a name.
 */
internal fun downloadedCopy(cacheDir: File, file: FileItem): File {
    val id = file.id.toLong() and 0xFFFFFFFFL
    return File(File(cacheDir, "cloud_cache"), "${id}_${file.name.replace(UnsafeInFileNames, "_")}")
}

/** The starts of every location that isn't on the phone or a card in it. */
private val RemotePrefixes = listOf("drive", "mega", "dropbox", "smb_", "ftp_", "sftp_", "webdav_", "nextcloud_")

/** Whether [location] is a place on a cloud account or a server. */
internal fun isRemotePlace(location: String): Boolean = RemotePrefixes.any(location::startsWith)
