package com.kobblo.gallery.file.explorer.files.drive

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.kobblo.gallery.file.explorer.AppBrand
import java.util.concurrent.ConcurrentHashMap

/**
 * The Google accounts whose Drive the person added, which of them is in use, and a connection for each.
 *
 * Both are saved by email in the app's main preferences. An install from when there could only be one account
 * has just the one in use saved, and [all] then answers with that.
 */
internal class DriveAccounts(private val context: Context, private val prefs: SharedPreferences) {
    private val connections = ConcurrentHashMap<String, Drive>()

    /** The account chosen since the app started, which doesn't wait for a save to land. */
    private var chosen: String? = null

    val current: String?
        get() = chosen ?: saved()

    fun all(): List<String> {
        val added = prefs.getStringSet(AddedKey, null).orEmpty()
        return if (added.isEmpty()) listOfNotNull(saved()) else added.toList()
    }

    private fun saved(): String? = prefs.getString(CurrentKey, null)

    /** Switches to an account that's already there. */
    fun switchTo(email: String) {
        chosen = email
        prefs.edit().putString(CurrentKey, email).apply()
    }

    fun add(email: String) {
        val added = all().toSet() + email
        chosen = email
        prefs.edit().putStringSet(AddedKey, added).putString(CurrentKey, email).apply()
        connection(email)
    }

    /** No account is in use any more, though the ones that were added stay added. */
    fun useNone() {
        chosen = null
        prefs.edit().remove(CurrentKey).apply()
    }

    /** Forgets an account, and goes on with the first of those left, if there are any. */
    fun remove(email: String) {
        val left = all().toSet() - email
        connections.remove(email)
        chosen = left.firstOrNull()
        prefs.edit().putStringSet(AddedKey, left).putString(CurrentKey, chosen).apply()
    }

    /** The connection for [email], or for the account in use. Null when there's no account at all. */
    fun connection(email: String? = null): Drive? {
        val account = email ?: current ?: all().firstOrNull() ?: return null
        return connections.getOrPut(account) { connect(account) }
    }

    /** Makes every account's connection, so that the first thing opened on Drive needn't. */
    fun connectAll() {
        for (email in all()) {
            try {
                connection(email)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun connect(email: String): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE))
        credential.selectedAccount = Account(email, "com.google")
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(AppBrand.NAME)
            .build()
    }

    private companion object {
        const val CurrentKey = "google_drive_email"
        const val AddedKey = "google_drive_accounts"
    }
}
