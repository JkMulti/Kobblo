package com.kobblo.gallery.file.explorer.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.kobblo.gallery.file.explorer.compat.StorageWalk
import com.kobblo.gallery.file.explorer.data.StorageUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** How big a storage is, and how much of it is left. */
internal class StorageSpace(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

/** The folders and storages of the phone, which the system only names at run time. */
internal interface PhoneStorage {
    /** The top of the phone's own storage. */
    val root: File

    val downloads: File

    /** Where people look for what they used last: downloads, documents, pictures, and the top of storage. */
    val busyFolders: List<File>

    /** The memory card's top folder, or null when there's none. */
    val cardPath: String?

    val space: StorageSpace

    /** Visits what the Cleaner counts. It's called off when the coroutine is cancelled. */
    suspend fun walk(
        onFile: (path: String, size: Long, modifiedMillis: Long) -> Unit,
        onEmptyFolder: (path: String, modifiedMillis: Long) -> Unit = { _, _ -> }
    )

    /** What the Downloads category holds: everything directly in the folder that isn't hidden. */
    fun downloadEntries(): List<File> = downloads.listFiles().orEmpty().filterNot { it.name.startsWith(".") }

    /** The folder a word stands for, or the folder at that path. */
    fun folderAt(location: String): File = if (location == Places.Home) root else File(location)
}

internal class AndroidStorage(private val context: Context) : PhoneStorage {
    override val root: File get() = Environment.getExternalStorageDirectory()

    override val downloads: File get() = shared(Environment.DIRECTORY_DOWNLOADS)

    override val busyFolders: List<File>
        get() = listOf(downloads, shared(Environment.DIRECTORY_DOCUMENTS), shared(Environment.DIRECTORY_PICTURES), root)

    override val cardPath: String? get() = StorageUtils.getSdCardPath(context)

    override val space: StorageSpace
        get() = StatFs(Environment.getDataDirectory().absolutePath).let { StorageSpace(it.totalBytes, it.availableBytes) }

    override suspend fun walk(
        onFile: (path: String, size: Long, modifiedMillis: Long) -> Unit,
        onEmptyFolder: (path: String, modifiedMillis: Long) -> Unit
    ) {
        val job = currentCoroutineContext()
        StorageWalk.walk(root.toPath(), checkCancelled = { job.ensureActive() }, onFile, onEmptyFolder)
    }

    private fun shared(kind: String): File = Environment.getExternalStoragePublicDirectory(kind)
}
