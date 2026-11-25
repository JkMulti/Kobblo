package com.kobblo.gallery.file.explorer.transfer.web

import android.content.Context
import android.media.MediaScannerConnection
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.DeviceDto
import com.kobblo.gallery.file.explorer.localsend.IncomingTransferRequest
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.PromptAnswer
import com.kobblo.gallery.file.explorer.localsend.PromptKind
import com.kobblo.gallery.file.explorer.localsend.TransferFileItem
import com.kobblo.gallery.file.explorer.transfer.core.DeviceName
import com.kobblo.gallery.file.explorer.transfer.core.LocalAddresses
import com.kobblo.gallery.file.explorer.transfer.core.TransferDirection
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecord
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecordFile
import com.kobblo.gallery.file.explorer.transfer.core.TransferResult
import com.kobblo.gallery.file.explorer.transfer.send.ReceiveFolder
import com.kobblo.gallery.file.explorer.transfer.send.SendPlanner
import com.kobblo.gallery.file.explorer.transfer.send.TransferHistory
import com.kobblo.gallery.file.explorer.transfer.send.TransferSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What the Web Share screen shows. */
data class WebShareState(
    val running: Boolean = false,
    val starting: Boolean = false,
    /** The link to open, or null if this device has no address another could reach. */
    val url: String? = null,
    val otherUrls: List<String> = emptyList(),
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val clients: List<WebClient> = emptyList(),
    val stoppedForInactivity: Boolean = false,
    val failure: String? = null
)

/**
 * Starts and stops the Web Share server and keeps its state for the screen. The service holds the
 * process alive and shows the notification; this is what it holds.
 */
