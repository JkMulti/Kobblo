package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.explorer.ExplorerSort
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Which of a location's files show, and in what order. */
internal class Arrangement(private val isReversed: (location: String) -> Boolean) {

    /** [files] as [state] asks for them: its location's rules, its filter, its search and its sort. */
    fun of(files: List<FileItem>, state: AppState): List<FileItem> {
        // The Home feed is in date order whatever a folder's sort is set to, and the viewer follows it.
        if (state.location == Places.Gallery) return files
        val category = Places.isCategory(state.location)
        val shown = files.filter { file ->
            (state.showHiddenFiles || !file.isHidden) &&
                (category || ExplorerSort.matchesFilter(file, state.filter)) &&
                file.name.contains(state.query, ignoreCase = true)
        }
        // A category always reads newest first, whatever a folder's sort is set to.
        if (category) return shown.sortedByDescending { it.lastModified }
        return shown.sortedWith(ExplorerSort.comparator(state.sortMode, isReversed(state.location)))
    }
}

/**
 * Everything that was read at the open location. What's on screen is made from this, so a change of filter,
 * sort or search doesn't have to read it all again.
 */
internal class FolderContents(
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val arrangement: Arrangement
) {
    @Volatile
    var all: List<FileItem> = emptyList()

    /** Shows what's been read the way it's now asked for. */
    fun rearrange() {
        scope.launch(Dispatchers.Default) {
            val shown = arrangement.of(all, live.now).toImmutableList()
            live.update { it.showing(shown) }
        }
    }

    private fun AppState.showing(shown: ImmutableList<FileItem>) = copy(
        files = shown,
        // What a search leaves isn't the folder.
        folderCache = if (query.isEmpty()) folderCache.put(location, shown) else folderCache
    )
}

/** Above this many files a list is only worth its memory while it's on screen, apart from the Home feed. */
private const val MostCached = 20_000

/** This state with [location] read and [shown] of it on screen. */
internal fun AppState.withListing(location: String, shown: ImmutableList<FileItem>): AppState {
    val isFeed = location == Places.Gallery
    return copy(
        isLoading = false,
        files = shown,
        galleryFiles = if (isFeed) shown else galleryFiles,
        folderCache = when {
            query.isNotEmpty() -> folderCache
            isFeed || shown.size <= MostCached -> folderCache.put(location, shown)
            else -> folderCache.remove(location)
        }
    )
}
