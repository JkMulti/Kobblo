package com.kobblo.gallery.file.explorer.files

import android.content.Context
import android.content.SharedPreferences
import com.kobblo.gallery.file.explorer.fileops.LockedPaths
import com.kobblo.gallery.file.explorer.fileops.Locks

/** The favorites, locks and settings kept in the app's main preferences that the file lists go by. */
internal class SavedChoices(private val context: Context, private val prefs: SharedPreferences) {
    val favorites: Set<String> get() = paths(FavoritesKey)

    val locks: Set<String> get() = paths(LocksKey)

    var vaultAlwaysShown: Boolean
        get() = prefs.getBoolean(VaultShownKey, true)
        set(value) = prefs.edit().putBoolean(VaultShownKey, value).apply()

    fun setLocked(path: String, locked: Boolean) {
        val changed = if (locked) locks + path else locks - path
        prefs.edit().putStringSet(LocksKey, changed).apply()
    }

    /** For every list but an open folder's own, where a lock always holds. */
    fun marksInLists() = PathMarks(favorites, Locks.forLists(context))

    /** For the folder someone's in. One that was locked and has just been opened shows what's in it. */
    fun marksInFolder() = PathMarks(favorites, Locks.forBrowsing(context))

    private fun paths(key: String): Set<String> = prefs.getStringSet(key, null).orEmpty()

    private companion object {
        const val FavoritesKey = "pinned_files"
        const val LocksKey = "locked_files"
        const val VaultShownKey = "vault_always_shown"
    }
}

/** What the person has put on files: a star or a lock. It's read once for a whole list. */
internal class PathMarks(val favorites: Set<String>, private val locks: LockedPaths) {
    fun isFavorite(path: String): Boolean = path in favorites

    fun isLocked(path: String): Boolean = locks.covers(path)
}
