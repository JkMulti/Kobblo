package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.MegaAccount
import com.kobblo.gallery.file.explorer.R
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nz.mega.sdk.MegaClient
import java.io.File

/**
 * The MEGA accounts that are signed in, saved as a JSON list. The first one is saved a second time in two
 * keys of its own, which is all that builds with a single account wrote and read.
 */
internal class MegaAccountStore(private val prefs: SharedPreferences) {
    private val gson = Gson()
    private val listType = TypeToken.getParameterized(List::class.java, MegaAccount::class.java).type

    fun load(): List<MegaAccount> = readList().ifEmpty {
        listOfNotNull(readSingle()).also { if (it.isNotEmpty()) save(it) }
    }

    fun save(accounts: List<MegaAccount>) {
        val first = accounts.firstOrNull()
        prefs.edit()
            .putString(LIST, gson.toJson(accounts, listType))
            .putString(SESSION, first?.session)
            .putString(EMAIL, first?.email)
            .apply()
    }

    private fun readList(): List<MegaAccount> {
        val json = prefs.getString(LIST, null)
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<MegaAccount>>(json, listType).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readSingle(): MegaAccount? {
        val email = prefs.getString(EMAIL, null) ?: return null
        val session = prefs.getString(SESSION, null) ?: return null
        return MegaAccount(email, session)
    }

    private companion object {
        const val LIST = "mega_accounts"
        const val SESSION = "mega_session"
        const val EMAIL = "mega_email"
    }
}

/**
 * Signing in and out of MEGA, and reading what's there. Each account has a client of its own that stays
 * signed in, so all of them can be browsed without signing in again.
 *
 * @param reload reads a location again.
 */
internal class MegaAccounts(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val store: MegaAccountStore,
    private val reload: (String) -> Unit
) {
    /** Shows the saved accounts, then signs each of them in again. One that fails is still listed. */
    suspend fun restore() {
        val accounts = store.load()
        if (accounts.isEmpty()) return
        show(accounts, inUse = accounts.first().email)
        for (account in accounts) {
            try {
                val client = clientOf(account.email)
                if (client.fastLogin(account.session)) client.fetchNodes()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun signIn(email: String, password: String) {
        live.update { it.copy(megaLoginError = null) }
        scope.launch(Dispatchers.IO) {
            try {
                val client = clientOf(email)
                if (client.login(email, password)) {
                    client.dumpSession()?.let { keep(client, MegaAccount(email, it)) }
                } else {
                    showProblem(R.string.app_mega_wrong_login)
                }
            } catch (_: Exception) {
                showProblem(R.string.app_mega_login_failed)
            }
        }
    }

    private suspend fun keep(client: MegaClient, account: MegaAccount) {
        val accounts = live.now.megaAccounts.filter { it.email != account.email } + account
        store.save(accounts)
        client.fetchNodes()
        show(accounts, inUse = account.email) { withoutPopup() }
        if (CloudService.Mega.isTop(live.now.location)) reload(CloudService.Mega.topOf(account.email))
    }

    /** Signs out of [email], or of the account in use. */
    fun signOut(email: String?) {
        val leaving = email ?: live.now.megaAccountEmail
        val left = live.now.megaAccounts.filter { it.email != leaving }
        store.save(left)
        if (leaving != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    clientOf(leaving).localLogout()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        show(left, inUse = left.firstOrNull()?.email) { withoutPopup() }
    }

    fun use(email: String) = live.update { it.copy(megaAccountEmail = email) }

    fun showPopup(show: Boolean) = live.update { it.copy(showMegaPopup = show, megaLoginError = null) }

    /** What's in a folder, or at the top of an account. */
    fun list(location: String): List<FileItem> {
        val client = clientOf(CloudService.Mega.accountIn(location) ?: live.now.megaAccountEmail)
        val folder = if (CloudService.Mega.isFolder(location)) CloudService.Mega.idIn(location) else null
        return client.getChildren(folder)
    }

    suspend fun download(path: String, target: File): Boolean =
        clientOf(live.now.megaAccountEmail).downloadFile(CloudService.Mega.idIn(path), target.absolutePath)

    private fun clientOf(email: String?): MegaClient =
        MegaClient.getInstance(app, email ?: live.now.megaAccounts.firstOrNull()?.email ?: "default")

    private fun show(accounts: List<MegaAccount>, inUse: String?, also: AppState.() -> AppState = { this }) = live.update {
        it.copy(
            isMegaAuthenticated = accounts.isNotEmpty(),
            megaAccountEmail = inUse,
            megaAccounts = accounts.toPersistentList()
        ).also()
    }

    private fun AppState.withoutPopup() = copy(megaLoginError = null, showMegaPopup = false)

    private fun showProblem(id: Int) {
        val problem = words.text(id)
        live.update { it.copy(megaLoginError = problem) }
    }
}
