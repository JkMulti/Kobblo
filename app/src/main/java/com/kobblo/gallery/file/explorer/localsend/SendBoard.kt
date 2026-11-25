package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.ProgressGate
import com.kobblo.gallery.file.explorer.transfer.core.SpeedMeter
import java.util.UUID

/** A file the receiver said yes to: where it sits in the session's list, and the token to send it with. */
internal class Upload(val index: Int, val item: TransferFileItem, val token: String)

/**
 * The picture of one try at sending, as the screen and the notification show it. Files can go several at a
 * time, each from a thread of its own, so every change to the picture is made here, one at a time, and each
 * one that should be seen is handed to [show].
 *
 * It keeps two things apart. What's settled is the session itself: which files are done, which failed, and
 * the bytes those account for. What's moving is how far the files in flight have got, which changes many times
 * a second and is only ever laid over the session for a moment, to show it.
 *
 * @param carried files that got through on an earlier try. They're in the list and the totals, and nothing
 *   happens to them.
 * @param items what this try is offering.
 */
internal class SendBoard(
    peer: NearbyPeer,
    carried: List<TransferFileItem>,
    private val items: List<TransferFileItem>,
    private val show: (TransferSession) -> Unit
) {
    private val firstIndex = carried.size
    private val meter = SpeedMeter()
    private val gate = ProgressGate()

    /** Bytes sent so far of each file in flight, by file id. */
    private val moving = HashMap<String, Long>()

    /** Bytes of files that are over with, one way or another. A file that failed counts, so the bar stays honest. */
    private var settledBytes = carried.sumOf { it.size }
    private var firstRefusal: String? = null

    private var session = TransferSession(
        // Until the receiver names the session, it needs some id of its own to be told apart by.
        sessionId = UUID.randomUUID().toString(),
        peer = peer,
        files = carried + items,
        isIncoming = false,
        status = TransferStatus.INITIALIZING,
        bytesTransferred = settledBytes,
        totalBytes = settledBytes + items.sumOf { it.size }
    )
    private var lastShown = session

    init {
        show(session)
    }

    val status: TransferStatus @Synchronized get() = session.status

    val fileCount: Int @Synchronized get() = session.files.size

    val sentCount: Int @Synchronized get() = session.finishedFiles

    /** Why it stopped short: what the receiver said, or failing that, what went wrong with the first file that failed. */
    val whyIncomplete: String
        @Synchronized get() = firstRefusal ?: session.files.firstNotNullOfOrNull { it.failure }.orEmpty()

    /** A retry isn't asking anyone anything, as far as the person can tell: it's still trying to get back. */
    @Synchronized
    fun awaitingAnswer(isRetry: Boolean) {
        change { it.copy(status = if (isRetry) TransferStatus.RECONNECTING else TransferStatus.WAITING_ACCEPT) }
    }

    /** The receiver took it, and named the session. */
    @Synchronized
    fun accepted(remoteSessionId: String) {
        change { it.copy(sessionId = remoteSessionId, status = TransferStatus.IN_PROGRESS) }
    }

    /**
     * Goes through what was offered against what the receiver answered, and returns what there is to upload.
     * A file it already has counts as sent. One it gave no token for wasn't accepted: a receiver may take
     * only some of what's offered.
     */
    @Synchronized
    fun sort(tokens: Map<String, String>, alreadyThere: Set<String>, notAccepted: String): List<Upload> {
        val uploads = ArrayList<Upload>(items.size)
        items.forEachIndexed { offset, item ->
            val index = firstIndex + offset
            val token = tokens[item.fileId]
            when {
                item.fileId in alreadyThere -> {
                    settledBytes += item.size
                    session = session
                        .withFile(index) { it.copy(isFinished = true, alreadyThere = true, bytesTransferred = item.size) }
                        .copy(bytesTransferred = settledBytes)
                }
                token == null -> {
                    settledBytes += item.size
                    session = session.withFile(index) { it.copy(failure = notAccepted) }
                }
                else -> uploads += Upload(index, item, token)
            }
        }
        publish(session)
        return uploads
    }

    @Synchronized
    fun starting(upload: Upload) {
        change { it.copy(currentFileIndex = upload.index, currentFileBytes = 0L) }
    }

    /**
     * [sent] bytes of a file have gone, out of [size]. Returns how much of the whole transfer that makes,
     * which is worked out every time. The screen is only told as often as it can use.
     */
    @Synchronized
    fun moved(fileId: String, sent: Long, size: Long, nowMs: Long): Long {
        moving[fileId] = sent
        val total = settledBytes + moving.values.sum()
        meter.update(nowMs, total)
        if (gate.allow(nowMs, force = sent == size)) {
            publish(session.copy(currentFileBytes = sent, bytesTransferred = total, speedBytesPerSec = meter.bytesPerSecond))
        }
        return total
    }

    @Synchronized
    fun sent(upload: Upload) {
        val size = settle(upload)
        change { current ->
            current
                .withFile(upload.index) { it.copy(isFinished = true, bytesTransferred = size) }
                .copy(bytesTransferred = settledBytes + moving.values.sum(), currentFileBytes = size)
        }
    }

    /** This phone couldn't read the file. The rest may still go, so it's passed over and the send carries on. */
    @Synchronized
    fun unreadable(upload: Upload, reason: String) {
        settle(upload)
        change { current ->
            current
                .withFile(upload.index) { it.copy(failure = reason) }
                .copy(bytesTransferred = settledBytes + moving.values.sum())
        }
    }

    /** The receiver wouldn't take the file. That ends the send, so there's nothing to show until it has. */
    @Synchronized
    fun refused(upload: Upload, reason: String) {
        moving.remove(upload.item.fileId)
        if (firstRefusal == null) firstRefusal = reason
        session = session.withFile(upload.index) { it.copy(failure = reason) }
    }

    /** The file stopped going without an answer either way, so what it had sent no longer counts. */
    @Synchronized
    fun dropped(upload: Upload) {
        moving.remove(upload.item.fileId)
    }

    /** Nothing has gone anywhere for a few seconds. A stuck bar shouldn't go on quoting the last speed it saw. */
    @Synchronized
    fun stalled() {
        publish(lastShown.copy(waitingOnNetwork = true, speedBytesPerSec = 0L))
    }

    /** There was nothing to upload: a note the receiver took whole, or files that all arrived on an earlier try. */
    @Synchronized
    fun nothingLeftToSend(): TransferSession {
        session = session.copy(
            files = session.files.map { if (it.isFinished) it else it.copy(isFinished = true, bytesTransferred = it.size) }
        )
        return completed()
    }

    @Synchronized
    fun completed(): TransferSession {
        session = session.copy(bytesTransferred = session.totalBytes)
        return finish(TransferStatus.COMPLETED)
    }

    @Synchronized
    fun finish(status: TransferStatus, message: String? = null, connectionLost: Boolean = false): TransferSession =
        change { it.copy(status = status, errorMessage = message, connectionLost = connectionLost) }

    private fun settle(upload: Upload): Long {
        moving.remove(upload.item.fileId)
        settledBytes += upload.item.size
        return upload.item.size
    }

    private fun change(transform: (TransferSession) -> TransferSession): TransferSession {
        session = transform(session)
        publish(session)
        return session
    }

    private fun publish(picture: TransferSession) {
        lastShown = picture
        show(picture)
    }

    private fun TransferSession.withFile(index: Int, transform: (TransferFileItem) -> TransferFileItem): TransferSession =
        copy(files = files.mapIndexed { at, file -> if (at == index) transform(file) else file })
}
