package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.core.LocalAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.StandardSocketOptions

/**
 * Finds the other devices on this network, and lets them find this one, the way LocalSend protocol version 2
 * has it. Every few seconds this phone says who it is to a multicast group. Whoever hears a device say so, and
 * is asked to, answers twice over: once to the group, and once straight to that device's own server, because on
 * plenty of networks only one of the two gets through.
 *
 * While it runs it holds a multicast lock, without which a phone's Wi-Fi drops such packets to save power, and
 * one socket joined to the group. [stop] gives both back.
 */
internal class LocalSendDiscovery(
    context: Context,
    private val self: OwnDevice,
    private val http: OkHttpClient
) {
    private val appContext = context.applicationContext
    private val log: ShareLog = PhoneLog("LocalSendDiscovery")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val table = PeerTable(
        ownFingerprint = { self.fingerprint },
        pin = LocalSendSecurity::pinDiscovered,
        unpin = LocalSendSecurity::releaseDiscoveredPin
    )

    private val linkLock = Any()

    /** The socket that's joined to the group right now. Only changed while holding [linkLock]. */
    @Volatile
    private var link: GroupSocket? = null
    private var airtime: WifiManager.MulticastLock? = null

    /** The devices heard from lately. Empty while stopped. */
    val peers: StateFlow<List<NearbyPeer>> get() = table.listed

    /** Starts listening and announcing. Starting again begins afresh, with an empty list. */
    @Synchronized
    fun start() {
        stop()
        holdAirtime()
        scope.launch { keepListening() }
        scope.launch { rejoinWhenAddressesChange() }
        scope.launch { keepAnnouncing() }
        scope.launch { keepListFresh() }
    }

    @Synchronized
    fun stop() {
        scope.coroutineContext.cancelChildren()
        // After the cancel, so a listener that was still opening its socket sees it has been stopped.
        synchronized(linkLock) {
            link?.close()
            link = null
        }
        releaseAirtime()
        table.clear()
    }

    /** Lists a device found some other way than by hearing it: by asking around, or because it called here. */
    fun add(peer: NearbyPeer) = table.admit(peer)

    /** Tells the group this phone is here. With [wantsReply], everyone who hears it says so back. */
    fun announce(wantsReply: Boolean = true) {
        scope.launch {
            try {
                val datagram = LocalSendWire.announcement(self.describe(), wantsReply)
                val joined = link?.takeUnless { it.isClosed }
                if (joined != null) joined.sendToGroup(datagram) else GroupSocket.sendWithoutJoining(datagram)
            } catch (e: Exception) {
                log.warn("Failed to send announcement", e)
            }
        }
    }

    private fun holdAirtime() {
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            airtime = wifi?.createMulticastLock(MULTICAST_LOCK_NAME)?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            log.warn("Failed to acquire multicast lock", e)
        }
    }

    private fun releaseAirtime() {
        try {
            airtime?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            log.debug("Multicast lock was already gone: ${e.javaClass.simpleName}")
        }
        airtime = null
    }

    /** A listener ends when its socket closes, which is how it's told to start over, so there's always a next one. */
    private suspend fun keepListening() {
        val listener = currentCoroutineContext().job
        while (listener.isActive) {
            listenUntilClosed(listener)
            delay(REJOIN_AFTER_MS)
        }
    }

    private fun listenUntilClosed(listener: Job) {
        val socket = try {
            GroupSocket.open(log)
        } catch (e: Exception) {
            log.warn("Couldn't join the announcement group", e)
            return
        }
        // Stopping cancels the listener first and then closes whatever socket is here, so one of the two
        // always sees the other: a socket opened too late is never left joined with nobody to close it.
        val stillWanted = synchronized(linkLock) {
            listener.isActive.also { wanted -> if (wanted) link = socket }
        }
        if (!stillWanted) {
            socket.close()
            return
        }
        try {
            while (true) {
                val heard = socket.receive() ?: continue
                onDatagram(heard.text, heard.fromIp)
            }
        } catch (e: Exception) {
            // Closed on purpose, when stopping or when the network changed, needs no comment.
            if (!socket.isClosed) log.warn("Error in multicast listener", e)
        } finally {
            synchronized(linkLock) {
                if (link === socket) link = null
            }
            socket.close()
        }
    }

    /**
     * A network that comes or goes leaves the socket joined on an interface that's no use any more, and
     * nothing fails to say so: it just never hears anyone again. So when this phone's addresses change, the
     * socket is closed, and the listener joins again with a new one.
     */
    private suspend fun rejoinWhenAddressesChange() {
        var addresses = LocalAddresses.current()
        while (currentCoroutineContext().isActive) {
            delay(ADDRESS_CHECK_MS)
            val now = LocalAddresses.current()
            if (now != addresses) {
                addresses = now
                link?.close()
            }
        }
    }

    private suspend fun keepAnnouncing() {
        while (currentCoroutineContext().isActive) {
            announce(wantsReply = true)
            delay(ANNOUNCE_EVERY_MS)
        }
    }

    private suspend fun keepListFresh() {
        while (currentCoroutineContext().isActive) {
            delay(FRESHNESS_CHECK_MS)
            table.dropSilent(System.currentTimeMillis(), SILENT_AFTER_MS)
        }
    }

    private fun onDatagram(text: String, fromIp: String) {
        try {
            val device = LocalSendWire.readDevice(text) ?: return
            // Every announcement comes back round to the phone that sent it.
            if (device.statedFingerprint.equals(self.fingerprint, ignoreCase = true)) return
            val announced = LocalSendWire.peer(device, fromIp, unknownName(), System.currentTimeMillis()) ?: return
            table.admit(announced)
            if (device.announce == true) {
                scope.launch {
                    announce(wantsReply = false)
                    registerWith(announced)
                }
            }
        } catch (e: Exception) {
            log.debug("Couldn't read an announcement: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Calls the server of a device that asked to hear back, and lists it again from its own answer. That
     * answer leaves out where the device is, so its address, port and protocol stay as announced.
     */
    private fun registerWith(asker: NearbyPeer) {
        try {
            val hello = LocalSendWire.write(self.describe().copy(announce = false))
                .toRequestBody(LocalSendWire.JSON_UTF8_TYPE.toMediaType())
            val request = Request.Builder().url(asker.baseUrl + LocalSendWire.REGISTER_PATH).post(hello).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string()
                if (body.isNullOrBlank()) return
                val answered = LocalSendWire.readDevice(body) ?: return
                LocalSendWire.peer(
                    device = answered,
                    ip = asker.ip,
                    unknownName = unknownName(),
                    nowMs = System.currentTimeMillis(),
                    port = asker.port,
                    protocol = asker.protocol,
                    fingerprint = answered.statedFingerprint?.ifBlank { asker.fingerprint }
                )?.let(table::admit)
            }
        } catch (e: Exception) {
            // It may have gone, or a firewall may be in the way. Announcing to the group covers for it.
            log.debug("HTTP register failed: ${e.javaClass.simpleName}")
        }
    }

    private fun unknownName(): String = appContext.getString(R.string.share_unknown_device)

    private companion object {
        const val MULTICAST_LOCK_NAME = "KobbloLocalSendMulticastLock"
        const val ANNOUNCE_EVERY_MS = 4_000L
        const val ADDRESS_CHECK_MS = 3_000L
        const val REJOIN_AFTER_MS = 1_000L
        const val FRESHNESS_CHECK_MS = 5_000L

        /** Six announcements missed in a row. */
        const val SILENT_AFTER_MS = 25_000L
    }
}

/** One datagram from the group: what it said, and the address it came from. */
private class Heard(val text: String, val fromIp: String)

/**
 * A socket on the announcement port, joined to the group on every interface a nearby device could be on. Left
 * to itself the system would use its default interface alone, which on a phone making a hotspot is its mobile
 * data, where nobody nearby can hear.
 */
private class GroupSocket private constructor(private val socket: MulticastSocket) : Closeable {
    private val buffer = ByteArray(MAX_DATAGRAM_BYTES)

    /**
     * One send at a time, since each points the socket at an interface first. It's a lock of its own and not
     * the socket's: a receive holds on to the socket until something arrives, and on a quiet network every
     * announcement would queue up behind it, each one keeping a thread.
     */
    private val sendLock = Any()

    val isClosed: Boolean get() = socket.isClosed

    /** Waits for the next datagram. Null for one with no usable sender. Throws once the socket is closed. */
    fun receive(): Heard? {
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        val fromIp = packet.address?.hostAddress ?: return null
        return Heard(String(packet.data, packet.offset, packet.length, Charsets.UTF_8), fromIp)
    }

    fun sendToGroup(datagram: ByteArray) {
        val packet = groupPacket(datagram)
        synchronized(sendLock) {
            val reached = LocalAddresses.multicastInterfaces().count { via ->
                runCatching {
                    socket.networkInterface = via
                    socket.send(packet)
                }.isSuccess
            }
            if (reached == 0) socket.send(packet)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        /** The largest a UDP datagram can be. */
        private const val MAX_DATAGRAM_BYTES = 65_535

        private fun group(): InetAddress = InetAddress.getByName(LocalSendWire.GROUP_ADDRESS)

        private fun groupPacket(datagram: ByteArray) = DatagramPacket(datagram, datagram.size, group(), LocalSendWire.GROUP_PORT)

        /**
         * Binds to the group's port, shared with any other LocalSend app on this phone, and joins. If the port
         * can't be had, any port will still do for sending, though nothing will be heard on it.
         */
        @Suppress("DEPRECATION")
        fun open(log: ShareLog): GroupSocket {
            val socket = MulticastSocket(null)
            try {
                socket.reuseAddress = true
                // Android 13 is when sockets gained this option. Reusing the address does the job before that.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        socket.setOption(StandardSocketOptions.SO_REUSEPORT, true)
                    } catch (e: Throwable) {
                        log.debug("SO_REUSEPORT not supported: ${e.message}")
                    }
                }
                try {
                    socket.bind(InetSocketAddress(LocalSendWire.GROUP_PORT))
                } catch (e: Exception) {
                    log.warn("Couldn't bind to ${LocalSendWire.GROUP_PORT}, so listening on any free port", e)
                    socket.bind(InetSocketAddress(0))
                }
                val group = group()
                val joined = LocalAddresses.multicastInterfaces().count { via ->
                    runCatching { socket.joinGroup(InetSocketAddress(group, LocalSendWire.GROUP_PORT), via) }.isSuccess
                }
                // With no interface to name, the system's default one is better than none.
                if (joined == 0) socket.joinGroup(group)
                return GroupSocket(socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /** For while nothing is joined, such as the second after the network changed. */
        fun sendWithoutJoining(datagram: ByteArray) {
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                runCatching { socket.bind(InetSocketAddress(0)) }
                socket.send(groupPacket(datagram))
            }
        }
    }
}
