package com.kobblo.gallery.file.explorer.transfer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kobblo.gallery.file.explorer.transfer.web.SharedFile
import com.kobblo.gallery.file.explorer.transfer.web.WebAsk
import com.kobblo.gallery.file.explorer.transfer.web.WebClient
import com.kobblo.gallery.file.explorer.transfer.web.WebOutcome
import com.kobblo.gallery.file.explorer.transfer.web.WebShareServer
import com.kobblo.gallery.file.explorer.transfer.web.WebShareToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/** Who says yes to what: the phone to a browser and what it sends, and the page to what the phone offers. */
class WebShareAskTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var server: WebShareServer
    private val token = WebShareToken.generate()
    private var now = 1_000_000L
    private var asksFirst = true

    private val asked = mutableListOf<WebAsk>()
    private val withdrawn = mutableListOf<WebAsk>()
    private val finished = mutableListOf<Pair<List<File>, Int>>()
    private val answers = mutableListOf<Pair<List<String>, Boolean>>()
    private val downloadedAll = mutableListOf<Int>()
    private val notes = mutableListOf<String>()
    private val hello = "hello".toByteArray()

    private val listener = object : WebShareServer.Listener {
        override fun onAsk(ask: WebAsk) { synchronized(asked) { asked += ask } }
        override fun onAskWithdrawn(ask: WebAsk) { synchronized(withdrawn) { withdrawn += ask } }
        override fun onBatchFinished(client: WebClient, saved: List<File>, expected: Int) { synchronized(finished) { finished += saved to expected } }
        override fun onOfferAnswered(client: WebClient, files: List<SharedFile>, accepted: Boolean) { synchronized(answers) { answers += files.map { it.name } to accepted } }
        override fun onOfferDownloaded(client: WebClient, count: Int) { synchronized(downloadedAll) { downloadedAll += count } }
        override fun onText(client: WebClient, text: String) { synchronized(notes) { notes += text } }
    }

    @Before fun start() {
        root = temp.newFolder("Received")
        server = WebShareServer(0, token, "Pixel", englishWebShareText, { root }, listener, clock = { now }, asksFirst = { asksFirst })
        server.setFiles(listOf(file(1, "a.txt"), file(2, "b.txt")))
        server.startListening()
    }

    @After fun stop() {
        server.stop()
    }

    private fun file(id: Int, name: String, note: String? = null) =
        SharedFile(id, name, hello.size.toLong(), "text/plain", note = note) { ByteArrayInputStream(hello) }

    private fun connect(path: String, method: String = "GET"): HttpURLConnection =
        (URL("http://127.0.0.1:${server.listeningPort}/$token/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 5000
        }

    private fun status(path: String, method: String = "GET") = connect(path, method).responseCode

    private fun post(path: String, json: String = "{}"): HttpURLConnection = connect(path, "POST").apply {
        doOutput = true
        outputStream.use { it.write(json.toByteArray()) }
    }

    private fun json(c: HttpURLConnection): JsonObject = JsonParser.parseString(c.inputStream.use { it.readBytes() }.decodeToString()).asJsonObject

    private fun askOpen(): JsonObject = json(post("ask", """{"kind":"open"}"""))

    private fun letIn() {
        askOpen()
        server.accept(asked.last().id)
    }

    private fun stateOf(id: String) = json(connect("ask/$id")).get("state").asString

    private fun askFiles(vararg files: Pair<String, Int>): JsonObject {
        val list = files.joinToString(",") { """{"name":"${it.first}","size":${it.second}}""" }
        return json(post("ask", """{"kind":"files","files":[$list]}"""))
    }

    private fun upload(name: String, data: ByteArray, ask: String?): HttpURLConnection {
        val query = "upload?name=" + URLEncoder.encode(name, "UTF-8") + (ask?.let { "&ask=$it" } ?: "")
        return connect(query, "POST").apply {
            doOutput = true
            setFixedLengthStreamingMode(data.size)
            runCatching { outputStream.use { it.write(data) } }
        }
    }

    private fun files() = root.walkTopDown().filter { it.isFile }.toList()

    // ── Letting a browser in ─────────────────────────────────────────────────

    @Test fun aNewBrowserGetsThePageAndNothingElse() {
        assertEquals(200, status(""))
        for (path in listOf("api/files", "d/1", "zip")) assertEquals("for $path", 403, status(path))
        assertEquals(403, upload("x.txt", hello, null).responseCode)
        assertEquals(403, post("ask", """{"kind":"files","files":[{"name":"x","size":1}]}""").responseCode)
        assertTrue(asked.isEmpty())
        assertTrue(files().isEmpty())
    }

    @Test fun allowingLetsItIn() {
        val answer = askOpen()
        assertEquals("waiting", answer.get("state").asString)
        assertEquals(WebAsk.Kind.OPEN, asked.single().kind)
        assertEquals("waiting", stateOf(answer.get("id").asString))
        assertEquals(403, status("api/files"))

        server.accept(answer.get("id").asString)
        assertEquals("accepted", stateOf(answer.get("id").asString))
        assertEquals(200, status("api/files"))
        assertTrue(server.clientList().single().allowed)
    }

    @Test fun openingThePageAgainWhileAskedDoesntAskAgain() {
        val first = askOpen().get("id").asString
        val second = askOpen().get("id").asString
        assertEquals(first, second)
        assertEquals(1, asked.size)
    }

    @Test fun onceInItIsntAskedAgain() {
        letIn()
        asked.clear()
        assertEquals("accepted", askOpen().get("state").asString)
        assertTrue(asked.isEmpty())
    }

    @Test fun rejectingKeepsItOutAndSlowsItDown() {
        val id = askOpen().get("id").asString
        server.reject(id)
        assertEquals("rejected", stateOf(id))
        assertEquals(403, status("api/files"))
        assertEquals(429, post("ask", """{"kind":"open"}""").responseCode)
        assertEquals(1, asked.size)
        now += 10_000
        assertEquals("waiting", askOpen().get("state").asString)
        assertEquals(2, asked.size)
    }

    @Test fun noAnswerKeepsItOutButItCanAskAgainStraightAway() {
        val id = askOpen().get("id").asString
        server.unanswered(id)
        assertEquals("unanswered", stateOf(id))
        assertEquals(403, status("api/files"))
        assertEquals("waiting", askOpen().get("state").asString)
    }

    // ── A browser sending to the phone ───────────────────────────────────────

    @Test fun anUploadWithoutAYesIsRefusedAndWritesNothing() {
        letIn()
        assertEquals(403, upload("nope.txt", hello, null).responseCode)
        assertEquals(403, upload("nope.txt", hello, "made-up").responseCode)
        assertTrue(files().isEmpty())
    }

    @Test fun anUploadWhileItsStillWaitingIsRefused() {
        letIn()
        val id = askFiles("a.txt" to hello.size).get("id").asString
        assertEquals("waiting", stateOf(id))
        assertEquals(403, upload("a.txt", hello, id).responseCode)
        assertTrue(files().isEmpty())
    }

    @Test fun rejectedMeansNothingOnDisk() {
        letIn()
        val id = askFiles("a.txt" to hello.size).get("id").asString
        server.reject(id)
        assertEquals("rejected", stateOf(id))
        assertEquals(403, upload("a.txt", hello, id).responseCode)
        assertTrue(files().isEmpty())
    }

    @Test fun unansweredMeansNothingOnDisk() {
        letIn()
        val id = askFiles("a.txt" to hello.size).get("id").asString
        server.unanswered(id)
        assertEquals("unanswered", stateOf(id))
        assertEquals(403, upload("a.txt", hello, id).responseCode)
        assertTrue(files().isEmpty())
    }

    @Test fun whatIsAskedIsWhatThePhoneSees() {
        letIn()
        askFiles("Trip/a.jpg" to 3, "b.jpg" to 4)
        val ask = asked.last()
        assertEquals(WebAsk.Kind.FILES, ask.kind)
        assertEquals(listOf("Trip/a.jpg" to 3L, "b.jpg" to 4L), ask.files.map { it.name to it.size })
        assertEquals(7L, ask.bytes)
    }

    @Test fun aYesCoversExactlyWhatWasAsked() {
        letIn()
        val id = askFiles("a.txt" to hello.size, "b.txt" to 3).get("id").asString
        server.accept(id)
        assertEquals("accepted", stateOf(id))
        // Another name, another size, or the same one twice is not what was agreed to.
        assertEquals(403, upload("other.txt", hello, id).responseCode)
        assertEquals(403, upload("a.txt", byteArrayOf(1), id).responseCode)
        assertEquals(200, upload("a.txt", hello, id).responseCode)
        assertEquals(403, upload("a.txt", hello, id).responseCode)
        assertEquals(1, files().size)
        assertTrue(finished.isEmpty())

        assertEquals(200, upload("b.txt", byteArrayOf(1, 2, 3), id).responseCode)
        assertEquals(2, files().size)
        val done = finished.single()
        assertEquals(2, done.first.size)
        assertEquals(2, done.second)
    }

    @Test fun aFailedUploadCanGoAgainUnderTheSameYes() {
        letIn()
        val id = askFiles("cut.bin" to 1000).get("id").asString
        server.accept(id)
        val partial = "POST /$token/upload?name=cut.bin&ask=$id HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\nshort"
        java.net.Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.getOutputStream().write(partial.toByteArray())
            socket.getOutputStream().flush()
        }
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && upload("cut.bin", ByteArray(1000), id).responseCode != 200) Thread.sleep(50)
        assertEquals(1000L, File(root, "cut.bin").length())
        assertEquals(1, finished.size)
    }

    @Test fun aBatchLeftHalfDoneEndsWithWhatArrived() {
        letIn()
        val id = askFiles("a.txt" to hello.size, "b.txt" to 3).get("id").asString
        server.accept(id)
        assertEquals(200, upload("a.txt", hello, id).responseCode)
        now += 59_000
        server.sweep()
        assertTrue(finished.isEmpty())
        now += 1_000
        server.sweep()
        assertEquals(1, finished.single().first.size)
        assertEquals(2, finished.single().second)
        assertEquals(403, upload("b.txt", byteArrayOf(1, 2, 3), id).responseCode)
    }

    @Test fun aPageThatStopsAskingIsTakenToHaveGone() {
        letIn()
        val id = askFiles("a.txt" to hello.size).get("id").asString
        now += 9_000
        assertEquals("waiting", stateOf(id))
        now += 9_000
        server.sweep()
        assertTrue(withdrawn.isEmpty())
        now += 1_000
        server.sweep()
        assertEquals(id, withdrawn.single().id)
        assertEquals("withdrawn", stateOf(id))
    }

    @Test fun thePageCanCancelWhileItWaits() {
        letIn()
        val id = askFiles("a.txt" to hello.size).get("id").asString
        assertEquals(200, post("ask/$id/cancel").responseCode)
        assertEquals("withdrawn", stateOf(id))
        assertEquals(id, withdrawn.single().id)
        server.accept(id)
        assertEquals(403, upload("a.txt", hello, id).responseCode)
    }

    @Test fun aNewerQuestionReplacesAnOlderOne() {
        letIn()
        val first = askFiles("a.txt" to 1).get("id").asString
        val second = askFiles("b.txt" to 1).get("id").asString
        assertEquals(first, withdrawn.single().id)
        assertEquals("waiting", stateOf(second))
    }

    @Test fun stoppingEndsWhatsBeingAsked() {
        val id = askOpen().get("id").asString
        server.stop()
        assertEquals(id, withdrawn.single().id)
    }

    @Test fun moreThanThereIsRoomForIsRefusedWithoutAsking() {
        letIn()
        val asksBefore = asked.size
        val c = post("ask", """{"kind":"files","files":[{"name":"huge","size":${Long.MAX_VALUE}}]}""")
        assertEquals(507, c.responseCode)
        assertEquals(asksBefore, asked.size)
    }

    @Test fun nonsenseIsRefused() {
        letIn()
        for (body in listOf("""{"kind":"files"}""", """{"kind":"files","files":[]}""", """{"kind":"files","files":[{"name":"","size":1}]}""", """{"kind":"files","files":[{"name":"a","size":-1}]}""", """{"kind":"what"}""", "[]", "nonsense")) {
            assertEquals("for $body", 400, post("ask", body).responseCode)
        }
    }

    @Test fun dontAskAgainLastsOnlyAsLongAsThisServer() {
        letIn()
        server.trust("127.0.0.1")
        asked.clear()
        val answer = askFiles("a.txt" to hello.size)
        assertEquals("accepted", answer.get("state").asString)
        assertTrue(asked.isEmpty())
        assertEquals(200, upload("a.txt", hello, answer.get("id").asString).responseCode)

        // Web Share stopping and starting is a new server, which knows nobody.
        val port = server.listeningPort
        server.stop()
        server = WebShareServer(port, WebShareToken.generate(), "Pixel", englishWebShareText, { root }, listener, clock = { now })
        server.startListening()
        assertTrue(server.clientList().isEmpty())
    }

    @Test fun theSettingNotToAskCoversWhatsSentButNotLettingABrowserIn() {
        asksFirst = false
        assertEquals("waiting", askOpen().get("state").asString)
        server.accept(asked.last().id)
        assertEquals("accepted", askFiles("a.txt" to hello.size).get("state").asString)
    }

    @Test fun aNoteAsksBeforeTheWordsCome() {
        letIn()
        val c = connect("text", "POST").apply { doOutput = true; outputStream.use { it.write(hello) } }
        assertEquals(403, c.responseCode)
        assertTrue(notes.isEmpty())

        val answer = json(post("ask", """{"kind":"note","size":${hello.size}}"""))
        val id = answer.get("id").asString
        assertEquals(WebAsk.Kind.NOTE, asked.last().kind)
        val early = connect("text?ask=$id", "POST").apply { doOutput = true; outputStream.use { it.write(hello) } }
        assertEquals(403, early.responseCode)
        assertTrue(notes.isEmpty())

        server.accept(id)
        val sent = connect("text?ask=$id", "POST").apply { doOutput = true; outputStream.use { it.write(hello) } }
        assertEquals(200, sent.responseCode)
        assertEquals(listOf("hello"), notes)
        // Said once. The yes doesn't go on working.
        val again = connect("text?ask=$id", "POST").apply { doOutput = true; outputStream.use { it.write(hello) } }
        assertEquals(403, again.responseCode)
        assertEquals(1, notes.size)
        assertTrue(files().isEmpty())
    }

    // ── The phone sending to a page ──────────────────────────────────────────

    private fun list() = json(connect("api/files"))

    @Test fun whatsOnOfferIsAQuestionNotAList() {
        letIn()
        val list = list()
        assertEquals(0, list.getAsJsonArray("files").size())
        val offer = list.getAsJsonObject("offer")
        assertEquals(2, offer.get("count").asInt)
        assertEquals(hello.size * 2L, offer.get("bytes").asLong)
        assertEquals(listOf("a.txt", "b.txt"), offer.getAsJsonArray("names").map { it.asString })
        assertEquals(403, status("d/1"))
        assertEquals(403, status("zip"))
    }

    @Test fun acceptingMakesItDownloadableAndTheyreTold() {
        letIn()
        assertEquals(200, post("offer", """{"accept":[1,2]}""").responseCode)
        val list = list()
        assertNull(list.get("offer"))
        assertEquals(2, list.getAsJsonArray("files").size())
        assertEquals(listOf(listOf("a.txt", "b.txt") to true), answers)
        assertEquals(WebOutcome.Kind.ACCEPTED, server.clientList().single().outcome?.kind)

        assertEquals(200, status("d/1"))
        connect("d/1").inputStream.use { it.readBytes() }
        assertTrue(downloadedAll.isEmpty())
        connect("d/2").inputStream.use { it.readBytes() }
        awaitUntil { downloadedAll.isNotEmpty() }
        assertEquals(listOf(2), downloadedAll)
        assertEquals(WebOutcome(WebOutcome.Kind.DOWNLOADED, 2), server.clientList().single().outcome)
    }

    @Test fun rejectingKeepsItFromThePageAndTheyreTold() {
        letIn()
        post("offer", """{"reject":[1,2]}""").responseCode
        val list = list()
        assertNull(list.get("offer"))
        assertEquals(0, list.getAsJsonArray("files").size())
        assertEquals(2, list.get("rejected").asInt)
        assertEquals(403, status("d/1"))
        assertEquals(403, status("zip"))
        assertEquals(listOf(listOf("a.txt", "b.txt") to false), answers)
        assertEquals(WebOutcome(WebOutcome.Kind.REJECTED, 2), server.clientList().single().outcome)
    }

    @Test fun aRejectionCanBeTakenBack() {
        letIn()
        post("offer", """{"reject":[1,2]}""").responseCode
        post("offer", """{"undo":true}""").responseCode
        assertEquals(2, list().getAsJsonObject("offer").get("count").asInt)
    }

    @Test fun whatsAddedLaterIsAskedAboutOnItsOwn() {
        letIn()
        post("offer", """{"accept":[1,2]}""").responseCode
        server.setFiles(listOf(file(1, "a.txt"), file(2, "b.txt"), file(3, "c.txt")))
        val list = list()
        assertEquals(listOf(3), list.getAsJsonObject("offer").getAsJsonArray("ids").map { it.asInt })
        assertEquals(2, list.getAsJsonArray("files").size())
        assertEquals(403, status("d/3"))
    }

    @Test fun anOffersZipHasOnlyWhatItSaid() {
        letIn()
        server.setFiles(listOf(file(1, "a.txt"), file(2, "b.txt"), file(3, "c.txt")))
        post("offer", """{"accept":[1,2,3]}""").responseCode
        val names = ArrayList<String>()
        ZipInputStream(connect("zip?ids=2,3").inputStream).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("b.txt", "c.txt"), names)
    }

    @Test fun aZipCantReachWhatWasntAccepted() {
        letIn()
        post("offer", """{"accept":[1]}""").responseCode
        val names = ArrayList<String>()
        ZipInputStream(connect("zip?ids=1,2").inputStream).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(listOf("a.txt"), names)
    }

    @Test fun aNoteOnOfferStaysHiddenUntilAccepted() {
        letIn()
        server.setFiles(listOf(file(1, "Note.txt", note = "secret words")))
        val before = connect("api/files").inputStream.use { it.readBytes() }.decodeToString()
        assertFalse(before, "secret words" in before)
        assertEquals(1, JsonParser.parseString(before).asJsonObject.getAsJsonObject("offer").get("notes").asInt)
        post("offer", """{"accept":[1]}""").responseCode
        assertTrue("secret words" in connect("api/files").inputStream.use { it.readBytes() }.decodeToString())
        assertTrue("a note has nothing to download", downloadedAll.isEmpty())
    }

    // ── Staying open by itself ───────────────────────────────────────────────

    @Test fun anOpenPageLookingForNewFilesDoesntKeepItAwake() {
        letIn()
        now += 5000
        assertTrue(server.isIdle(1000))
        assertEquals(200, status("api/files"))
        assertTrue("a forgotten tab mustn't hold the link open", server.isIdle(1000))
    }

    @Test fun aPageWaitingForAnAnswerDoesKeepItAwake() {
        letIn()
        val id = askFiles("a.txt" to 1).get("id").asString
        now += 5000
        assertTrue(server.isIdle(1000))
        stateOf(id)
        assertFalse(server.isIdle(1000))
    }

    private fun awaitUntil(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3000
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }
}
