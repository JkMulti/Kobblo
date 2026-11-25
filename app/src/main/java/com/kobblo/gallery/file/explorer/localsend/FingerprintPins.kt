package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A device is its certificate, and a certificate is known by its fingerprint: the SHA-256 of its encoded bytes,
 * written as 64 capital hex digits. That is what LocalSend devices announce and what a pairing code carries.
 */
internal object Fingerprints {
    fun of(certificateDer: ByteArray): String = SenderProof.fingerprintOf(certificateDer)

    /**
     * Whether [seen] is the fingerprint [expected]. Some devices write theirs with colons and in lower case, so
     * neither counts. Having nothing to expect is never a match.
     */
    fun same(expected: String, seen: String): Boolean =
        expected.isNotEmpty() && withoutColons(expected).equals(withoutColons(seen), ignoreCase = true)

    private fun withoutColons(fingerprint: String) = fingerprint.replace(":", "")
}

/**
 * Which certificate each address is expected to answer with. Every device signs its own, so there's no authority
 * to ask: a connection is only as good as the fingerprint it's held to.
 *
 * A pin comes from one of two places. One heard on the network is a claim, which anyone on that network can
 * make, so a later claim simply replaces it. One read from a scanned code was handed over out of reach of the
 * network, and nothing heard afterwards may change it.
 */
internal class FingerprintPins {
    /** The fingerprint each `host:port` must present. */
    val expected = ConcurrentHashMap<String, String>()

    private val settledByCode: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun pinFromCode(hostAndPort: String, fingerprint: String) {
        settledByCode.add(hostAndPort)
        expected[hostAndPort] = fingerprint
    }

    fun isFromCode(hostAndPort: String): Boolean = hostAndPort in settledByCode

    fun forget(hostAndPort: String) {
        settledByCode.remove(hostAndPort)
        expected.remove(hostAndPort)
    }

    /**
     * Notes what the network says about an address. False when a scanned code already settled that address
     * and says otherwise, in which case nothing changes and the claim shouldn't be believed.
     */
    fun pinFromNetwork(hostAndPort: String, fingerprint: String): Boolean {
        if (isFromCode(hostAndPort)) return Fingerprints.same(expected[hostAndPort].orEmpty(), fingerprint)
        expected[hostAndPort] = fingerprint
        return true
    }

    /**
     * Sets [builder]'s client up to talk TLS to devices that sign their own certificates, holding each to its
     * pin. The handshake itself can't judge a self-signed certificate, and doesn't know which address it's for,
     * so it lets any through and the check is made straight after it, where the address is known.
     */
    fun guard(builder: OkHttpClient.Builder) {
        val anyCertificate = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) throw CertificateException("No certificate was presented")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(anyCertificate), SecureRandom()) }
        builder.sslSocketFactory(tls.socketFactory, anyCertificate)
        // These certificates name no host: a device doesn't know what address it will be given.
        builder.hostnameVerifier { _, _ -> true }
        builder.addNetworkInterceptor(afterHandshake)
    }

    /**
     * A network interceptor runs once the connection is up and before any of the request is written, so a
     * device with the wrong certificate never sees so much as a file name. The fingerprint it did present is
     * added to the response, for callers that want to learn or double-check who answered.
     */
    private val afterHandshake = Interceptor { chain ->
        val request = chain.request()
        if (!request.isHttps) return@Interceptor chain.proceed(request)

        val hostAndPort = "${request.url.host}:${request.url.port}"
        val presented = presentedOn(chain) ?: throw LocalSendSecurity.FingerprintMismatchException(hostAndPort)
        val pinned = expected[hostAndPort]
        if (pinned != null && !Fingerprints.same(pinned, presented)) {
            throw LocalSendSecurity.FingerprintMismatchException(hostAndPort)
        }
        chain.proceed(request).withSeenFingerprint(presented)
    }

    /**
     * The fingerprint of the certificate this connection's peer really used. It's read from the TLS session
     * itself: the handshake record OkHttp keeps has been through a check against trusted roots, which leaves
     * nothing of a self-signed chain.
     */
    private fun presentedOn(chain: Interceptor.Chain): String? {
        val socket = chain.connection()?.socket() as? SSLSocket ?: return null
        val certificate = runCatching { socket.session.peerCertificates.firstOrNull() }.getOrNull() ?: return null
        return Fingerprints.of(certificate.encoded)
    }

    private fun Response.withSeenFingerprint(fingerprint: String): Response =
        newBuilder().header(LocalSendSecurity.SEEN_FINGERPRINT_HEADER, fingerprint).build()
}
