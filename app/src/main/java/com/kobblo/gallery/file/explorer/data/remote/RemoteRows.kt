package com.kobblo.gallery.file.explorer.data.remote

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A file or folder the way a server lists it, before it becomes a row. */
internal data class RemoteEntry(
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    /** Milliseconds since 1970, or 0 when the server didn't say. */
    val modifiedAt: Long
)

/**
 * A listing without `.` and `..`: the folder itself and the one above it, which FTP, SFTP and SMB servers
 * send along with the real contents.
 */
internal fun List<RemoteEntry>.withoutDotEntries(): List<RemoteEntry> =
    filterNot { it.name == "." || it.name == ".." }

/**
 * Makes the explorer's rows out of a server's listing, the same way for every kind of server.
 *
 * Each provider keeps one of these for as long as it lives, and [dates] with it, so its rows are dated in
 * the language that was in use when the provider was made.
 */
internal class RemoteRows(private val dates: DateFormat = DisplayDates.dateTime()) {

    /**
     * The rows for [entries]: folders first, and within folders and within files by name with capitals
     * and small letters filed together. [pathOf] gives an entry's path on the server from its name,
     * [address] turns that into the row's path, and [owner] is the kind of server, shown where a local
     * file shows who owns it.
     */
    fun of(
        entries: List<RemoteEntry>,
        address: RemoteAddress,
        owner: String,
        pathOf: (name: String) -> String
    ): List<FileItem> =
        entries.sortedWith(FoldersThenNames).map { row(it, pathOf(it.name), address, owner) }

    private fun row(entry: RemoteEntry, serverPath: String, address: RemoteAddress, owner: String): FileItem {
        val type = RemoteFileTypes.typeOf(entry.name, entry.isFolder)
        return FileItem(
            // Of the server's path alone, without the connection in front.
            id = serverPath.hashCode(),
            path = address.of(serverPath),
            name = entry.name,
            type = type,
            kind = RemoteFileTypes.kindOf(type),
            size = if (entry.isFolder) "" else FileSizes.label(entry.sizeBytes),
            changed = dates.format(Date(entry.modifiedAt)),
            owner = owner,
            sizeBytes = entry.sizeBytes,
            lastModified = entry.modifiedAt
        )
    }

    private companion object {
        val FoldersThenNames: Comparator<RemoteEntry> =
            compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.name.lowercase() }
    }
}

/** The words a remote row carries in [FileItem.type], and the label made from them for [FileItem.kind]. */
internal object RemoteFileTypes {
    private const val Folder = "folder"
    private const val Other = "unknown"

    private val byExtension: Map<String, String> = buildMap {
        fun file(type: String, vararg extensions: String) = extensions.forEach { put(it, type) }
        file("music", "mp3", "wav", "flac", "m4a", "ogg")
        file("document", "pdf", "txt", "json", "doc", "docx")
        file("archive", "zip", "rar", "7z", "tar", "gz")
        file("apk", "apk")
    }

    /** Pictures and videos are whatever the viewer can open; everything else goes by a short list of its own. */
    fun typeOf(name: String, isFolder: Boolean): String {
        if (isFolder) return Folder
        val extension = FileKinds.extensionOf(name)
        return when (extension) {
            in FileKinds.Images -> "image"
            in FileKinds.Videos -> "video"
            else -> byExtension[extension] ?: Other
        }
    }

    /** The type with a capital first letter, as the language in use writes one. */
    fun kindOf(type: String): String = type.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
