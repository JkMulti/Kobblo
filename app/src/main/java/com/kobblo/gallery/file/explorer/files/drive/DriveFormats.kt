package com.kobblo.gallery.file.explorer.files.drive

import com.kobblo.gallery.file.explorer.fileops.DriveRules
import com.kobblo.gallery.file.explorer.viewer.FileKinds

/** What Drive's MIME types come to here: a row's type, and the file that a Google document is saved as. */
internal object DriveFormats {
    const val Folder = "application/vnd.google-apps.folder"
    private const val Drawing = "application/vnd.google-apps.drawing"
    private const val Pdf = "application/pdf"
    private const val Png = "image/png"

    /** The value for a row's type. Drive often knows better than a name does, and a name can have no extension. */
    fun typeOf(mimeType: String?, name: String): String {
        val mime = mimeType.orEmpty()
        return when {
            mime == Folder -> "folder"
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            // Word's own types say "document" as well as Google's.
            "pdf" in mime || "document" in mime -> "doc"
            else -> FileKinds.typeOf(name)
        }
    }

    /** The format to have a Google document turned into, or null for a file that's downloaded as it is. */
    fun exportOf(mimeType: String?): String? = when {
        !DriveRules.isNativeGoogleType(mimeType) -> null
        mimeType == Drawing -> Png
        else -> Pdf
    }

    /** The name to save an [exportFormat] under, for a document called [name]. */
    fun exportName(name: String, exportFormat: String): String {
        val endings = if (exportFormat == Png) listOf(".png", ".pdf") else listOf(".pdf")
        if (endings.any { name.endsWith(it, ignoreCase = true) }) return name
        return name.substringBeforeLast('.') + endings.first()
    }
}
