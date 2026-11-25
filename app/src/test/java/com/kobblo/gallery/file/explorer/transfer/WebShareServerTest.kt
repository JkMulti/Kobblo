package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.web.SharedFile
import com.kobblo.gallery.file.explorer.transfer.web.WebAsk
import com.kobblo.gallery.file.explorer.transfer.web.WebClient
import com.kobblo.gallery.file.explorer.transfer.web.WebSharePage
import com.kobblo.gallery.file.explorer.transfer.web.WebShareServer
import com.kobblo.gallery.file.explorer.transfer.web.WebShareToken
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Runs the real server on a real socket, so these cover what a browser would actually hit. Here the person with the
 * phone says yes to everything, and this browser has accepted what's on offer. WebShareAskTest is about asking.
 */
class WebShareServerTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: WebShareServer
    private lateinit var root: File
    private val token = WebShareToken.generate()
    private val hello = "hello world".toByteArray()
    private val photo = ByteArray(300_000) { (it % 251).toByte() }
    private val uploads = mutableListOf<String>()
    private val downloads = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private var now = 1_000_000L

    @Before fun start() {
        root = temp.newFolder("Received")
        val listener = object : WebShareServer.Listener {
            override fun onUploaded(client: WebClient, saved: File, relativeName: String, bytes: Long) {
                synchronized(uploads) { uploads += relativeName }
            }
            override fun onDownloaded(client: WebClient, file: SharedFile) {
                synchronized(downloads) { downloads += file.name }
            }
            override fun onUploadFailed(client: WebClient, name: String, reason: String) {
                synchronized(failures) { failures += reason }
            }
            override fun onText(client: WebClient, text: String) {
                synchronized(notes) { notes += text }
            }
            override fun onAsk(ask: WebAsk) {
                server.accept(ask.id)
            }
        }
        server = WebShareServer(0, token, "Pixel <8>", englishWebShareText, { root }, listener, clock = { now })
        server.setFiles(
            listOf(
                SharedFile(1, "hello.txt", hello.size.toLong(), "text/plain") { ByteArrayInputStream(hello) },
                SharedFile(2, "Camera/trip/photo.jpg", photo.size.toLong(), "image/jpeg") { ByteArrayInputStream(photo) },
                SharedFile(3, "dupe.txt", 1, "text/plain") { ByteArrayInputStream(byteArrayOf(1)) },
                SharedFile(4, "dupe.txt", 1, "text/plain") { ByteArrayInputStream(byteArrayOf(2)) }
            )
        )
        server.startListening()
        postJson("/$token/ask", """{"kind":"open"}""")
        acceptOffer(1, 2, 3, 4)
    }

    @After fun stop() {
        server.stop()
    }

    private val base get() = "http://127.0.0.1:${server.listeningPort}"

    private fun connect(path: String, method: String = "GET"): HttpURLConnection =
        (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 3000
            readTimeout = 5000
        }

    private fun status(path: String, method: String = "GET") = connect(path, method).responseCode

    private fun body(path: String) = connect(path).inputStream.use { it.readBytes() }

    private fun postJson(path: String, json: String): String {
        val c = connect(path, "POST")
        c.doOutput = true
        c.outputStream.use { it.write(json.toByteArray()) }
        return c.inputStream.use { it.readBytes() }.decodeToString()
    }

    private fun acceptOffer(vararg ids: Int) {
        postJson("/$token/offer", """{"accept":[${ids.joinToString(",")}]}""")
    }

    /** What the page does first: says what's coming, and gets back the id of the yes. */
    private fun askToSend(name: String, size: Int): String {
        val quoted = com.google.gson.Gson().toJson(name)
        val answer = postJson("/$token/ask", """{"kind":"files","files":[{"name":$quoted,"size":$size}]}""")
        return com.google.gson.JsonParser.parseString(answer).asJsonObject.get("id").asString
    }

    private fun upload(name: String, data: ByteArray): HttpURLConnection {
        val ask = askToSend(name, data.size)
        return connect("/$token/upload?name=${java.net.URLEncoder.encode(name, "UTF-8")}&ask=$ask", "POST").apply {
            doOutput = true
            setFixedLengthStreamingMode(data.size)
            outputStream.use { it.write(data) }
        }
    }

    /** Sends a request exactly as written, because a URL class would tidy up the attacks. */
    private fun raw(request: String): String = Socket("127.0.0.1", server.listeningPort).use { socket ->
        socket.soTimeout = 4000
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        socket.getInputStream().bufferedReader().readLine().orEmpty()
    }

    // ── The token ────────────────────────────────────────────────────────────

    @Test fun tokensAreLongAndDifferent() {
        val a = WebShareToken.generate()
        assertTrue(a.length >= 32)
        assertNotEquals(a, WebShareToken.generate())
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun tokenComparison() {
        assertTrue(WebShareToken.matches("abc", "abc"))
        assertFalse(WebShareToken.matches("abc", "abd"))
        assertFalse(WebShareToken.matches("abc", "ab"))
        assertFalse(WebShareToken.matches("abc", null))
    }

    @Test fun nothingWorksWithoutTheToken() {
        for (path in listOf("/", "/api/files", "/d/1", "/zip", "/upload", "/wrong/", "/wrong/d/1", "/${token.dropLast(1)}/d/1", "/${token}x/d/1")) {
            assertEquals("for $path", 404, status(path))
        }
        assertEquals(404, status("/wrong/upload", "POST"))
    }

    @Test fun aWrongTokenLooksLikeAMissingPage() {
        val wrong = connect("/wrong/d/1").let { it.responseCode to it.errorStream.readBytes().decodeToString() }
        val missing = connect("/$token/nope").let { it.responseCode to it.errorStream.readBytes().decodeToString() }
        assertEquals(missing, wrong)
    }

    @Test fun aWrongTokenDoesntCountAsAClient() {
        val other = WebShareServer(0, token, "Pixel", englishWebShareText, { root })
        other.startListening()
        try {
            val base = "http://127.0.0.1:${other.listeningPort}"
            assertEquals(404, (URL("$base/wrong/d/1").openConnection() as HttpURLConnection).responseCode)
            assertTrue(other.clientList().isEmpty())
            assertEquals(200, (URL("$base/$token/").openConnection() as HttpURLConnection).responseCode)
            assertEquals(1, other.clientList().size)
        } finally {
            other.stop()
        }
    }

    @Test fun tokenlessPageRedirectsToTheSlash() {
        val c = connect("/$token")
        assertEquals(301, c.responseCode)
        assertEquals("/$token/", c.getHeaderField("Location"))
    }

    // ── The page and list ────────────────────────────────────────────────────

    @Test fun pageLoadsAndEscapesTheDeviceName() {
        val html = body("/$token/").decodeToString()
        assertTrue(html.contains("<title>Pixel &lt;8&gt;</title>"))
        assertFalse(html.contains("Pixel <8>"))
        assertFalse("no external assets", Regex("(src|href)=\"https?://").containsMatchIn(html))
    }

    @Test fun pageCantLoadAnythingFromElsewhere() {
        val csp = connect("/$token/").getHeaderField("Content-Security-Policy")
        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("connect-src 'self'"))
    }

    @Test fun thePagesWordsAreEscapedAndItsScriptsAreSafeToInline() {
        val words = englishWebShareText.copy(
            language = "ar",
            rightToLeft = true,
            page = mapOf("SEND" to "Send <now>", "DROP" to "%1\$s or drop & go", "CHOOSE_FILES" to "Choose \"these\""),
            script = mapOf("waiting" to "</script><script>alert(1)</script>", "files" to mapOf("one" to "%1\$d file", "other" to "%1\$d files"))
        )
        val html = WebSharePage.html("__SEND__", words)
        assertTrue(html.contains("<html lang=\"ar\" dir=\"rtl\">"))
        assertTrue(html.contains(">Send &lt;now&gt;</button>"))
        assertTrue(html.contains("<strong>Choose &quot;these&quot;</strong> or drop &amp; go"))
        // A device name that looks like a place for words is only a name.
        assertTrue(html.contains("<title>__SEND__</title>"))
        assertFalse(html.contains("</script><script>alert"))
        assertTrue(html.contains("\"lang\":\"ar\""))
    }

    @Test fun escapeHtmlCoversAttributeBreakouts() {
        assertEquals("&lt;a b=&quot;c&#39;&amp;&gt;", WebSharePage.escapeHtml("<a b=\"c'&>"))
    }

    @Test fun listShowsOnlyChosenFiles() {
        val json = body("/$token/api/files").decodeToString()
        assertTrue(json.contains("hello.txt"))
        assertTrue(json.contains("Camera/trip/photo.jpg"))
        assertFalse("no paths on the phone leak", json.contains(root.path))
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    @Test fun downloadsAFile() {
        val c = connect("/$token/d/2")
        assertEquals(200, c.responseCode)
        assertEquals("300000", c.getHeaderField("Content-Length"))
        assertArrayEquals(photo, c.inputStream.use { it.readBytes() })
        c.disconnect()
        assertTrue(c.getHeaderField("Content-Disposition").startsWith("attachment"))
        assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"))
    }

    @Test fun downloadsAreAttachmentsSoAnHtmlFileCantRunHere() {
        assertTrue(connect("/$token/d/1").getHeaderField("Content-Disposition").contains("attachment"))
    }

    @Test fun headTellsTheSizeWithoutTheBody() {
        val c = connect("/$token/d/2", "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals("300000", c.getHeaderField("Content-Length"))
        assertEquals(0, c.inputStream.use { it.readBytes() }.size)
    }

    @Test fun resumesWithARange() {
        val c = connect("/$token/d/2").apply { setRequestProperty("Range", "bytes=1000-1999") }
        assertEquals(206, c.responseCode)
        assertEquals("bytes 1000-1999/300000", c.getHeaderField("Content-Range"))
        assertArrayEquals(photo.copyOfRange(1000, 2000), c.inputStream.use { it.readBytes() })
    }

    @Test fun openEndedAndSuffixRanges() {
        val open = connect("/$token/d/2").apply { setRequestProperty("Range", "bytes=299990-") }
        assertArrayEquals(photo.copyOfRange(299990, 300000), open.inputStream.use { it.readBytes() })
        val suffix = connect("/$token/d/2").apply { setRequestProperty("Range", "bytes=-5") }
        assertArrayEquals(photo.copyOfRange(299995, 300000), suffix.inputStream.use { it.readBytes() })
    }

    @Test fun impossibleRangeIsRefused() {
        val c = connect("/$token/d/2").apply { setRequestProperty("Range", "bytes=999999-") }
        assertEquals(416, c.responseCode)
    }

    @Test fun unknownIdsAreNotFound() {
        for (id in listOf("0", "5", "-1", "abc", "1.0", "99999999999999999999")) {
            assertEquals("for $id", 404, status("/$token/d/$id"))
        }
    }

    @Test fun zipHasEverythingWithPathsAndUniqueNames() {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(connect("/$token/zip").inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("hello.txt", "Camera/trip/photo.jpg", "dupe.txt", "dupe (2).txt"), entries.keys)
        assertArrayEquals(photo, entries["Camera/trip/photo.jpg"])
        assertArrayEquals(byteArrayOf(2), entries["dupe (2).txt"])
    }

    @Test fun zipNotesWhatItCouldNotRead() {
        server.setFiles(
            listOf(
                SharedFile(1, "good.txt", 1, "text/plain") { ByteArrayInputStream(byteArrayOf(1)) },
                SharedFile(2, "gone.txt", 1, "text/plain") { throw java.io.FileNotFoundException("gone") }
            )
        )
        val names = ArrayList<String>()
        var note = ""
        ZipInputStream(connect("/$token/zip").inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                val data = zip.readBytes()
                if (entry.name.startsWith("Files that")) note = data.decodeToString()
            }
        }
        assertTrue(names.contains("good.txt"))
        assertEquals("gone.txt", note)
    }

    @Test fun aFileWhoseShareGrantEndedDoesntBreakAnything() {
        server.setFiles(
            listOf(
                SharedFile(1, "good.txt", 1, "text/plain") { ByteArrayInputStream(byteArrayOf(1)) },
                SharedFile(2, "revoked.txt", 1, "text/plain") { throw SecurityException("grant ended") }
            )
        )
        assertEquals(410, status("/$token/d/2"))
        val names = ArrayList<String>()
        ZipInputStream(connect("/$token/zip").inputStream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        assertEquals(listOf("good.txt", "Files that could not be read.txt"), names)
    }

    // ── Traversal ────────────────────────────────────────────────────────────

    @Test fun traversalInRequestsGetsNothing() {
        File(temp.root, "secret.txt").writeText("secret")
        val attacks = listOf(
            "/$token/d/../../secret.txt",
            "/$token/d/..%2f..%2fsecret.txt",
            "/$token/d/%2e%2e%2f%2e%2e%2fsecret.txt",
            "/$token/d/..\\..\\secret.txt",
            "/$token/../secret.txt",
            "/$token/d//etc/passwd",
            "/../$token/d/1/../../../etc/passwd",
            "/$token/d/1/../../etc/passwd",
            "/$token/d/1%00.txt",
            "/$token/%2e%2e/%2e%2e/%2e%2e/etc/passwd"
        )
        for (attack in attacks) {
            val line = raw("GET $attack HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
            assertFalse("$attack gave $line", line.contains(" 200 "))
        }
    }

    @Test fun uploadNamesCantEscapeTheReceivedFolder() {
        val parent = root.parentFile!!
        val names = listOf(
            "../evil1.txt",
            "../../evil2.txt",
            "/etc/evil3.txt",
            "..\\..\\evil4.txt",
            "%2e%2e%2fevil5.txt",
            "a/../../../evil6.txt"
        )
        for (name in names) {
            assertEquals("for $name", 200, upload(name, "x".toByteArray()).responseCode)
        }
        val outside = parent.walkTopDown().filter { it.isFile && !it.canonicalPath.startsWith(root.canonicalPath) }.toList()
        assertEquals(emptyList<File>(), outside)
    }

    // ── Uploads ──────────────────────────────────────────────────────────────

    @Test fun uploadLandsInTheReceivedFolder() {
        val data = ByteArray(200_000) { it.toByte() }
        val c = upload("notes.bin", data)
        assertEquals(200, c.responseCode)
        assertTrue(c.inputStream.readBytes().decodeToString().contains("notes.bin"))
        assertArrayEquals(data, File(root, "notes.bin").readBytes())
        assertEquals(listOf("notes.bin"), uploads)
    }

    @Test fun uploadNeverOverwrites() {
        File(root, "a.txt").writeText("original")
        assertEquals(200, upload("a.txt", "new".toByteArray()).responseCode)
        assertEquals("original", File(root, "a.txt").readText())
        assertEquals("new", File(root, "a (2).txt").readText())
    }

    @Test fun interruptedUploadLeavesNothing() {
        val ask = askToSend("cut.bin", 100000)
        val line = "POST /$token/upload?name=cut.bin&ask=$ask HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\nonly a little"
        Socket("127.0.0.1", server.listeningPort).use { socket ->
            socket.getOutputStream().write(line.toByteArray())
            socket.getOutputStream().flush()
        } // Closing mid-body is the interruption.
        val deadline = System.currentTimeMillis() + 5000
        while (failures.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(listOf("The upload was interrupted"), failures)
        assertEquals(emptyList<File>(), root.walkTopDown().filter { it.isFile }.toList())
    }

    @Test fun uploadWithoutALengthIsRefused() {
        val ask = askToSend("x", 1)
        val line = raw("POST /$token/upload?name=x&ask=$ask HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
        assertTrue(line, line.contains(" 411 "))
    }

    @Test fun getCantUpload() {
        assertEquals(404, status("/$token/upload?name=x"))
    }

    @Test fun anUploadOfNothingIsAnEmptyFile() {
        assertEquals(200, upload("empty.txt", ByteArray(0)).responseCode)
        assertEquals(0, File(root, "empty.txt").length())
    }

    // ── Clients and idleness ─────────────────────────────────────────────────

    @Test fun aBrowserIsDescribedFromItsFirstRequest() {
        val other = WebShareServer(0, token, "Pixel", englishWebShareText, { root })
        other.startListening()
        try {
            val c = URL("http://127.0.0.1:${other.listeningPort}/$token/").openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X) Chrome/1.0 Safari/1.0")
            assertEquals(200, c.responseCode)
            assertEquals("Chrome on Mac", other.clientList().single().userAgent)
        } finally {
            other.stop()
        }
    }

    @Test fun clientsAreTrackedWithWhatTheyDid() {
        connect("/$token/d/1").inputStream.use { it.readBytes() }
        upload("u.txt", "abc".toByteArray()).responseCode
        val deadline = System.currentTimeMillis() + 3000
        while (server.clientList().firstOrNull()?.downloads != 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val client = server.clientList().single()
        assertEquals("127.0.0.1", client.address)
        assertEquals(1, client.downloads)
        assertEquals(1, client.uploads)
        assertEquals(hello.size.toLong(), client.bytesSent)
        assertEquals(3L, client.bytesReceived)
        assertEquals(listOf("hello.txt"), downloads)
    }

    @Test fun becomesIdleOnlyAfterTheTimeout() {
        status("/$token/")
        assertFalse(server.isIdle(1000))
        now += 999
        assertFalse(server.isIdle(1000))
        now += 1
        assertTrue(server.isIdle(1000))
    }

    @Test fun aRequestResetsIdleness() {
        now += 5000
        assertTrue(server.isIdle(1000))
        status("/$token/")
        assertFalse(server.isIdle(1000))
    }

    @Test fun aWrongTokenDoesntKeepItAwake() {
        now += 5000
        status("/wrong/")
        assertTrue(server.isIdle(1000))
    }

    @Test fun aDownloadInProgressKeepsItAwake() {
        val big = SharedFile(9, "big.bin", 50_000_000, "application/octet-stream") { ByteArrayInputStream(ByteArray(50_000_000)) }
        server.setFiles(listOf(big))
        acceptOffer(9)
        val c = connect("/$token/d/9")
        val input = c.inputStream
        input.read(ByteArray(1024))
        now += 60_000
        assertFalse("still sending", server.isIdle(1000))
        c.disconnect()
        input.close()
    }

    private fun postText(path: String, body: ByteArray): Int {
        val c = connect(path, "POST")
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        return try {
            c.outputStream.use { it.write(body) }
            c.responseCode
        } catch (e: java.io.IOException) {
            // Refused before the body was read, so the socket can close mid-write.
            -1
        } finally {
            c.disconnect()
        }
    }

    private fun askToSay(size: Int): String {
        val answer = postJson("/$token/ask", """{"kind":"note","size":$size}""")
        return com.google.gson.JsonParser.parseString(answer).asJsonObject.get("id").asString
    }

    @Test fun aNoteFromThePageArrivesAsWords() {
        val words = "see https://example.com — é".toByteArray()
        assertEquals(200, postText("/$token/text?ask=${askToSay(words.size)}", words))
        assertEquals(listOf("see https://example.com — é"), notes)
        assertTrue("nothing was saved", root.listFiles().isNullOrEmpty())
    }

    @Test fun aNoteNeedsTheToken() {
        assertEquals(404, postText("/wrong/text", "hello".toByteArray()))
        assertTrue(notes.isEmpty())
    }

    @Test fun anEnormousNoteIsRefused() {
        val asked = connect("/$token/ask", "POST").apply {
            doOutput = true
            outputStream.use { it.write("""{"kind":"note","size":${200 * 1024}}""".toByteArray()) }
        }
        assertEquals(413, asked.responseCode)
        // One that said it was small isn't taken at its word either.
        assertNotEquals(200, postText("/$token/text?ask=${askToSay(5)}", ByteArray(200 * 1024)))
        assertTrue(notes.isEmpty())
    }

    @Test fun aSharedNoteIsShownAsWordsNotOfferedAsAFile() {
        server.setFiles(
            listOf(
                SharedFile(1, "Note.txt", 5, "text/plain", note = "hello") { ByteArrayInputStream("hello".toByteArray()) },
                SharedFile(2, "a.txt", 1, "text/plain") { ByteArrayInputStream(byteArrayOf(1)) }
            )
        )
        val list = connect("/$token/api/files").inputStream.bufferedReader().readText()
        assertTrue(list, "\"notes\":[\"hello\"]" in list)
        assertFalse(list, "Note.txt" in list)
        assertTrue(list, "a.txt" in list)
    }

    @Test fun aFolderUploadKeepsItsPath() {
        assertEquals(200, upload("Trip/day 1/a.txt", byteArrayOf(1, 2, 3)).responseCode)
        assertTrue(File(root, "Trip/day 1/a.txt").isFile)
    }

    @Test fun tooManyConnectionsAreClosedRatherThanCostingAThreadEach() {
        val held = List(48) { Socket("127.0.0.1", server.listeningPort) }
        Thread.sleep(300)
        val extra = Socket("127.0.0.1", server.listeningPort).apply { soTimeout = 3000 }
        assertEquals(-1, extra.getInputStream().read())
        extra.close()
        held.forEach { it.close() }
        Thread.sleep(300)
        assertEquals(200, status("/$token/"))
    }

    @Test fun stoppedServerRefusesConnections() {
        val port = server.listeningPort
        server.stop()
        val refused = try {
            Socket("127.0.0.1", port).close()
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue(refused)
    }
}
