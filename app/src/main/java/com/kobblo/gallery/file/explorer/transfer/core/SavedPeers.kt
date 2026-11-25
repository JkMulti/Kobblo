package com.kobblo.gallery.file.explorer.transfer.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import java.io.File
import java.io.IOException

/**
 * A device this phone has dealt with, remembered so it needs no discovery next time and comes first in
 * the list. Who it is means its certificate's fingerprint. Its name is only a label anyone can copy.
 */
data class SavedPeer(
    val fingerprint: String,
    val alias: String,
    val deviceModel: String? = null,
    val deviceType: String = "mobile",
    val host: String,
    val port: Int,
    val protocol: String = "https",
    /** True if its fingerprint came through a scanned code rather than from the device itself. */
    val verified: Boolean = false,
    /** When something last went to it or came from it. Zero if it was only connected to. */
    val lastUsedMs: Long = 0,
    /** Save what it sends without asking. Only ever honoured together with a valid [SenderProof]. */
    val autoAccept: Boolean = false,
    /** It has proved who it is when sending here, so accepting automatically is possible at all. */
    val canProve: Boolean = false
) {
    fun toPeer(lastSeenMs: Long) = NearbyPeer(
        ip = host,
        port = port,
        alias = alias,
        deviceModel = deviceModel,
        deviceType = deviceType,
        protocol = protocol,
        fingerprint = fingerprint,
        lastSeenMs = lastSeenMs,
        verified = verified,
        known = lastUsedMs > 0,
        lastUsedMs = lastUsedMs
    )
}

/** Remembers saved devices in one small JSON file, replaced whole so a crash can't damage it. */
class SavedPeerStore(private val file: File, private val maxPeers: Int = 32) {
    private val gson = Gson()
    private val type = object : TypeToken<List<SavedPeer>>() {}.type

    @Synchronized
    fun all(): List<SavedPeer> = try {
        if (file.exists()) {
            val parsed: List<SavedPeer>? = gson.fromJson(file.readText(), type)
            parsed.orEmpty().filter(::isComplete)
        } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Saves [peer], replacing any earlier entry for the same device. What was earned stays earned: a
     * new connection doesn't undo a scanned code, a proof, or a choice to accept automatically.
     */
    @Synchronized
    fun save(peer: SavedPeer): List<SavedPeer> {
        val current = all()
        val previous = current.firstOrNull { sameDevice(it, peer) }
        val merged = peer.copy(
            verified = peer.verified || previous?.verified == true,
            lastUsedMs = maxOf(peer.lastUsedMs, previous?.lastUsedMs ?: 0),
            autoAccept = peer.autoAccept || previous?.autoAccept == true,
            canProve = peer.canProve || previous?.canProve == true
        )
        val updated = (current.filterNot { sameDevice(it, peer) } + merged)
            .sortedByDescending { it.lastUsedMs }
            .take(maxPeers)
        write(updated)
        return updated
    }

    @Synchronized
    fun setAutoAccept(fingerprint: String, on: Boolean): List<SavedPeer> {
        val updated = all().map { if (it.fingerprint.equals(fingerprint, ignoreCase = true)) it.copy(autoAccept = on) else it }
        write(updated)
        return updated
    }

    @Synchronized
    fun forget(fingerprint: String): List<SavedPeer> {
        val updated = all().filterNot { it.fingerprint.equals(fingerprint, ignoreCase = true) }
        write(updated)
        return updated
    }

    private fun sameDevice(a: SavedPeer, b: SavedPeer) = a.fingerprint.equals(b.fingerprint, ignoreCase = true)

    private fun write(list: List<SavedPeer>) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(gson.toJson(list))
            if (!temp.renameTo(file)) throw IOException("Couldn't replace ${file.name}")
        } catch (e: IOException) {
            // Only costs scanning the code again next time.
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun isComplete(peer: SavedPeer): Boolean =
        peer.fingerprint != null && peer.alias != null && peer.host != null && PairingCode.isValidHost(peer.host) && peer.port in 1..65535
}

object PeerMerge {
    /**
     * One list for the radar. A device seen both ways appears once, as the direct connection because
     * that one is known to work. Known devices come first, most recently used first, then the rest by
     * name.
     *
     * A fingerprint only means something over TLS, where the certificate has to match it before a byte
     * is sent. Anyone can announce a plain HTTP device claiming a trusted fingerprint, so one of those
     * is never treated as the device it names and never takes the place of the real one.
     */
    fun merge(discovered: List<NearbyPeer>, direct: List<NearbyPeer>, saved: List<SavedPeer>): List<NearbyPeer> {
        val byDevice = LinkedHashMap<String, NearbyPeer>()
        fun key(peer: NearbyPeer) =
            if (peer.protocol == "https" && peer.fingerprint.isNotBlank()) peer.fingerprint.uppercase() else "address:${peer.id}"
        discovered.forEach { byDevice[key(it)] = it }
        direct.forEach { byDevice[key(it)] = it }
        val savedBy = saved.filter { it.protocol == "https" }.associateBy { it.fingerprint.uppercase() }
        return byDevice.values
            .map { peer ->
                val mine = savedBy[peer.fingerprint.uppercase()]?.takeIf { peer.protocol == "https" }
                if (mine != null) peer.copy(verified = mine.verified, known = mine.lastUsedMs > 0, lastUsedMs = mine.lastUsedMs)
                else peer.copy(verified = false, known = false, lastUsedMs = 0)
            }
            .sortedWith(compareByDescending<NearbyPeer> { it.known }.thenByDescending { it.lastUsedMs }.thenBy { it.alias.lowercase() })
    }

    /** Known devices that aren't reachable right now, most recently used first, so they can still be named. */
    fun absent(saved: List<SavedPeer>, present: List<NearbyPeer>, limit: Int = 4): List<SavedPeer> {
        val here = present.map { it.fingerprint.uppercase() }.toSet()
        return saved
            .filter { it.lastUsedMs > 0 && it.fingerprint.uppercase() !in here }
            .sortedByDescending { it.lastUsedMs }
            .take(limit)
    }

    /** Whether a name in the list is shared, in which case the rows need more than the name to tell apart. */
    fun nameIsShared(peer: NearbyPeer, all: List<NearbyPeer>): Boolean =
        all.count { it.alias.equals(peer.alias, ignoreCase = true) } > 1

    /** A device claiming the name of a known device, without being it. */
    fun impersonates(alias: String, fingerprint: String, saved: List<SavedPeer>): Boolean =
        saved.any { it.alias.equals(alias.trim(), ignoreCase = true) && !it.fingerprint.equals(fingerprint, ignoreCase = true) }
}

/** The name other devices give themselves is theirs to choose, so it's cleaned before it's shown. */
object PeerNames {
    private const val MAX_LENGTH = 64

    /** [fallback] is what to call it when nothing is left of its own name. */
    fun clean(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty()
            .filterNot { c ->
                when (Character.getType(c).toByte()) {
                    // Format characters include the right-to-left override, which can dress one name up as another.
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
                    else -> false
                }
            }
            .trim()
        return cleaned.take(MAX_LENGTH).ifBlank { fallback }
    }
}
