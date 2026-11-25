package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository

/** What's at each location: on the phone, in the cloud, on a server, or in one of the app's own lists. */
internal class LocationFiles(
    private val live: LiveState,
    private val repository: FileRepository,
    private val drive: DriveAccounts,
    private val mega: MegaAccounts,
    private val servers: Servers,
    private val cleaner: Cleaner
) : LocationReader {

    override suspend fun read(location: String, firstRows: suspend (List<FileItem>) -> Unit): List<FileItem> {
        if (location == Places.Gallery) return feed(firstRows)
        // Recents lists the files it selects from, so select and delete act on them.
        if (location == Places.Home) return repository.getFiles(Places.Recent)
        if (CloudService.Mega.owns(location)) return mega.list(location)
        if (CloudService.Drive.owns(location)) return drive.list(location)
        ServerKind.of(location)?.let { return servers.list(it, location) }
        Places.categoryIn(location)?.let { return repository.getCategoryFiles(it, cleaner.dataFor(it)) }
        return repository.getFiles(location)
    }

    /**
     * The whole Home feed. The first time, the newest photos go on screen first, because they don't have to
     * wait for the rest of a large library, and that wait was most of a cold start at 100,000 files.
     */
    private suspend fun feed(firstRows: suspend (List<FileItem>) -> Unit): List<FileItem> {
        val showHidden = live.now.showHiddenFiles
        if (live.now.galleryFiles.isEmpty()) {
            val (newest, isWholeFeed) = repository.getNewestGalleryMedia(showHidden, FirstFeedRows)
            if (isWholeFeed) return newest
            firstRows(newest)
        }
        return repository.getGalleryMedia(showHidden)
    }

    private companion object {
        /** Enough rows to fill the first few screens at any column count. */
        const val FirstFeedRows = 300
    }
}
