package com.kobblo.gallery.file.explorer.transfer.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Which of this device's addresses another device could reach. A phone has several, and showing the
 * mobile-data one in a QR code would make pairing fail for no visible reason.
 */
object LocalAddresses {
    data class Candidate(val interfaceName: String, val address: String)

    private val unreachable = listOf("rmnet", "ccmni", "pdp", "dummy", "tun", "ppp", "lo", "v4-", "clat", "ifb", "sit", "ip6")
    private val hotspot = listOf("ap", "swlan", "softap", "wlan1", "wifi_ap")

    /** Best first. With [preferHotspot], the address on the hotspot's own network comes ahead of Wi-Fi. */
    fun rank(candidates: List<Candidate>, preferHotspot: Boolean = false): List<String> =
        candidates
            .filter { candidate ->
                unreachable.none { candidate.interfaceName.startsWith(it) } && isUsable(candidate.address)
            }
            .sortedBy { score(it, preferHotspot) }
            .map { it.address }
            .distinct()

    fun isUsable(address: String): Boolean {
        if (!PairingCode.isValidIpv4(address)) return false
        return !address.startsWith("127.") && !address.startsWith("169.254.") && address != "0.0.0.0"
    }

    fun current(preferHotspot: Boolean = false): List<String> = rank(enumerate(), preferHotspot)

    /**
     * The interfaces announcements should go out on and be listened for on. Left to itself the system uses
     * the default one, which on a phone making a hotspot is its mobile data, where nobody nearby can hear.
     */
    fun multicastInterfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { iface ->
            runCatching { iface.isUp && !iface.isLoopback && iface.supportsMulticast() }.getOrDefault(false) &&
                unreachable.none { iface.name.startsWith(it) } &&
                iface.inetAddresses.toList().any { it is Inet4Address && isUsable(it.hostAddress.orEmpty()) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun enumerate(): List<Candidate> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address -> address.hostAddress?.let { Candidate(iface.name, it) } }
            }
    } catch (e: Exception) {
        emptyList()
    }

    private fun score(candidate: Candidate, preferHotspot: Boolean): Int {
        val name = candidate.interfaceName
        val isHotspot = hotspot.any { name.startsWith(it) }
        return when {
            preferHotspot && isHotspot -> 0
            name.startsWith("wlan") -> 1
            isHotspot -> 2
            name.startsWith("eth") -> 3
            else -> 4
        }
    }
}
