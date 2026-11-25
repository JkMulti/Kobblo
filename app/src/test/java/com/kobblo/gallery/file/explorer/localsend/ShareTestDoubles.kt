package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.transfer.core.NetworkFacts
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Words as tests see them: the string's id and whatever was filled in, so no test depends on a translation. */
internal fun words(id: Int, vararg args: Any): String = (listOf<Any>(id) + args).joinToString("|")

/**
 * A phone to receive on, and the app behind it, with nothing of Android in either. What the app is told is
 * kept in [told], in order, and whoever is "holding the phone" gives [answer] to everything they're asked.
 */
internal class FakeReceiveHost(private val folder: File) : ReceiveHost, IncomingEvents {
    var device = DeviceDto(alias = "Receiver", deviceModel = "Test phone", fingerprint = "RECEIVER", port = 53318, announce = true)
    var asksFirst = true
    var busy = false

    /** What the person says when asked. Null leaves the question up until something else ends it. */
    @Volatile var answer: PromptAnswer? = PromptAnswer.ACCEPT
    var proof: (header: String?, claimedFingerprint: String) -> Boolean = { _, _ -> false }
    val knownDevices = ConcurrentHashMap<String, SavedPeer>()

    val told = CopyOnWriteArrayList<String>()
    val asked = CopyOnWriteArrayList<IncomingTransferRequest>()
    val heardPeers = CopyOnWriteArrayList<NearbyPeer>()
    val scanned = CopyOnWriteArrayList<String>()
    val remembered = CopyOnWriteArrayList<String>()
    val savedFiles = CopyOnWriteArrayList<File>()
    val arrived = ConcurrentHashMap<String, Long>()

    @Volatile var onScreen: IncomingTransferRequest? = null
    private val ended = CountDownLatch(1)
    private val wasAsked = CountDownLatch(1)

    /** Waits for a session to end, however it ends. */
    fun awaitEnd(): Boolean = ended.await(10, TimeUnit.SECONDS)

    fun awaitQuestion(): Boolean = wasAsked.await(10, TimeUnit.SECONDS)

    override val app: IncomingEvents get() = this

    override fun self(): DeviceDto = device

    override fun heard(peer: NearbyPeer) {
        heardPeers += peer
    }

    override fun text(id: Int, vararg args: Any): String = words(id, *args)

    override fun asksFirst(): Boolean = asksFirst

    override fun receiveFolder(): File = folder

    override fun scan(paths: Array<String>) {
        scanned += paths
    }

    override fun isProven(proofHeader: String?, claimedFingerprint: String): Boolean = proof(proofHeader, claimedFingerprint)

    override fun known(fingerprint: String): SavedPeer? = knownDevices[fingerprint.uppercase()]

    override fun rememberSender(sender: DeviceDto, ip: String) {
        remembered += "${sender.fingerprint}@$ip"
    }

    override fun isBusy(): Boolean = busy || onScreen != null

    override fun isAsking(): Boolean = onScreen != null

    override fun ask(request: IncomingTransferRequest) {
        asked += request
        onScreen = request
        told += "asked"
        wasAsked.countDown()
        answer?.let {
            onScreen = null
            request.decisionChannel.complete(it)
        }
    }

    override fun askerLeft(sessionId: String) {
        told += "asker left"
        onScreen?.takeIf { it.sessionId == sessionId }?.let {
            onScreen = null
            it.decisionChannel.complete(PromptAnswer.WITHDRAWN)
        }
    }

    override fun unanswered(sessionId: String) {
        told += "unanswered"
        onScreen = null
    }

    override fun acceptedUnasked(request: IncomingTransferRequest) {
        asked += request
        told += "accepted unasked"
    }

    override fun noteArrived(sender: DeviceDto, senderIp: String, text: String) {
        told += "note from ${sender.alias}: $text"
    }

    override fun nothingNew(sender: DeviceDto, count: Int) {
        told += "nothing new of $count"
    }

    override fun refusedForSpace(request: IncomingTransferRequest, freeBytes: Long) {
        told += "no space"
    }

