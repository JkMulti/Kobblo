package com.kobblo.gallery.file.explorer.data.share

import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.util.Locale

/**
 * What can be said about a shared file from its name, its declared type and its size. Apps that share are
 * careless with all three, so each answer here leans on whichever of them is there.
 */
internal object IncomingFileRules {
    private const val Unnamed = "shared_file"
    private const val ApkMime = "application/vnd.android.package-archive"

    /** Text that opens as a document, whatever type its sender declared. */
    private val TextExtensions = setOf("txt", "md", "json", "xml", "csv", "html", "log", "kt", "java", "py")
    private val ArchiveExtensions = setOf("zip", "rar", "7z", "tar", "gz")

    /** Binary steps, each one a `%.1f` of the unit before the last it reaches. */
    private val SizeUnits = listOf("B", "KB", "MB", "GB")

    /**
     * "image", "video", "audio", "doc", "apk", "archive" or "file". The declared type and the name's
     * extension each get a say, since a sender may give a real type with a meaningless name or a good name
     * with `application/octet-stream`.
     */
    fun typeOf(name: String, mimeType: String?): String {
        val extension = FileKinds.extensionOf(name)
        fun declared(family: String) = mimeType?.startsWith(family) == true
        return when {
            declared("image/") || extension in FileKinds.Images -> "image"
            declared("video/") || extension in FileKinds.Videos -> "video"
            declared("audio/") || extension in FileKinds.Audio -> "audio"
            mimeType == "application/pdf" || extension == "pdf" -> "doc"
            declared("text/") || extension in TextExtensions -> "doc"
            extension == "apk" || mimeType == ApkMime -> "apk"
            extension in ArchiveExtensions -> "archive"
            else -> "file"
        }
    }

    /** The size for the save sheet, or two dashes when the sender never said how big the file is. */
    fun sizeLabel(bytes: Long): String {
        if (bytes <= 0L) return "--"
        var amount = bytes.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < SizeUnits.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", amount, SizeUnits[unit])
    }

    /**
     * A name for a file whose sender gave none, taken from the end of its address. That is often a bare
     * number, so the declared type lends it an extension when [extensionFor] knows one for it.
     */
    fun nameFromAddress(lastSegment: String?, mimeType: String?, extensionFor: (String) -> String?): String {
        val name = (lastSegment ?: Unnamed).substringAfterLast('/')
        if ('.' in name || mimeType == null) return name
        val extension = extensionFor(mimeType)
        return if (extension.isNullOrBlank()) name else "$name.$extension"
    }
}
