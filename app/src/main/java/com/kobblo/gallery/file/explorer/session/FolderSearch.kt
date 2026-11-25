package com.kobblo.gallery.file.explorer.session

import android.app.Application
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import com.kobblo.gallery.file.explorer.explorer.search.SearchEngine
import com.kobblo.gallery.file.explorer.explorer.search.SearchFilters
import com.kobblo.gallery.file.explorer.explorer.search.SearchSnapshot
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Searching from the open location. On the phone that goes through the disk, and answers as it finds. In
 * the cloud, on a server and in the lists of recents and favorites, it goes through what's been read.
 */
internal class FolderSearch(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val repository: FileRepository,
    private val contents: FolderContents,
    private val arrangement: Arrangement
) {
    private var search: Job? = null

    /** @param keepSelection for asking the same question again, where what's chosen should stay chosen. */
    fun ask(query: String, keepSelection: Boolean = false) {
        live.update {
            // A different search is a different list, and what was chosen in the last one is out of sight.
            val chosen = if (keepSelection) it else it.copy(selectedFiles = persistentSetOf(), isSelectionMode = false)
            chosen.copy(query = query)
        }
        stop()
        val filters = ExplorerSearch.filters.value
        val location = live.now.location
        val throughPhone = Places.searchesPhone(location)
        when {
            // With nothing typed, only a filter can still be a question, and only of the phone.
            query.isEmpty() && !(throughPhone && filters.narrows) -> showWholeFolder()
            throughPhone -> searchPhone(query, filters, location)
            else -> searchList(query)
        }
    }

    fun stop() {
        search?.cancel()
    }

    private fun showWholeFolder() {
        ExplorerSearch.setCapped(false)
        live.update { it.copy(isLoading = false) }
        contents.rearrange()
    }

    private fun searchPhone(query: String, filters: SearchFilters, location: String) {
        live.update { it.copy(isLoading = true) }
        search = scope.launch(Dispatchers.Default) {
            val start = live.now
            SearchEngine.run(app, repository, query, filters, location, start.showHiddenFiles, start.files) { found ->
                ExplorerSearch.setCapped(found.capped)
                live.update { it.withFound(found, query, location) }
            }
        }
    }

    private fun searchList(query: String) {
        search = scope.launch(Dispatchers.Default) {
            val state = live.now
            val matches = contents.all.filter { it.name.contains(query, ignoreCase = true) }
            val shown = arrangement.of(matches, state).toImmutableList()
            live.update { it.copy(files = shown, isLoading = false) }
        }
    }
}

/**
 * This state with what a search has found so far. An answer can arrive just as someone leaves the folder or
 * types another letter, and then it changes nothing. Until a search is done, what's chosen stays chosen,
 * since it may yet turn up.
 */
internal fun AppState.withFound(found: SearchSnapshot, searched: String, at: String): AppState {
    if (location != at || query != searched) return this
    val stillChosen = if (found.done && selectedFiles.isNotEmpty()) {
        val present = found.files.mapTo(HashSet()) { it.id }
        selectedFiles.filter { it in present }.toImmutableSet()
    } else {
        selectedFiles
    }
    return copy(
        files = found.files.toImmutableList(),
        isLoading = !found.done,
        selectedFiles = stillChosen,
        isSelectionMode = stillChosen.isNotEmpty()
    )
}
