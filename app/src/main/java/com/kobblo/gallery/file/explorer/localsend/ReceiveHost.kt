package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import android.media.MediaScannerConnection
import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.send.ReceiveFolder
import java.io.File

/**
 * What the receiving side tells the rest of the app, as it happens. Every call comes from the thread of the
 * connection it's about, and several can come at once.
 *
 * A session that was accepted ends with exactly one of [completed], [cancelled], [failed] or [superseded].
 */
internal interface IncomingEvents {
    /** Something is already on its way in or out, or a question is waiting, so a new offer has to be turned away. */
    fun isBusy(): Boolean

    /** A question is on the screen right now. */
    fun isAsking(): Boolean

    /** Puts [request] to the person holding the phone. The answer arrives on its `decisionChannel`. */
    fun ask(request: IncomingTransferRequest)

    /** Whoever asked hung up before anyone answered. */
    fun askerLeft(sessionId: String)

    /** Nobody answered, and the sender has waited long enough. */
    fun unanswered(sessionId: String)

    /** [request] was accepted without being put to anyone: its sender is trusted, or is carrying on. */
    fun acceptedUnasked(request: IncomingTransferRequest)

    fun noteArrived(sender: DeviceDto, senderIp: String, text: String)

    /** All [count] files a sender offered are here already, from an earlier try. */
    fun nothingNew(sender: DeviceDto, count: Int)

    fun refusedForSpace(request: IncomingTransferRequest, freeBytes: Long)

    /** [bytes] of one file have arrived so far. */
    fun progress(sessionId: String, fileId: String, bytes: Long)

    fun fileSaved(sessionId: String, fileId: String, saved: File)

    fun completed(sessionId: String, sender: DeviceDto, saved: List<File>)

    fun cancelled(sessionId: String)

    /** It stopped short. [saved] is what had arrived whole by then, which stays. */
    fun failed(sessionId: String, message: String, saved: List<File>)

    /** The same device started over, so this try of it is done with. */
    fun superseded(sessionId: String)
}

/**
 * Everything the receiving side needs from outside itself: the phone, its settings, what it knows of other
 * devices, and the app to report to. Nothing that speaks the protocol touches Android except through here,
 * so a test can stand in for all of it.
 */
internal interface ReceiveHost {
    val app: IncomingEvents

    /** This phone, as it describes itself to others. */
    fun self(): DeviceDto

    /** A device made itself known by calling here. */
    fun heard(peer: NearbyPeer)

    fun text(@StringRes id: Int, vararg args: Any): String

    /** The person wants to be asked before anything is saved. */
    fun asksFirst(): Boolean

    fun receiveFolder(): File

    /** Tells the media library these files exist, so they show up in the gallery. */
    fun scan(paths: Array<String>)

    /** Whether [proofHeader] shows its sender holds the key behind [claimedFingerprint]. */
    fun isProven(proofHeader: String?, claimedFingerprint: String): Boolean

    /** The device with this fingerprint, if this phone has dealt with it before. */
    fun known(fingerprint: String): SavedPeer?

    /** Something was accepted from a device that proved who it is. */
    fun rememberSender(sender: DeviceDto, ip: String)
}

/** The real phone. */
internal class PhoneReceiveHost(
    context: Context,
    private val device: OwnDevice,
    private val discovery: LocalSendDiscovery,
    override val app: IncomingEvents
) : ReceiveHost {
    private val appContext = context.applicationContext

    override fun self(): DeviceDto = device.describe()

    override fun heard(peer: NearbyPeer) = discovery.add(peer)

    // Without arguments it must not go through a formatter, or a lone % in a translation would be an error.
    override fun text(id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)

    override fun asksFirst(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASK_FIRST, true)

    override fun receiveFolder(): File = ReceiveFolder.resolve(appContext)

    override fun scan(paths: Array<String>) = MediaScannerConnection.scanFile(appContext, paths, null, null)

    override fun isProven(proofHeader: String?, claimedFingerprint: String): Boolean =
        LocalSendSecurity.isProven(appContext, proofHeader, claimedFingerprint)

    override fun known(fingerprint: String): SavedPeer? = PeerDirectory.known(fingerprint)

    override fun rememberSender(sender: DeviceDto, ip: String) = PeerDirectory.rememberSender(sender, ip)

    private companion object {
        const val PREFS = "kobblo_prefs"
        const val KEY_ASK_FIRST = "nearby_ask_before_receiving"
    }
}
