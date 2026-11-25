package com.kobblo.gallery.file.explorer.transfer.connect

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kobblo.gallery.file.explorer.localsend.DeviceDto
import com.kobblo.gallery.file.explorer.localsend.LocalSendSecurity
import com.kobblo.gallery.file.explorer.localsend.LocalSendServer
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.transfer.core.PairingCode
import com.kobblo.gallery.file.explorer.transfer.core.PeerNames
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface ConnectResult {
    data class Connected(val peer: NearbyPeer) : ConnectResult
    data object Unreachable : ConnectResult
    /** Something answered, but not with the certificate it should have. */
    data object IdentityMismatch : ConnectResult
    data object NotADevice : ConnectResult
    /** The address is this phone itself. */
    data object IsThisDevice : ConnectResult
}

/**
 * The devices this phone knows: connected to directly, or sent to before. They're remembered, and
 * re-checked while the service runs, so they appear by themselves next time, first in the list. Each
 * check also registers this phone with them, which is how they learn about it in return.
 *
 * A device moves when its network hands it a new address. Discovery says where, but discovery can be
 * forged, so a new address only counts once that certificate has answered from it.
 */
object PeerDirectory {
    private const val TAG = "PeerDirectory"
    private const val KEEP_ALIVE_MS = 10_000L
    private const val MISSES_BEFORE_OFFLINE = 2
    private const val MAX_CHECKED = 8
    private const val LOCALSEND_DEFAULT_PORT = 53317
    private const val REGISTER_PATH = "/api/localsend/v2/register"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .apply { LocalSendSecurity.configureOkHttpClient(this) }
            .build()
    }

    private var store: SavedPeerStore? = null
    private val _saved = MutableStateFlow<List<SavedPeer>>(emptyList())
    val saved: StateFlow<List<SavedPeer>> = _saved.asStateFlow()

    private val _online = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val online: StateFlow<List<NearbyPeer>> = _online.asStateFlow()

    private val misses = ConcurrentHashMap<String, Int>()
    private var keepAliveJob: Job? = null

    @Synchronized
    fun attach(context: Context) {
        if (store != null) return
        val created = SavedPeerStore(File(context.applicationContext.filesDir, "saved_devices.json"))
        store = created
        scope.launch {
            val loaded = created.all()
            loaded.forEach(::restorePin)
            _saved.value = loaded
        }
    }

    /** Connects using a scanned code. The fingerprint in it is the one that must answer. */
    suspend fun connect(code: PairingCode, local: DeviceDto): ConnectResult = withContext(Dispatchers.IO) {
        var last: ConnectResult = ConnectResult.Unreachable
        for (host in code.hosts) {
            val hostAndPort = "$host:${code.port}"
            val pinnedBefore = LocalSendSecurity.pinnedFingerprints[hostAndPort]
            val wasVerified = LocalSendSecurity.isVerified(hostAndPort)
            code.fingerprint?.let { LocalSendSecurity.pinVerified(hostAndPort, it) }
            val result = probe(host, code.port, code.protocol, local, verified = code.fingerprint != null, fallbackAlias = code.alias)
            if (result is ConnectResult.Connected) return@withContext result
            // A failed attempt shouldn't leave a promise about an address nothing was found at.
            if (!wasVerified) {
                LocalSendSecurity.forgetPin(hostAndPort)
                pinnedBefore?.let { LocalSendSecurity.pinnedFingerprints[hostAndPort] = it }
            }
            if (result != ConnectResult.Unreachable) last = result
        }
        last
    }

    /**
     * Connects to an address someone typed. There's nothing yet to check its certificate against, so
     * this trusts it the first time and holds it to the same one after that, as SSH does.
     */
    suspend fun connect(host: String, port: Int?, local: DeviceDto): ConnectResult = withContext(Dispatchers.IO) {
        val ports = if (port != null) listOf(port) else listOf(LocalSendServer.DEFAULT_HTTP_PORT, LOCALSEND_DEFAULT_PORT)
        var last: ConnectResult = ConnectResult.Unreachable
        for (candidate in ports) {
            for (protocol in listOf("https", "http")) {
                val result = probe(host, candidate, protocol, local, verified = false, fallbackAlias = null)
                if (result is ConnectResult.Connected || result == ConnectResult.IdentityMismatch || result == ConnectResult.IsThisDevice) {
                    return@withContext result
                }
                if (result != ConnectResult.Unreachable) last = result
            }
        }
        last
    }

    fun forget(peer: SavedPeer) {
        LocalSendSecurity.forgetPin("${peer.host}:${peer.port}")
        _online.value = _online.value.filterNot { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) }
        _saved.value = _saved.value.filterNot { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) }
        scope.launch { changeSaved { it.forget(peer.fingerprint) } }
    }

    fun known(fingerprint: String): SavedPeer? =
        _saved.value.firstOrNull { it.fingerprint.equals(fingerprint, ignoreCase = true) }

    /**
     * A send to [peer] went through. Over TLS its certificate had to match before a byte left, so this
     * really was that device, and it goes to the top of the list. Plain HTTP proves nothing, so a device
     * reached that way isn't remembered as anyone.
     */
    fun rememberSentTo(peer: NearbyPeer, nowMs: Long = System.currentTimeMillis()) {
        if (peer.protocol != "https" || peer.fingerprint.isBlank()) return
        saveNow(
            SavedPeer(
                fingerprint = peer.fingerprint,
                alias = peer.alias,
                deviceModel = peer.deviceModel,
                deviceType = peer.deviceType,
                host = peer.ip,
                port = peer.port,
                protocol = peer.protocol,
                lastUsedMs = nowMs
            )
        )
    }

    /** Files were accepted from a device that signed for its fingerprint. An unsigned claim isn't remembered. */
    fun rememberSender(device: DeviceDto, ip: String, nowMs: Long = System.currentTimeMillis()) {
        saveNow(signedSender(device, ip, nowMs, autoAccept = false))
    }

    /** Always accept from a device that has just signed for itself, chosen on the question it asked. */
    fun trustSender(device: DeviceDto, ip: String) {
        saveNow(signedSender(device, ip, System.currentTimeMillis(), autoAccept = true))
    }

    /**
     * The record of a device that has just signed for its fingerprint while sending here, reached at
     * [ip]. A signature only ever arrives over TLS, so that is how the record says to reach it, and it
     * is marked as able to prove itself again, which is what makes accepting automatically possible.
     */
    private fun signedSender(device: DeviceDto, ip: String, usedMs: Long, autoAccept: Boolean) = SavedPeer(
        fingerprint = device.fingerprint,
        alias = device.alias,
        deviceModel = device.deviceModel,
        deviceType = device.deviceType ?: "mobile",
        host = ip,
        port = device.port,
        protocol = "https",
        lastUsedMs = usedMs,
        autoAccept = autoAccept,
        canProve = true
    )

    fun setAutoAccept(fingerprint: String, on: Boolean) {
        _saved.value = _saved.value.map {
            if (it.fingerprint.equals(fingerprint, ignoreCase = true)) it.copy(autoAccept = on) else it
        }
        scope.launch { changeSaved { it.setAutoAccept(fingerprint, on) } }
    }

    private fun saveNow(peer: SavedPeer) {
        scope.launch {
            changeSaved { it.save(peer) }
            restorePin(peer)
        }
    }

    // Results land in the order they were made, or an older list could overwrite a newer one.
    @Synchronized
    private fun changeSaved(change: (SavedPeerStore) -> List<SavedPeer>) {
        val current = store ?: return
        _saved.value = change(current)
    }

    /** Runs while the service does, so a known device shows up without anyone asking. */
    @Synchronized
    fun startKeepAlive(local: () -> DeviceDto, discovered: () -> List<NearbyPeer> = { emptyList() }) {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                checkSavedDevices(local(), discovered())
                delay(KEEP_ALIVE_MS)
            }
        }
    }

    @Synchronized
    fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        _online.value = emptyList()
        misses.clear()
    }

    /**
     * Side by side, since a device that isn't around takes seconds to time out, and a person shouldn't
     * wait for the laptop at home before their friend's phone appears.
     */
    private suspend fun checkSavedDevices(local: DeviceDto, discovered: List<NearbyPeer>) = coroutineScope {
        _saved.value.sortedByDescending { it.lastUsedMs }.take(MAX_CHECKED).map { peer ->
            async { checkOne(peer, local, discovered) }
        }.awaitAll()
    }

    private fun checkOne(peer: SavedPeer, local: DeviceDto, discovered: List<NearbyPeer>) {
        val key = peer.fingerprint
        val moved = discovered.firstOrNull {
            it.protocol == peer.protocol && it.fingerprint.equals(key, ignoreCase = true) && (it.ip != peer.host || it.port != peer.port)
        }
        val target = moved?.let { peer.copy(host = it.ip, port = it.port) } ?: peer
        restorePin(target)
        val result = probe(target.host, target.port, target.protocol, local, target.verified, target.alias)
        if (result is ConnectResult.Connected) {
            misses.remove(key)
        } else {
            val count = (misses[key] ?: 0) + 1
            misses[key] = count
            if (count >= MISSES_BEFORE_OFFLINE) {
                _online.value = _online.value.filterNot { it.fingerprint.equals(key, ignoreCase = true) }
            }
        }
    }

    private fun restorePin(peer: SavedPeer) {
        val hostAndPort = "${peer.host}:${peer.port}"
        if (peer.protocol != "https") return
        if (peer.verified) LocalSendSecurity.pinVerified(hostAndPort, peer.fingerprint)
        else LocalSendSecurity.pinDiscovered(hostAndPort, peer.fingerprint)
    }

    /** What a device says when asked, with its fingerprint taken from the connection where that's possible. */
    sealed interface Hello {
        data class Answered(val device: DeviceDto, val fingerprint: String) : Hello
        data object NoAnswer : Hello
        data object WrongIdentity : Hello
        data object NotADevice : Hello
    }

    /** Asks one address who it is and tells it who this phone is. Remembers nothing. */
    fun hello(host: String, port: Int, protocol: String, local: DeviceDto, client: OkHttpClient = http): Hello {
        val request = Request.Builder()
            .url("$protocol://$host:$port$REGISTER_PATH")
            .post(gson.toJson(local.copy(announce = null)).toRequestBody(json))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Hello.NotADevice
                val dto = try {
                    gson.fromJson(response.body?.string().orEmpty(), DeviceDto::class.java)
                } catch (e: Exception) {
                    null
                } ?: return Hello.NotADevice

                // Only over TLS was that header put there by this phone. Over plain HTTP the device could say anything.
                val seen = if (protocol == "https") response.header(LocalSendSecurity.SEEN_FINGERPRINT_HEADER) else null
                if (protocol == "https" && seen == null) return Hello.WrongIdentity
                // Over TLS the device's own claim must agree with the certificate it's using.
                if (seen != null && dto.fingerprint.isNotBlank() && !LocalSendSecurity.fingerprintsMatch(dto.fingerprint, seen)) {
                    return Hello.WrongIdentity
                }
                Hello.Answered(dto, seen ?: dto.fingerprint)
            }
        } catch (e: LocalSendSecurity.FingerprintMismatchException) {
            Log.w(TAG, "Fingerprint mismatch")
            Hello.WrongIdentity
        } catch (e: IOException) {
            Hello.NoAnswer
        } catch (e: IllegalArgumentException) {
            Hello.NoAnswer // An address OkHttp won't parse.
        }
    }

    private fun probe(
        host: String,
        port: Int,
        protocol: String,
        local: DeviceDto,
        verified: Boolean,
        fallbackAlias: String?
    ): ConnectResult = when (val hello = hello(host, port, protocol, local)) {
        Hello.NoAnswer -> ConnectResult.Unreachable
        Hello.NotADevice -> ConnectResult.NotADevice
        Hello.WrongIdentity -> ConnectResult.IdentityMismatch
        is Hello.Answered -> {
            val dto = hello.device
            if (LocalSendSecurity.fingerprintsMatch(local.fingerprint, hello.fingerprint)) {
                ConnectResult.IsThisDevice
            } else {
                if (protocol == "https") LocalSendSecurity.pinDiscovered("$host:$port", hello.fingerprint)
                val saved = SavedPeer(
                    fingerprint = hello.fingerprint,
                    alias = PeerNames.clean(dto.alias.takeIf { it != "Unknown" }, fallbackAlias ?: host),
                    deviceModel = dto.deviceModel,
                    deviceType = dto.deviceType ?: "mobile",
                    host = host,
                    port = port,
                    protocol = protocol,
                    verified = verified
                )
                ConnectResult.Connected(remember(saved).toPeer(System.currentTimeMillis()))
            }
        }
    }

    private fun remember(peer: SavedPeer): SavedPeer {
        val previous = _saved.value.firstOrNull { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) }
        val merged = peer.copy(
            verified = peer.verified || previous?.verified == true,
            lastUsedMs = previous?.lastUsedMs ?: 0,
            autoAccept = previous?.autoAccept == true,
            canProve = previous?.canProve == true
        )
        // This runs every few seconds per device, and nothing has usually changed.
        if (merged != previous) changeSaved { it.save(merged) }
        val fresh = merged.toPeer(System.currentTimeMillis())
        synchronized(this) {
            _online.value = _online.value.filterNot { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) } + fresh
        }
        return merged
    }
}
