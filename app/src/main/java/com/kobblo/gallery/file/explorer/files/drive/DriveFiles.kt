package com.kobblo.gallery.file.explorer.files.drive

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.kobblo.gallery.file.explorer.data.core.resultOf
import com.kobblo.gallery.file.explorer.files.DriveLocation
import java.io.File
import java.io.FileOutputStream
import com.google.api.services.drive.model.File as DriveFile

/**
 * What can be done with one file or folder on Google Drive, or with a whole account. Each call waits for the
 * network, so it belongs on a thread that may.
 *
 * @param connection the Drive of an account, or of the one in use, or null when there's none.
 * @param cacheRoot the app's cache folder.
 */
internal class DriveFiles(private val connection: (account: String?) -> Drive?, private val cacheRoot: () -> File) {

    fun createFolder(parent: DriveLocation, name: String): Boolean = create(parent, name, DriveFormats.Folder)

    fun createFile(parent: DriveLocation, name: String): Boolean = create(parent, name, mimeType = null)

    private fun create(parent: DriveLocation, name: String, mimeType: String?): Boolean {
        val drive = connection(parent.account) ?: return false
        val entry = DriveFile().setName(name).setMimeType(mimeType)
        // Without a parent it goes to the top.
        parent.folderId?.let { entry.parents = listOf(it) }
        return worked { drive.files().create(entry).setSupportsAllDrives(true).execute() }
    }

    fun rename(id: String, newName: String): Boolean = worked {
        val drive = connection(null) ?: return false
        drive.files().update(id, DriveFile().setName(newName)).execute()
    }

    /** Null when it's gone, or else what went wrong. A file that Drive won't put in its trash is deleted for good. */
    fun delete(id: String): String? {
        val files = connection(null)?.files() ?: return "Drive service not initialized"
        if (worked { files.update(id, DriveFile().setTrashed(true)).setSupportsAllDrives(true).execute() }) return null
        return try {
            files.delete(id).setSupportsAllDrives(true).execute()
            null
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "Unknown error"
        }
    }

    fun upload(source: File, to: String, account: String?): Result<Unit> = connected(account) { drive ->
        val entry = DriveFile().setName(source.name)
        // Anything that isn't one of Drive's own locations is already a folder's id.
        entry.parents = listOf(DriveLocation.of(to)?.parentId ?: to)
        drive.files().create(entry, FileContent("application/octet-stream", source)).setSupportsAllDrives(true).execute()
        Unit
    }

    fun download(id: String, destination: File, account: String?): Result<File> = connected(account) { drive ->
        val mimeType = mimeTypeOf(drive, id)
        check(mimeType != DriveFormats.Folder) { "Cannot download folder as file" }
        fetch(drive, id, DriveFormats.exportOf(mimeType), destination)
        destination
    }

    /** Puts a file in the cache to be opened, and says where. Null if it couldn't, or it's a folder. */
    fun downloadToCache(id: String, name: String, account: String?): File? {
        val drive = connection(account) ?: return null
        return try {
            val folder = File(cacheRoot(), CacheFolder).apply { mkdirs() }
            val asIs = File(folder, name).also(::dropIfEmpty)
            val mimeType = mimeTypeOf(drive, id)
            if (mimeType == DriveFormats.Folder) return null
            val export = DriveFormats.exportOf(mimeType)
            val exportName = export?.let { DriveFormats.exportName(asIs.name, it) }
            val target = if (exportName == null || exportName == asIs.name) asIs else File(folder, exportName)
            fetch(drive, id, export, target)
            target
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** How much of an account is used, and how much there is, in bytes. A limit of zero means there's none. */
    fun quota(account: String?): Pair<Long, Long>? {
        val drive = connection(account) ?: return null
        return try {
            val quota = drive.about().get().setFields("storageQuota").execute().storageQuota
            (quota.usage ?: 0L) to (quota.limit ?: 0L)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun mimeTypeOf(drive: Drive, id: String): String? =
        drive.files().get(id).setSupportsAllDrives(true).setFields("mimeType").execute().mimeType

    /** A Google document has no bytes of its own, so it's [export]ed to a format that does. */
    private fun fetch(drive: Drive, id: String, export: String?, target: File) {
        dropIfEmpty(target)
        FileOutputStream(target).use { out ->
            if (export != null) drive.files().export(id, export).executeMediaAndDownloadTo(out)
            else drive.files().get(id).setSupportsAllDrives(true).executeMediaAndDownloadTo(out)
        }
    }

    /** What a download that never got started leaves behind. */
    private fun dropIfEmpty(file: File) {
        if (file.exists() && file.length() == 0L) file.delete()
    }

    private inline fun <T> connected(account: String?, work: (Drive) -> T): Result<T> {
        val drive = connection(account) ?: return Result.failure(IllegalStateException("Drive not connected"))
        return resultOf { work(drive) }
    }

    private inline fun worked(call: () -> Unit): Boolean = try {
        call()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private companion object {
        const val CacheFolder = "cloud_cache"
    }
}
