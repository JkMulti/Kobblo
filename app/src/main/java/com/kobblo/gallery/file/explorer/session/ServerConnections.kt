package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ConnectionStatus
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** The saved connections of one kind of server, with their passwords. */
internal interface SavedServers<C> {
    fun all(): ImmutableList<C>
    fun save(connection: C, password: String)
    fun delete(connectionId: String)
    fun passwordOf(connectionId: String): String?
}

/** The one server of a kind that can be open at a time. Opening another lets go of the first. */
internal interface ServerLink<C> {
    /** The saved connection that's open, or null. */
    val openId: String?

    suspend fun open(connection: C, password: String): Result<Any?>
    suspend fun close()
    suspend fun list(location: String, connectionId: String): Result<List<FileItem>>
    suspend fun download(path: String, target: File): Result<Any?>

    /** Lets go at once, as the app goes away. Only where a server would otherwise keep the line open. */
    fun dropNow() {}
}

/** What the app's state says about one kind of server, whichever kind that is. */
internal data class ServerView<C, E>(
    val saved: ImmutableList<C>,
    val activeId: String?,
    val path: String,
    val status: ConnectionStatus,
    val error: E?
)

/** Where one kind of server is in the app's state. */
internal interface ServerSlot<C, E> {
    fun of(state: AppState): ServerView<C, E>
    fun into(state: AppState, view: ServerView<C, E>): AppState
}

/** What sets one kind of server apart from the others. */
internal abstract class ServerTraits<C, E>(val kind: ServerKind, val slot: ServerSlot<C, E>) {
    abstract fun idOf(connection: C): String

    /** What went wrong, as the kind's own screen explains it. */
    abstract fun errorOf(failure: Throwable?): E

    /** The scheme of a connection's locations. */
    open fun schemeOf(connection: C): String = kind.schemes.first()

    /** A connection that was asked for has opened, and [answer] is what its server gave back. */
    open fun opened(connection: C, answer: Any?) {}
}

/**
 * The connections to one kind of server: saving them, opening one and letting go of it, and reading what's
 * on it.
 *
 * @param goTo opens a location on screen.
 * @param fallback where to go when a server is let go of and no other one takes its place.
 */
internal class ServerConnections<C : Any, E>(
    private val traits: ServerTraits<C, E>,
    private val saved: SavedServers<C>,
    private val link: ServerLink<C>,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val settings: SettingsStore,
    private val messages: Messages,
    private val words: Words,
    val goTo: (String) -> Unit,
    private val fallback: () -> String
) {
    val kind: ServerKind get() = traits.kind

    /** What's saved, with the connection that was open last time still the one in use. */
    fun restore() {
        val all = saved.all()
        val last = settings.activeServer(kind)?.takeIf { id -> all.any { traits.idOf(it) == id } }
        show {
            it.copy(
                saved = all,
                activeId = last,
                status = if (last == null) ConnectionStatus.Idle else ConnectionStatus.Connected
            )
        }
    }

    fun add(connection: C, password: String) {
        saved.save(connection, password)
        show { it.copy(saved = saved.all()) }
    }

    fun delete(connectionId: String) {
        saved.delete(connectionId)
        show { it.copy(saved = saved.all()) }
        if (traits.slot.of(live.now).activeId == connectionId) disconnect()
    }

    /** Opens a saved connection and goes to its top folder. One that's no longer whole is left alone. */
    fun connect(connectionId: String) {
        val connection = find(connectionId) ?: return
        val password = saved.passwordOf(connectionId) ?: return
        show { it.copy(status = ConnectionStatus.Connecting, error = null) }
        scope.launch {
            val answer = link.open(connection, password)
            if (answer.isFailure) {
                val error = traits.errorOf(answer.exceptionOrNull())
                show { it.copy(status = ConnectionStatus.Error, error = error) }
                messages.show(words.text(R.string.app_connect_failed))
                return@launch
            }
            traits.opened(connection, answer.getOrNull())
            show { it.copy(activeId = connectionId, status = ConnectionStatus.Connected, error = null) }
            settings.setActiveServer(kind, connectionId)
            goTo(ServerKind.topOf(traits.schemeOf(connection), connectionId))
        }
    }

    fun disconnect() {
        scope.launch {
            link.close()
            show { it.copy(activeId = null, path = "", status = ConnectionStatus.Idle, error = null) }
            settings.setActiveServer(kind, null)
            goTo(fallback())
        }
    }

    /** The top folder of the connection in use, or null when none is. */
    fun activeTop(state: AppState): String? {
        val view = traits.slot.of(state)
        val id = view.activeId ?: return null
        val scheme = view.saved.firstOrNull { traits.idOf(it) == id }?.let(traits::schemeOf) ?: kind.schemes.first()
        return ServerKind.topOf(scheme, id)
    }

    /**
     * The files at [location]. A server that isn't open, or has since hung up, is opened again first with
     * what was saved, so a folder that was on screen yesterday still opens.
     */
    suspend fun list(location: String): List<FileItem> {
        val id = ServerKind.connectionIn(location)
        link.list(location, id).onSuccess { return it }
        val connection = find(id) ?: throw Exception("Connection not found")
        val password = saved.passwordOf(id) ?: throw Exception("Password not found")
        link.open(connection, password).getOrThrow()
        return link.list(location, id).getOrThrow()
    }

    /** Copies the file at [path] to [target], opening its server first if another one is open. */
    suspend fun download(path: String, target: File): Boolean {
        val id = ServerKind.connectionIn(path)
        if (link.openId != id) {
            val connection = find(id)
            val password = saved.passwordOf(id)
            // If it won't open, the download is what fails.
            if (connection != null && password != null) link.open(connection, password)
        }
        return link.download(path, target).isSuccess
    }

    fun dropNow() = link.dropNow()

    private fun find(connectionId: String): C? = saved.all().firstOrNull { traits.idOf(it) == connectionId }

    private fun show(change: (ServerView<C, E>) -> ServerView<C, E>) =
        live.update { traits.slot.into(it, change(traits.slot.of(it))) }
}
