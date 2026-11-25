package com.kobblo.gallery.file.explorer.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a call fails with when there is no session to make it on. */
internal const val NotConnected = "Not connected"

/**
 * Makes one call to a server off the main thread and answers with what it returned, or with the exception
 * that ended it. Servers and networks fail in every way there is, and the explorer wants each of those as
 * a failed [Result] to look at, not as something thrown into it. Errors that aren't exceptions, such as
 * running out of memory, are not caught.
 */
internal suspend fun <T> remoteCall(call: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(call())
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}

/**
 * The one session a storage provider keeps: the client library's own object, together with the id of the
 * saved connection it was opened for. The two are set and dropped as a pair, so the id never names a
 * session that has gone.
 */
internal class RemoteSession<C : Any> {
    @Volatile
    private var held: Held<C>? = null

    /** The saved connection whose session is open, or null when none is. */
    val connectionId: String? get() = held?.connectionId

    val client: C? get() = held?.client

    fun open(connectionId: String, client: C) {
        held = Held(connectionId, client)
    }

    /** Lets go of the session and hands its client back, for the caller to close. */
    fun close(): C? = held?.client.also { held = null }

    /** Makes a call on whichever session is open. */
    suspend fun <T> call(work: suspend (C) -> T): Result<T> =
        remoteCall { work(client ?: throw IllegalStateException(NotConnected)) }

    /**
     * Makes a call that only [connectionId]'s own session may answer. Listing goes through here: its rows
     * carry that id in their paths, so another connection's files must never fill them.
     */
    suspend fun <T> callFor(connectionId: String, work: suspend (C) -> T): Result<T> = remoteCall {
        val own = held?.takeIf { it.connectionId == connectionId } ?: throw Exception(NotConnected)
        work(own.client)
    }

    private class Held<C>(val connectionId: String, val client: C)
}
