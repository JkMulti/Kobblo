package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileDetails
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import java.io.File
import java.util.Date
import java.util.Locale

/** Gathers what a file's details sheet shows. It can open the file, so it may take a moment. */
internal class DetailsReader(
    private val ofPicture: (path: String) -> MediaFacts = MediaFacts::ofPicture,
    private val ofRecording: (path: String) -> MediaFacts = MediaFacts::ofRecording
) {
    fun read(path: String): FileDetails {
        val file = File(path)
        val extension = file.extension
        val isFolder = file.isDirectory
        val facts = if (isFolder) MediaFacts.None else factsOf(path, extension.lowercase(Locale.getDefault()))
        return FileDetails(
            name = file.name,
            path = file.absolutePath,
            isFolder = isFolder,
            // The menu shows a folder's recursive size through FolderSizes, which can be cancelled
            // and remembers its answer. Walking the tree here would hold up the rest of the details.
            size = FileSizes.label(if (isFolder) 0L else file.length()),
            changed = DisplayDates.dateTime().format(Date(file.lastModified())),
            owner = LocalOwner,
            itemCount = if (isFolder) file.list().orEmpty().size else null,
            resolution = facts.resolution,
            duration = facts.duration,
            format = extension.uppercase(Locale.getDefault()).ifEmpty { null }
        )
    }

    private fun factsOf(path: String, extension: String): MediaFacts = when (extension) {
        in Pictures -> ofPicture(path)
        in Recordings -> ofRecording(path)
        else -> MediaFacts.None
    }

    private companion object {
        val Pictures = setOf("jpg", "jpeg", "png", "webp", "bmp")
        val Recordings = setOf("mp4", "mkv", "avi", "mov", "mp3", "wav", "m4a", "ogg")
    }
}
