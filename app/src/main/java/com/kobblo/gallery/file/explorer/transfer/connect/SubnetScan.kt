package com.kobblo.gallery.file.explorer.transfer.connect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.LocalSendServer
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.core.LocalAddresses
import com.kobblo.gallery.file.explorer.transfer.core.NetworkDiagnosis
import com.kobblo.gallery.file.explorer.transfer.core.NetworkFacts
import com.kobblo.gallery.file.explorer.transfer.core.PeerNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

object NetworkInspector {
    fun facts(context: Context): NetworkFacts = NetworkFacts(LocalAddresses.current(), vpnOn(context))

    private fun vpnOn(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
}

/** How the last look around went. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Finished(val found: Int) : ScanState
}

/**
 * The fallback for networks that block multicast, as a lot of guest and office Wi-Fi does: knock on this
 * network's addresses at the two ports the protocol uses. It stays on private addresses and the phone's
 * own /24, it asks only "who are you", and it only runs when someone is looking for a device and
 * announcing hasn't found one.
 */
object SubnetScan {
    private const val TAG = "SubnetScan"
    private const val PARALLEL = 64
    // A phone saving power on Wi-Fi can take a few hundred milliseconds just to answer who has an address.
    private const val CONNECT_MS = 900
    private const val LOCALSEND_PORT = 53317

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Its own threads, because these all sit waiting at once, and the rest of the app shares the IO pool.
    private val knocking = Dispatchers.IO.limitedParallelism(PARALLEL)
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val found = ConcurrentHashMap<String, Target>()
    private var job: Job? = null

    private data class Target(val host: String, val port: Int, val protocol: String)

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = ScanState.Scanning
            NearbyShareRepository.broadcastAnnouncement()
            val addresses = LocalAddresses.current().filter(NetworkDiagnosis::isPrivate).take(2)
            val hosts = addresses.flatMap(NetworkDiagnosis::neighbours).distinct()
            val open = coroutineScope {
                hosts.flatMap { host ->
                    listOf(LocalSendServer.DEFAULT_HTTP_PORT, LOCALSEND_PORT).map { port ->
                        async(knocking) { if (accepts(host, port)) host to port else null }
                    }
                }.awaitAll().filterNotNull()
            }
            open.forEach { (host, port) -> identify(app, host, port) }
            Log.i(TAG, "Asked ${hosts.size} addresses: ${open.size} answered, ${found.size} are devices")
            _state.value = ScanState.Finished(found.size)
        }
    }

    /** A device found this way doesn't announce, so it goes quiet in the list unless it's asked again. */
    fun refresh(context: Context) {
        if (found.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            val local = NearbyShareRepository.localDevice(app)
            found.values.toList().forEach { target ->
                val hello = PeerDirectory.hello(target.host, target.port, target.protocol, local)
                if (hello is PeerDirectory.Hello.Answered) register(app, target, hello) else found.remove("${target.host}:${target.port}")
            }
        }
    }

    fun clear() {
        found.clear()
        _state.value = ScanState.Idle
    }

    private fun accepts(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_MS) }
        true
    } catch (e: java.io.IOException) {
        false
    }

    private fun identify(context: Context, host: String, port: Int) {
        val local = NearbyShareRepository.localDevice(context)
        for (protocol in listOf("https", "http")) {
            val hello = PeerDirectory.hello(host, port, protocol, local)
            if (hello is PeerDirectory.Hello.Answered) {
                if (!hello.fingerprint.equals(local.fingerprint, ignoreCase = true)) {
                    val target = Target(host, port, protocol)
                    found["$host:$port"] = target
                    register(context, target, hello)
                }
                return
            }
            if (hello == PeerDirectory.Hello.WrongIdentity) return
        }
    }

    private fun register(context: Context, target: Target, hello: PeerDirectory.Hello.Answered) {
        val unknown = context.getString(R.string.share_unknown_device)
        NearbyShareRepository.addFound(
            NearbyPeer(
                ip = target.host,
                port = target.port,
                alias = PeerNames.clean(hello.device.alias, unknown),
                deviceModel = hello.device.deviceModel?.let { PeerNames.clean(it, unknown) },
                deviceType = hello.device.deviceType ?: "mobile",
                protocol = target.protocol,
                fingerprint = hello.fingerprint,
                lastSeenMs = System.currentTimeMillis()
            )
        )
    }
}
