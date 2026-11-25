package com.kobblo.gallery.file.explorer.explorer.search

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Where Back goes from a folder that was opened out of search results. */
internal data class SearchReturn(
    val location: String,
    val query: String,
    val filters: SearchFilters,
    val openedFolder: String
)

/**
 * The search's own state. The query and the results stay in `AppState`, because selection, delete and
 * the viewer all read the list there, and this holds only what nothing else needs.
 */
object ExplorerSearch {
    private const val MaxRecent = 6
    private const val RecentKey = "recent"

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    /** Whether the search field holds the cursor, which is when the filters should show. */
    var fieldFocused by mutableStateOf(false)

    private val _capped = MutableStateFlow(false)

    /** Whether the last answer stopped at [SearchEngine.MaxResults]. It's set from the search's own thread. */
    val capped: StateFlow<Boolean> = _capped.asStateFlow()

    internal fun setCapped(value: Boolean) {
        _capped.value = value
    }

    internal var returnTo by mutableStateOf<SearchReturn?>(null)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("explorer_search", Context.MODE_PRIVATE)
        _recent.value = try {
            val stored = JSONArray(prefs?.getString(RecentKey, "[]") ?: "[]")
            List(stored.length()) { stored.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isOpen(query: String): Boolean = query.isNotEmpty() || _filters.value.narrows

    fun setFilters(filters: SearchFilters) {
        _filters.value = filters
    }

    /** A folder starts clean, and any previous folder's filters would quietly hide its files. */
    fun onLocationChanged() {
        _filters.value = SearchFilters()
        fieldFocused = false
        _capped.value = false
    }

    /** Called when a search has found what it was for, so half-typed words never end up in the list. */
    fun remember(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val next = (listOf(trimmed) + _recent.value.filterNot { it.equals(trimmed, ignoreCase = true) }).take(MaxRecent)
        _recent.value = next
        prefs?.edit()?.putString(RecentKey, JSONArray(next).toString())?.apply()
    }

    fun clearRecent() {
        _recent.value = emptyList()
        prefs?.edit()?.remove(RecentKey)?.apply()
    }

    internal fun rememberReturn(location: String, query: String, openedFolder: String) {
        returnTo = SearchReturn(location, query, _filters.value, openedFolder)
    }

    /** The search to go back to, if Back from [location] should land on one. */
    internal fun takeReturn(location: String): SearchReturn? {
        val back = returnTo?.takeIf { it.openedFolder == location } ?: return null
        returnTo = null
        return back
    }
}
