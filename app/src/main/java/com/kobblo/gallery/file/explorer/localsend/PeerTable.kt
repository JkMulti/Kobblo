package com.kobblo.gallery.file.explorer.localsend

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The devices heard from on this network, one for each address, and the list of them that the Send screen
 * shows. Everything that comes in here was said by a stranger over the network, so this is also where it's
 * decided what such a claim is allowed to do.
 *
 * @param ownFingerprint this phone's, so that it never lists itself.
 * @param pin notes the fingerprint an address claims, and says no if a scanned code says that address is
 *   someone else. A claim that's refused isn't listed.
 * @param unpin lets go of an address's claim once nothing has been heard from it for a while.
 */
internal class PeerTable(
    private val ownFingerprint: () -> String,
    private val pin: (hostAndPort: String, fingerprint: String) -> Boolean,
    private val unpin: (hostAndPort: String) -> Unit
) {
    private val byAddress = ConcurrentHashMap<String, NearbyPeer>()
    private val _listed = MutableStateFlow<List<NearbyPeer>>(emptyList())

    /** By name, so the list doesn't shuffle as devices announce themselves in turn. */
    val listed: StateFlow<List<NearbyPeer>> = _listed.asStateFlow()

    /**
     * Lists [claimed], or brings what's listed at its address up to date. What it says ends up in a web
     * address, so its port has to be one, and its protocol is one of the two there are.
     */
    fun admit(claimed: NearbyPeer) {
        if (claimed.port !in VALID_PORTS) return
        val peer = claimed.copy(protocol = LocalSendWire.scheme(claimed.protocol))
        if (peer.fingerprint.equals(ownFingerprint(), ignoreCase = true)) return
        if (!pin(peer.id, peer.fingerprint)) return
        byAddress[peer.id] = peer
        publish()
    }

    /** Drops every device last heard from more than [silenceMs] before [nowMs]. They've left, or turned off. */
    fun dropSilent(nowMs: Long, silenceMs: Long) {
        var dropped = false
        for (peer in byAddress.values) {
            if (nowMs - peer.lastSeenMs <= silenceMs) continue
            // Only if it's still the entry that went quiet: it may have announced itself again just now.
            if (byAddress.remove(peer.id, peer)) {
                unpin(peer.id)
                dropped = true
            }
        }
        if (dropped) publish()
    }

    /** Empties the list. Pins stay: they're dropped by silence or by being forgotten, not by a restart. */
    fun clear() {
        byAddress.clear()
        _listed.value = emptyList()
    }

    private fun publish() {
        _listed.value = byAddress.values.sortedBy { it.alias }
    }

    private companion object {
        val VALID_PORTS = 1..65535
    }
}
