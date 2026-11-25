package com.kobblo.gallery.file.explorer.files.drive

import com.google.api.services.drive.Drive
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.files.DriveLocation
import com.kobblo.gallery.file.explorer.files.PathMarks
import com.kobblo.gallery.file.explorer.files.listKindOf
import java.text.DateFormat
import java.util.Date
import com.google.api.services.drive.model.File as DriveFile

/**
 * Lists folders on Google Drive.
 *
 * @param unknown the word for whatever Drive didn't say about a file.
 */
internal class DriveFolders(private val unknown: () -> String) {
    /** Files the person chose to share with the app. While there are any, the top folder shows only those. */
    @Volatile
    var pickedIds: Set<String>? = null

    fun list(drive: Drive, location: DriveLocation, marks: PathMarks): List<FileItem> {
        val files = if (location.isTop) onlyPicked(atTheTop(drive)) else search(drive, inFolder(location))
        val rows = Rows(marks, unknown(), DisplayDates.date())
        return files.mapIndexed { position, file -> rows.of(file, id = FirstRowId + position) }
    }

    /**
     * The top folder, or everything the app may see where Drive won't list that folder or says it's empty. With
     * access to only some files, none of them need be in the top folder at all.
     */
    private fun atTheTop(drive: Drive): List<DriveFile> {
        val top = try {
            search(drive, inFolder(DriveLocation(account = null, folderId = null)))
        } catch (_: Exception) {
            return search(drive, NotTrashed)
        }
        if (top.isNotEmpty()) return top
        return try {
            search(drive, NotTrashed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun onlyPicked(files: List<DriveFile>): List<DriveFile> {
        val picked = pickedIds.orEmpty()
        return if (picked.isEmpty()) files else files.filter { it.id in picked }
    }

    private fun inFolder(location: DriveLocation) = "$NotTrashed and '${location.parentId}' in parents"

    private fun search(drive: Drive, query: String): List<DriveFile> =
        drive.files().list().apply {
            spaces = "drive"
            q = query
            supportsAllDrives = true
            includeItemsFromAllDrives = true
            fields = "nextPageToken, files(${RowFields.joinToString()})"
        }.execute().files.orEmpty()

    private class Rows(private val marks: PathMarks, private val unknown: String, private val dates: DateFormat) {
        fun of(file: DriveFile, id: Int): FileItem {
            val name = file.name ?: unknown
            val path = DriveLocation.pathOf(file.id)
            val type = DriveFormats.typeOf(file.mimeType, name)
            val isFolder = file.mimeType == DriveFormats.Folder
            val bytes: Long? = file.getSize()
            val modified: Long? = file.modifiedTime?.value
            return FileItem(
                id = id,
                path = path,
                name = name,
                type = type,
                kind = listKindOf(type),
                size = if (isFolder) "--" else bytes?.let(FileSizes::label) ?: unknown,
                changed = modified?.let { dates.format(Date(it)) } ?: unknown,
                owner = file.owners?.firstOrNull()?.displayName ?: "Me",
                isPinned = marks.isFavorite(path),
                isLocked = marks.isLocked(path),
                // Drive doesn't count what's in a folder, so there's nothing to say for any of them.
                isEmptyFolder = isFolder,
                sizeBytes = bytes ?: 0L,
                lastModified = modified ?: 0L,
                thumbnailLink = file.thumbnailLink
            )
        }
    }

    private companion object {
        const val NotTrashed = "trashed = false"
        const val FirstRowId = 1000

        /** What a row needs to know, which is all Drive is asked for. */
        val RowFields = listOf("id", "name", "mimeType", "size", "modifiedTime", "owners", "thumbnailLink")
    }
}
