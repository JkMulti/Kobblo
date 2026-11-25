package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where files from the cloud and from servers are kept once they've been downloaded to be opened, in the
 * app's own cache. A row's id is part of the name, so two files that are called the same don't share one.
 */
internal class CloudCopies(private val cacheDir: File) {
    private val folder get() = File(cacheDir, "cloud_cache")

    /** The copy of [file], if it has been downloaded already. */
    fun ready(file: FileItem): File? = placeFor(file).takeIf { it.exists() && it.length() > 0L }

    /** Where to download [file] to. What an interrupted download left there is cleared away. */
    fun fresh(file: FileItem): File {
        folder.mkdirs()
        val place = placeFor(file)
        if (place.exists() && place.length() == 0L) place.delete()
        return place
    }

    private fun placeFor(file: FileItem): File {
        val id = file.id.toLong() and 0xFFFFFFFFL
        return File(folder, "${id}_${file.name.replace(Unsafe, "_")}")
    }

    private companion object {
        val Unsafe = Regex("[^a-zA-Z0-9._-]")
    }
}

/** What [file]'s copy on the phone opens as. A cloud document with no file of its own arrives as a PDF. */
private fun typeOfCopy(copy: File, file: FileItem): String =
    if (copy.name.endsWith(".pdf", ignoreCase = true)) "document" else file.type

/**
 * Opening files in the viewer, and fetching the ones that aren't on the phone first.
 *
 * @param openFolder opens a folder under a name.
 */
internal class FileOpening(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val recents: Recents,
    private val copies: CloudCopies,
    private val drive: DriveAccounts,
    private val mega: MegaAccounts,
    private val servers: Servers,
    private val openFolder: (location: String, name: String) -> Unit
) {
    /** Opens the file in the list on screen, or among the recents, with this id. */
    fun view(id: Int) {
        val state = live.now
        val file = state.files.firstOrNull { it.id == id } ?: state.recentFiles.firstOrNull { it.id == id }
        if (file != null) view(file)
    }

    fun view(file: FileItem) {
        // A vault file's name doesn't belong in recents, and this path is gone once it closes.
        if (VaultManager.isDecryptedCopy(file.path)) return show(file)
        if (!Places.isInsideCloud(file.path)) return showAndLog(file)
        val copy = copies.ready(file) ?: return download(file)
        showAndLog(file.copy(path = copy.absolutePath, type = typeOfCopy(copy, file)))
    }

    private fun showAndLog(file: FileItem) {
        recents.opened(file.path)
        show(file)
    }

    private fun show(file: FileItem) = live.update { it.copy(viewingFile = file) }

    fun close() {
        VaultManager.discardDecryptedCopy(live.now.viewingFile?.path)
        live.update { it.copy(viewingFile = null) }
    }

    /** Fetches a file and then offers the copy to be opened. One at a time: another asked for meanwhile is ignored. */
    fun download(file: FileItem) {
        if (file.type == "folder") return openFolder(file.path, file.name)
        if (live.now.isDownloading) return
        live.update { it.copy(isDownloading = true) }
        scope.launch(Dispatchers.IO) {
            try {
                val copy = fetch(file)
                withContext(Dispatchers.Main) {
                    live.update { it.copy(isDownloading = false) }
                    if (copy == null) messages.post(words.text(R.string.app_open_failed_named, file.name)) else offerCopy(copy, file)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    live.update { it.copy(isDownloading = false) }
                    messages.post(words.text(R.string.app_open_failed))
                }
            }
        }
    }

    /** The file's copy on the phone, or null if it couldn't be fetched. */
    private suspend fun fetch(file: FileItem): File? {
        val path = file.path
        val place = copies.fresh(file)
        val server = ServerKind.of(path)
        val arrived = when {
            CloudService.Drive.isFolder(path) -> return drive.download(path, place.name)
            CloudService.Mega.isFolder(path) -> mega.download(path, place)
            server != null -> servers.download(server, path, place)
            else -> false
        }
        return place.takeIf { arrived }
    }

    private fun offerCopy(copy: File, file: FileItem) = offer(
        file.copy(
            path = copy.absolutePath,
            name = copy.name,
            type = typeOfCopy(copy, file),
            sizeBytes = copy.length()
        )
    )

    /** Leaves [file] for the screen to open, which can be before there is a screen. */
    fun offer(file: FileItem) = live.update { it.copy(downloadedCloudFileToOpen = file) }

    fun offerTaken() = live.update { it.copy(downloadedCloudFileToOpen = null) }
}
