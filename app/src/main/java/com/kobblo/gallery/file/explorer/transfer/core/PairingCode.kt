package com.kobblo.gallery.file.explorer.transfer.core

import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.R
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The Wi-Fi network a code asks you to join first, for two phones with no network in common. Some phones
 * make a WPA3-only network, and a WPA2 password request can't join one.
 */
data class PairingWifi(val ssid: String, val password: String?, val wpa3: Boolean = false)

/**
 * What a pairing QR says: where a device is listening and the certificate fingerprint it will
 * present. Scanning one is safer than discovery, because the fingerprint arrives through the camera
 * and can't be forged by anyone else on the network.
 */
data class PairingCode(
    val hosts: List<String>,
    val port: Int,
    val protocol: String = "https",
    val fingerprint: String? = null,
    val alias: String? = null,
    val wifi: PairingWifi? = null
) {
    fun encode(): String = buildString {
        append(PREFIX).append("?v=").append(VERSION)
        append("&h=").append(hosts.joinToString(",") { it.urlEncoded() })
        append("&p=").append(port)
        append("&s=").append(protocol)
        fingerprint?.let { append("&f=").append(it) }
        alias?.let { append("&n=").append(it.urlEncoded()) }
        wifi?.let { network ->
            append("&w=").append(network.ssid.urlEncoded())
            network.password?.let { append("&k=").append(it.urlEncoded()) }
            if (network.wpa3) append("&t=3")
        }
    }

    companion object {
        const val PREFIX = "kobblo://pair"
        const val VERSION = 1
        private const val MAX_LENGTH = 2048
        private const val MAX_ALIAS = 64
        private const val FINGERPRINT_HEX = 64

        fun parse(text: String): PairingParse {
            val trimmed = text.trim()
            if (!trimmed.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return PairingParse.NotPairing
            if (trimmed.length > MAX_LENGTH) return PairingParse.Invalid(R.string.share_pair_too_long)
            val rest = trimmed.substring(PREFIX.length)
            if (rest.isNotEmpty() && !rest.startsWith("?")) return PairingParse.NotPairing

            val fields = HashMap<String, String>()
            rest.removePrefix("?").split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val key = pair.substringBefore('=')
                val value = if ('=' in pair) pair.substringAfter('=') else ""
                // The first value wins, so nothing added to the end can change what was shown.
                if (key !in fields) fields[key] = value
            }

            val version = fields["v"]?.toIntOrNull() ?: return PairingParse.Invalid(R.string.share_pair_missing_version)
            if (version > VERSION) return PairingParse.Invalid(R.string.share_pair_newer_version)
            if (version < 1) return PairingParse.Invalid(R.string.share_pair_bad_version)

            val hosts = fields["h"].orEmpty().split(',').filter { it.isNotEmpty() }.map { it.urlDecoded() ?: "" }
            if (hosts.isEmpty()) return PairingParse.Invalid(R.string.share_pair_missing_address)
            if (hosts.size > 8 || !hosts.all(::isValidHost)) return PairingParse.Invalid(R.string.share_pair_bad_address)

            val port = fields["p"]?.toIntOrNull() ?: return PairingParse.Invalid(R.string.share_pair_missing_port)
            if (port !in 1..65535) return PairingParse.Invalid(R.string.share_pair_bad_port)

            val protocol = fields["s"]?.lowercase() ?: "https"
            if (protocol != "https" && protocol != "http") return PairingParse.Invalid(R.string.share_pair_bad_protocol)

            val fingerprint = fields["f"]?.let(::normalizeFingerprint)
            if (protocol == "https") {
                if (fingerprint == null) return PairingParse.Invalid(R.string.share_pair_missing_fingerprint)
                if (!isValidFingerprint(fingerprint)) return PairingParse.Invalid(R.string.share_pair_bad_fingerprint)
            }

            val alias = fields["n"]?.urlDecoded()?.filterNot { it.isISOControl() }?.take(MAX_ALIAS)?.ifBlank { null }

            val wifi = fields["w"]?.let { rawSsid ->
                val ssid = rawSsid.urlDecoded() ?: return PairingParse.Invalid(R.string.share_pair_bad_network_name)
                if (ssid.isEmpty() || ssid.toByteArray(Charsets.UTF_8).size > 32) return PairingParse.Invalid(R.string.share_pair_bad_network_name)
                val password = fields["k"]?.let { it.urlDecoded() ?: return PairingParse.Invalid(R.string.share_pair_bad_network_password) }
                if (password != null && password.length !in 8..63) return PairingParse.Invalid(R.string.share_pair_bad_network_password)
                PairingWifi(ssid, password, wpa3 = fields["t"] == "3")
            }

            return PairingParse.Ok(
                PairingCode(
                    hosts = hosts,
                    port = port,
                    protocol = protocol,
                    fingerprint = if (protocol == "https") fingerprint else null,
                    alias = alias,
                    wifi = wifi
                )
            )
        }

        /** An address goes straight into a URL, so it must not be able to carry a path or a user. */
        fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (host.all { it.isDigit() || it == '.' }) return isValidIpv4(host)
            if (host.startsWith("-") || host.startsWith(".") || host.endsWith(".")) return false
            return host.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' || it == '.' }
        }

        fun isValidIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() in 0..255
            }
        }

        fun normalizeFingerprint(raw: String): String = raw.replace(":", "").replace(" ", "").uppercase()

        fun isValidFingerprint(normalized: String): Boolean =
            normalized.length == FINGERPRINT_HEX && normalized.all { it in '0'..'9' || it in 'A'..'F' }

        private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

        private fun String.urlDecoded(): String? = try {
            URLDecoder.decode(this.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

sealed interface PairingParse {
    data class Ok(val code: PairingCode) : PairingParse
    /** Readable, but some other kind of QR code altogether. */
    data object NotPairing : PairingParse
    /** Why it can't be used, for the screen to say. */
    data class Invalid(@StringRes val reason: Int) : PairingParse
}