    override fun progress(sessionId: String, fileId: String, bytes: Long) {
        arrived[fileId] = bytes
    }

    override fun fileSaved(sessionId: String, fileId: String, saved: File) {
        savedFiles += saved
        told += "saved $fileId"
    }

    override fun completed(sessionId: String, sender: DeviceDto, saved: List<File>) {
        told += "completed ${saved.size}"
        ended.countDown()
    }

    override fun cancelled(sessionId: String) {
        told += "cancelled"
        ended.countDown()
    }

    override fun failed(sessionId: String, message: String, saved: List<File>) {
        told += "failed $message"
        ended.countDown()
    }

    override fun superseded(sessionId: String) {
        told += "superseded"
    }
}

/** A phone to send from. Its files are byte arrays by name, and its pins are its own. */
internal class FakeSendHost(
    private val files: Map<String, ByteArray>,
    private val device: DeviceDto = DeviceDto(alias = "Sender", deviceModel = "Test phone", fingerprint = "SENDER", port = 53318),
    private val proof: (receiverFingerprint: String) -> String? = { null }
) : SendHost {
    val pins = FingerprintPins()

    /** How a file is opened. Tests swap it to make a read fail, or to do something as a file starts going. */
    @Volatile var opener: (String) -> InputStream = ::whole

    override val log: ShareLog = ShareLog.Silent

    fun whole(source: String): InputStream = ByteArrayInputStream(files[source] ?: throw FileNotFoundException(source))

    override fun self(): DeviceDto = device

    override fun text(id: Int, vararg args: Any): String = words(id, *args)

    override fun uptimeMs(): Long = System.nanoTime() / 1_000_000

    override fun open(source: String): InputStream = opener(source)

    override fun proofFor(receiverFingerprint: String): String? = proof(receiverFingerprint)

    override fun expect(hostAndPort: String, fingerprint: String) {
        pins.pinFromNetwork(hostAndPort, fingerprint)
    }

    override fun network(): NetworkFacts = NetworkFacts(listOf("127.0.0.1"), vpnOn = false)

    override fun secure(builder: OkHttpClient.Builder) = pins.guard(builder)
}

/** One request, as the server library would hand it over, without a socket. */
internal class FakeCall(
    private val method: NanoHTTPD.Method,
    private val path: String,
    private val body: ByteArray = ByteArray(0),
    private val from: String = "10.0.0.2",
    private val query: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    connection: InputStream? = null
) : NanoHTTPD.IHTTPSession {
    private val allHeaders = HashMap<String, String>().apply {
        put("content-length", body.size.toString())
        putAll(headers)
    }
    private val input = connection ?: ByteArrayInputStream(body)

    override fun execute() = Unit

    override fun getCookies(): NanoHTTPD.CookieHandler = throw UnsupportedOperationException()

    override fun getHeaders(): Map<String, String> = allHeaders

    override fun getInputStream(): InputStream = input

    override fun getMethod(): NanoHTTPD.Method = method

    @Deprecated("The server library's own older way of reading parameters")
    override fun getParms(): Map<String, String> = query

    override fun getParameters(): Map<String, List<String>> = query.mapValues { listOf(it.value) }

    override fun getQueryParameterString(): String = query.entries.joinToString("&") { "${it.key}=${it.value}" }

    override fun getUri(): String = path

    override fun parseBody(files: MutableMap<String, String>) {
        val text = String(body, Charsets.UTF_8).trim()
        if (text.isNotEmpty()) files["postData"] = text
    }

    override fun getRemoteIpAddress(): String = from

    override fun getRemoteHostName(): String = from
}

/** A connection with nothing arriving on it, which is what a sender waiting for an answer looks like. */
internal class QuietConnection : InputStream() {
    private val closed = CountDownLatch(1)

    override fun read(): Int {
        closed.await()
        return -1
    }

    /** The other end hangs up. */
    override fun close() = closed.countDown()
}

internal fun NanoHTTPD.Response.code(): Int = status.requestStatus

internal fun NanoHTTPD.Response.text(): String = data.readBytes().toString(Charsets.UTF_8)
