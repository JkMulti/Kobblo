package com.kobblo.gallery.file.explorer.transfer.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kobblo.gallery.file.explorer.transfer.core.BoundedAsyncRunner
import com.kobblo.gallery.file.explorer.transfer.core.PromptCooldown
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveResult
import com.kobblo.gallery.file.explorer.transfer.core.ReceivePaths
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import com.kobblo.gallery.file.explorer.transfer.web.WebShareText.Reply
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** A file on offer. Only these can ever be downloaded: a request names an id, never a path. */
data class SharedFile(
    val id: Int,
    /** Relative path shown to the browser and used inside a zip. */
    val name: String,
    val size: Long,
    val mime: String,
    val modifiedMs: Long = 0,
    /** A note's words, shown on the page itself rather than offered as a download. */
    val note: String? = null,
    val open: () -> InputStream
)

/** One browser that has used the link. */
data class WebClient(
    val address: String,
    val userAgent: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val downloads: Int = 0,
    val uploads: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    /** The person with the phone has let it in. */
    val allowed: Boolean = false,
    /** What it last did with what it was offered. */
    val outcome: WebOutcome? = null
)

data class WebOutcome(val kind: Kind, val count: Int) {
    enum class Kind { ACCEPTED, REJECTED, DOWNLOADED }
}

/** Something a browser wants, which the person with the phone has to say yes or no to. */
data class WebAsk(
    val id: String,
    val kind: Kind,
    val client: WebClient,
    val files: List<Item> = emptyList(),
    val bytes: Long = files.sumOf { it.size }
) {
    enum class Kind { OPEN, FILES, NOTE }

    data class Item(val name: String, val size: Long)
}

object WebShareToken {
    private const val BYTES = 24

    /** 192 random bits, URL-safe. Long enough that guessing it over a network is not a thing. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))
    }
}

/** A single `Range: bytes=a-b` request, resolved against a file's length. Null means "send it all". */
internal fun parseRange(header: String?, length: Long): LongRange? {
    if (header == null || !header.startsWith("bytes=") || ',' in header) return null
    val spec = header.removePrefix("bytes=").trim()
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    val range = if (first.isEmpty()) {
        val suffix = last.toLongOrNull() ?: return null
        (length - suffix).coerceAtLeast(0)..(length - 1)
    } else {
        val start = first.toLongOrNull() ?: return null
        val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: return null).coerceAtMost(length - 1)
        start..end
    }
    return range
}

