package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Everything that makes this phone visible and able to receive, as one thing that's either up or not: the
 * server other devices send to, the discovery that tells them where it is, and the regular check on the
 * devices this phone already knows.
 *
 * Bringing it up binds ports and may have to make this phone's key, and taking it down closes sockets that
 * can be slow to let go, so neither happens on the caller's thread. They do happen strictly in the order
 * they were asked for, across every engine there has ever been, which is what lets Receive be turned off and
 * straight back on: the new engine waits for the old one to give the port back before asking for it.
 *
 * @param onActivity something came in that means the phone is in use, and shouldn't be let go idle.
 */
internal class ShareEngine(context: Context, private val onActivity: () -> Unit) {
    private val appContext = context.applicationContext
    private val log: ShareLog = PhoneLog("NearbyShareEngine")

    private class Parts(val device: OwnDevice, val discovery: LocalSendDiscovery, val server: LocalSendServer)

    @Volatile
    private var parts: Parts? = null

    @Volatile
    private var stopped = false

    /** The port the server is on, once it has one. */
    val port: Int? get() = parts?.server?.boundPort

    /**
     * Brings it up, some time soon. [whenSettled] hears whether it's now listening. It also hears false
     * if [stop] came first, by which time everything this started has been taken down again.
     */
    fun start(whenSettled: (listening: Boolean) -> Unit) {
        oneAtATime.execute {
            val listening = try {
                bringUp()
            } catch (e: Exception) {
                log.warn("Couldn't start receiving", e)
                false
            }
            whenSettled(listening)
        }
    }

    /**
     * Says this phone is receiving, and tells the network so. Kept apart from [start] so that whoever
     * started it can first make sure it's still wanted.
     */
    fun goLive() {
        val up = parts ?: return
        NearbyShareRepository.setReceiving(true)
        up.discovery.announce(wantsReply = true)
        PeerDirectory.startKeepAlive(local = up.device::describe, discovered = { up.discovery.peers.value })
    }

    /**
     * Takes it down. The rest of the app stops seeing it at once. The sockets and the multicast lock are
     * given back shortly after, behind whatever was still starting.
     */
    fun stop() {
        stopped = true
        withdraw()
        oneAtATime.execute {
            // Again, for a start that was halfway through when the stop came and has put itself forward since.
            withdraw()
            val up = parts ?: return@execute
            try {
                up.discovery.stop()
                up.server.stop()
            } catch (e: Exception) {
                log.warn("Error stopping server or discovery", e)
            }
        }
    }

    private fun bringUp(): Boolean {
        if (stopped) return false
        val device = OwnDevice(appContext)
        val discovery = LocalSendDiscovery(
            appContext,
            device,
            OkHttpClient.Builder().also(LocalSendSecurity::configureOkHttpClient).build()
        )
        val serverLog = PhoneLog("LocalSendServer")
        val host = PhoneReceiveHost(appContext, device, discovery, NotingActivity(NearbyShareRepository.incoming, onActivity))
        val server = LocalSendServer(
            desk = ReceiveDesk(host, serverLog),
            tls = { LocalSendSecurity.serverSocketFactory(appContext) },
            log = serverLog
        )
        parts = Parts(device, discovery, server)

        // Before the server is up, so that what this phone says about itself is already this engine's.
        NearbyShareRepository.discoveryStarted(discovery, device)
        val port = server.start()
        if (port == null) {
            log.warn("No port to receive on")
            return false
        }
        // Only now is it known which port to announce.
        device.port = port
        discovery.start()
        NearbyShareRepository.serverStarted(server)
        return !stopped
    }

    private fun withdraw() {
        val up = parts ?: return
        NearbyShareRepository.discoveryStopped(up.discovery)
        NearbyShareRepository.serverStopped(up.server)
    }

    private companion object {
        val oneAtATime: ExecutorService = Executors.newSingleThreadExecutor { work ->
            Thread(work, "nearby-share-engine").apply { isDaemon = true }
        }
    }
}

/**
 * Passes everything on, and for the events that mean someone is really using this phone to receive, says so
 * first. Progress isn't one of them: the transfer on the card already keeps the service awake while it moves.
 */
private class NotingActivity(
    private val events: IncomingEvents,
    private val onActivity: () -> Unit
) : IncomingEvents by events {
    override fun ask(request: IncomingTransferRequest) {
        onActivity()
        events.ask(request)
    }

    override fun completed(sessionId: String, sender: DeviceDto, saved: List<File>) {
        onActivity()
        events.completed(sessionId, sender, saved)
    }

    override fun cancelled(sessionId: String) {
        onActivity()
        events.cancelled(sessionId)
    }

    override fun failed(sessionId: String, message: String, saved: List<File>) {
        onActivity()
        events.failed(sessionId, message, saved)
    }
}
