package com.kobblo.gallery.file.explorer.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * The saved connections of one [kind] of server (FTP, SFTP, SMB or WebDAV). The connections themselves
 * are kept as a JSON list; each one's password is kept apart, in Android's encrypted preferences.
 *
 * What's on disk, which earlier builds also wrote and so must not change:
 * - `<kind>_connections`: the list, under the key `connections_list`.
 * - `<kind>_secure_prefs`: one `pwd_<connection id>` entry per password.
 */
open class SavedConnections<C : Any>(
    context: Context,
    private val kind: String,
    type: Class<C>,
    private val idOf: (C) -> String
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, type).type
    private val listPrefs = appContext.getSharedPreferences("${kind}_connections", Context.MODE_PRIVATE)

    // Opened on first use: creating the encryption key can take a moment, and a screen that only lists
    // connections never needs a password.
    private val secrets: SharedPreferences by lazy(::openSecrets)

    fun getConnections(): PersistentList<C> = read().toPersistentList()

    /** Adds [connection], or replaces the saved one with the same id, and stores its [password]. */
    fun saveConnection(connection: C, password: String) {
        val id = idOf(connection)
        val all = read().toMutableList()
        val position = all.indexOfFirst { idOf(it) == id }
        if (position < 0) all += connection else all[position] = connection
        write(all)
        secrets.edit().putString(passwordKey(id), password).apply()
    }

    fun deleteConnection(connectionId: String) {
        write(read().filterNot { idOf(it) == connectionId })
        secrets.edit().remove(passwordKey(connectionId)).apply()
    }

    fun getPassword(connectionId: String): String? = secrets.getString(passwordKey(connectionId), null)

    protected fun find(connectionId: String): C? = read().firstOrNull { idOf(it) == connectionId }

    private fun read(): List<C> {
        val json = listPrefs.getString(LIST_KEY, null) ?: return emptyList()
        return gson.fromJson<List<C>>(json, listType).orEmpty()
    }

    private fun write(connections: List<C>) {
        listPrefs.edit().putString(LIST_KEY, gson.toJson(connections, listType)).apply()
    }

    private fun passwordKey(connectionId: String) = "pwd_$connectionId"

    private fun openSecrets(): SharedPreferences {
        val name = "${kind}_secure_prefs"
        fun encrypted(): SharedPreferences = EncryptedSharedPreferences.create(
            appContext,
            name,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return runCatching { encrypted() }
            .recoverCatching {
                // The file and its key can fall out of step, after a restore for one. Nothing can read the
                // file again then, so it's dropped and a new one started.
                appContext.deleteSharedPreferences(name)
                encrypted()
            }
            // With no working keystore at all, passwords still have to be kept somewhere to connect.
            .getOrElse { appContext.getSharedPreferences("${name}_fallback", Context.MODE_PRIVATE) }
    }

    private companion object {
        const val LIST_KEY = "connections_list"
    }
}
