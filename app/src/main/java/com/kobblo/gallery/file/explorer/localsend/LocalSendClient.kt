package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.core.NetworkDiagnosis
import com.kobblo.gallery.file.explorer.transfer.core.ReachProblem
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import com.kobblo.gallery.file.explorer.transfer.core.RetryWindow
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import com.kobblo.gallery.file.explorer.transfer.core.WriteWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The sending half of LocalSend protocol version 2. It offers files to a device, uploads the ones that device
 * accepts, and tells it when the rest is called off. One send runs at a time, and [activeSession] is how it's
 * going.
 *
 * A receiver that is this same app, and has seen proof of which device this is, answers with a little more
 * than the protocol has: which files it already holds, how many it will take at once, and where a file that
 * was cut off can carry on from. Any other receiver gets plain LocalSend.
 */
internal class LocalSendClient(private val host: SendHost) {
    private val log: ShareLog get() = host.log

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // The answer to an offer waits on a person tapping Accept.
            .readTimeout(5, TimeUnit.MINUTES)
            // A last resort. When a network dies quietly, this timeout has been seen not to end a blocked write
            // for well over its length, because it closes the TLS socket, and that waits on the same write. The
            // stall watch cancels the call underneath instead, which does end it.
            .writeTimeout(90, TimeUnit.SECONDS)
            // Trying again is this class's decision. Done automatically, it would put a second question in front
            // of a receiver that's still showing the first, which then answers that it's busy.
            .retryOnConnectionFailure(false)
            .also(host::secure)
            .build()
    }

    /** After a file's last byte, the wait is for the receiver to get it onto storage, which can be slow. */
    private val uploadHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(2, TimeUnit.MINUTES).build() }

    private val _activeSession = MutableStateFlow<TransferSession?>(null)
    val activeSession: StateFlow<TransferSession?> = _activeSession.asStateFlow()

    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
    private val errands = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    /** The most bytes any try of the current send has got across. */
    private val furthest = AtomicLong()

    /**
     * Sends [items] to [peer] and returns how it ended. It doesn't throw: every way a send can end, partly
     * included, comes back as the session's status and message. [carried] are files that already got through
     * on an earlier try: they count, and nothing happens to them.
     *
     * A connection that drops partway is tried again for a while, unasked. Wi-Fi often comes back within
     * seconds, and what already arrived stays where it is, so going again costs little. How long is a matter
     * of time and not of tries, because with no network at all each try fails at once. It's time since the
     * send last got further than before, because a big one may already have been going far longer than that.
     */
    suspend fun send(
        peer: NearbyPeer,
        items: List<TransferFileItem>,
        carried: List<TransferFileItem> = emptyList()
    ): TransferSession = withContext(Dispatchers.IO) {
        cancelled = false
        furthest.set(0L)
        val patience = RetryWindow(RETRY_WINDOW_MS)
        var result = attempt(peer, items, carried, isRetry = false)
        while (result.droppedOut && !cancelled && patience.mayRetry(host.uptimeMs(), furthest.get())) {
            show(result.copy(status = TransferStatus.RECONNECTING, errorMessage = null))
            if (!pauseBeforeRetry()) {
                return@withContext result.copy(status = TransferStatus.CANCELLED, errorMessage = null).also(::show)
            }
            val (arrived, stillToGo) = result.files.partition { it.isFinished }
            result = attempt(peer, stillToGo.map { it.forAnotherTry() }, arrived, isRetry = true)
        }
        result
    }

    /** Stops the send that's running, whatever it's in the middle of. */
    fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
    }

    /** Takes the last send off show. */
    fun forget() {
        _activeSession.value = null
    }

    private fun show(session: TransferSession) {
        _activeSession.value = session
    }

    private val TransferSession.droppedOut: Boolean get() = status == TransferStatus.FAILED && connectionLost

    /** A breath for the network to come back in. False if the send was cancelled meanwhile. */
    private suspend fun pauseBeforeRetry(): Boolean {
        repeat(RETRY_PAUSE_STEPS) {
            delay(RETRY_PAUSE_STEP_MS)
            if (cancelled) return false
        }
        return true
    }

    private suspend fun attempt(
        peer: NearbyPeer,
        items: List<TransferFileItem>,
        carried: List<TransferFileItem>,
        isRetry: Boolean
    ): TransferSession {
        val board = SendBoard(peer, carried, items, ::show)
        var remoteSession: String? = null
        try {
            if (peer.isHeldToFingerprint) host.expect(peer.id, peer.fingerprint)
            board.awaitingAnswer(isRetry)
            val terms = when (val answer = offer(peer, items)) {
                Answer.NothingToSend -> return board.nothingLeftToSend()
                is Answer.Refused -> return board.finish(answer.status, answer.message)
                is Answer.Accepted -> answer
            }
            remoteSession = terms.sessionId
            board.accepted(terms.sessionId)

            val run = UploadRun(peer, terms, board)
            run.sendAll(board.sort(terms.tokens, terms.alreadyThere, host.text(R.string.transfer_file_not_accepted)))
            if (cancelled) throw CancellationException()
            run.brokenBy?.let { throw it }

            if (board.sentCount == board.fileCount) return board.completed()
            callOff(peer, terms.sessionId)
            val summary = host.text(R.string.transfer_partial_sent, board.sentCount, board.fileCount, board.whyIncomplete)
            return board.finish(TransferStatus.FAILED, summary.trim())
        } catch (e: CancellationException) {
            remoteSession?.let { callOff(peer, it) }
            return board.finish(TransferStatus.CANCELLED)
        } catch (e: Exception) {
            remoteSession?.let { callOff(peer, it) }
            if (cancelled) return board.finish(TransferStatus.CANCELLED)
            log.warn("Transfer failed at ${board.status}: ${e.javaClass.simpleName}")
            // A device that can't be reached at all, first time, isn't a dropped connection. One that goes
            // quiet mid-transfer, or while this is already trying to get back to it, is.
            val lost = e is IOException && e !is LocalSendSecurity.FingerprintMismatchException &&
                (board.status == TransferStatus.IN_PROGRESS || isRetry)
            return board.finish(TransferStatus.FAILED, describeFailure(e, peer, board, isRetry), lost)
        } finally {
            calls.clear()
        }
    }

    /** A fingerprint only holds a device to anything over TLS, where its certificate has to match it. */
    private val NearbyPeer.isHeldToFingerprint: Boolean get() = protocol == LocalSendWire.HTTPS && fingerprint.isNotBlank()

    // ---- the offer ----

    private sealed interface Answer {
        class Accepted(
            val sessionId: String,
            /** By file id. A file with no token wasn't accepted. */
            val tokens: Map<String, String>,
            val alreadyThere: Set<String>,
            val slots: Int,
            val resume: Map<String, Long>
        ) : Answer

        data object NothingToSend : Answer

        class Refused(val status: TransferStatus, val message: String) : Answer
    }

    private fun offer(peer: NearbyPeer, items: List<TransferFileItem>): Answer {
        val body = LocalSendWire.write(LocalSendWire.offer(host.self(), items))
            .toRequestBody(LocalSendWire.JSON_UTF8_TYPE.toMediaTypeOrNull())
        // A connection left over from earlier may have been dropped since, and this call can't be made twice.
        http.connectionPool.evictAll()
        val request = Request.Builder().url(peer.baseUrl + LocalSendWire.PREPARE_PATH).post(body)
        // Only worth sending over TLS, where it's sure to reach the device it names and nobody else.
        if (peer.isHeldToFingerprint) host.proofFor(peer.fingerprint)?.let { request.header(SenderProof.HEADER, it) }
        return execute(request.build()).use { read(it, peer) }
    }

    private fun read(response: Response, peer: NearbyPeer): Answer {
        fun failed(message: String) = Answer.Refused(TransferStatus.FAILED, message)
        return when (response.code) {
            200 -> {
                val answer = LocalSendWire.readAnswer(response.text()) ?: throw IOException("Empty response")
                Answer.Accepted(
                    sessionId = checkNotNull(answer.statedSessionId) { "An answer that names no session" },
                    tokens = checkNotNull(answer.statedFiles) { "An answer that lists no files" },
                    alreadyThere = answer.alreadyHave.orEmpty().toSet(),
                    slots = answer.uploadSlots ?: 1,
                    resume = answer.resume.orEmpty()
                )
            }
            // A note, which is taken whole from the offer, or files that all arrived on an earlier try.
            204 -> Answer.NothingToSend
            403 ->
                if (LocalSendWire.NOT_ANSWERED in response.text()) failed(host.text(R.string.transfer_not_answered_by, peer.alias))
                else Answer.Refused(TransferStatus.REJECTED, host.text(R.string.transfer_declined_by, peer.alias))
            401 -> failed(host.text(R.string.transfer_pin_required, peer.alias))
            409 -> failed(host.text(R.string.transfer_peer_busy, peer.alias))
            429 -> failed(host.text(R.string.transfer_too_many_requests, peer.alias))
            else -> failed(fullOrBroken(response, peer))
        }
    }

    /** For a status the protocol gives no meaning to: this app's receiver says in the body when it's out of space. */
    private fun fullOrBroken(response: Response, peer: NearbyPeer): String =
        if (LocalSendWire.STORAGE_FULL in response.text()) host.text(R.string.transfer_peer_full, peer.alias)
        else host.text(R.string.transfer_peer_error, peer.alias, response.code)

    private fun Response.text(): String = body?.string().orEmpty()

    // ---- the uploads ----

    /** How one upload ended, as far as the receiver had a say. */
    private sealed interface Reply {
        data object Taken : Reply

        /** What it had kept of this file doesn't match, so the whole file is wanted again. */
        data object StartOver : Reply

        class Refused(val why: String) : Reply
    }

    /** Everything one try needs while its files are going. */
    private inner class UploadRun(
        private val peer: NearbyPeer,
        private val terms: Answer.Accepted,
        private val board: SendBoard
    ) {
        private val slots = terms.slots.coerceIn(1, MAX_PARALLEL)
        private val writes: MutableSet<WriteWatch> = ConcurrentHashMap.newKeySet()
        private val halted = AtomicBoolean(false)
        private val firstBreak = AtomicReference<Exception?>(null)

        /** What cut the run short, if it wasn't the receiver saying no. */
        val brokenBy: Exception? get() = firstBreak.get()

        private val mayGoOn: Boolean get() = !halted.get() && !cancelled

        /**
         * Small files mostly wait on the far side's storage, a little for each one, so a few at once is much
         * faster and costs nothing. Only this app's receiver says it's happy with that. A big file gets the
         * link to itself, where going side by side would only make the storage work harder.
         */
        suspend fun sendAll(uploads: List<Upload>) = coroutineScope {
            val (small, large) = uploads.partition { slots > 1 && it.item.size <= SIDE_BY_SIDE_MAX_BYTES }
            val stallWatch = launch(Dispatchers.IO) { watchForStalls() }
            try {
                if (small.isNotEmpty()) {
                    val queue = ConcurrentLinkedQueue(small)
                    coroutineScope {
                        repeat(slots) {
                            launch(Dispatchers.IO) {
                                while (mayGoOn) sendOne(queue.poll() ?: break)
                            }
                        }
                    }
                }
                for (upload in large) {
                    if (!mayGoOn) break
                    sendOne(upload)
                }
            } finally {
                stallWatch.cancel()
            }
        }

        private fun sendOne(upload: Upload, startAt: Long = resumePoint(upload.item)) {
            val item = upload.item
            board.starting(upload)
            val watch = WriteWatch()
            val body = FileBody(host, item, startAt, watch) { sent ->
                val total = board.moved(item.fileId, sent, item.size, host.uptimeMs())
                furthest.updateAndGet { maxOf(it, total) }
            }
            val request = Request.Builder().url(uploadUrl(upload, startAt)).post(body).build()

            writes.add(watch)
            try {
                when (val reply = execute(request, uploadHttp).use { read(it, startAt) }) {
                    Reply.Taken -> board.sent(upload)
                    Reply.StartOver -> {
                        board.dropped(upload)
                        sendOne(upload, startAt = 0L)
                    }
                    is Reply.Refused -> {
                        board.refused(upload, reply.why)
                        halted.set(true)
                    }
                }
            } catch (e: UnreadableSource) {
                // Only the kind of failure: the message of a failed open names the file.
                log.warn("Couldn't read a file to send: ${e.cause?.javaClass?.simpleName}")
                board.unreadable(upload, host.text(R.string.transfer_file_unreadable))
            } catch (e: Exception) {
                board.dropped(upload)
                firstBreak.compareAndSet(null, e)
                halted.set(true)
            } finally {
                writes.remove(watch)
            }
        }

        private fun resumePoint(item: TransferFileItem): Long =
            terms.resume[item.fileId]?.takeIf { it in 1 until item.size } ?: 0L

        private fun uploadUrl(upload: Upload, startAt: Long): HttpUrl {
            val url = (peer.baseUrl + LocalSendWire.UPLOAD_PATH).toHttpUrl().newBuilder()
                .addQueryParameter(LocalSendWire.SESSION_PARAM, terms.sessionId)
                .addQueryParameter(LocalSendWire.FILE_PARAM, upload.item.fileId)
                .addQueryParameter(LocalSendWire.TOKEN_PARAM, upload.token)
            if (startAt > 0) {
                url.addQueryParameter(LocalSendWire.OFFSET_PARAM, startAt.toString())
                url.addQueryParameter(LocalSendWire.TAIL_PARAM, tailBefore(upload.item, startAt))
            }
            return url.build()
        }

        private fun read(response: Response, startAt: Long): Reply = when {
            response.isSuccessful -> Reply.Taken
            response.code == 412 && startAt > 0 -> Reply.StartOver
            else -> Reply.Refused(
                when (response.code) {
                    403 -> host.text(R.string.transfer_stopped_by, peer.alias)
                    409 -> host.text(R.string.transfer_peer_busy, peer.alias)
                    422 -> host.text(R.string.transfer_damaged)
                    else -> fullOrBroken(response, peer)
                }
            )
        }

        /**
         * When a network dies quietly nothing fails: a write just never returns. So this looks, every second,
         * at how long the slowest write has been stuck. A few seconds and the screen is told it's waiting.
         * Half a minute and the connection is gone, whatever it claims, and cutting it is the only way on.
         */
        private suspend fun watchForStalls() {
            var wasQuiet = false
            while (true) {
                delay(STALL_CHECK_MS)
                val now = host.uptimeMs()
                val stuckFor = writes.maxOfOrNull { it.blockedFor(now) } ?: 0L
                if (stuckFor >= STALLED_AFTER_MS) {
                    log.info("Nothing moved for ${stuckFor / 1000} s, so dropping the connection")
                    calls.forEach { it.cancel() }
                    return
                }
                val quiet = stuckFor >= QUIET_AFTER_MS
                if (quiet && !wasQuiet) board.stalled()
                wasQuiet = quiet
            }
        }
    }

    /**
     * A hash of the file just before [position], which the receiver compares with the same stretch of what it
     * kept. If it can't be worked out it's left empty, which matches nothing, so the receiver asks for the
     * whole file. That's always safe.
     */
    private fun tailBefore(item: TransferFileItem, position: Long): String = try {
        host.open(item.localPath.orEmpty()).use { ReceiveSink.tailHash(it, position) }.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun execute(request: Request, client: OkHttpClient = http): Response {
        if (cancelled) throw CancellationException()
        val call = client.newCall(request)
        calls.add(call)
        // The cancel may have come between looking and adding, when it would have missed this call.
        if (cancelled) call.cancel()
        return call.execute().also { calls.remove(call) }
    }

    /** Tells the receiver not to expect the rest. Nobody waits for it: it's a courtesy, and the send is over either way. */
    private fun callOff(peer: NearbyPeer, sessionId: String) {
        errands.launch {
            try {
                val url = (peer.baseUrl + LocalSendWire.CANCEL_PATH).toHttpUrl().newBuilder()
                    .addQueryParameter(LocalSendWire.SESSION_PARAM, sessionId)
                    .build()
                http.newCall(Request.Builder().url(url).post(NO_BODY).build()).execute().close()
            } catch (e: Exception) {
                log.debug("Cancel notification to peer failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- saying what went wrong ----

    private fun describeFailure(e: Exception, peer: NearbyPeer, board: SendBoard, isRetry: Boolean): String {
        if (e is LocalSendSecurity.FingerprintMismatchException) return host.text(R.string.transfer_identity_changed, peer.alias)
        val sent = board.sentCount
        return when {
            board.status == TransferStatus.WAITING_ACCEPT && !isRetry -> describeUnreachable(e, peer)
            sent > 0 -> host.text(R.string.transfer_connection_lost_partial, peer.alias, sent, board.fileCount)
            else -> host.text(R.string.transfer_connection_lost, peer.alias)
        }
    }

    /** Why the first contact failed, as nearly as this phone can tell, put as what would fix it. */
    private fun describeUnreachable(e: Exception, peer: NearbyPeer): String {
        val neverConnected = e is ConnectException || e is NoRouteToHostException ||
            (e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))
        // A device that took the request and then went quiet is there. It just never said yes or no.
        if (!neverConnected) return host.text(R.string.transfer_no_answer, peer.alias)
        // Refused means the device is there and answered, but nothing on it is listening any more.
        val turnedAway = generateSequence<Throwable>(e) { it.cause }.any { it.message.orEmpty().contains("refused", ignoreCase = true) }
        if (turnedAway) return host.text(R.string.transfer_problem_not_listening, peer.alias)
        return when (NetworkDiagnosis.whyUnreachable(host.network(), peer.ip)) {
            ReachProblem.NoNetwork -> host.text(R.string.transfer_problem_no_network, peer.alias)
            ReachProblem.Vpn -> host.text(R.string.transfer_problem_vpn, peer.alias)
            ReachProblem.DifferentNetwork -> host.text(R.string.transfer_problem_other_network, peer.alias, peer.ip)
            ReachProblem.Blocked ->
                if (peer.deviceType == "desktop") host.text(R.string.transfer_problem_firewall, peer.alias)
                else host.text(R.string.transfer_problem_blocked, peer.alias)
        }
    }

    private companion object {
        const val RETRY_WINDOW_MS = 45_000L
        const val RETRY_PAUSE_STEP_MS = 500L
        const val RETRY_PAUSE_STEPS = 6
        const val STALL_CHECK_MS = 1_000L
        const val QUIET_AFTER_MS = 4_000L
        const val STALLED_AFTER_MS = 30_000L

        /** However many a receiver offers to take at once, no more than this are sent. */
        const val MAX_PARALLEL = 6
        const val SIDE_BY_SIDE_MAX_BYTES = 16L * 1024 * 1024

        val NO_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}

/** This phone couldn't read the file, as opposed to the network failing. The next file may still go through. */
private class UnreadableSource(cause: Throwable) : IOException(cause.message, cause)

/**
 * One file, or the rest of one, as the body of an upload. The source is opened when the body is written and
 * not before, so nothing is held open while files wait their turn, and what [onSent] hears is always how far
 * through the whole file it has got.
 *
 * @param startAt how much of the file the receiver already has. That much is skipped.
 */
private class FileBody(
    private val host: SendHost,
    private val item: TransferFileItem,
    private val startAt: Long,
    private val watch: WriteWatch,
    private val onSent: (Long) -> Unit
) : RequestBody() {
    override fun contentType(): MediaType? = LocalSendWire.BINARY_TYPE.toMediaTypeOrNull()

    override fun contentLength(): Long = item.size - startAt

    override fun writeTo(sink: BufferedSink) {
        open().use { source ->
            readingSource { source.skipFully(startAt) }
            val chunk = ByteArray(CHUNK_BYTES)
            var sent = startAt
            while (sent < item.size) {
                val wanted = minOf(chunk.size.toLong(), item.size - sent).toInt()
                val read = readingSource { source.read(chunk, 0, wanted) }
                if (read < 0) throw UnreadableSource(IOException("File got shorter"))
                writing { sink.write(chunk, 0, read) }
                sent += read
                onSent(sent)
            }
            writing { sink.flush() }
        }
    }

    private fun open(): InputStream = try {
        host.open(item.localPath.orEmpty())
    } catch (e: Exception) {
        // Not only a missing file: leave to read something another app shared can run out, too.
        throw UnreadableSource(e)
    }

    /** Tells a failure to read the file apart from a failure to send it, which looks the same from outside. */
    private inline fun <T> readingSource(read: () -> T): T = try {
        read()
    } catch (e: IOException) {
        throw UnreadableSource(e)
    }

    /** Only writes to the network are timed. A file from a cloud provider may take as long as it likes to arrive. */
    private inline fun writing(write: () -> Unit) {
        watch.begin(host.uptimeMs())
        write()
        watch.end()
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                // Some streams won't skip. Reading a byte moves them along, and shows whether any are left.
                if (read() < 0) throw IOException("File got shorter")
                left--
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
