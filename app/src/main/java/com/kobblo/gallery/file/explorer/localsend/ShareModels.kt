package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.TransferMath
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.UUID

/*
 * What the app itself knows about devices and transfers. None of this travels: the bodies that do are in
 * LocalSendDto.kt. Screens, the history and Web Share all build and read these, so their fields stay as they are.
 */

/** A device that can be sent to, at the address it was last found at. */
data class NearbyPeer(
    val ip: String,
    val port: Int,
    val alias: String,
    val deviceModel: String?,
    val deviceType: String,
    val protocol: String,
    val fingerprint: String,
    val lastSeenMs: Long,
    /** Its fingerprint came through a scanned code, not over the network. */
    val verified: Boolean = false,
    /** Something has gone to it or come from it before. Only ever true over TLS, where that can be checked. */
    val known: Boolean = false,
    val lastUsedMs: Long = 0
) {
    /** Its address, which is also what a certificate is pinned to. */
    val id: String get() = "$ip:$port"
    val baseUrl: String get() = "$protocol://$ip:$port"
}

enum class TransferStatus {
    INITIALIZING,
    WAITING_ACCEPT,
    IN_PROGRESS,

    /** The connection dropped, and it's about to try again by itself. */
    RECONNECTING,
    COMPLETED,
    CANCELLED,
    REJECTED,
    FAILED;

    /** Nothing more will happen to a transfer in this state. */
    val isFinal: Boolean
        get() = when (this) {
            COMPLETED, CANCELLED, REJECTED, FAILED -> true
            INITIALIZING, WAITING_ACCEPT, IN_PROGRESS, RECONNECTING -> false
        }
}

/** One file of a transfer, in either direction, and how far it has got. */
data class TransferFileItem(
    val fileId: String,
    val token: String? = null,
    /** May hold folders: `Camera/trip/a.jpg`. */
    val fileName: String,
    val size: Long,
    val fileType: String,
    /** A file path or a `content://` address on this phone. */
    val localPath: String? = null,
    val bytesTransferred: Long = 0L,
    val isFinished: Boolean = false,
    val modifiedMs: Long = 0L,
    /** Why this one didn't make it, while others in the same transfer may have. */
    val failure: String? = null,
    /** A note's whole text, which a receiver shows instead of saving a file. */
    val preview: String? = null,
    /** It was already on the other device, from an earlier try, so it didn't need to travel. */
    val alreadyThere: Boolean = false
) {
    /** The same file, ready to be offered again under an id the other device hasn't seen. */
    fun forAnotherTry(): TransferFileItem = copy(
        fileId = UUID.randomUUID().toString(),
        token = null,
        bytesTransferred = 0,
        isFinished = false,
        failure = null
    )
}

/** A transfer as the screen and the notification show it. A new one is published for every change. */
data class TransferSession(
    val sessionId: String,
    val peer: NearbyPeer,
    val files: List<TransferFileItem>,
    val isIncoming: Boolean,
    val status: TransferStatus,
    val currentFileIndex: Int = 0,
    /** How far through the current file. Kept apart from [files] so a tick doesn't copy the whole list. */
    val currentFileBytes: Long = 0L,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    /** It stopped because the network went away, rather than because anyone said so. */
    val connectionLost: Boolean = false,
    /** Sending, but nothing has gone anywhere for a few seconds. */
    val waitingOnNetwork: Boolean = false
) {
    /** From 0 to 1, and 0 while the total isn't known. */
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    val finishedFiles: Int get() = files.count { it.isFinished }

    val etaSeconds: Long? get() = TransferMath.etaSeconds(totalBytes - bytesTransferred, speedBytesPerSec)
}

/** What someone is asking this phone to take. */
enum class PromptKind {
    FILES,
    NOTE,
    BROWSER_FILES,
    BROWSER_NOTE,

    /** A browser that has the link wants to use it. */
    BROWSER_OPEN;

    val isBrowser: Boolean get() = this == BROWSER_FILES || this == BROWSER_NOTE || this == BROWSER_OPEN
}

/** How a question ended. */
enum class PromptAnswer {
    ACCEPT,
    REJECT,

    /** Nobody answered in time, which the sender hears as a Reject. */
    UNANSWERED,

    /** Whoever asked went away, so there's no longer anything to answer. */
    WITHDRAWN
}

/** A question for the person holding the phone, and the place its answer goes. */
data class IncomingTransferRequest(
    val sessionId: String,
    val sender: DeviceDto,
    val senderIp: String,
    val files: List<TransferFileItem>,
    val totalBytes: Long,
    /** Completed once, by whichever of a tap, the clock or the asker hanging up comes first. */
    val decisionChannel: CompletableDeferred<PromptAnswer>,
    /** It signed for the fingerprint it claims. Without this, the claim is only words. */
    val proven: Boolean = false,
    /** How many offered files were left out because they're already here. */
    val alreadyHere: Int = 0,
    val kind: PromptKind = PromptKind.FILES
)

/** A note someone sent. Nothing was saved, and nothing opens unless the person taps it. */
data class ReceivedNote(
    val fromAlias: String,
    val text: String,
    val timeMs: Long
)

/** Something for the screen to say or do once, as opposed to state it goes on showing. */
sealed interface NearbyShareEvent {
    data class Success(val message: String) : NearbyShareEvent

    data class Error(val message: String) : NearbyShareEvent

    /** Files arrived. [expected] is how many were meant to, where that's known and may be more than did. */
    data class FilesReceived(val peerAlias: String, val files: List<File>, val expected: Int? = null) : NearbyShareEvent
}

/** How the send to one device ended, when sending to several. */
data class PeerSendResult(val peer: NearbyPeer, val status: TransferStatus, val message: String?)

/** What did and didn't get through on the last try, to one device, ready to go again. */
data class UnfinishedSend(
    val peer: NearbyPeer,
    val done: List<TransferFileItem> = emptyList(),
    val missing: List<TransferFileItem> = emptyList()
)

/** Progress through a list of devices, which are sent to one after another. */
data class SendQueueState(
    val peers: List<NearbyPeer>,
    val index: Int,
    val results: List<PeerSendResult> = emptyList(),
    val finished: Boolean = false
)
