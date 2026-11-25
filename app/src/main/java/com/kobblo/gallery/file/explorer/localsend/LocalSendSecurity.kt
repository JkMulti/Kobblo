package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import com.kobblo.gallery.file.explorer.transfer.core.NonceMemory
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocketFactory

/**
 * The one set of trust facts the whole app shares while it runs: which certificate this phone answers with,
 * and which certificate each address it talks to must answer with. The working parts are [DeviceIdentity] and
 * [FingerprintPins]. This is where they're held, so that discovery, sending, receiving and connecting by code
 * all see the same pins.
 */
object LocalSendSecurity {
    /** Added to a TLS response here, on this phone: the fingerprint of the certificate it really came with. */
    const val SEEN_FINGERPRINT_HEADER = "X-Kobblo-Seen-Fingerprint"

    private val pins = FingerprintPins()
    private val usedProofs = NonceMemory()

    @Volatile
    private var ownIdentity: DeviceIdentity? = null

    /** The fingerprint each `host:port` is held to. */
    val pinnedFingerprints: ConcurrentHashMap<String, String> get() = pins.expected

    /** Pins an address to the fingerprint read from a scanned code, which nothing heard on the network may change. */
    fun pinVerified(hostAndPort: String, fingerprint: String) = pins.pinFromCode(hostAndPort, fingerprint)

    fun isVerified(hostAndPort: String): Boolean = pins.isFromCode(hostAndPort)

    fun forgetPin(hostAndPort: String) = pins.forget(hostAndPort)

    /** Records what discovery claims about a host, unless a scanned code already settled it. */
    fun pinDiscovered(hostAndPort: String, fingerprint: String): Boolean = pins.pinFromNetwork(hostAndPort, fingerprint)

    fun fingerprintsMatch(a: String, b: String): Boolean = Fingerprints.same(a, b)

    /** The device at this address isn't the one its fingerprint says should be there. */
    class FingerprintMismatchException(host: String) : IOException("Certificate from $host does not match its fingerprint")

    /** Lets [builder]'s client talk TLS to other devices, each held to its pin. */
    fun configureOkHttpClient(builder: OkHttpClient.Builder) = pins.guard(builder)

    /** Lets an address announce a different certificate again, unless a scanned code said which it has. */
    internal fun releaseDiscoveredPin(hostAndPort: String) {
        if (!pins.isFromCode(hostAndPort)) pins.expected.remove(hostAndPort)
    }

    /** This phone's fingerprint. The first call may take a moment: that's when the key is read, or made. */
    internal fun ownFingerprint(context: Context): String = identity(context).fingerprint

    internal fun serverSocketFactory(context: Context): SSLServerSocketFactory = identity(context).serverSocketFactory()

    /** A statement for [receiverFingerprint] that this phone holds its certificate's key. See [SenderProof]. */
    internal fun proofFor(context: Context, receiverFingerprint: String): String? =
        identity(context).proofFor(receiverFingerprint, System.currentTimeMillis())

    /** Whether [header] proves that a sender holds the key behind [claimedFingerprint]. Each proof counts once. */
    internal fun isProven(context: Context, header: String?, claimedFingerprint: String): Boolean =
        SenderProof.verify(header, claimedFingerprint, ownFingerprint(context), System.currentTimeMillis(), usedProofs)

    private fun identity(context: Context): DeviceIdentity =
        ownIdentity ?: synchronized(this) {
            ownIdentity ?: DeviceIdentity.at(context.filesDir).also { ownIdentity = it }
        }
}
