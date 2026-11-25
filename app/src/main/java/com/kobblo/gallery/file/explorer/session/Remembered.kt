package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.RecentActions
import com.kobblo.gallery.file.explorer.gallery.Favorites
import com.kobblo.gallery.file.explorer.vault.VaultManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** What's been opened and changed lately. */
internal class Recents(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository
) {
    fun refresh() = read {}

    /** Notes that [action] was done to [path], one of [RecentActions]. */
    fun log(path: String, action: String) = read { repository.logRecentAction(path, action) }

    /** Notes that [path] was opened, before anything else can be. */
    fun opened(path: String) {
        repository.logRecentAction(path, RecentActions.Opened)
        refresh()
    }

    private fun read(first: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                first()
                val recent = repository.getFiles(Places.Recent)
                live.update { state ->
                    state.copy(recentFiles = recent.filter { state.showHiddenFiles || !it.isHidden }.toImmutableList())
                }
            } catch (e: Exception) {
                // A list that couldn't be read stays as it was.
                e.printStackTrace()
            }
        }
    }
}

/**
 * Starring files. A star shows on every list, so each change has to reach the lists already on screen.
 *
 * @param reload reads everything on screen again.
 */
internal class Favorited(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository,
    private val contents: FolderContents,
    private val reload: () -> Unit
) {
    fun toggle(path: String) {
        // A vault file's copy is gone once it closes, and its name doesn't belong in a list outside.
        if (VaultManager.isDecryptedCopy(path)) return
        repository.togglePin(path)
        changed()
    }

    fun set(paths: List<String>, favorite: Boolean) {
        Favorites.set(app, paths, favorite)
        changed()
    }

    /** Starred items that live on this phone, which are the ones Browse can list as favorites. */
    fun onPhone(): ImmutableList<String> = repository.getPinnedFiles()
        .filter { Places.isOnPhone(it) && File(it).exists() }
        .sortedBy { File(it).name.lowercase() }
        .toImmutableList()

    private fun changed() {
        scope.launch(Dispatchers.IO) {
            val favorites = onPhone()
            live.update { it.copy(favoritePaths = favorites) }
        }
        if (live.now.location == Places.Gallery) restampFeed() else reload()
    }

    /** Stars change one at a time and often, and rereading a large library for each would make every tap wait. */
    private fun restampFeed() {
        scope.launch(Dispatchers.Default) {
            val before = live.now
            val starred = repository.getPinnedFiles()
            contents.all = contents.all.stamped(starred)
            val files = before.files.stamped(starred).toImmutableList()
            val feed = if (before.galleryFiles === before.files) files else before.galleryFiles.stamped(starred).toImmutableList()
            live.update { it.withStars(before, files, feed) }
        }
    }

    private fun AppState.withStars(before: AppState, files: ImmutableList<FileItem>, feed: ImmutableList<FileItem>): AppState {
        // A reload that landed meanwhile has read them already.
        if (this.files !== before.files || galleryFiles !== before.galleryFiles) return this
        return copy(files = files, galleryFiles = feed, folderCache = folderCache.put(Places.Gallery, feed))
    }
}

/** This list with each file starred if it's in [starred]. A file that's right already stays the same object. */
internal fun List<FileItem>.stamped(starred: Set<String>): List<FileItem> = map {
    val isStarred = it.path in starred
    if (it.isPinned == isStarred) it else it.copy(isPinned = isStarred)
}
