package com.kobblo.gallery.file.explorer.files

import android.content.SharedPreferences
import com.kobblo.gallery.file.explorer.vault.VaultPaths
import org.json.JSONArray
import org.json.JSONObject

/** The last thing done to one file or folder. [action] is a key that RecentActions puts into words. */
internal class RecentEntry(val path: String, val action: String, val at: Long)

/** The rules for what Recents remembers. */
internal object RecentRules {
    /** What to keep, newest first. A path is in there once, for the last thing done to it. */
    const val Kept = 30

    /** Only files on the phone, and nothing from the Vault, since Recents has no password in front of it. */
    fun isWorthKeeping(path: String): Boolean = path.startsWith("/") && !VaultPaths.isVaultPath(path)

    /** [history] with [newest] at the front. It looks no further into the history than it has room for. */
    fun <T> adding(newest: T, history: Sequence<T>, pathOf: (T) -> String): List<T> {
        val path = pathOf(newest)
        return listOf(newest) + history.filter { pathOf(it) != path }.take(Kept - 1)
    }
}

/** What was opened, visited or renamed lately, saved in the app's main preferences as a JSON list. */
internal class RecentHistory(private val prefs: SharedPreferences) {

    fun add(path: String, action: String, now: Long = System.currentTimeMillis()) {
        if (!RecentRules.isWorthKeeping(path)) return
        val newest = JSONObject().put(PathField, path).put(ActionField, action).put(TimeField, now)
        try {
            val kept = RecentRules.adding(newest, saved()) { it.getString(PathField) }
            prefs.edit().putString(Key, JSONArray(kept).toString()).apply()
        } catch (e: Exception) {
            // Whatever is saved can't be read, and it's better left alone than written over.
            e.printStackTrace()
        }
    }

    /** Newest first. It's read as it goes, so a broken entry doesn't take those before it along. */
    fun entries(): Sequence<RecentEntry> = saved().map {
        RecentEntry(it.getString(PathField), it.getString(ActionField), it.getLong(TimeField))
    }

    private fun saved(): Sequence<JSONObject> {
        val list = JSONArray(prefs.getString(Key, null) ?: "[]")
        return (0 until list.length()).asSequence().map(list::getJSONObject)
    }

    private companion object {
        const val Key = "recent_actions"
        const val PathField = "path"
        const val ActionField = "action"
        const val TimeField = "timestamp"
    }
}
