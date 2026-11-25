package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.BoundedAsyncRunner
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.net.ssl.SSLServerSocketFactory

/**
 * The socket other devices call to send to this phone, and the lifetime of everything behind it. What each
 * call means, and what's said back, is [ReceiveDesk]'s business. This only listens, hands each connection a
 * thread, and keeps house while it's up.
 *
 * One of these serves once: after [stop], make another.
 *
 * @param tls makes the socket answer as this device, over TLS. Without it the server speaks plain HTTP, which
 *   the protocol also allows and tests use.
 */
class LocalSendServer internal constructor(
    private val desk: ReceiveDesk,
    private val tls: (() -> SSLServerSocketFactory)?,
    private val preferredPort: Int = DEFAULT_HTTP_PORT,
    private val log: ShareLog = ShareLog.Silent
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var listener: Listener? = null

    /** The port it's listening on, once [start] has said so. */
    var boundPort: Int = preferredPort
        private set

    /**
     * Starts listening on the usual port, or on any free one if something else has that. Other devices are
     * told the port in every announcement, so either works. Null if no port could be had at all.
     */
    fun start(): Int? {
        val listening = listenOn(preferredPort) ?: listenOn(ANY_FREE_PORT) ?: return null
        listener = listening
        boundPort = listening.listeningPort
        log.info("Listening on port $boundPort")
        keepHouse()
        return boundPort
    }

    /** Stops listening. Whatever was still arriving is cut off, and each upload removes its own partial file. */
    fun stop() {
        desk.close()
        scope.cancel()
        try {
            listener?.stop()
        } catch (e: Exception) {
            log.warn("Error stopping the server", e)
        }
        listener = null
    }

    /** The person here doesn't want the rest of what's arriving. */
    fun cancel(sessionId: String) = desk.cancel(sessionId)

    private fun listenOn(port: Int): Listener? {
        val candidate = Listener(port)
        try {
            tls?.let { candidate.makeSecure(it(), null) }
            candidate.start(READ_TIMEOUT_MS, false)
            if (candidate.listeningPort > 0) return candidate
            log.warn("Asked for port $port and was given none")
        } catch (e: Exception) {
            log.warn("Couldn't listen on port $port", e)
        }
        runCatching { candidate.stop() }
        return null
    }

    private fun keepHouse() {
        scope.launch {
            desk.clearLeftovers()
            while (isActive) {
                delay(STALL_CHECK_MS)
                desk.endStalled()
            }
        }
    }

    private inner class Listener(port: Int) : NanoHTTPD(port) {
        init {
            // A thread for each connection, so the limit is also the most a stranger on the network can cost.
            setAsyncRunner(BoundedAsyncRunner(MAX_CONNECTIONS))
        }

        override fun serve(session: IHTTPSession): Response = desk.answer(session)

        // A compressed reply would also lose its Content-Length, which other LocalSend apps count on here.
        override fun useGzipWhenAccepted(r: Response): Boolean = false
    }

    companion object {
        /**
         * Where this app serves when it's free. Every device is told which port to call, so it needn't be the
         * protocol's own 53317, which the multicast socket is on.
         */
        const val DEFAULT_HTTP_PORT = 53318

        private const val ANY_FREE_PORT = 0
        private const val MAX_CONNECTIONS = 48
        private const val STALL_CHECK_MS = 5_000L

        /** A read this long without a byte ends an upload. The server library's own five seconds would cut off a slow Wi-Fi. */
        private const val READ_TIMEOUT_MS = 30_000
    }
}