/** The header that makes a browser save a file instead of showing it, whatever its name holds. */
internal fun contentDisposition(name: String): String {
    val leaf = name.substringAfterLast('/')
    val ascii = leaf.map { if (it.code in 32..126 && it != '"' && it != '\\' && it != ';') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(leaf, "UTF-8").replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

/** A plain description of a browser, for the list of who is connected. */
internal fun describeUserAgent(agent: String?, wording: WebShareText): String {
    if (agent.isNullOrBlank()) return wording.someBrowser
    val browser = when {
        "Edg/" in agent -> "Edge"
        "OPR/" in agent -> "Opera"
        "Chrome/" in agent -> "Chrome"
        "Firefox/" in agent -> "Firefox"
        "Safari/" in agent -> "Safari"
        "curl/" in agent -> "curl"
        else -> wording.someBrowser
    }
    val system = when {
        "Android" in agent -> "Android"
        "iPhone" in agent || "iPad" in agent -> "iOS"
        "Mac OS" in agent || "Macintosh" in agent -> "Mac"
        "Windows" in agent -> "Windows"
        "CrOS" in agent -> "ChromeOS"
        "Linux" in agent -> "Linux"
        else -> null
    }
    return if (system != null) WebShareText.fill(wording.browserOn, browser, system) else browser
}

/**
 * The server behind Web Share: a page on plain HTTP that lists the chosen files and takes uploads.
 *
 * There's no encryption on this path, so every URL carries a long random token that changes each time it starts,
 * and nothing is reachable without it: a wrong token gets the same 404 as a page that doesn't exist. Anyone who sees
 * the traffic could see the token too, so it isn't the only thing in the way. A browser also has to be let in by the
 * person with the phone before it sees anything, which goes by its address. Then each side gets a say over what
 * moves: what a browser wants to send waits for a yes on the phone before a byte of it is accepted, and what the
 * phone offers waits for a yes on the page before it can be downloaded.
 *
 * Shared files are looked up by number from a fixed list, so no part of a URL ever becomes a path on this phone.
 * Uploads only ever land in [receiveRoot], under a clean name, and never replace anything.
 */
class WebShareServer(
    port: Int,
    val token: String,
    private val deviceName: String,
    /** Everything a person reads, on the page or in what this server answers it, in the phone's language. */
    private val wording: WebShareText,
    private val receiveRoot: () -> File,
    private val listener: Listener = object : Listener {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** The setting that says incoming things should be asked about. Only letting a browser in always is. */
    private val asksFirst: () -> Boolean = { true }
) : NanoHTTPD(port) {

    interface Listener {
        fun onClientsChanged() {}
        fun onDownloaded(client: WebClient, file: SharedFile) {}
        fun onUploaded(client: WebClient, saved: File, relativeName: String, bytes: Long) {}
        fun onUploadFailed(client: WebClient, name: String, reason: String) {}
        fun onText(client: WebClient, text: String) {}

        /** Someone has to answer this. [accept], [reject] or [unanswered] says how it went. */
        fun onAsk(ask: WebAsk) {}

        /** It no longer needs an answer: the page was closed, or asked for it to be forgotten. */
        fun onAskWithdrawn(ask: WebAsk) {}

        /** A batch that was accepted is over: all of it is here, or the browser has stopped sending the rest. */
        fun onBatchFinished(client: WebClient, saved: List<File>, expected: Int) {}

        /** The page answered what the phone offered it. */
        fun onOfferAnswered(client: WebClient, files: List<SharedFile>, accepted: Boolean) {}

        /** Everything the page accepted has now been downloaded. */
        fun onOfferDownloaded(client: WebClient, count: Int) {}
    }

    private enum class AskState(val word: String) {
        WAITING("waiting"),
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        UNANSWERED("unanswered"),
        WITHDRAWN("withdrawn")
    }

    private inner class Ask(val ask: WebAsk) {
        @Volatile var state = AskState.WAITING
        @Volatile var lastHeardMs = clock()
        private val left = ArrayList(ask.files)
        val saved: MutableList<File> = java.util.Collections.synchronizedList(ArrayList())
        val active = AtomicInteger(0)

        /** Each thing that was agreed to can only come once. */
        fun claim(item: WebAsk.Item): Boolean = synchronized(left) { left.remove(item) }

        fun release(item: WebAsk.Item) {
            synchronized(left) { left.add(item) }
        }

        fun isFinished(): Boolean = active.get() == 0 && synchronized(left) { left.isEmpty() }
    }

    /** What a browser has said about what's on offer, by file. */
    private class Offers {
        val answers = HashMap<Int, Boolean>()
        val toDownload = HashSet<Int>()
        var round = 0
    }

    init {
        // Page, list and a few files at once, so nobody with a browser comes near this. Someone who doesn't
        // have the link still gets a thread per connection, until they reach it.
        setAsyncRunner(BoundedAsyncRunner(MAX_CONNECTIONS))
    }

    @Volatile private var files: List<SharedFile> = emptyList()
    private val clients = ConcurrentHashMap<String, WebClient>()
    private val inFlight = AtomicInteger(0)
    private val lastActivity = AtomicLong(clock())
    private val gson = Gson()
    private val stopped = AtomicBoolean(false)
    private val asks = ConcurrentHashMap<String, Ask>()
    private val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val trusted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val offers = ConcurrentHashMap<String, Offers>()
    private val cooldown = PromptCooldown()

    fun setFiles(list: List<SharedFile>) {
        files = list
    }

    fun clientList(): List<WebClient> = clients.values.sortedByDescending { it.lastSeenMs }

    /** True once nothing has been asked of the server for [timeoutMs], and nothing is still moving. */
    fun isIdle(timeoutMs: Long, nowMs: Long = clock()): Boolean =
        inFlight.get() == 0 && nowMs - lastActivity.get() >= timeoutMs

    /** Starts listening, with a read timeout long enough for a browser to pause between chunks. */
    fun startListening() {
        start(READ_TIMEOUT_MS, false)
    }

    override fun stop() {
        stopped.set(true)
        for (ask in asks.values) {
            if (ask.state == AskState.WAITING) {
                ask.state = AskState.WITHDRAWN
                listener.onAskWithdrawn(ask.ask)
            }
        }
        super.stop()
    }

    /** Lets the browser in, or lets what it wants to send come in. */
    fun accept(id: String) {
        val ask = asks[id]?.takeIf { it.state == AskState.WAITING } ?: return
        ask.lastHeardMs = clock()
        ask.state = AskState.ACCEPTED
        if (ask.ask.kind == WebAsk.Kind.OPEN) letIn(ask.ask.client.address)
    }

    fun reject(id: String) {
        val ask = asks[id]?.takeIf { it.state == AskState.WAITING } ?: return
        ask.lastHeardMs = clock()
        ask.state = AskState.REJECTED
        // Someone just told no doesn't get to put the question straight back on the screen.
        cooldown.declined(ask.ask.client.address, clock())
    }

    fun unanswered(id: String) {
        val ask = asks[id]?.takeIf { it.state == AskState.WAITING } ?: return
        ask.lastHeardMs = clock()
        ask.state = AskState.UNANSWERED
    }

    /** Whatever this browser asks is a yes, for as long as this server runs, which is only until Web Share stops. */
    fun trust(address: String) {
        trusted += address
        letIn(address)
    }

    private fun letIn(address: String) {
        allowed += address
        clients.update(address) { it.copy(allowed = true) }
        listener.onClientsChanged()
    }

    /** Lets go of what has been left behind: a closed page's question, a batch that stopped coming. Call it often. */
    fun sweep(nowMs: Long = clock()) {
        for (ask in asks.values) {
            val quiet = nowMs - ask.lastHeardMs
            when (ask.state) {
                AskState.WAITING -> if (quiet >= PAGE_GONE_MS) {
                    ask.state = AskState.WITHDRAWN
                    ask.lastHeardMs = nowMs
                    listener.onAskWithdrawn(ask.ask)
                }
                AskState.ACCEPTED -> if (quiet >= BATCH_GONE_MS && ask.active.get() == 0) finish(ask)
                else -> if (quiet >= BATCH_GONE_MS) asks.remove(ask.ask.id)
            }
        }
    }

    private fun finish(ask: Ask) {
        if (asks.remove(ask.ask.id) == null || ask.ask.kind != WebAsk.Kind.FILES) return
        clients[ask.ask.client.address]?.let { listener.onBatchFinished(it, ask.saved.toList(), ask.ask.files.size) }
    }

    // Compressing would hide a file's length from the browser and break resuming a download.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return notFound()
        val segments = uri.split('/').filter { it.isNotEmpty() }
        val isToken = WebShareToken.matches(token, segments.firstOrNull())
        // A wrong token and a wrong path look identical, so probing learns nothing.
        if (!isToken) return notFound(session)
        val route = segments.drop(1)
        // An open page keeps looking for what's new, and that's not someone using this. A forgotten tab mustn't
        // keep the link open past the idle limit.
        val looking = route == listOf("api", "files")
        if (!looking) lastActivity.set(clock())

        val client = touchClient(session)
        val key = client.address
        val method = session.method
        return try {
            when {
                route.isEmpty() && !uri.endsWith("/") -> redirectToPage()
                route.isEmpty() && method == Method.GET -> page()
                route == listOf("ask") && method == Method.POST -> startAsk(session, key)
                route.size == 2 && route[0] == "ask" && method == Method.GET -> askState(route[1], key)
                route.size == 3 && route[0] == "ask" && route[2] == "cancel" && method == Method.POST -> cancelAsk(route[1], key)
                // Everything past here is for a browser that's been let in.
                route != emptyList<String>() && key !in allowed -> notAllowed(session)
                looking && method == Method.GET -> fileList(key)
                route == listOf("offer") && method == Method.POST -> answerOffer(session, key)
                route.size == 2 && route[0] == "d" && (method == Method.GET || method == Method.HEAD) ->
                    download(session, route[1], key)
                route == listOf("zip") && method == Method.GET -> zip(session, key)
                route == listOf("upload") && method == Method.POST -> upload(session, key)
                route == listOf("text") && method == Method.POST -> text(session, key)
                else -> notFound(session)
            }
        } catch (e: IOException) {
            plain(Response.Status.INTERNAL_ERROR, wording.reply(Reply.FAILED))
        } catch (e: RuntimeException) {
            // A body that isn't the shape it says it is.
            plain(Response.Status.BAD_REQUEST, "Bad request").apply { closeConnection(true) }
        }
    }

    private fun page(): Response = secured(
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", WebSharePage.html(deviceName, wording))
    ).apply { addHeader("Content-Security-Policy", PAGE_CSP) }

    private fun redirectToPage(): Response = secured(
        newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, "").apply { addHeader("Location", "/$token/") }
    )

    /** What's been accepted, and what's waiting for an answer. A page sees nothing it hasn't said yes to, but that it's there. */
    private fun fileList(clientKey: String): Response {
        val current = files
        val answers = offersFor(clientKey).let { synchronized(it) { HashMap(it.answers) } }
        val taken = current.filter { answers[it.id] == true }
        val open = current.filter { it.id !in answers }
        val openFiles = open.filter { it.note == null }
        val body = HashMap<String, Any>()
        body["device"] = deviceName
        body["free"] = receiveRoot().usableSpace
        body["notes"] = taken.mapNotNull { it.note }
        body["files"] = taken.filter { it.note == null }.map { mapOf("id" to it.id, "name" to it.name, "size" to it.size, "type" to it.mime) }
        body["rejected"] = current.count { answers[it.id] == false }
        if (open.isNotEmpty()) {
            body["offer"] = mapOf(
                "ids" to open.map { it.id },
                "fileIds" to openFiles.map { it.id },
                "count" to openFiles.size,
                "notes" to open.size - openFiles.size,
                "bytes" to openFiles.sumOf { it.size },
                "names" to openFiles.take(OFFER_NAMES_SHOWN).map { it.name.substringAfterLast('/') }
            )
        }
        return secured(newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(body)))
    }

    private fun offersFor(clientKey: String): Offers = offers.getOrPut(clientKey) { Offers() }

    private fun hasAccepted(clientKey: String, file: SharedFile): Boolean =
        offersFor(clientKey).let { synchronized(it) { it.answers[file.id] == true } }

    private fun answerOffer(session: IHTTPSession, clientKey: String): Response {
        val body = readJson(session) ?: return plain(Response.Status.BAD_REQUEST, "Bad request").apply { closeConnection(true) }
        val current = files
        val offer = offersFor(clientKey)
        if (body.get("undo")?.asBoolean == true) {
            synchronized(offer) { offer.answers.values.removeAll { !it } }
            return secured(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
        }
        fun chosen(name: String): List<SharedFile> {
            val ids = body.getAsJsonArray(name)?.mapNotNull { runCatching { it.asInt }.getOrNull() }.orEmpty().toSet()
            return current.filter { it.id in ids }
        }
        val accepted = chosen("accept")
        val rejected = chosen("reject")
        synchronized(offer) {
            accepted.forEach {
                offer.answers[it.id] = true
                if (it.note == null && offer.toDownload.add(it.id)) offer.round++
            }
            rejected.forEach { offer.answers[it.id] = false }
        }
        if (accepted.isNotEmpty()) tellOffer(clientKey, accepted, accepted = true)
        if (rejected.isNotEmpty()) tellOffer(clientKey, rejected, accepted = false)
        return secured(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
    }

    private fun tellOffer(clientKey: String, chosen: List<SharedFile>, accepted: Boolean) {
        val kind = if (accepted) WebOutcome.Kind.ACCEPTED else WebOutcome.Kind.REJECTED
        clients.update(clientKey) { it.copy(outcome = WebOutcome(kind, chosen.size)) }?.let { listener.onOfferAnswered(it, chosen, accepted) }
    }

    private fun onFileDownloaded(clientKey: String, file: SharedFile) {
        clients.update(clientKey) { it.copy(downloads = it.downloads + 1) }?.let { listener.onDownloaded(it, file) }
        val offer = offersFor(clientKey)
        val finished = synchronized(offer) {
            offer.toDownload.remove(file.id)
            if (offer.toDownload.isEmpty() && offer.round > 0) offer.round.also { offer.round = 0 } else 0
        }
        if (finished > 0) {
            clients.update(clientKey) { it.copy(outcome = WebOutcome(WebOutcome.Kind.DOWNLOADED, finished)) }
                ?.let { listener.onOfferDownloaded(it, finished) }
        }
    }

    // ── Asking the person with the phone ────────────────────────────────────

    private fun startAsk(session: IHTTPSession, clientKey: String): Response {
        val body = readJson(session) ?: return plain(Response.Status.BAD_REQUEST, "Bad request").apply { closeConnection(true) }
        val client = clients[clientKey] ?: return notFound(session)
        val kind = when (body.get("kind")?.asString) {
            "open" -> WebAsk.Kind.OPEN
            "files" -> WebAsk.Kind.FILES
            "note" -> WebAsk.Kind.NOTE
            else -> return plain(Response.Status.BAD_REQUEST, "Bad request")
        }
        if (kind == WebAsk.Kind.OPEN) {
            if (clientKey in allowed) return askAnswer("", AskState.ACCEPTED)
            // A page opened again while it's being asked about shouldn't ask a second time.
            asks.values.firstOrNull { it.ask.client.address == clientKey && it.ask.kind == kind && it.state == AskState.WAITING }?.let {
                it.lastHeardMs = clock()
                return askAnswer(it.ask.id, it.state)
            }
        } else if (clientKey !in allowed) {
            return notAllowed(session)
        }
        if (cooldown.isCooling(clientKey, clock())) return plain(TOO_MANY_REQUESTS, "Give the phone a moment before asking again.")

        val items = when (kind) {
            WebAsk.Kind.OPEN -> emptyList()
            WebAsk.Kind.NOTE -> {
                val size = body.get("size")?.asLong ?: 0L
                if (size !in 1..MAX_TEXT_BYTES.toLong()) return plain(Response.Status.PAYLOAD_TOO_LARGE, wording.reply(Reply.NOTE_TOO_LONG))
                listOf(WebAsk.Item("Note.txt", size))
            }
            WebAsk.Kind.FILES -> {
                val list = body.getAsJsonArray("files")?.mapNotNull { element ->
                    val file = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val name = file.get("name")?.asString.orEmpty()
                    val size = file.get("size")?.asLong ?: -1L
                    if (name.isBlank() || size < 0) null else WebAsk.Item(name, size)
                }.orEmpty()
                if (list.isEmpty() || list.size > MAX_ASK_FILES) return plain(Response.Status.BAD_REQUEST, "Bad request")
                if (receiveRoot().usableSpace < list.sumOf { it.size }) return plain(STORAGE_FULL, wording.reply(Reply.NO_SPACE))
                list
            }
        }
        // Only ever one question from a browser. A newer one means the page has moved on from the last.
        if (kind != WebAsk.Kind.OPEN) {
            asks.values.filter { it.ask.client.address == clientKey && it.state == AskState.WAITING }.forEach {
                it.state = AskState.WITHDRAWN
                listener.onAskWithdrawn(it.ask)
            }
        }
        val ask = Ask(WebAsk(UUID.randomUUID().toString(), kind, client, items))
        asks[ask.ask.id] = ask
        val answered = kind != WebAsk.Kind.OPEN && (clientKey in trusted || !asksFirst())
        if (answered) {
            ask.state = AskState.ACCEPTED
        } else {
            listener.onAsk(ask.ask)
        }
        return askAnswer(ask.ask.id, ask.state)
    }

    private fun askAnswer(id: String, state: AskState): Response =
        secured(newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(mapOf("id" to id, "state" to state.word))))

    private fun askState(id: String, clientKey: String): Response {
        val ask = asks[id]?.takeIf { it.ask.client.address == clientKey } ?: return notFound()
        if (ask.state == AskState.WAITING) ask.lastHeardMs = clock()
        return askAnswer(id, ask.state)
    }

    private fun cancelAsk(id: String, clientKey: String): Response {
        val ask = asks[id]?.takeIf { it.ask.client.address == clientKey }
        if (ask != null && ask.state == AskState.WAITING) {
            ask.state = AskState.WITHDRAWN
            ask.lastHeardMs = clock()
            listener.onAskWithdrawn(ask.ask)
        }
        return secured(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
    }

    private fun notAllowed(session: IHTTPSession): Response =
        plain(Response.Status.FORBIDDEN, wording.reply(Reply.NOT_LET_IN)).apply {
            if (session.method == Method.POST || session.method == Method.PUT) closeConnection(true)
        }

    private fun readJson(session: IHTTPSession): JsonObject? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length !in 1..MAX_ASK_BYTES) return null
        return runCatching {
            JsonParser.parseString(String(readExactly(session.inputStream, length), Charsets.UTF_8)).asJsonObject
        }.getOrNull()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val got = input.read(bytes, read, length - read)
            if (got < 0) throw IOException("Cut short")
            read += got
        }
        return bytes
    }

    private fun download(session: IHTTPSession, rawId: String, clientKey: String): Response {
        val file = rawId.toIntOrNull()?.let { id -> files.firstOrNull { it.id == id } } ?: return notFound(session)
        if (!hasAccepted(clientKey, file)) return plain(Response.Status.FORBIDDEN, wording.reply(Reply.ACCEPT_FIRST))
        val range = parseRange(session.headers["range"], file.size)
        if (range != null && (range.first < 0 || range.first > range.last || range.first >= file.size)) {
            return secured(
                newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                    addHeader("Content-Range", "bytes */${file.size}")
                }
            )
        }
        val isHead = session.method == Method.HEAD
        val start = range?.first ?: 0L
        val count = if (range != null) range.last - range.first + 1 else file.size
        val input = if (isHead) java.io.ByteArrayInputStream(ByteArray(0)) else {
            val raw = try {
                file.open()
            } catch (e: SecurityException) {
                // A file shared in from another app, whose permission has since ended.
                return plain(Response.Status.GONE, wording.reply(Reply.GONE))
            }
            skipFully(raw, start)
            raw
        }
        val tracked = if (isHead) input else tracking(input, clientKey) { sent ->
            // A range is one piece of a resume, not a finished download.
            if (range == null && sent >= count) onFileDownloaded(clientKey, file)
        }
        val response = newFixedLengthResponse(
            if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            file.mime,
            tracked,
            count
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Disposition", contentDisposition(file.name))
        if (range != null) response.addHeader("Content-Range", "bytes ${range.first}-${range.last}/${file.size}")
        return secured(response)
    }

    private fun zip(session: IHTTPSession, clientKey: String): Response {
        // Just this offer's files, when it says which, so that a second offer doesn't bring the first along again.
        val only = session.parameters["ids"]?.firstOrNull()?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
        val snapshot = files.filter { it.note == null && hasAccepted(clientKey, it) && (only == null || it.id in only) }
        if (snapshot.isEmpty()) return plain(Response.Status.FORBIDDEN, wording.reply(Reply.ACCEPT_FIRST))
        val pipeIn = PipedInputStream(PIPE_BYTES)
        val pipeOut = PipedOutputStream(pipeIn)
        thread(name = "webshare-zip", isDaemon = true) {
            try {
                ZipOutputStream(BufferedOutputStream(pipeOut, PIPE_BYTES)).use { zip ->
                    // Photos and video don't shrink, and speed is the point on a local network.
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    val used = HashSet<String>()
                    val missing = mutableListOf<String>()
                    for (file in snapshot) {
                        val entryName = uniqueZipName(file.name, used)
                        val source = try {
                            file.open()
                        } catch (e: Exception) { // Includes a SecurityException from a share grant that ended.
                            missing += file.name
                            continue
                        }
                        source.use { input ->
                            zip.putNextEntry(ZipEntry(entryName).apply { if (file.modifiedMs > 0) time = file.modifiedMs })
                            input.copyTo(zip, COPY_BYTES)
                            zip.closeEntry()
                        }
                        onFileDownloaded(clientKey, file)
                    }
                    if (missing.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry(uniqueZipName("${wording.unreadableName}.txt", used)))
                        zip.write(missing.joinToString("\n").toByteArray())
                        zip.closeEntry()
                    }
                }
            } catch (e: Exception) {
                // Usually the browser stopped reading, which closed the pipe. An uncaught error on this
                // thread would take the whole app down, so nothing gets out of here.
                runCatching { pipeOut.close() }
            }
        }
        val response = newChunkedResponse(Response.Status.OK, "application/zip", tracking(pipeIn, clientKey))
        response.addHeader("Content-Disposition", contentDisposition("${WebShareText.fill(wording.zipName, deviceName)}.zip"))
        return secured(response)
    }

    private fun upload(session: IHTTPSession, clientKey: String): Response {
        // Nothing is read, let alone written, for anything that hasn't been said yes to on the phone.
        val ask = session.parameters["ask"]?.firstOrNull()?.let { asks[it] }
            ?.takeIf { it.ask.client.address == clientKey && it.ask.kind == WebAsk.Kind.FILES && it.state == AskState.ACCEPTED }
            ?: return plain(Response.Status.FORBIDDEN, wording.reply(Reply.NOT_ACCEPTED)).apply { closeConnection(true) }
        val client = clients[clientKey]
        val rawName = session.parameters["name"]?.firstOrNull().orEmpty()
        fun refuse(status: Response.IStatus, reason: String): Response {
            client?.let { listener.onUploadFailed(it, rawName, reason) }
            // The body is still on its way, so this connection can't be used again.
            return plain(status, reason).apply { closeConnection(true) }
        }
        if ("chunked" in session.headers["transfer-encoding"].orEmpty().lowercase()) {
            return refuse(Response.Status.LENGTH_REQUIRED, "Length required")
        }
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return refuse(Response.Status.LENGTH_REQUIRED, "Length required")
        if (length < 0) return refuse(Response.Status.BAD_REQUEST, "Bad length")
        val item = WebAsk.Item(rawName, length)
        if (!ask.claim(item)) return plain(Response.Status.FORBIDDEN, wording.reply(Reply.NOT_ACCEPTED)).apply { closeConnection(true) }
        val root = receiveRoot()
        var arrived = false
        ask.active.incrementAndGet()
        inFlight.incrementAndGet()
        try {
            if (!root.isDirectory && !root.mkdirs()) return refuse(Response.Status.INTERNAL_ERROR, wording.reply(Reply.NO_FOLDER))
            if (root.usableSpace < length) return refuse(STORAGE_FULL, wording.reply(Reply.NO_SPACE))
            val sink = ReceiveSink(root, rawName)
            var counted = 0L
            val result = sink.receive(
                input = session.inputStream,
                expectedBytes = length,
                isCancelled = { stopped.get() },
                onProgress = { total ->
                    clients.update(clientKey) { it.copy(bytesReceived = it.bytesReceived + (total - counted), lastSeenMs = clock()) }
                    counted = total
                    lastActivity.set(clock())
                    ask.lastHeardMs = clock()
                }
            )
            return when (result) {
                is ReceiveResult.Saved -> {
                    arrived = true
                    ask.saved.add(result.file)
                    val relative = result.file.relativeTo(root).path
                    val updated = clients.update(clientKey) { it.copy(uploads = it.uploads + 1) }
                    updated?.let { listener.onUploaded(it, result.file, relative, result.bytes) }
                    secured(
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "application/json",
                            gson.toJson(mapOf("name" to relative, "size" to result.bytes))
                        )
                    )
                }
                ReceiveResult.DiskFull -> refuse(STORAGE_FULL, wording.reply(Reply.RAN_OUT))
                ReceiveResult.Cancelled -> refuse(Response.Status.SERVICE_UNAVAILABLE, wording.reply(Reply.STOPPED))
                is ReceiveResult.Truncated -> refuse(Response.Status.BAD_REQUEST, wording.reply(Reply.INTERRUPTED))
                ReceiveResult.ChecksumMismatch -> refuse(Response.Status.BAD_REQUEST, wording.reply(Reply.DAMAGED))
                // Its reason can hold a path, which a browser doesn't need to see.
                is ReceiveResult.Failed -> refuse(Response.Status.INTERNAL_ERROR, wording.reply(Reply.CANT_SAVE))
            }
        } finally {
            // One that didn't make it can be tried again under the same yes.
            if (!arrived) ask.release(item)
            ask.lastHeardMs = clock()
            ask.active.decrementAndGet()
            inFlight.decrementAndGet()
            lastActivity.set(clock())
            if (ask.isFinished()) finish(ask)
        }
    }

    /** Words typed into the page. Nothing is saved, and they only ever appear as text on the phone. */
    private fun text(session: IHTTPSession, clientKey: String): Response {
        val ask = session.parameters["ask"]?.firstOrNull()?.let { asks[it] }
            ?.takeIf { it.ask.client.address == clientKey && it.ask.kind == WebAsk.Kind.NOTE && it.state == AskState.ACCEPTED }
            ?: return plain(Response.Status.FORBIDDEN, wording.reply(Reply.NOT_ACCEPTED)).apply { closeConnection(true) }
        val length = session.headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 1..MAX_TEXT_BYTES) {
            return plain(Response.Status.PAYLOAD_TOO_LARGE, wording.reply(Reply.NOTE_TOO_LONG)).apply { closeConnection(true) }
        }
        val bytes = try {
            readExactly(session.inputStream, length)
        } catch (e: IOException) {
            return plain(Response.Status.BAD_REQUEST, wording.reply(Reply.NOTE_CUT_SHORT)).apply { closeConnection(true) }
        }
        asks.remove(ask.ask.id)
        clients[clientKey]?.let { listener.onText(it, String(bytes, Charsets.UTF_8)) }
        return secured(newFixedLengthResponse(Response.Status.OK, "application/json", "{}"))
    }

    private fun touchClient(session: IHTTPSession): WebClient {
        val address = session.remoteIpAddress ?: "unknown"
        val now = clock()
        var isNew = false
        val client = clients.compute(address) { _, existing ->
            if (existing == null) {
                isNew = true
                WebClient(address, describeUserAgent(session.headers["user-agent"], wording), now, now)
            } else {
                existing.copy(lastSeenMs = now)
            }
        }!!
        if (isNew) listener.onClientsChanged()
        return client
    }

    private fun ConcurrentHashMap<String, WebClient>.update(key: String, change: (WebClient) -> WebClient): WebClient? =
        computeIfPresent(key) { _, value -> change(value) }

    /** Counts what leaves, and keeps the server from calling itself idle while it does. */
    private fun tracking(input: InputStream, clientKey: String, onClose: (Long) -> Unit = {}): InputStream {
        inFlight.incrementAndGet()
        val closed = AtomicBoolean(false)
        var sent = 0L
        return object : FilterInputStream(input) {
            private fun count(bytes: Int) {
                if (bytes <= 0) return
                sent += bytes
                lastActivity.set(clock())
                clients.update(clientKey) { it.copy(bytesSent = it.bytesSent + bytes, lastSeenMs = clock()) }
            }

            override fun read(): Int = super.read().also { if (it >= 0) count(1) }

            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also(::count)

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    inFlight.decrementAndGet()
                    onClose(sent)
                    listener.onClientsChanged()
                }
                super.close()
            }
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (input.read() < 0) throw IOException("Past the end of the file")
                left--
            }
        }
    }

    private fun uniqueZipName(name: String, used: MutableSet<String>): String {
        val clean = ReceivePaths.sanitizeRelative(name).joinToString("/")
        var candidate = clean
        var n = 2
        while (!used.add(candidate)) {
            candidate = ReceivePaths.numbered(clean, n++)
        }
        return candidate
    }

    private fun notFound(session: IHTTPSession? = null): Response =
        plain(Response.Status.NOT_FOUND, "Not found").apply {
            // Anything with a body we didn't read would corrupt the next request on this connection.
            if (session?.method == Method.POST || session?.method == Method.PUT) closeConnection(true)
        }

    private fun plain(status: Response.IStatus, text: String): Response =
        secured(newFixedLengthResponse(status, MIME_PLAINTEXT, text))

    private fun secured(response: Response): Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Frame-Options", "DENY")
    }

    companion object {
        const val DEFAULT_PORT = 53319
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_CONNECTIONS = 48
        private const val MAX_TEXT_BYTES = 128 * 1024
        // A list of names and sizes, which is all that's sent before a yes. About 40,000 files.
        private const val MAX_ASK_BYTES = 4 * 1024 * 1024
        private const val MAX_ASK_FILES = 100_000
        private const val OFFER_NAMES_SHOWN = 3
        // A waiting page checks every second, so this means it isn't there any more.
        private const val PAGE_GONE_MS = 10_000L
        private const val BATCH_GONE_MS = 60_000L
        private const val PIPE_BYTES = 256 * 1024
        private const val COPY_BYTES = 64 * 1024

        // A shared HTML file downloads rather than rendering here, but the page itself holds the token,
        // so it may only talk back to this server and may not be framed.
        private const val PAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

        private val TOO_MANY_REQUESTS = object : Response.IStatus {
            override fun getDescription() = "429 Too Many Requests"
            override fun getRequestStatus() = 429
        }

        private val STORAGE_FULL = object : Response.IStatus {
            override fun getDescription() = "507 Insufficient Storage"
            override fun getRequestStatus() = 507
        }
    }
}
