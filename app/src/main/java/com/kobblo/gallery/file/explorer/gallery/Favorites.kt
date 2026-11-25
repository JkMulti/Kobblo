package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import com.kobblo.gallery.file.explorer.fileops.LockedPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Favorites, in one place. They're kept by path, which is how every list in the app reads them, so they have to be
 * told when a path changes: anything that moves or renames a file already tells [com.kobblo.gallery.file.explorer.fileops.Locks],
 * and that passes it on here.
 */
internal object Favorites {
    private const val PREFS = "kobblo_prefs"
    private const val KEY = "pinned_files"

    private var flow: MutableStateFlow<Set<String>>? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun current(context: Context): MutableStateFlow<Set<String>> = synchronized(this) {
        flow ?: MutableStateFlow(prefs(context).getStringSet(KEY, emptySet()).orEmpty().toSet()).also { flow = it }
    }

    /** What's a favorite now, for the star a photo wears, which shouldn't have to wait for the feed. */
    fun paths(context: Context): StateFlow<Set<String>> = current(context).asStateFlow()

    fun toggle(context: Context, path: String) {
        update(context) { if (path in it) it - path else it + path }
    }

    fun set(context: Context, paths: Collection<String>, favorite: Boolean) {
        update(context) { if (favorite) it + paths else it - paths.toSet() }
    }

    fun moved(context: Context, from: String, to: String) {
        if (from == to) return
        update(context) { favoritesAfterMove(it, from, to) ?: it }
    }

    /** It's gone for good, so nothing new that ends up at this path should inherit the star. */
    fun forget(context: Context, path: String) {
        update(context) { set -> if (set.none { it == path || it.startsWith("$path/") }) set else set.filterNot { it == path || it.startsWith("$path/") }.toSet() }
    }

    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        synchronized(this) {
            val state = current(context)
            val next = change(state.value)
            if (next !== state.value && next != state.value) {
                state.value = next
                prefs(context).edit().putStringSet(KEY, HashSet(next)).apply()
            }
        }
    }
}

/** Where favorites go when [from] moves to [to], or null if none of them is affected. */
internal fun favoritesAfterMove(favorites: Set<String>, from: String, to: String): Set<String>? {
    if (favorites.none { LockedPaths.afterMove(it, from, to) != null }) return null
    return favorites.mapTo(HashSet(favorites.size)) { LockedPaths.afterMove(it, from, to) ?: it }
}
