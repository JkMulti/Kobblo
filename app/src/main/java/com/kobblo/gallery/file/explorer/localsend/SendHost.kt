package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.transfer.connect.NetworkInspector
import com.kobblo.gallery.file.explorer.transfer.core.NetworkFacts
import com.kobblo.gallery.file.explorer.transfer.send.TransferSources
import okhttp3.OkHttpClient
import java.io.InputStream

/**
 * Everything the sending side needs from outside itself: the phone, its files and its network, and who it is
 * to other devices. Nothing that speaks the protocol touches Android except through here, so a test can stand
 * in for all of it.
 */
internal interface SendHost {
    val log: ShareLog

    /** This phone, as it describes itself to a receiver. */
    fun self(): DeviceDto

    fun text(@StringRes id: Int, vararg args: Any): String

    /** Milliseconds on a clock that never jumps, for measuring speed and stalls. */
    fun uptimeMs(): Long

    /** Opens what's to be sent: a file path, or a `content://` address another app shared. */
    fun open(source: String): InputStream

    /** A signed statement, for the device with this fingerprint alone, of which device this is. */
    fun proofFor(receiverFingerprint: String): String?

    /** Holds an address to the fingerprint its device was found with, before anything is sent to it. */
    fun expect(hostAndPort: String, fingerprint: String)

    /** What can be said about this phone's network, for explaining why a device couldn't be reached. */
    fun network(): NetworkFacts

    /** Sets a client up to talk TLS to other devices, each held to its fingerprint. */
    fun secure(builder: OkHttpClient.Builder)
}

/**
 * The real phone.
 *
 * @param device which description of this phone goes out with an offer. It's swapped for the running
 *   server's whenever one starts, because only that one knows the port it ended up on.
 */
internal class PhoneSendHost(context: Context, @Volatile var device: OwnDevice) : SendHost {
    private val appContext = context.applicationContext

    override val log: ShareLog = PhoneLog("LocalSendClient")

    override fun self(): DeviceDto = device.describe()

    // Without arguments it must not go through a formatter, or a lone % in a translation would be an error.
    override fun text(id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)

    override fun uptimeMs(): Long = SystemClock.elapsedRealtime()

    override fun open(source: String): InputStream = TransferSources.open(appContext, source)

    override fun proofFor(receiverFingerprint: String): String? = LocalSendSecurity.proofFor(appContext, receiverFingerprint)

    override fun expect(hostAndPort: String, fingerprint: String) {
        LocalSendSecurity.pinDiscovered(hostAndPort, fingerprint)
    }

    override fun network(): NetworkFacts = NetworkInspector.facts(appContext)

    override fun secure(builder: OkHttpClient.Builder) = LocalSendSecurity.configureOkHttpClient(builder)
}
