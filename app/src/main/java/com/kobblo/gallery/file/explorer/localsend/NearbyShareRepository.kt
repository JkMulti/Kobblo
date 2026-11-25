package com.kobblo.gallery.file.explorer.localsend

import android.content.Context
import android.os.SystemClock
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.PeerMerge
import com.kobblo.gallery.file.explorer.transfer.core.TransferDirection
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecord
import com.kobblo.gallery.file.explorer.transfer.core.TransferResult
import com.kobblo.gallery.file.explorer.transfer.send.NoteNotification
import com.kobblo.gallery.file.explorer.transfer.send.Notes
import com.kobblo.gallery.file.explorer.transfer.send.SendPlanner
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.send.TransferHistory
import com.kobblo.gallery.file.explorer.transfer.send.TransferNotices
import com.kobblo.gallery.file.explorer.transfer.web.WebShareController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * What the rest of the app sees of sharing with nearby devices, and the one place it asks for anything: who's
 * around, what's chosen to send, the transfer that's under way, the question waiting for an answer, and whether
 * this phone can be seen at all.
 *
 * It lasts as long as the process does. The service that does the listening comes and goes underneath it, and
 * hands over its discovery and its server while it's up. A send needs neither.
 */
object NearbyShareRepository {
    private const val PREFS = "kobblo_prefs"
    private const val KEY_RECEIVE_OFF = "nearby_receive_off"
    private const val MAX_NOTE_CHARS = 200_000

    /** Moving between two of the system's screens leaves the app for a moment. That shouldn't ring the phone. */
    private const val LEFT_APP_SETTLE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null

    private val _events = MutableSharedFlow<NearbyShareEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NearbyShareEvent> = _events.asSharedFlow()

    private val _appShowing = MutableStateFlow(false)

