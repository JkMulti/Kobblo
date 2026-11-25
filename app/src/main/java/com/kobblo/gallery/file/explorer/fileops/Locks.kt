package com.kobblo.gallery.file.explorer.fileops

import android.content.Context

/**
 * Which paths a set of locks covers: the locked file or folder itself, and everything inside a locked folder.
 * Lists ask this for each row, and a large library is a lot of rows, so each folder is only worked out once. A
 * long folder's rows are made on several threads at once, so that's kept in a map that can take it.
 *
 * @param opened locks the password has already been given for, while someone is inside that folder.
 */
internal class LockedPaths(private val locks: Set<String>, private val opened: Set<String> = emptySet()) {
    private val folders = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun covers(path: String): Boolean {
        if (locks.isEmpty()) return false
        if (path in locks && path !in opened) return true
        val cut = path.lastIndexOf('/')
        if (cut <= 0) return false
        val parent = path.substring(0, cut)
        return folders[parent] ?: covers(parent).also { folders[parent] = it }
    }

    /** The lock that keeps [path] closed, from the top down, or null if none does. */
    fun coveringLock(path: String): String? {
        if (locks.isEmpty()) return null
        var found: String? = null
        var walk = path
        while (walk.isNotEmpty()) {
            if (walk in locks && walk !in opened) found = walk
            val cut = walk.lastIndexOf('/')
            if (cut <= 0) break
            walk = walk.substring(0, cut)
        }
        return found
    }

    companion object {
        /** Where a lock goes when [from] moves to [to]: on it, or on anything inside it. Null if it isn't affected. */
        fun afterMove(lock: String, from: String, to: String): String? = when {
            lock == from -> to
            lock.startsWith("$from/") -> to + lock.substring(from.length)
            else -> null
        }
    }
}

/**
 * An action on a locked item that's waiting on the password: renaming or deleting one takes the same password as
 * looking at it, since either would reveal or remove what the lock is there to keep. Only ever one, as the password
 * prompt only ever shows one at a time.
 */
internal object LockGate {
    private var pending: (() -> Unit)? = null

    fun ask(action: () -> Unit) {
        pending = action
    }

    fun confirmed() {
        val action = pending
        pending = null
        action?.invoke()
    }

    fun cancelled() {
        pending = null
    }
}

/**
 * Lock, in one place. Lock is a privacy screen inside this app: it hides a file's picture and contents from anyone
 * using Kobblo until the password is given. It isn't encryption, and other apps can still read the file, which
 * is what the Vault is for. So this is what every list in the app asks, and every list gets the same answer.
 */
internal object Locks {
    private const val PREFS = "kobblo_prefs"
    private const val KEY = "locked_files"

    private val openedLocks = HashSet<String>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty()

    /** For everything that isn't one open folder: search, Recents, the gallery, the widget, other apps. */
    fun forLists(context: Context) = LockedPaths(all(context))

    /** For the folder someone's in, so that a locked folder they've just opened shows what's in it. */
    fun forBrowsing(context: Context) = LockedPaths(all(context), synchronized(openedLocks) { openedLocks.toSet() })

    fun covers(context: Context, path: String): Boolean = forLists(context).covers(path)

    /** The lock a direct path would have to get past first, or null if there isn't one in the way. */
    fun inTheWay(context: Context, path: String): String? = forBrowsing(context).coveringLock(path)

    /** The password's been given for [path], so whatever lock stood in the way of it no longer does. */
    fun openAllOver(context: Context, path: String) {
        synchronized(openedLocks) {
            while (true) openedLocks += forBrowsingLocked(context).coveringLock(path) ?: break
        }
    }

    private fun forBrowsingLocked(context: Context) = LockedPaths(all(context), openedLocks.toSet())

    /** Once someone's left the folder, the password has to be given again to go back. */
    fun closeAllBut(location: String) {
        synchronized(openedLocks) { openedLocks.removeAll { location != it && !location.startsWith("$it/") } }
    }

    fun closeAll() {
        synchronized(openedLocks) { openedLocks.clear() }
    }

    fun moved(context: Context, from: String, to: String) {
        if (from == to) return
        // Every move and rename already comes through here, and a favorite has to follow its file as a lock does.
        com.kobblo.gallery.file.explorer.gallery.Favorites.moved(context, from, to)
        update(context) { locks ->
            locks.map { LockedPaths.afterMove(it, from, to) ?: it }.toSet()
        }
        synchronized(openedLocks) {
            val moved = openedLocks.map { LockedPaths.afterMove(it, from, to) ?: it }
            openedLocks.clear()
            openedLocks += moved
        }
    }

    /** A copy of something locked is as private as the original. */
    fun copied(context: Context, from: String, to: String) {
        update(context) { locks -> locks + locks.mapNotNull { LockedPaths.afterMove(it, from, to) } }
    }

    /** It's gone for good, so nothing new that ends up at this path should inherit its lock. */
    fun forget(context: Context, path: String) {
        com.kobblo.gallery.file.explorer.gallery.Favorites.forget(context, path)
        update(context) { locks -> locks.filterNot { it == path || it.startsWith("$path/") }.toSet() }
    }

    private fun update(context: Context, change: (Set<String>) -> Set<String>) {
        synchronized(this) {
            val current = all(context)
            if (current.isEmpty()) return
            val next = change(current)
            if (next != current) prefs(context).edit().putStringSet(KEY, HashSet(next)).apply()
        }
    }
}
