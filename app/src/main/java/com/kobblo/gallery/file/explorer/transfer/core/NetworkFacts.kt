package com.kobblo.gallery.file.explorer.transfer.core

/** What this phone can say about its network, enough to explain why another device can't be reached. */
data class NetworkFacts(
    /** Addresses another device could reach. Empty means no Wi-Fi, hotspot or cable. */
    val addresses: List<String>,
    val vpnOn: Boolean
)

/** The likeliest reason, most actionable first. */
enum class ReachProblem {
    NoNetwork,
    Vpn,
    DifferentNetwork,
    /** Same network and still no answer: isolation on guest Wi-Fi, or a firewall on the other device. */
    Blocked
}

object NetworkDiagnosis {
    fun whyUnreachable(facts: NetworkFacts, peerIp: String?): ReachProblem = when {
        facts.addresses.isEmpty() -> ReachProblem.NoNetwork
        facts.vpnOn -> ReachProblem.Vpn
        peerIp != null && facts.addresses.none { sameSubnet(it, peerIp) } -> ReachProblem.DifferentNetwork
        else -> ReachProblem.Blocked
    }

    /**
     * Compares the first three parts of the address. A home network is a /24 almost everywhere, and a
     * wider one only makes this cautious in a harmless way: the hint says to check the network.
     */
    fun sameSubnet(a: String, b: String): Boolean {
        val left = a.split('.')
        val right = b.split('.')
        return left.size == 4 && right.size == 4 && left.take(3) == right.take(3)
    }

    /** Private addresses only: a scan must never wander onto the internet through an odd interface. */
    fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            else -> false
        }
    }

    /** Everything else on this address's /24, nearest first so a small network answers soonest. */
    fun neighbours(address: String): List<String> {
        if (!isPrivate(address)) return emptyList()
        val parts = address.split('.')
        val own = parts[3].toInt()
        val prefix = parts.take(3).joinToString(".")
        return (1..254)
            .filter { it != own }
            .sortedBy { kotlin.math.abs(it - own) }
            .map { "$prefix.$it" }
    }
}