    /** Hooks the pieces that need a Context up to one. Safe to call from anywhere, any number of times. */
    fun attach(context: Context) {
        val firstTime = appContext == null
        appContext = context.applicationContext
        TransferHistory.attach(context)
        PeerDirectory.attach(context)
        if (!firstTime) return
        _receiveOffByChoice.value = prefs(context).getBoolean(KEY_RECEIVE_OFF, false)
        TransferNotices.ensureChannels(context.applicationContext)
        keepQuestionNoticeInStep()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- being visible ----

    private val _isReceiving = MutableStateFlow(false)

    /** This phone is announcing itself and its server is listening. */
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private val _receiveOffByChoice = MutableStateFlow(false)

    /** The person turned Receive off, so nothing turns it back on behind their back. */
    val receiveOffByChoice: StateFlow<Boolean> = _receiveOffByChoice.asStateFlow()

    /** Getting visible, for opening the Send tab, unless the person has said not to be. */
    fun startService(context: Context) {
        attach(context)
        if (_receiveOffByChoice.value) return
        NearbyShareService.start(context)
    }

    /** False if the system wouldn't allow it from here, in which case nothing has changed. */
    fun toggleReceiving(context: Context, enabled: Boolean): Boolean {
        attach(context)
        if (enabled && !NearbyShareService.start(context)) return false
        _receiveOffByChoice.value = !enabled
        prefs(context).edit().putBoolean(KEY_RECEIVE_OFF, !enabled).apply()
        if (!enabled) NearbyShareService.stop(context)
        return true
    }

    /** For anything that can't work without the server listening, which asking for it overrides. */
    fun turnOnReceiving(context: Context) {
        toggleReceiving(context, true)
    }

    /** The service says whether it's listening. When it stops, a sender's question has nobody left to answer to. */
    internal fun setReceiving(receiving: Boolean) {
        _isReceiving.value = receiving
        // A browser's question has nothing to do with Receive: Web Share is its own thing to turn off.
        if (!receiving) prompts.onScreen.value?.takeUnless { it.kind.isBrowser }?.let { withdrawPrompt(it.sessionId) }
    }

    // ---- who's around ----

    private var discovery: LocalSendDiscovery? = null
    private var server: LocalSendServer? = null
    private var listeningDevice: OwnDevice? = null
    private var discoveredWatch: Job? = null
    private val _discovered = MutableStateFlow<List<NearbyPeer>>(emptyList())

    /** Everyone reachable: found by discovery, or connected to directly by a code or an address. */
    val peers: StateFlow<List<NearbyPeer>> =
        combine(_discovered, PeerDirectory.online, PeerDirectory.saved) { discovered, online, saved ->
            PeerMerge.merge(discovered, online, saved)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The service has a discovery going. From now on this phone describes itself the way that one does. */
    @Synchronized
    internal fun discoveryStarted(started: LocalSendDiscovery, device: OwnDevice) {
        discovery = started
        listeningDevice = device
        sendHost?.device = device
        discoveredWatch?.cancel()
        discoveredWatch = scope.launch { started.peers.collect { _discovered.value = it } }
    }

    @Synchronized
    internal fun serverStarted(started: LocalSendServer) {
        server = started
    }

    /** [stopped] is going away. Nothing happens if a newer one has already taken its place. */
    @Synchronized
    internal fun discoveryStopped(stopped: LocalSendDiscovery) {
        if (discovery !== stopped) return
        discovery = null
        listeningDevice = null
        discoveredWatch?.cancel()
        _discovered.value = emptyList()
    }

    @Synchronized
    internal fun serverStopped(stopped: LocalSendServer) {
        if (server === stopped) server = null
    }

    /** What this device says about itself, for anything that isn't the transfer itself. */
    fun localDevice(context: Context): DeviceDto = ownDevice(context).describe()

    private fun ownDevice(context: Context): OwnDevice = listeningDevice ?: OwnDevice(context)

    fun broadcastAnnouncement() {
        discovery?.announce(wantsReply = true)
    }

    /** A device found by asking around rather than by hearing it announce itself. */
    fun addFound(peer: NearbyPeer) {
        discovery?.add(peer)
    }

    // ---- what's chosen to send ----

    private val _stagedFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val stagedFiles: StateFlow<List<FileItem>> = _stagedFiles.asStateFlow()

    /** Adds to what's chosen. Something already there, by its path, isn't added twice. */
    fun stageFiles(files: List<FileItem>) {
        forgetUnfinished()
        val staged = _stagedFiles.value
        val paths = staged.mapTo(HashSet()) { it.path }
        _stagedFiles.value = staged + files.filter { paths.add(it.path) }
    }

    fun removeStagedFile(file: FileItem) {
        forgetUnfinished()
        _stagedFiles.value = _stagedFiles.value.filterNot { it.path == file.path }
    }

    fun clearStagedFiles() {
        _stagedFiles.value = emptyList()
        forgetUnfinished()
        appContext?.let(Notes::clear)
    }

    fun setStagedFiles(files: List<FileItem>) {
        if (files != _stagedFiles.value) forgetUnfinished()
        _stagedFiles.value = files
    }

    /** Adds a note to what's chosen, to go along like any other item. */
    fun stageNote(context: Context, text: String) {
        val note = Notes.write(context, text) ?: return
        stageFiles(listOf(note))
    }

    // ---- sending ----

    private val _activeTransfer = MutableStateFlow<TransferSession?>(null)

    /** The transfer the card shows, in either direction. It stays after it ends, until it's dismissed. */
    val activeTransfer: StateFlow<TransferSession?> = _activeTransfer.asStateFlow()

    private val _sendQueue = MutableStateFlow<SendQueueState?>(null)
    val sendQueue: StateFlow<SendQueueState?> = _sendQueue.asStateFlow()

    private val _unfinished = MutableStateFlow<List<UnfinishedSend>>(emptyList())

    /** What the last send left behind, if anything. Empty once it's been retried or dismissed. */
    val unfinished: StateFlow<List<UnfinishedSend>> = _unfinished.asStateFlow()

    private var sender: LocalSendClient? = null
    private var sendHost: PhoneSendHost? = null
    private var sendJob: Job? = null

    @Volatile
    private var sendingCalledOff = false

    /** True while something is on its way in or out, which keeps the service alive. */
    fun isTransferActive(): Boolean = sendJob?.isActive == true || _activeTransfer.value?.status?.isFinal == false

    fun isBusy(): Boolean = isTransferActive() || isAsking()

    fun sendStagedFiles(context: Context, peer: NearbyPeer) = sendStagedFiles(context, listOf(peer))

    /** Sends what's staged to each of [peers] in turn. One failing doesn't stop the others. */
    fun sendStagedFiles(context: Context, peers: List<NearbyPeer>) {
        val staged = _stagedFiles.value
        if (staged.isEmpty()) {
            emitError(context.getString(R.string.transfer_nothing_chosen))
            return
        }
        if (isBusy()) {
            emitError(context.getString(R.string.transfer_already_running))
            return
        }
        // Tapping the same device again after a failure means carry on, not start over.
        val carryOn = peers.singleOrNull()?.let(::unfinishedFor)
        if (carryOn != null) {
            startSending(context, listOf(carryOn))
        } else {
            startSending(context, peers.map { UnfinishedSend(it) }, chosen = staged)
        }
    }

    /** Sends only what didn't get through last time, to whichever devices it didn't get through to. */
    fun retryUnfinished(context: Context) {
        if (isBusy()) {
            emitError(context.getString(R.string.transfer_already_running))
            return
        }
        val pending = _unfinished.value
        if (pending.isEmpty()) return
        // A device's address may have changed since, in which case it's already listed under its new one.
        val around = peers.value
        startSending(context, pending.map { left -> left.copy(peer = around.firstOrNull { it.isSameDeviceAs(left.peer) } ?: left.peer) })
    }

    private fun NearbyPeer.isSameDeviceAs(other: NearbyPeer): Boolean =
        fingerprint.isNotBlank() && fingerprint.equals(other.fingerprint, ignoreCase = true)

    /** What's left over for [peer], whether it's found at the same address as before or by its fingerprint. */
    private fun unfinishedFor(peer: NearbyPeer): UnfinishedSend? =
        _unfinished.value.firstOrNull { it.peer.id == peer.id || peer.isSameDeviceAs(it.peer) }?.copy(peer = peer)

    private fun forgetUnfinished() {
        _unfinished.value = emptyList()
    }

    /**
     * @param chosen what's staged, to be turned into the exact files to send. Without it, each target is
     *   sent what it's still missing.
     */
    private fun startSending(context: Context, targets: List<UnfinishedSend>, chosen: List<FileItem>? = null) {
        attach(context)
        // Keeps the process in the foreground, so leaving the app doesn't end the send. Whether this phone
        // can be seen stays as the person left it.
        NearbyShareService.hold(context)
        sendingCalledOff = false
        forgetUnfinished()
        sendJob = scope.launch(Dispatchers.IO) { sendToEach(context, targets, chosen) }
    }

    private suspend fun sendToEach(context: Context, targets: List<UnfinishedSend>, chosen: List<FileItem>?) {
        val plan = chosen?.let { SendPlanner.plan(context, it) }
        if (plan != null && plan.items.isEmpty()) {
            emitError(context.getString(R.string.transfer_files_not_found))
            return
        }
        if (plan != null && plan.skipped.isNotEmpty()) {
            emitError(context.resources.getQuantityString(R.plurals.transfer_skipped_count, plan.skipped.size, plan.skipped.size))
        }

        val client = senderFor(context)
        val everyone = targets.map { it.peer }
        val results = ArrayList<PeerSendResult>()
        val leftOver = ArrayList<UnfinishedSend>()
        for ((position, target) in targets.withIndex()) {
            if (sendingCalledOff) break
            _sendQueue.value = SendQueueState(everyone, position, results.toList())
            val session = client.send(target.peer, plan?.items ?: target.missing.map { it.forAnotherTry() }, carried = target.done)
            TransferHistory.record(session)
            tellResult(context, session)
            results += PeerSendResult(target.peer, session.status, session.errorMessage)
            if (session.finishedFiles > 0) PeerDirectory.rememberSentTo(target.peer)
            // A refusal or a cancel was somebody's decision, and going again isn't the answer to that.
            if (session.status == TransferStatus.FAILED) {
                val (arrived, missing) = session.files.partition { it.isFinished }
                if (missing.isNotEmpty()) leftOver += UnfinishedSend(target.peer, arrived, missing)
            }
        }
        _sendQueue.value = SendQueueState(everyone, everyone.size, results.toList(), finished = true)
        _unfinished.value = leftOver

        val reachedEveryone = results.size == everyone.size && results.all { it.status == TransferStatus.COMPLETED }
        if (reachedEveryone && plan?.skipped.isNullOrEmpty()) {
            _stagedFiles.value = emptyList()
            Notes.clear(context)
        }
        // What's been sent may still be on offer in a browser, and a send that failed may yet go again.
        if (leftOver.isEmpty() && !WebShareController.isRunning()) SendPlanner.clearCache(context)
    }

    /** The one sender, made on first use. What it sends shows on the card as it goes. */
    private fun senderFor(context: Context): LocalSendClient = sender ?: synchronized(this) {
        sender ?: run {
            val host = PhoneSendHost(context, ownDevice(context))
            LocalSendClient(host).also { made ->
                sendHost = host
                sender = made
                scope.launch { made.activeSession.collect { session -> if (session != null) _activeTransfer.value = session } }
            }
        }
    }

    /** Stops whatever is on the card. For a send to several devices, that's the rest of them too. */
    fun cancelActiveTransfer() {
        val onCard = _activeTransfer.value
        when (onCard?.isIncoming) {
            true -> server?.cancel(onCard.sessionId)
            false -> {
                sendingCalledOff = true
                sender?.cancel()
            }
            null -> Unit
        }
    }

    fun dismissTransferCard() {
        if (isTransferActive()) return
        _activeTransfer.value = null
        _sendQueue.value = null
        forgetUnfinished()
        sender?.forget()
    }

    // ---- questions ----

    private val prompts = PromptDesk(scope, SystemClock::elapsedRealtime, onTimeUp = ::nobodyAnswered)

    /** The question to put to the person now, if there is one. */
    val incomingRequest: StateFlow<IncomingTransferRequest?> = prompts.onScreen

    /** Something is waiting on the person's yes or no. */
    fun isAsking(): Boolean = prompts.onScreen.value != null

    /** Whether the screen that can show a question and a transfer's card is what the person is looking at. */
    fun setAppShowing(showing: Boolean) {
        _appShowing.value = showing
        if (showing) appContext?.let(TransferNotices::clearResult)
    }

    fun isAppShowing(): Boolean = _appShowing.value

    /**
     * The notification is for a question the sheet can't show. That can change while the question is up, in
     * either direction: leaving the app with one waiting puts it in the shade, and coming back takes it out.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepQuestionNoticeInStep() {
        scope.launch {
            combine(prompts.onScreen, _appShowing) { question, showing -> question?.takeUnless { showing } }
                .distinctUntilChanged()
                .collectLatest { unseen ->
                    val context = appContext ?: return@collectLatest
                    if (unseen == null) {
                        TransferNotices.clearPrompt(context)
                    } else {
                        delay(LEFT_APP_SETTLE_MS)
                        TransferNotices.showPrompt(context, unseen, prompts.timeLeftMs)
                    }
                }
        }
    }

    fun acceptIncomingRequest(sessionId: String) {
        val request = prompts.settle(sessionId) ?: return
        // On the card before the sender hears yes, so the first bytes have somewhere to show.
        if (request.kind == PromptKind.FILES) arriving.begin(request)
        request.decisionChannel.complete(PromptAnswer.ACCEPT)
    }

    fun declineIncomingRequest(sessionId: String) {
        val request = prompts.settle(sessionId) ?: return
        if (!request.kind.isBrowser) recordNotTaken(request, TransferStatus.REJECTED, null)
        request.decisionChannel.complete(PromptAnswer.REJECT)
    }

    /** A browser wants something. It goes in line, so two of them are asked about one after the other. */
    fun askForBrowser(request: IncomingTransferRequest) = prompts.ask(request)

    /** Whoever asked has gone away, so there's nothing left to say yes or no to. */
    fun withdrawPrompt(sessionId: String) {
        val wasOnScreen = prompts.isOnScreen(sessionId)
        val request = prompts.settle(sessionId) ?: return
        request.decisionChannel.complete(PromptAnswer.WITHDRAWN)
        if (request.kind.isBrowser) return
        val context = appContext ?: return
        val gaveUp = context.getString(R.string.transfer_sender_gave_up, request.sender.alias)
        recordNotTaken(request, TransferStatus.CANCELLED, gaveUp)
        // Without a word, a sheet that vanishes just looks broken.
        if (wasOnScreen && _appShowing.value) emitSuccess(gaveUp)
    }

    /** Nobody answered in time, so the question goes away rather than sitting there, and the sender is told. */
    private fun nobodyAnswered(sessionId: String) {
        val request = prompts.settle(sessionId) ?: return
        if (!request.kind.isBrowser) {
            recordNotTaken(request, TransferStatus.REJECTED, appContext?.getString(R.string.transfer_not_answered))
        }
        request.decisionChannel.complete(PromptAnswer.UNANSWERED)
    }

    /** Keeps what was offered and not taken in the history, with why. */
    private fun recordNotTaken(request: IncomingTransferRequest, status: TransferStatus, why: String?) {
        TransferHistory.record(
            TransferSession(
                sessionId = request.sessionId,
                peer = request.senderAsPeer(),
                files = request.files,
                isIncoming = true,
                status = status,
                totalBytes = request.totalBytes,
                errorMessage = why
            )
        )
    }

    // ---- receiving ----

    private val arriving = IncomingPicture(_activeTransfer, SystemClock::elapsedRealtime)

    private val _receivedNote = MutableStateFlow<ReceivedNote?>(null)
    val receivedNote: StateFlow<ReceivedNote?> = _receivedNote.asStateFlow()

    /** What the server reports, turned into what the app shows, remembers and says. */
    internal val incoming: IncomingEvents = object : IncomingEvents {
        override fun isBusy(): Boolean = this@NearbyShareRepository.isBusy()

        override fun isAsking(): Boolean = this@NearbyShareRepository.isAsking()

        override fun ask(request: IncomingTransferRequest) = prompts.ask(request)

        override fun askerLeft(sessionId: String) = withdrawPrompt(sessionId)

        override fun unanswered(sessionId: String) = nobodyAnswered(sessionId)

        override fun acceptedUnasked(request: IncomingTransferRequest) = arriving.begin(request)

        override fun noteArrived(sender: DeviceDto, senderIp: String, text: String) = onNoteReceived(sender, senderIp, text)

        /** Offering only what's already here is what a retry looks like when all of it had arrived. */
        override fun nothingNew(sender: DeviceDto, count: Int) {
            val context = appContext ?: return
            emitSuccess(context.resources.getQuantityString(R.plurals.transfer_all_already_here, count, count, sender.alias))
        }

        override fun refusedForSpace(request: IncomingTransferRequest, freeBytes: Long) {
            val why = appContext?.getString(
                R.string.transfer_refused_no_space,
                request.sender.alias,
                FileSizes.label(request.totalBytes),
                FileSizes.label(freeBytes)
            )
            recordNotTaken(request, TransferStatus.FAILED, why)
            why?.let(::emitError)
        }

        override fun progress(sessionId: String, fileId: String, bytes: Long) = arriving.progress(sessionId, fileId, bytes)

        override fun fileSaved(sessionId: String, fileId: String, saved: File) = arriving.fileSaved(sessionId, fileId, saved)

        override fun completed(sessionId: String, sender: DeviceDto, saved: List<File>) {
            endArriving(sessionId, TransferStatus.COMPLETED, null, allArrived = true)
            val received = appContext?.resources
                ?.getQuantityString(R.plurals.transfer_received_files_from, saved.size, saved.size, sender.alias)
                .orEmpty()
            scope.launch {
                _events.emit(NearbyShareEvent.Success(received))
                if (saved.isNotEmpty()) _events.emit(NearbyShareEvent.FilesReceived(sender.alias, saved))
            }
        }

        override fun cancelled(sessionId: String) = endArriving(sessionId, TransferStatus.CANCELLED, null)

        override fun failed(sessionId: String, message: String, saved: List<File>) {
            endArriving(sessionId, TransferStatus.FAILED, message)
            scope.launch {
                _events.emit(NearbyShareEvent.Error(message))
                // What did arrive whole is still worth showing.
                if (saved.isNotEmpty()) {
                    _events.emit(NearbyShareEvent.FilesReceived(_activeTransfer.value?.peer?.alias.orEmpty(), saved))
                }
            }
        }

        /** The same device has started again, so what it was doing here ended, whether or not that had shown yet. */
        override fun superseded(sessionId: String) =
            endArriving(sessionId, TransferStatus.FAILED, appContext?.getString(R.string.transfer_sender_stopped_short))
    }

    private fun endArriving(sessionId: String, status: TransferStatus, message: String?, allArrived: Boolean = false) {
        val ended = arriving.end(sessionId, status, message, allArrived) ?: return
        TransferHistory.record(ended)
        appContext?.let { tellResult(it, ended) }
    }

    /**
     * Holds on to a note until it's dismissed, and keeps it in the history, so one swipe doesn't lose it.
     * Nothing about it opens by itself.
     */
    fun onNoteReceived(sender: DeviceDto, senderIp: String, text: String) {
        val shown = text.take(MAX_NOTE_CHARS)
        val note = ReceivedNote(sender.alias, shown, System.currentTimeMillis())
        _receivedNote.value = note
        appContext?.let { NoteNotification.show(it, note) }
        TransferHistory.add(
            TransferRecord(
                id = UUID.randomUUID().toString(),
                direction = TransferDirection.RECEIVED,
                peerAlias = sender.alias,
                peerAddress = "$senderIp:${sender.port}",
                peerFingerprint = sender.fingerprint.ifBlank { null },
                timeMs = System.currentTimeMillis(),
                result = TransferResult.COMPLETED,
                note = shown.take(Notes.MAX_HISTORY_CHARS),
                totalBytes = shown.length.toLong()
            )
        )
    }

    fun dismissReceivedNote() {
        _receivedNote.value = null
        appContext?.let(NoteNotification::clear)
    }

    /** What a browser sent, now that it's all here or has stopped trying. */
    fun onBrowserFilesReceived(alias: String, files: List<File>, expected: Int) {
        val context = appContext ?: return
        if (files.isEmpty()) return
        scope.launch { _events.emit(NearbyShareEvent.FilesReceived(alias, files, expected)) }
        if (_appShowing.value) return
        TransferNotices.showResult(
            context,
            context.resources.getQuantityString(R.plurals.transfer_received_title, files.size, files.size, alias),
            TransferFormat.size(context, files.sumOf { it.length() })
        )
    }

    // ---- telling the person ----

    internal fun emitError(message: String) {
        scope.launch { _events.emit(NearbyShareEvent.Error(message)) }
    }

    private fun emitSuccess(message: String) {
        scope.launch { _events.emit(NearbyShareEvent.Success(message)) }
    }

    /** A short line about what happened, wherever it can be seen. */
    fun tell(message: String) {
        val context = appContext ?: return
        if (_appShowing.value) emitSuccess(message) else TransferNotices.showResult(context, message, null)
    }

    /**
     * The card can't say how something ended if nobody's looking at it, and a service that stops takes its
     * own notification along. So an ending nobody saw gets a notification that stays.
     */
    private fun tellResult(context: Context, session: TransferSession) {
        if (_appShowing.value || !session.status.isFinal) return
        TransferNotices.showResult(context, TransferFormat.title(context, session), TransferFormat.detail(context, session))
    }
}
