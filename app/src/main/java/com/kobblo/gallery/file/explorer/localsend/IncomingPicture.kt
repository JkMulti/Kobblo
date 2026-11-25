package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.ProgressGate
import com.kobblo.gallery.file.explorer.transfer.core.SpeedMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Whoever sent a request, as a device, for the transfer card and the history to name. */
internal fun IncomingTransferRequest.senderAsPeer(): NearbyPeer = NearbyPeer(
    ip = senderIp,
    port = sender.port,
    alias = sender.alias,
    deviceModel = sender.deviceModel,
    deviceType = sender.deviceType ?: LocalSendWire.MOBILE,
    protocol = sender.protocol,
    fingerprint = sender.fingerprint,
    lastSeenMs = System.currentTimeMillis()
)

/**
 * The picture of something arriving, as the transfer card and the notification show it. The server reports
 * each file's progress from the thread it's arriving on, several at once when the sender is this same app,
 * and this turns those reports into one session on [shown].
 *
 * [shown] is the app's one transfer card, which a send uses too. So everything here names the session it's
 * about, and leaves the card alone if that isn't the one on it.
 *
 * @param uptimeMs a clock that never jumps, for the speed.
 */
internal class IncomingPicture(
    private val shown: MutableStateFlow<TransferSession?>,
    private val uptimeMs: () -> Long
) {
    @Volatile
    private var tally: Tally? = null

    /** Puts [request] on the card as a transfer under way, from the moment it's accepted. */
    fun begin(request: IncomingTransferRequest) {
        tally = Tally(request.sessionId)
        shown.value = TransferSession(
            sessionId = request.sessionId,
            peer = request.senderAsPeer(),
            files = request.files,
            isIncoming = true,
            status = TransferStatus.IN_PROGRESS,
            totalBytes = request.totalBytes
        )
    }

    /** [bytes] of one file have arrived so far. The card only changes as often as a screen can use. */
    fun progress(sessionId: String, fileId: String, bytes: Long) {
        val counting = tally?.takeIf { it.sessionId == sessionId } ?: return
        val total = counting.arrived(fileId, bytes) ?: return
        shown.update { card ->
            if (card == null || card.sessionId != sessionId || card.status.isFinal) return@update card
            val index = card.files.indexOfFirst { it.fileId == fileId }
            card.copy(
                currentFileIndex = if (index >= 0) index else card.currentFileIndex,
                currentFileBytes = bytes,
                bytesTransferred = total,
                speedBytesPerSec = counting.bytesPerSecond
            )
        }
    }

    fun fileSaved(sessionId: String, fileId: String, saved: File) {
        shown.update { card ->
            if (card == null || card.sessionId != sessionId) return@update card
            card.copy(
                files = card.files.map { file ->
                    if (file.fileId == fileId) file.copy(isFinished = true, bytesTransferred = file.size, localPath = saved.absolutePath)
                    else file
                }
            )
        }
    }

    /**
     * Ends the session on the card and returns it as it ended, for the history. Null if it isn't on the card,
     * or had already ended, so nothing gets recorded twice. With [allArrived], the count is squared up to the
     * total, which the last gated tick may have stopped short of.
     */
    fun end(sessionId: String, status: TransferStatus, message: String?, allArrived: Boolean = false): TransferSession? {
        var ended: TransferSession? = null
        shown.update { card ->
            // This can run more than once if the card changes under it, and only the last run counts.
            ended = null
            if (card == null || card.sessionId != sessionId || card.status.isFinal) return@update card
            card.copy(
                status = status,
                errorMessage = message,
                bytesTransferred = if (allArrived) card.totalBytes else card.bytesTransferred
            ).also { ended = it }
        }
        tally = null
        return ended
    }

    /** Adds up what has arrived across files, which may come in at the same time. */
    private inner class Tally(val sessionId: String) {
        private val perFile = HashMap<String, Long>()
        private var total = 0L
        private val meter = SpeedMeter()
        private val gate = ProgressGate()

        val bytesPerSecond: Long @Synchronized get() = meter.bytesPerSecond

        /** The new total, or null when it's too soon after the last one to be worth showing. */
        @Synchronized
        fun arrived(fileId: String, bytes: Long): Long? {
            total += bytes - (perFile.put(fileId, bytes) ?: 0L)
            val now = uptimeMs()
            meter.update(now, total)
            return if (gate.allow(now)) total else null
        }
    }
}
