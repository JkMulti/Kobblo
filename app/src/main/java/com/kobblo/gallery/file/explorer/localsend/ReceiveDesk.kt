package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.core.IncomingRules
import com.kobblo.gallery.file.explorer.transfer.core.KeptPart
import com.kobblo.gallery.file.explorer.transfer.core.PeerNames
import com.kobblo.gallery.file.explorer.transfer.core.PromptCooldown
import com.kobblo.gallery.file.explorer.transfer.core.PromptRules
import com.kobblo.gallery.file.explorer.transfer.core.ReceivePaths
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveResult
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import com.kobblo.gallery.file.explorer.transfer.core.ResumeGrants
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import com.kobblo.gallery.file.explorer.transfer.send.Notes
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The receiving half of LocalSend protocol version 2: what this phone answers to each call another device can
 * make, and what it does about it.
 *
 * - `GET  /api/localsend/v2/info`: who this is.
 * - `POST /api/localsend/v2/register`: a device says who it is, and hears who this is.
 * - `POST /api/localsend/v2/prepare-upload`: a device offers files. The answer says which it may send, and
 *   can wait on the person holding the phone.
 * - `POST /api/localsend/v2/upload`: one accepted file arrives.
 * - `POST /api/localsend/v2/cancel`: the sender calls the rest off.
 *
 * Whoever calls is a stranger until shown otherwise, and everything in a call is theirs to choose. So the
 * limits are here, and so is the rule that only a sender that signed for its fingerprint is told anything
 * about what's on this phone, or let off being asked.
 */
