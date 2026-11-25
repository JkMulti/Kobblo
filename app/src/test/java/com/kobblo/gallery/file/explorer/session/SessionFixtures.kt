package com.kobblo.gallery.file.explorer.session

import android.content.SharedPreferences
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem

internal const val PhoneRoot = "/storage/emulated/0"

/** A state as the app starts with. The browse location is given so nothing asks Android for it. */
internal fun appState() = AppState(lastBrowseLocation = PhoneRoot)

internal fun fileAt(
    path: String,
    id: Int = path.hashCode(),
    type: String = "doc",
    locked: Boolean = false,
    modified: Long = 0L,
    size: Long = 0L
) = FileItem(
    id = id,
    path = path,
    name = path.substringAfterLast('/'),
    type = type,
    kind = if (type == "folder") "folder" else "file",
    size = "",
    changed = "",
    owner = "Me",
    isLocked = locked,
    sizeBytes = size,
    lastModified = modified
)

/** Preferences in memory. */
internal class MemoryPreferences(vararg saved: Pair<String, Any>) : SharedPreferences {
    val values = HashMap<String, Any>().apply { putAll(saved) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String?, default: T): T = (values[key] ?: default) as T

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, default: String?): String? = read(key, default)
    override fun getStringSet(key: String?, default: MutableSet<String>?): MutableSet<String>? = read(key, default)
    override fun getInt(key: String?, default: Int): Int = read(key, default)
    override fun getLong(key: String?, default: Long): Long = read(key, default)
    override fun getFloat(key: String?, default: Float): Float = read(key, default)
    override fun getBoolean(key: String?, default: Boolean): Boolean = read(key, default)
    override fun contains(key: String?): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Edit()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Edit : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()

        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[key!!] = value }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, value: MutableSet<String>?) = put(key, value?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear() = apply { values.keys.forEach { changes[it] = null } }

        override fun commit(): Boolean {
            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

internal fun settingsIn(prefs: MemoryPreferences) =
    SettingsStore(prefs, SettingsDefaults(downloads = "/downloads", deviceName = { "Phone" }))

/** Words that only say which string was asked for, and with what. */
internal object PlainWords : Words {
    override fun text(id: Int, vararg args: Any) = "text $id ${args.toList()}"
    override fun counted(id: Int, count: Int, vararg args: Any) = "counted $id $count ${args.toList()}"
}