object WebShareController {
    const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
    private const val SWEEP_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WebShareState())
    val state: StateFlow<WebShareState> = _state.asStateFlow()

    @Volatile private var server: WebShareServer? = null
    private var sessionStartMs = 0L
    // Files keep their number while they stay on offer, or a browser that said yes to number 2 has said yes to whatever is number 2 now.
    private val fileIds = HashMap<String, Int>()
    private var nextFileId = 1

    fun isRunning(): Boolean = server != null

    /** Asks no more about this browser, until Web Share stops. Goes by its address, which is all there is to go by. */
    fun trustBrowser(address: String) {
        server?.trust(address)
    }

    /** Binds the server. Runs on the service's request, on a background thread. */
    @Synchronized
    fun startServer(context: Context, staged: List<FileItem>): Boolean {
        if (server != null) return true
        val app = context.applicationContext
        _state.value = WebShareState(starting = true)
        val token = WebShareToken.generate()
        val name = DeviceName.current(app)
        sessionStartMs = System.currentTimeMillis()

        val listener = object : WebShareServer.Listener {
            override fun onClientsChanged() = publishClients()

            override fun onAsk(ask: WebAsk) {
                val request = IncomingTransferRequest(
                    sessionId = ask.id,
                    sender = DeviceDto(alias = ask.client.userAgent, deviceType = "desktop"),
                    senderIp = ask.client.address,
                    files = ask.files.mapIndexed { index, item ->
                        TransferFileItem(fileId = index.toString(), fileName = item.name, size = item.size, fileType = "application/octet-stream")
                    },
                    totalBytes = ask.bytes,
                    decisionChannel = CompletableDeferred(),
                    kind = when (ask.kind) {
                        WebAsk.Kind.OPEN -> PromptKind.BROWSER_OPEN
                        WebAsk.Kind.FILES -> PromptKind.BROWSER_FILES
                        WebAsk.Kind.NOTE -> PromptKind.BROWSER_NOTE
                    }
                )
                NearbyShareRepository.askForBrowser(request)
                scope.launch {
                    val answer = request.decisionChannel.await()
                    when (answer) {
                        PromptAnswer.ACCEPT -> server?.accept(ask.id)
                        PromptAnswer.REJECT -> server?.reject(ask.id)
                        PromptAnswer.UNANSWERED -> server?.unanswered(ask.id)
                        PromptAnswer.WITHDRAWN -> Unit
                    }
                    if (ask.kind != WebAsk.Kind.OPEN && (answer == PromptAnswer.REJECT || answer == PromptAnswer.UNANSWERED)) {
                        recordRefused(app, ask, answer)
                    }
                }
            }

            override fun onAskWithdrawn(ask: WebAsk) = NearbyShareRepository.withdrawPrompt(ask.id)

            override fun onBatchFinished(client: WebClient, saved: List<File>, expected: Int) {
                publishClients()
                NearbyShareRepository.onBrowserFilesReceived(client.userAgent, saved, expected)
            }

            override fun onOfferAnswered(client: WebClient, files: List<SharedFile>, accepted: Boolean) {
                publishClients()
                val words = app.resources.getQuantityString(
                    if (accepted) R.plurals.web_share_told_accepted else R.plurals.web_share_told_rejected,
                    files.size,
                    client.userAgent,
                    files.size
                )
                NearbyShareRepository.tell(words)
            }

            override fun onOfferDownloaded(client: WebClient, count: Int) {
                publishClients()
                NearbyShareRepository.tell(app.resources.getQuantityString(R.plurals.web_share_told_downloaded, count, client.userAgent, count))
            }

            override fun onDownloaded(client: WebClient, file: SharedFile) {
                publishClients()
                TransferHistory.merge(
                    TransferRecord(
                        id = "web-$sessionStartMs-${client.address}-sent",
                        direction = TransferDirection.SENT,
                        peerAlias = client.userAgent,
                        peerAddress = client.address,
                        channel = "web",
                        timeMs = System.currentTimeMillis(),
                        result = TransferResult.COMPLETED,
                        files = listOf(TransferRecordFile(file.name, file.size))
                    )
                )
            }

            override fun onUploaded(client: WebClient, saved: File, relativeName: String, bytes: Long) {
                publishClients()
                MediaScannerConnection.scanFile(app, arrayOf(saved.absolutePath), null, null)
                TransferHistory.merge(
                    TransferRecord(
                        id = "web-$sessionStartMs-${client.address}-received",
                        direction = TransferDirection.RECEIVED,
                        peerAlias = client.userAgent,
                        peerAddress = client.address,
                        channel = "web",
                        timeMs = System.currentTimeMillis(),
                        result = TransferResult.COMPLETED,
                        files = listOf(TransferRecordFile(relativeName, bytes, saved.absolutePath))
                    )
                )
            }

            override fun onText(client: WebClient, text: String) {
                NearbyShareRepository.onNoteReceived(
                    com.kobblo.gallery.file.explorer.localsend.DeviceDto(alias = client.userAgent, fingerprint = ""),
                    client.address,
                    text
                )
            }

            override fun onUploadFailed(client: WebClient, name: String, reason: String) {
                TransferHistory.merge(
                    TransferRecord(
                        id = "web-$sessionStartMs-${client.address}-received",
                        direction = TransferDirection.RECEIVED,
                        peerAlias = client.userAgent,
                        peerAddress = client.address,
                        channel = "web",
                        timeMs = System.currentTimeMillis(),
                        result = TransferResult.PARTIAL,
                        message = reason,
                        files = listOf(TransferRecordFile(name, 0, ok = false))
                    )
                )
            }
        }

        val created = bind(token, name, WebShareText.from(app), app, listener)
        if (created == null) {
            _state.value = WebShareState(failure = "bind")
            return false
        }
        server = created
        fileIds.clear()
        nextFileId = 1
        setFilesNow(app, staged)
        scope.launch {
            while (server === created) {
                delay(SWEEP_MS)
                created.sweep()
            }
        }
        val urls = LocalAddresses.current().map { "http://$it:${created.listeningPort}/$token/" }
        _state.value = _state.value.copy(
            running = true,
            starting = false,
            url = urls.firstOrNull(),
            otherUrls = urls.drop(1)
        )
        return true
    }

    private fun bind(token: String, name: String, wording: WebShareText, app: Context, listener: WebShareServer.Listener): WebShareServer? {
        // The usual port when it's free, so a link typed once still looks familiar, then any port.
        for (port in listOf(WebShareServer.DEFAULT_PORT, 0)) {
            val candidate = WebShareServer(port, token, name, wording, { ReceiveFolder.resolve(app) }, listener, asksFirst = {
                app.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE).getBoolean("nearby_ask_before_receiving", true)
            })
            try {
                candidate.startListening()
                return candidate
            } catch (e: IOException) {
                candidate.stop()
            }
        }
        return null
    }

    private fun recordRefused(app: Context, ask: WebAsk, answer: PromptAnswer) {
        TransferHistory.add(
            TransferRecord(
                id = "web-$sessionStartMs-${ask.id}",
                direction = TransferDirection.RECEIVED,
                peerAlias = ask.client.userAgent,
                peerAddress = ask.client.address,
                channel = "web",
                timeMs = System.currentTimeMillis(),
                result = TransferResult.DECLINED,
                message = if (answer == PromptAnswer.UNANSWERED) app.getString(R.string.transfer_not_answered) else null,
                files = ask.files.map { TransferRecordFile(it.name, it.size, ok = false) }
            )
        )
    }

    @Synchronized
    fun stopServer(forInactivity: Boolean = false) {
        val current = server ?: return
        server = null
        scope.launch { runCatching { current.stop() } }
        _state.value = WebShareState(stoppedForInactivity = forInactivity)
    }

    /** Changes what's on offer while it's running. Only called for what the person chose. */
    fun setFiles(context: Context, staged: List<FileItem>) {
        val app = context.applicationContext
        scope.launch { setFilesNow(app, staged) }
    }

    fun shouldStopForInactivity(): Boolean = server?.isIdle(IDLE_TIMEOUT_MS) == true

    fun publishClients() {
        val current = server ?: return
        _state.value = _state.value.copy(clients = current.clientList())
    }

    @Synchronized
    private fun setFilesNow(app: Context, staged: List<FileItem>) {
        val current = server ?: return
        // A browser is told each file's length up front, so one whose length is unknown has to be read first.
        val plan = SendPlanner.plan(app, staged)
        val shared = plan.items.map { item ->
            SharedFile(
                id = fileIds.getOrPut("${item.localPath}|${item.fileName}|${item.size}") { nextFileId++ },
                name = item.fileName,
                size = item.size,
                mime = item.fileType,
                modifiedMs = item.modifiedMs,
                note = item.preview,
                open = { TransferSources.open(app, item.localPath.orEmpty()) }
            )
        }
        current.setFiles(shared)
        _state.value = _state.value.copy(fileCount = shared.size, totalBytes = plan.totalBytes)
    }

}