internal class ReceiveDesk(
    private val host: ReceiveHost,
    private val log: ShareLog = ShareLog.Silent
) {
    private val app: IncomingEvents get() = host.app
    private val sessions = IncomingSessions()
    private val grants = ResumeGrants()
    private val refusedLately = PromptCooldown()
    private val notesLately = PromptCooldown(cooldownMs = NOTE_GAP_MS)
    private val scans = ScanBatch(scan = host::scan)

    /** Kept parts being written right now, by path. Nothing else may open, measure or remove one of these. */
    private val partsInUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun answer(call: IHTTPSession): Response {
        val path = call.uri
        log.debug("Incoming ${call.method.name} $path")
        return when (call.method) {
            Method.GET -> if (path == LocalSendWire.INFO_PATH) info() else notFound()
            Method.POST -> when {
                path == LocalSendWire.REGISTER_PATH -> register(call)
                path == LocalSendWire.PREPARE_PATH -> offer(call)
                path.startsWith(LocalSendWire.UPLOAD_PATH) -> upload(call)
                path.startsWith(LocalSendWire.CANCEL_PATH) -> senderCancels(call)
                else -> notFound()
            }
            else -> notFound()
        }
    }

    /** The person here changed their mind. What already arrived stays, and what's arriving is removed. */
    fun cancel(sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (!sessions.end(session)) return
        session.stop()
        // Stopping it here is a no to the rest, so the same files coming again is a new question.
        grants.forget(session.grantHolder)
        scans.flush()
        app.cancelled(sessionId)
    }

    /** Ends the sessions whose senders have stopped sending without saying so. */
    fun endStalled(nowMs: Long = System.currentTimeMillis()) {
        for (session in sessions.stalled(nowMs, SESSION_IDLE_MS)) {
            if (!sessions.end(session)) continue
            session.stop()
            scans.flush()
            app.failed(session.id, host.text(R.string.transfer_sender_stopped, session.sender.alias), session.savedFiles())
        }
    }

    /** Clears out hidden partial files a crash left in the receive folder, long ago enough to be nobody's. */
    fun clearLeftovers() {
        ReceiveSink.deleteStaleParts(host.receiveFolder())
    }

    /** The server is going away. Uploads still arriving are cut off, each removing its own partial file. */
    fun close() {
        sessions.abandonAll()
        scans.flush()
    }

    // ---- info and register ----

    private fun info(): Response = json(LocalSendWire.write(host.self()))

    private fun register(call: IHTTPSession): Response {
        // It's one small description of a device. Read without a limit, any size would be spooled to disk.
        if (call.declaredLength() > MAX_REGISTER_BYTES) return tooLarge()
        return try {
            val caller = LocalSendWire.readDevice(call.bodyText())
            val ip = call.remoteIpAddress ?: UNKNOWN_ADDRESS
            // A call from this phone's own address came through a tunnel from somewhere it can't reach back to.
            if (caller != null && !isLoopback(ip)) {
                val peer = LocalSendWire.peer(
                    device = caller,
                    ip = ip,
                    unknownName = host.text(R.string.share_unknown_device),
                    nowMs = System.currentTimeMillis(),
                    protocol = caller.statedProtocol?.ifBlank { LocalSendWire.HTTPS }
                ) ?: return invalidBody()
                host.heard(peer)
            }
            json(LocalSendWire.write(host.self().copy(announce = null)))
        } catch (e: Exception) {
            log.warn("Bad register body: ${e.javaClass.simpleName}")
            invalidBody()
        }
    }

    private fun isLoopback(ip: String): Boolean = ip.startsWith("127.") || ip == "::1"

    // ---- prepare-upload ----

    private fun offer(call: IHTTPSession): Response = try {
        // The body is held in memory, and only a preposterous list of files comes anywhere near this.
        if (call.declaredLength() > MAX_OFFER_BYTES) tooLarge() else consider(call)
    } catch (e: OutOfMemoryError) {
        // Memory, not storage. A stranger's enormous list mustn't take the whole app down with it.
        log.warn("prepare-upload was too big to hold")
        tooLarge()
    } catch (e: Exception) {
        log.warn("prepare-upload failed: ${e.javaClass.simpleName}")
        plain(Status.INTERNAL_ERROR, "Server error")
    }

    /** Who is offering, and how far what they say about themselves can be taken. */
    private class Caller(
        val device: DeviceDto,
        val ip: String,
        /** It signed for the fingerprint it gives. Without that, the fingerprint is only a claim. */
        val proven: Boolean,
        /** This phone has dealt with that device before, which only means anything once it's [proven]. */
        val recognised: Boolean,
        /** Nothing lets it off being put to the person: neither the setting nor a standing yes to this device. */
        val needsAnswer: Boolean
    ) {
        /**
         * Who a leave to carry on belongs to. A proven device is itself wherever it turns up. Anything else
         * is only "whoever said this, from that address".
         */
        val grantHolder: String = if (proven) device.fingerprint else "$ip/${device.fingerprint}"
    }

    private fun consider(call: IHTTPSession): Response {
        val body = call.bodyText()
        if (body.isBlank()) {
            log.warn("prepare-upload: empty body received")
            return plain(Status.BAD_REQUEST, "Empty body")
        }
        val offer = LocalSendWire.readOffer(body) ?: return plain(Status.BAD_REQUEST, "Invalid JSON")
        val files = offer.files
        if (files.isNullOrEmpty() || files.size > MAX_FILES) {
            log.warn("prepare-upload: no files in request")
            return plain(Status.BAD_REQUEST, "No files provided")
        }
        if (files.values.any { it.size < 0 }) return plain(Status.BAD_REQUEST, "Bad file size")

        val ip = call.remoteIpAddress ?: UNKNOWN_ADDRESS
        val now = System.currentTimeMillis()
        // Someone just told no doesn't get to put the question straight back on the screen.
        if (refusedLately.isCooling(ip, now)) return slowDown()

        val caller = identify(offer.info, ip, call.headers[PROOF_HEADER])

        // Nothing is saved for a note, and it can't clash with anything, so it needs a yes at most.
        files.values.singleOrNull()
            ?.takeIf { IncomingRules.isNote(1, it.fileType, it.preview) }
            ?.let { return takeNote(call, caller, it, now) }

        makeWayForRetry(caller)
        // One transfer at a time, as the protocol has it. That also keeps a second sender from putting its
        // question over the one the person is looking at.
        if (app.isBusy() || !sessions.isEmpty) {
            log.info("Refused as busy: busy=${app.isBusy()} sessions=${sessions.count}")
            return busy()
        }

        val root = host.receiveFolder()
        // Whatever an earlier try delivered stays as it is, and only the rest is asked for. Saying what's here
        // would tell a stranger what's on this phone, so only a device with a reason to know is told.
        val alreadyHere = files.filter { (_, file) ->
            val relativeName = relativeNameOf(file)
            (caller.recognised || grants.covers(caller.grantHolder, listOf(relativeName to file.size), now)) &&
                IncomingRules.alreadyHave(File(root, relativeName), file.size, modifiedOf(file))
        }
        val wanted = if (alreadyHere.isEmpty()) files else files - alreadyHere.keys
        if (wanted.isEmpty()) {
            app.nothingNew(caller.device, files.size)
            return plain(Status.NO_CONTENT, "")
        }

        val sessionId = UUID.randomUUID().toString()
        val tokens = wanted.keys.associateWith { UUID.randomUUID().toString() }
        val items = wanted.map { (id, file) ->
            TransferFileItem(
                fileId = id,
                token = tokens[id],
                fileName = relativeNameOf(file),
                size = file.size,
                fileType = file.fileType ?: LocalSendWire.BINARY_TYPE
            )
        }
        val request = IncomingTransferRequest(
            sessionId = sessionId,
            sender = caller.device,
            senderIp = caller.ip,
            files = items,
            totalBytes = items.sumOf { it.size },
            decisionChannel = CompletableDeferred(),
            proven = caller.proven,
            alreadyHere = alreadyHere.size
        )
        val free = root.usableSpace
        if (free < request.totalBytes) {
            app.refusedForSpace(request, free)
            return plain(Status.INTERNAL_ERROR, LocalSendWire.STORAGE_FULL)
        }

        // Carrying on needs less than a signature: the very files already agreed to, from the same place.
        val namesAndSizes = items.map { it.fileName to it.size }
        val carryingOn = grants.covers(caller.grantHolder, namesAndSizes, now)
        val asked = caller.needsAnswer && !carryingOn
        if (asked) {
            val answer = askAndWait(call, request)
            if (answer != PromptAnswer.ACCEPT) return refused(answer, caller.ip)
        } else {
            app.acceptedUnasked(request)
        }
        grants.remember(caller.grantHolder, namesAndSizes, System.currentTimeMillis())
        if (caller.proven) host.rememberSender(caller.device, caller.ip)

        val accepted = wanted.mapValues { (id, file) -> accept(file, tokens.getValue(id), caller, root) }
        sessions.add(IncomingSession(sessionId, caller.device, caller.ip, root, caller.grantHolder, accepted))

        val resume = accepted.mapNotNull { (id, file) -> file.resumeFrom?.let { id to it } }.toMap()
        val reply = PrepareUploadResponseDto(
            sessionId = sessionId,
            files = tokens,
            alreadyHave = alreadyHere.keys.toList().takeIf { caller.proven && it.isNotEmpty() },
            uploadSlots = PARALLEL_UPLOADS.takeIf { caller.proven },
            resume = resume.takeIf { it.isNotEmpty() }
        )
        return json(LocalSendWire.write(reply)).apply { if (asked) hangUpAfter() }
    }

    /**
     * Cleans what the sender says about itself, and works out what it's worth. The names it gives are its own
     * choice and end up on the screen. A description with a hole where the protocol wants a value is no
     * description at all, and counts as the server failing to make sense of it.
     */
    private fun identify(info: DeviceDto?, ip: String, proofHeader: String?): Caller {
        val unnamed = host.text(R.string.share_localsend_device)
        val claimed = info ?: DeviceDto(alias = unnamed, fingerprint = "")
        check(claimed.statedVersion != null && claimed.statedFingerprint != null && claimed.statedProtocol != null) {
            "A device description with nulls in it"
        }
        val device = claimed.copy(
            alias = PeerNames.clean(claimed.alias, unnamed),
            deviceModel = claimed.deviceModel?.let { PeerNames.clean(it, host.text(R.string.share_unknown_device)) }
        )
        val proven = host.isProven(proofHeader, device.fingerprint)
        val asksFirst = host.asksFirst()
        // A claimed fingerprint is only words, so both of these take a signature.
        val saved = if (proven) host.known(device.fingerprint) else null
        return Caller(
            device = device,
            ip = ip,
            proven = proven,
            recognised = saved != null,
            needsAnswer = asksFirst && saved?.autoAccept != true
        )
    }

    private fun takeNote(call: IHTTPSession, caller: Caller, note: FileDto, now: Long): Response {
        // A note can be let straight in, so this is what keeps a stranger from filling the screen with them.
        if (notesLately.isCooling(caller.ip, now)) return slowDown()
        notesLately.declined(caller.ip, now)

        val text = note.preview.orEmpty()
        if (caller.needsAnswer) {
            // Unlike files, a note needn't wait for a transfer to end. A question on the screen, it must.
            if (app.isAsking()) return busy()
            val answer = askAndWait(
                call,
                IncomingTransferRequest(
                    sessionId = UUID.randomUUID().toString(),
                    sender = caller.device,
                    senderIp = caller.ip,
                    files = listOf(TransferFileItem(fileId = note.id, fileName = Notes.FILE_NAME, size = text.length.toLong(), fileType = "text/plain")),
                    totalBytes = text.length.toLong(),
                    decisionChannel = CompletableDeferred(),
                    proven = caller.proven,
                    kind = PromptKind.NOTE
                )
            )
            if (answer != PromptAnswer.ACCEPT) return refused(answer, caller.ip)
        }
        app.noteArrived(caller.device, caller.ip, text)
        return plain(Status.NO_CONTENT, "").apply { if (caller.needsAnswer) hangUpAfter() }
    }

    /**
     * The same device offering again means what it was sending here has stopped, even if this end hasn't
     * noticed yet: a dropped connection can take a long while to show. Its new try shouldn't be told this
     * phone is busy with its old one.
     */
    private fun makeWayForRetry(caller: Caller) {
        val earlier = sessions.from(caller.ip, caller.device.fingerprint) ?: return
        if (!sessions.end(earlier)) return
        earlier.stopForReplacement(LET_GO_WAIT_MS)
        scans.flush()
        app.superseded(earlier.id)
    }

    /**
     * What's agreed about one file. A kept part is only for a sender that signed: only then is "the same
     * device" really known, so nobody else can add to what it left. Other apps couldn't carry on anyway.
     */
    private fun accept(file: FileDto, token: String, caller: Caller, root: File): AcceptedFile {
        val relativeName = relativeNameOf(file)
        val modified = modifiedOf(file)
        if (!caller.proven || modified == null) return AcceptedFile(file, token, relativeName)

        val keptName = ReceiveSink.keptPartName(caller.device.fingerprint, relativeName, file.size, modified)
        val part = File(File(root, relativeName).parentFile, keptName)
        // Still being written by an upload that hasn't let go, so this try starts that file from nothing.
        if (part.path in partsInUse) return AcceptedFile(file, token, relativeName)
        val have = part.length()
        return AcceptedFile(file, token, relativeName, keptPart = keptName, resumeFrom = have.takeIf { it in 1 until file.size })
    }

    private fun relativeNameOf(file: FileDto): String = ReceivePaths.sanitizeRelative(file.fileName).joinToString("/")

    private fun modifiedOf(file: FileDto): Long? = IncomingRules.parseModified(file.metadata?.modified)

    /**
     * Puts the question and waits for its answer. A person can take a while, and the asker may give up first,
     * which only shows as its connection closing. A sender has nothing more to say until it's answered, so
     * reading from the connection is how to find out, and why the answer then has to close it: that read
     * would otherwise swallow the start of whatever came next.
     */
    private fun askAndWait(call: IHTTPSession, request: IncomingTransferRequest): PromptAnswer {
        app.ask(request)
        val hangUp = HangUpWatch(call.inputStream) { app.askerLeft(request.sessionId) }
        try {
            val answer = runBlocking { withTimeoutOrNull(ANSWER_BACKSTOP_MS) { request.decisionChannel.await() } }
            if (answer != null) return answer
            app.unanswered(request.sessionId)
            return PromptAnswer.UNANSWERED
        } finally {
            hangUp.stop()
        }
    }

    /** Watches a connection nobody expects anything on, and says so, once, if the other end closes it. */
    private class HangUpWatch(connection: InputStream, onHangUp: () -> Unit) {
        private val watching = AtomicBoolean(true)

        init {
            thread(isDaemon = true, name = "prepare-watch") {
                while (watching.get()) {
                    try {
                        if (connection.read() < 0) break
                    } catch (e: SocketTimeoutException) {
                        // A quiet connection times out of a read long before anyone has decided. It means nothing.
                    } catch (e: IOException) {
                        break
                    }
                }
                if (watching.getAndSet(false)) onHangUp()
            }
        }

        fun stop() = watching.set(false)
    }

    /** What a sender hears when the answer wasn't yes. The protocol only has 403 for it, which it reads as rejected. */
    private fun refused(answer: PromptAnswer, ip: String): Response {
        // Nobody said no to a question that went unanswered, so asking it again straight away is fair.
        if (answer == PromptAnswer.REJECT) refusedLately.declined(ip, System.currentTimeMillis())
        val why = if (answer == PromptAnswer.UNANSWERED) LocalSendWire.NOT_ANSWERED else "Rejected by user"
        return plain(Status.FORBIDDEN, why).hangUpAfter()
    }

    // ---- upload ----

    private fun upload(call: IHTTPSession): Response {
        val sessionId = call.query(LocalSendWire.SESSION_PARAM)
        val fileId = call.query(LocalSendWire.FILE_PARAM)
        val token = call.query(LocalSendWire.TOKEN_PARAM)
        if (sessionId == null || fileId == null || token == null) return turnAway(Status.BAD_REQUEST, "Missing parameters")

        val session = sessions[sessionId] ?: return turnAway(Status.FORBIDDEN, "Invalid or expired session")
        if (session.isStopped) return turnAway(Status.FORBIDDEN, "Session cancelled")
        // Tokens are random, and the protocol also ties a session to the address that opened it.
        if (call.remoteIpAddress != session.senderIp) return turnAway(Status.FORBIDDEN, "Invalid IP address")
        val file = session.files[fileId]?.takeIf { it.isUnlockedBy(token) } ?: return turnAway(Status.FORBIDDEN, "Invalid token")

        // The server library doesn't undo chunking, so taking chunks as data would save their framing into the file.
        if ("chunked" in call.headers["transfer-encoding"].orEmpty().lowercase()) {
            return turnAway(Status.LENGTH_REQUIRED, "Length required")
        }
        val startAt = call.query(LocalSendWire.OFFSET_PARAM)?.toLongOrNull() ?: 0L
        if (startAt != 0L && startAt != file.resumeFrom) return turnAway(Status.BAD_REQUEST, "Not where this file stopped")
        val stillToCome = file.offered.size - startAt
        if ((call.headers["content-length"]?.toLongOrNull() ?: stillToCome) != stillToCome) {
            return turnAway(Status.BAD_REQUEST, "Length differs from what was accepted")
        }
        if (!session.claim(fileId)) return turnAway(Status.CONFLICT, "Already received")

        val keptPath = file.keptPart?.let { File(File(session.root, file.relativeName).parentFile, it).path }
        val ownsPart = keptPath != null && partsInUse.add(keptPath)
        try {
            session.touch()
            if (startAt > 0) {
                // The two copies must agree where they'd join, or carrying on would quietly make a broken file.
                val part = keptPath?.takeIf { ownsPart }?.let(::File)
                if (part == null || !joinsUp(part, startAt, call.query(LocalSendWire.TAIL_PARAM))) {
                    log.info("A kept part didn't match, so that file starts over")
                    part?.delete()
                    return turnAway(Status.PRECONDITION_FAILED, "Start this file over")
                }
                log.info("Carrying on a file from byte $startAt of ${file.offered.size}")
            }
            session.arrivingOn(fileId, call.inputStream)
            val result = ReceiveSink(session.root, file.offered.fileName).receive(
                input = call.inputStream,
                expectedBytes = file.offered.size,
                sha256 = file.offered.sha256,
                isCancelled = { session.isStopped && !session.isReplaced },
                keep = file.keptPart?.takeIf { ownsPart }?.let { KeptPart(it, startAt) },
                onProgress = { bytes ->
                    session.touch()
                    app.progress(session.id, fileId, bytes)
                }
            )
            return conclude(session, fileId, file, result)
        } finally {
            // In this order: a retry waits for the upload to be let go of, and then expects the part to be free.
            if (ownsPart && keptPath != null) partsInUse.remove(keptPath)
            session.release(fileId)
        }
    }

    private fun joinsUp(part: File, startAt: Long, sendersTail: String?): Boolean {
        if (part.length() != startAt) return false
        val tail = runCatching { part.inputStream().use { ReceiveSink.tailHash(it, startAt) } }.getOrNull()
        return tail == sendersTail
    }

    private fun conclude(session: IncomingSession, fileId: String, file: AcceptedFile, result: ReceiveResult): Response =
        when (result) {
            is ReceiveResult.Saved -> {
                modifiedOf(file.offered)?.let(result.file::setLastModified)
                session.markSaved(fileId, result.file)
                app.fileSaved(session.id, fileId, result.file)
                scans.add(result.file.absolutePath)
                if (session.isComplete && sessions.end(session)) {
                    scans.flush()
                    grants.forget(session.grantHolder)
                    app.completed(session.id, session.sender, session.savedFiles())
                }
                plain(Status.OK, "")
            }
            ReceiveResult.Cancelled -> turnAway(Status.FORBIDDEN, "Transfer cancelled")
            ReceiveResult.ChecksumMismatch -> {
                fail(session, host.text(R.string.transfer_damaged))
                turnAway(UNPROCESSABLE, "Checksum mismatch")
            }
            ReceiveResult.DiskFull -> {
                fail(session, host.text(R.string.transfer_disk_full))
                turnAway(Status.INTERNAL_ERROR, LocalSendWire.STORAGE_FULL)
            }
            is ReceiveResult.Truncated -> {
                fail(session, host.text(R.string.transfer_sender_stopped, session.sender.alias))
                turnAway(Status.BAD_REQUEST, "Upload was cut short")
            }
            is ReceiveResult.Failed -> {
                // Its reason can hold a name from the sender's list, which doesn't belong in a log.
                log.warn("Couldn't save an upload")
                fail(session, host.text(R.string.transfer_cant_save))
                turnAway(Status.INTERNAL_ERROR, "Upload failed")
            }
        }

    private fun fail(session: IncomingSession, message: String) {
        if (!sessions.end(session)) return
        session.stop()
        scans.flush()
        app.failed(session.id, message, session.savedFiles())
    }

    // ---- cancel ----

    private fun senderCancels(call: IHTTPSession): Response {
        val session = call.query(LocalSendWire.SESSION_PARAM)?.let { sessions[it] }
        // Only whoever opened a session may end it. Anyone else is told the same as if it had worked.
        if (session != null && call.remoteIpAddress == session.senderIp && sessions.end(session)) {
            session.stop()
            scans.flush()
            app.cancelled(session.id)
        }
        return plain(Status.OK, "")
    }

    // ---- replies ----

    private fun plain(status: Response.IStatus, text: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, text)

    private fun json(body: String): Response = NanoHTTPD.newFixedLengthResponse(Status.OK, LocalSendWire.JSON_TYPE, body)

    private fun notFound(): Response = plain(Status.NOT_FOUND, "Not found")

    private fun invalidBody(): Response = plain(Status.BAD_REQUEST, "Invalid body")

    private fun busy(): Response = plain(Status.CONFLICT, "Blocked by another session")

    private fun slowDown(): Response = plain(Status.TOO_MANY_REQUESTS, "Try again shortly")

    private fun tooLarge(): Response = turnAway(Status.PAYLOAD_TOO_LARGE, "Request too large")

    /** A refusal sent while the rest of the request may still be on its way, so the connection can't be used again. */
    private fun turnAway(status: Response.IStatus, text: String): Response = plain(status, text).hangUpAfter()

    private fun Response.hangUpAfter(): Response = apply { closeConnection(true) }

    // ---- reading a request ----

    private fun IHTTPSession.query(name: String): String? = parameters[name]?.firstOrNull()

    private fun IHTTPSession.declaredLength(): Long = headers["content-length"]?.toLongOrNull() ?: 0L

    /**
     * The body of a request as text, or nothing. The server library hands a raw body over in one of two
     * places depending on the content type it was sent with, and if it can make nothing of the request the
     * bytes are still there to be read off the connection.
     */
    private fun IHTTPSession.bodyText(): String = bodyAsParsed() ?: bodyLeftInQuery() ?: bodyOffConnection() ?: ""

    private fun IHTTPSession.bodyAsParsed(): String? = try {
        val parts = HashMap<String, String>()
        parseBody(parts)
        parts["postData"]?.takeUnless { it.isBlank() }
    } catch (e: Exception) {
        log.debug("The body couldn't be parsed: ${e.javaClass.simpleName}")
        null
    }

    private fun IHTTPSession.bodyLeftInQuery(): String? = try {
        queryParameterString?.takeUnless { it.isBlank() }
    } catch (e: Exception) {
        log.debug("No query to read a body from: ${e.javaClass.simpleName}")
        null
    }

    private fun IHTTPSession.bodyOffConnection(): String? = try {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) {
            null
        } else {
            val bytes = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = inputStream.read(bytes, filled, length - filled)
                if (read < 0) break
                filled += read
            }
            if (filled > 0) String(bytes, 0, filled, Charsets.UTF_8) else null
        }
    } catch (e: Exception) {
        log.debug("The body couldn't be read off the connection: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val UNKNOWN_ADDRESS = "unknown"

        /** Header names arrive in lower case. */
        val PROOF_HEADER = SenderProof.HEADER.lowercase()

        val UNPROCESSABLE = object : Response.IStatus {
            override fun getDescription() = "422 Unprocessable Entity"
            override fun getRequestStatus() = 422
        }

        // The person's own clock runs out first, and answers for them. This is so a request can never wait for ever.
        const val ANSWER_BACKSTOP_MS = PromptRules.ANSWER_WITHIN_MS + 5_000L

        /** A minute with nothing arriving means the sender is gone, whatever the socket says. */
        const val SESSION_IDLE_MS = 60_000L

        /** A list of about 60,000 files comes to this much. */
        const val MAX_OFFER_BYTES = 16L * 1024 * 1024
        const val MAX_FILES = 100_000
        const val MAX_REGISTER_BYTES = 64L * 1024

        /** How many small files a sender that proved itself is told it may send at once. */
        const val PARALLEL_UPLOADS = 4
        const val NOTE_GAP_MS = 2_000L
        const val LET_GO_WAIT_MS = 1_500L
    }
}
