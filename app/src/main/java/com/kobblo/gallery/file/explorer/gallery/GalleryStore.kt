package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The gallery's small remembered choices. Everything here has a sensible default. */
class GalleryStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("gallery_prefs", Context.MODE_PRIVATE)

    private val _columns = MutableStateFlow(prefs.getInt(KeyColumns, 3).coerceIn(MinGridColumns, MaxGridColumns))
    val columns: StateFlow<Int> = _columns.asStateFlow()

    private val _albumSort = MutableStateFlow(
        runCatching { AlbumSort.valueOf(prefs.getString(KeyAlbumSort, null) ?: "") }.getOrDefault(AlbumSort.DATE)
    )
    val albumSort: StateFlow<AlbumSort> = _albumSort.asStateFlow()

    private val _pinnedAlbums = MutableStateFlow(prefs.getStringSet(KeyPinnedAlbums, emptySet()).orEmpty().toSet())
    val pinnedAlbums: StateFlow<Set<String>> = _pinnedAlbums.asStateFlow()

    /** Albums made here, kept so a new one shows before anything has been put in it. */
    private val _createdAlbums = MutableStateFlow(prefs.getStringSet(KeyCreatedAlbums, emptySet()).orEmpty().toSet())
    val createdAlbums: StateFlow<Set<String>> = _createdAlbums.asStateFlow()

    /**
     * Folders kept off Home, and whatever is inside them. Nothing happens to the files: no `.nomedia`, no rename,
     * so another gallery app still sees them, and the album stays listed here to be opened or brought back.
     */
    private val _hiddenAlbums = MutableStateFlow(prefs.getStringSet(KeyHiddenAlbums, emptySet()).orEmpty().toSet())
    val hiddenAlbums: StateFlow<Set<String>> = _hiddenAlbums.asStateFlow()

    /** A picture someone chose for an album, by the album's path, as album to file. */
    private val _albumCovers = MutableStateFlow(readPairs(KeyAlbumCovers))
    val albumCovers: StateFlow<Map<String, String>> = _albumCovers.asStateFlow()

    /** How an album's photos are sorted, by [GalleryDest.sortKey]. An album left at newest first has no entry. */
    private val _photoSorts = MutableStateFlow(
        readPairs(KeyPhotoSorts).mapNotNull { (album, name) ->
            runCatching { PhotoSort.valueOf(name) }.getOrNull()?.let { album to it }
        }.toMap()
    )
    val photoSorts: StateFlow<Map<String, PhotoSort>> = _photoSorts.asStateFlow()

    private val _recentSearches = MutableStateFlow(readRecentSearches())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    fun setColumns(value: Int) {
        val clamped = value.coerceIn(MinGridColumns, MaxGridColumns)
        if (_columns.value == clamped) return
        _columns.value = clamped
        prefs.edit().putInt(KeyColumns, clamped).apply()
    }

    fun setAlbumSort(sort: AlbumSort) {
        _albumSort.value = sort
        prefs.edit().putString(KeyAlbumSort, sort.name).apply()
    }

    fun togglePinnedAlbum(path: String) {
        val next = _pinnedAlbums.value.let { if (path in it) it - path else it + path }
        _pinnedAlbums.value = next
        prefs.edit().putStringSet(KeyPinnedAlbums, next).apply()
    }

    fun addCreatedAlbum(path: String) {
        val next = _createdAlbums.value + path
        _createdAlbums.value = next
        prefs.edit().putStringSet(KeyCreatedAlbums, next).apply()
    }

    fun removeCreatedAlbum(path: String) {
        val next = _createdAlbums.value - path
        _createdAlbums.value = next
        prefs.edit().putStringSet(KeyCreatedAlbums, next).apply()
    }

    fun toggleHiddenAlbum(path: String) {
        val next = _hiddenAlbums.value.let { if (path in it) it - path else it + path }
        _hiddenAlbums.value = next
        prefs.edit().putStringSet(KeyHiddenAlbums, next).apply()
    }

    fun setAlbumCover(album: String, file: String) {
        val next = _albumCovers.value + (album to file)
        _albumCovers.value = next
        writePairs(KeyAlbumCovers, next)
    }

    fun clearAlbumCover(album: String) {
        val next = _albumCovers.value - album
        _albumCovers.value = next
        writePairs(KeyAlbumCovers, next)
    }

    fun setPhotoSort(album: String, sort: PhotoSort) {
        savePhotoSorts(if (sort == PhotoSort.NEWEST) _photoSorts.value - album else _photoSorts.value + (album to sort))
    }

    private fun savePhotoSorts(sorts: Map<String, PhotoSort>) {
        _photoSorts.value = sorts
        writePairs(KeyPhotoSorts, sorts.mapValues { it.value.name })
    }

    fun rememberSearch(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        val next = (listOf(query) + _recentSearches.value.filterNot { it.equals(query, ignoreCase = true) }).take(MaxRecentSearches)
        _recentSearches.value = next
        prefs.edit().putString(KeyRecentSearches, org.json.JSONArray(next).toString()).apply()
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        prefs.edit().remove(KeyRecentSearches).apply()
    }

    private fun readRecentSearches(): List<String> = try {
        val stored = org.json.JSONArray(prefs.getString(KeyRecentSearches, "[]") ?: "[]")
        List(stored.length()) { stored.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun readPairs(key: String): Map<String, String> = try {
        val stored = org.json.JSONObject(prefs.getString(key, "{}") ?: "{}")
        stored.keys().asSequence().associateWith { stored.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writePairs(key: String, pairs: Map<String, String>) {
        prefs.edit().putString(key, org.json.JSONObject(pairs as Map<*, *>).toString()).apply()
    }

    /** Pins, hiding, covers, sorting and created albums follow a renamed folder. */
    fun renameAlbum(from: String, to: String) {
        if (from in _pinnedAlbums.value) {
            val next = _pinnedAlbums.value - from + to
            _pinnedAlbums.value = next
            prefs.edit().putStringSet(KeyPinnedAlbums, next).apply()
        }
        if (from in _createdAlbums.value) {
            val next = _createdAlbums.value - from + to
            _createdAlbums.value = next
            prefs.edit().putStringSet(KeyCreatedAlbums, next).apply()
        }
        if (_hiddenAlbums.value.any { it == from || it.startsWith("$from/") }) {
            val next = _hiddenAlbums.value.mapTo(HashSet()) { com.kobblo.gallery.file.explorer.fileops.LockedPaths.afterMove(it, from, to) ?: it }
            _hiddenAlbums.value = next
            prefs.edit().putStringSet(KeyHiddenAlbums, next).apply()
        }
        _albumCovers.value[from]?.let { cover ->
            val moved = com.kobblo.gallery.file.explorer.fileops.LockedPaths.afterMove(cover, from, to) ?: cover
            val next = _albumCovers.value - from + (to to moved)
            _albumCovers.value = next
            writePairs(KeyAlbumCovers, next)
        }
        _photoSorts.value[from]?.let { sort -> savePhotoSorts(_photoSorts.value - from + (to to sort)) }
    }

    /** Forgets created albums whose folder is gone, such as one deleted from Browse. */
    fun pruneCreatedAlbums(exists: (String) -> Boolean) {
        val next = _createdAlbums.value.filter(exists).toSet()
        if (next.size != _createdAlbums.value.size) {
            _createdAlbums.value = next
            prefs.edit().putStringSet(KeyCreatedAlbums, next).apply()
        }
    }

    companion object {
        private const val KeyColumns = "grid_columns"
        private const val KeyAlbumSort = "album_sort"
        private const val KeyPinnedAlbums = "pinned_albums"
        private const val KeyCreatedAlbums = "created_albums"
        private const val KeyHiddenAlbums = "hidden_albums"
        private const val KeyAlbumCovers = "album_covers"
        private const val KeyPhotoSorts = "album_photo_sorts"
        private const val KeyRecentSearches = "recent_searches"
        private const val MaxRecentSearches = 6

        @Volatile private var instance: GalleryStore? = null

        fun get(context: Context): GalleryStore =
            instance ?: synchronized(this) {
                instance ?: GalleryStore(context).also { instance = it }
            }
    }
}
