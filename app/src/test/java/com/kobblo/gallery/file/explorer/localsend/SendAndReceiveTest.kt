package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.core.NonceMemory
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.core.SenderProof
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The real sender talking to the real server over a real socket on this machine, with stand-ins only for the
 * two phones. These are whole exchanges, as two devices would have them: what one half writes is what the
 * other half has to understand.
 */
class SendAndReceiveTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var receiver: FakeReceiveHost
    private var server: LocalSendServer? = null
    private var port = 0

    private val hello = "hello world".toByteArray()
    private val photo = ByteArray(300_000) { (it % 251).toByte() }
    private val video = ByteArray(3_000_000) { (it * 31 % 253).toByte() }
    private val files = mapOf("/src/hello.txt" to hello, "/src/photo.jpg" to photo, "/src/video.mp4" to video, "/src/empty" to ByteArray(0))
    private val modified = 1_714_557_600_000L

    @Before fun setUp() {
        root = temp.newFolder("Received")
        receiver = FakeReceiveHost(root)
    }

    @After fun tearDown() {
        server?.stop()
    }

    private fun listen(identity: DeviceIdentity? = null) {
        val tls = identity?.let { own -> { own.serverSocketFactory() } }
        val started = LocalSendServer(ReceiveDesk(receiver), tls = tls, preferredPort = 0)
        port = started.start()!!
        server = started
    }

    private fun peer(protocol: String = "http", fingerprint: String = "RECEIVER") =
        NearbyPeer("127.0.0.1", port, "Receiver", null, "mobile", protocol, fingerprint, 0)

    private fun item(name: String, path: String = "/src/${name.substringAfterLast('/')}", modifiedMs: Long = 0) = TransferFileItem(
        fileId = "id-$name",
        fileName = name,
        size = files.getValue(path).size.toLong(),
        fileType = "application/octet-stream",
        localPath = path,
        modifiedMs = modifiedMs
    )

    private fun send(client: LocalSendClient, to: NearbyPeer, vararg items: TransferFileItem): TransferSession =
        runBlocking { client.send(to, items.toList()) }

    private fun eventually(what: String, holds: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!holds()) {
            if (System.currentTimeMillis() > deadline) fail("Never happened: $what")
            Thread.sleep(20)
        }
    }

    private fun received(): Set<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()

    // ---- over plain HTTP, as with any device that proves nothing ----

    @Test fun filesArriveWholeUnderTheNamesTheyWereSentWith() {
        listen()
        val client = LocalSendClient(FakeSendHost(files))

        val ended = send(client, peer(), item("hello.txt"), item("Camera/trip/photo.jpg"), item("video.mp4"), item("empty"))

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertNull(ended.errorMessage)
        assertTrue(ended.files.all { it.isFinished })
        assertEquals(ended.totalBytes, ended.bytesTransferred)
        assertEquals(ended, client.activeSession.value)
        assertTrue(receiver.awaitEnd())
        assertArrayEquals(hello, File(root, "hello.txt").readBytes())
        assertArrayEquals(photo, File(root, "Camera/trip/photo.jpg").readBytes())
        assertArrayEquals(video, File(root, "video.mp4").readBytes())
        assertEquals(0L, File(root, "empty").length())
        assertEquals("completed 4", receiver.told.last())

        val question = receiver.asked.single()
        assertEquals("Sender", question.sender.alias)
        assertEquals(ended.sessionId, question.sessionId)
        assertFalse(question.proven)
        assertEquals(listOf("hello.txt", "Camera/trip/photo.jpg", "video.mp4", "empty"), question.files.map { it.fileName })
    }

    @Test fun aSendThatIsDeclinedEndsAsRejectedWithNothingSent() {
        listen()
        receiver.answer = PromptAnswer.REJECT

        val ended = send(LocalSendClient(FakeSendHost(files)), peer(), item("hello.txt"))

        assertEquals(TransferStatus.REJECTED, ended.status)
        assertEquals(words(R.string.transfer_declined_by, "Receiver"), ended.errorMessage)
        assertFalse(ended.connectionLost)
        assertEquals(0, ended.finishedFiles)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun aSendNobodyAnsweredSaysSoAndIsNotARejection() {
        listen()
        receiver.answer = PromptAnswer.UNANSWERED

        val ended = send(LocalSendClient(FakeSendHost(files)), peer(), item("hello.txt"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(words(R.string.transfer_not_answered_by, "Receiver"), ended.errorMessage)
        assertFalse(ended.connectionLost)
    }

    @Test fun aReceiverThatIsBusySaysSo() {
        listen()
        receiver.busy = true

        val ended = send(LocalSendClient(FakeSendHost(files)), peer(), item("hello.txt"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(words(R.string.transfer_peer_busy, "Receiver"), ended.errorMessage)
        assertTrue(receiver.asked.isEmpty())
    }

    @Test fun aNoteIsDeliveredInTheOfferItself() {
        listen()
        val note = TransferFileItem(fileId = "n", fileName = "Note.txt", size = 9, fileType = "text/plain", localPath = "/src/hello.txt", preview = "see you \u263A")

        val ended = runBlocking { LocalSendClient(FakeSendHost(files)).send(peer(), listOf(note)) }

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertTrue(ended.files.single().isFinished)
        assertEquals(listOf("asked", "note from Sender: see you \u263A"), receiver.told)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun nobodyListeningIsNotADroppedConnection() {
        port = ServerSocket(0).use { it.localPort }

        val ended = send(LocalSendClient(FakeSendHost(files)), peer(), item("hello.txt"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(words(R.string.transfer_problem_not_listening, "Receiver"), ended.errorMessage)
        // So it isn't tried again by itself: there's nothing to get back to.
        assertFalse(ended.connectionLost)
    }

    @Test fun cancellingWhileWaitingForAnAnswerTakesTheQuestionAway() {
        listen()
        receiver.answer = null
        val client = LocalSendClient(FakeSendHost(files))
        val ended = AtomicReference<TransferSession>()
        val sending = thread { ended.set(send(client, peer(), item("hello.txt"))) }
        assertTrue(receiver.awaitQuestion())
        assertEquals(TransferStatus.WAITING_ACCEPT, client.activeSession.value?.status)

        client.cancel()
        sending.join(10_000)

        assertEquals(TransferStatus.CANCELLED, ended.get().status)
        // The receiver only finds out because the connection closed on it.
        eventually("the receiver noticing") { "asker left" in receiver.told }
        assertEquals(listOf("asked", "asker left"), receiver.told)
        assertNull(receiver.onScreen)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun cancellingMidwayKeepsWhatArrivedAndTellsTheReceiver() {
        listen()
        val host = FakeSendHost(files)
        val client = LocalSendClient(host)
        // The cancel comes just as the second file starts to go.
        host.opener = { source ->
            if (source == "/src/video.mp4") client.cancel()
            host.whole(source)
        }

        val ended = send(client, peer(), item("hello.txt"), item("video.mp4"), item("photo.jpg"))

        assertEquals(TransferStatus.CANCELLED, ended.status)
        assertEquals(listOf(true, false, false), ended.files.map { it.isFinished })
        assertTrue(receiver.awaitEnd())
        assertTrue(receiver.told.last(), receiver.told.last() == "cancelled" || receiver.told.last().startsWith("failed"))
        // Whatever had arrived of the file that was cut off is removed again.
        eventually("the partial file going") { received() == setOf("hello.txt") }
        assertArrayEquals(hello, File(root, "hello.txt").readBytes())
    }

    @Test fun aFileThisPhoneCannotOpenIsPassedOverAndTheOthersStillArrive() {
        listen()
        val gone = TransferFileItem(fileId = "id-gone", fileName = "gone.txt", size = 5, fileType = "text/plain", localPath = "/src/deleted-since")

        val ended = send(LocalSendClient(FakeSendHost(files)), peer(), item("hello.txt"), gone, item("photo.jpg"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(words(R.string.transfer_partial_sent, 2, 3, words(R.string.transfer_file_unreadable)), ended.errorMessage)
        assertFalse(ended.connectionLost)
        assertEquals(listOf(true, false, true), ended.files.map { it.isFinished })
        assertEquals(words(R.string.transfer_file_unreadable), ended.files[1].failure)
        // The receiver is told the rest isn't coming, and keeps what did.
        assertTrue(receiver.awaitEnd())
        assertEquals("cancelled", receiver.told.last())
        assertArrayEquals(hello, File(root, "hello.txt").readBytes())
        assertArrayEquals(photo, File(root, "photo.jpg").readBytes())
    }

    @Test fun aReceiverThatStopsItPartwayIsNamedAsWhyTheRestDidNotGo() {
        listen()
        val host = FakeSendHost(files)
        val opened = AtomicInteger()
        // The person at the other end stops it as the second file starts.
        host.opener = { source ->
            if (opened.incrementAndGet() == 2) server!!.cancel(receiver.asked.single().sessionId)
            host.whole(source)
        }

        val ended = send(LocalSendClient(host), peer(), item("hello.txt"), item("again.txt", "/src/hello.txt"), item("photo.jpg"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(
            words(R.string.transfer_partial_sent, 1, 3, words(R.string.transfer_stopped_by, "Receiver")),
            ended.errorMessage
        )
        assertFalse(ended.connectionLost)
        assertEquals(listOf(true, false, false), ended.files.map { it.isFinished })
        assertEquals(words(R.string.transfer_stopped_by, "Receiver"), ended.files[1].failure)
        assertNull(ended.files[2].failure)
        assertTrue("cancelled" in receiver.told)
        assertEquals(setOf("hello.txt"), received())
    }

    // ---- over TLS, between two installs of this app ----

    private class Phones(val sender: DeviceIdentity, val receiver: DeviceIdentity)

    /** Two identities, a server answering as one of them, and a receiver that checks the other's signature. */
    private fun twoPhones(): Phones {
        val phones = Phones(DeviceIdentity.at(temp.newFolder("sender-keys")), DeviceIdentity.at(temp.newFolder("receiver-keys")))
        val used = NonceMemory()
        receiver.device = receiver.device.copy(fingerprint = phones.receiver.fingerprint)
        receiver.proof = { header, claimed ->
            SenderProof.verify(header, claimed, phones.receiver.fingerprint, System.currentTimeMillis(), used)
        }
        listen(phones.receiver)
        return phones
    }

    private fun signingHost(phones: Phones) = FakeSendHost(
        files,
        device = DeviceDto(alias = "Sender", fingerprint = phones.sender.fingerprint, port = 53318),
        proof = { receiverFingerprint -> phones.sender.proofFor(receiverFingerprint, System.currentTimeMillis()) }
    )

    @Test fun overTlsTheSenderProvesItselfAndMaySendSeveralFilesAtOnce() {
        val phones = twoPhones()
        val client = LocalSendClient(signingHost(phones))
        val many = (1..12).map { n ->
            TransferFileItem(fileId = "small-$n", fileName = "album/$n.jpg", size = photo.size.toLong(), fileType = "image/jpeg", localPath = "/src/photo.jpg")
        }

        val ended = runBlocking { client.send(peer("https", phones.receiver.fingerprint), many + item("video.mp4")) }

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertTrue(receiver.awaitEnd())
        assertEquals("completed 13", receiver.told.last())
        assertTrue(receiver.asked.single().proven)
        assertEquals(listOf("${phones.sender.fingerprint}@127.0.0.1"), receiver.remembered)
        for (n in 1..12) assertArrayEquals(photo, File(root, "album/$n.jpg").readBytes())
        assertArrayEquals(video, File(root, "video.mp4").readBytes())
    }

    @Test fun aDeviceWithTheWrongCertificateIsToldNothing() {
        val phones = twoPhones()
        val client = LocalSendClient(signingHost(phones))

        val ended = send(client, peer("https", fingerprint = phones.sender.fingerprint), item("hello.txt"))

        assertEquals(TransferStatus.FAILED, ended.status)
        assertEquals(words(R.string.transfer_identity_changed, "Receiver"), ended.errorMessage)
        assertFalse(ended.connectionLost)
        // Not even the offer reached it.
        assertTrue(receiver.asked.isEmpty())
        assertTrue(receiver.told.isEmpty())
    }

    @Test fun theCertificateSeenIsReportedSoACallerCanLearnWhoAnswered() {
        val phones = twoPhones()
        val pins = FingerprintPins()
        val http = OkHttpClient.Builder().also(pins::guard).build()
        val register = Request.Builder()
            .url("https://127.0.0.1:$port${LocalSendWire.REGISTER_PATH}")
            .post("""{"alias":"Probe","fingerprint":"PROBE"}""".toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(register).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals(phones.receiver.fingerprint, response.header(LocalSendSecurity.SEEN_FINGERPRINT_HEADER))
            assertEquals(phones.receiver.fingerprint, LocalSendWire.readDevice(response.body!!.string())!!.fingerprint)
        }

        pins.pinFromCode("127.0.0.1:$port", phones.sender.fingerprint)
        val refused = runCatching { http.newCall(register).execute().close() }.exceptionOrNull()
        assertTrue("$refused", refused is LocalSendSecurity.FingerprintMismatchException)
    }

    /** Leaves behind what a dropped connection would have: the first [bytes] of the video, kept for this sender. */
    private fun leavePartOfVideo(phones: Phones, bytes: ByteArray): File {
        val name = ReceiveSink.keptPartName(phones.sender.fingerprint, "video.mp4", video.size.toLong(), modified)
        return File(root, name).apply { writeBytes(bytes) }
    }

    @Test fun aFileCutOffEarlierCarriesOnFromWhereItStopped() {
        val phones = twoPhones()
        val part = leavePartOfVideo(phones, video.copyOf(2_000_000))

        val ended = send(LocalSendClient(signingHost(phones)), peer("https", phones.receiver.fingerprint), item("video.mp4", modifiedMs = modified))

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertArrayEquals(video, File(root, "video.mp4").readBytes())
        assertFalse(part.exists())
        assertEquals(setOf("video.mp4"), received())
        assertEquals(listOf("asked", "saved id-video.mp4", "completed 1"), receiver.told)
    }

    @Test fun aKeptPartThatIsNotTheSameFileIsThrownAwayAndTheFileSentWhole() {
        val phones = twoPhones()
        val part = leavePartOfVideo(phones, ByteArray(2_000_000) { 9 })

        val ended = send(LocalSendClient(signingHost(phones)), peer("https", phones.receiver.fingerprint), item("video.mp4", modifiedMs = modified))

        assertEquals(TransferStatus.COMPLETED, ended.status)
        assertArrayEquals(video, File(root, "video.mp4").readBytes())
        assertFalse(part.exists())
        assertEquals(setOf("video.mp4"), received())
    }

    @Test fun whatAlreadyArrivedIsNotSentAgainToADeviceThatKnowsTheSender() {
        val phones = twoPhones()
        val client = LocalSendClient(signingHost(phones))
        val to = peer("https", phones.receiver.fingerprint)
        val both = listOf(item("hello.txt", modifiedMs = modified), item("photo.jpg", modifiedMs = modified))
        assertEquals(TransferStatus.COMPLETED, runBlocking { client.send(to, both) }.status)
        receiver.knownDevices[phones.sender.fingerprint] =
            SavedPeer(fingerprint = phones.sender.fingerprint, alias = "Sender", host = "127.0.0.1", port = 53318)

        val again = runBlocking { client.send(to, both.map { it.forAnotherTry() } + item("video.mp4", modifiedMs = modified)) }

        assertEquals(TransferStatus.COMPLETED, again.status)
        assertEquals(listOf(true, true, false), again.files.map { it.alreadyThere })
        assertTrue(again.files.all { it.isFinished })
        assertEquals(listOf("video.mp4"), receiver.asked.last().files.map { it.fileName })
        assertEquals(2, receiver.asked.last().alreadyHere)
        assertEquals(setOf("hello.txt", "photo.jpg", "video.mp4"), received())
    }

    @Test fun sendingEverythingAgainToADeviceThatHasItAllNeedsNothing() {
        val phones = twoPhones()
        val client = LocalSendClient(signingHost(phones))
        val to = peer("https", phones.receiver.fingerprint)
        val both = listOf(item("hello.txt", modifiedMs = modified), item("photo.jpg", modifiedMs = modified))
        runBlocking { client.send(to, both) }
        receiver.knownDevices[phones.sender.fingerprint] =
            SavedPeer(fingerprint = phones.sender.fingerprint, alias = "Sender", host = "127.0.0.1", port = 53318)
        receiver.told.clear()

        val again = runBlocking { client.send(to, both.map { it.forAnotherTry() }) }

        assertEquals(TransferStatus.COMPLETED, again.status)
        assertTrue(again.files.all { it.isFinished })
        assertEquals(listOf("nothing new of 2"), receiver.told)
        assertEquals(setOf("hello.txt", "photo.jpg"), received())
    }

    // ---- the server on its own ----

    @Test fun aReplyIsNeverCompressedAndAlwaysSaysItsLength() {
        listen()
        val body = """{"alias":"Raw","fingerprint":"RAW"}"""

        val reply = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(
                ("POST ${LocalSendWire.REGISTER_PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept-Encoding: gzip\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray()
            )
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

        assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK"))
        assertTrue(reply, reply.contains("Content-Length: ", ignoreCase = true))
        assertFalse(reply, reply.contains("gzip", ignoreCase = true))
        assertTrue(reply, reply.endsWith(LocalSendWire.write(receiver.device.copy(announce = null))))
    }

    @Test fun whenTheUsualPortIsTakenAnyOtherWillDo() {
        ServerSocket(0).use { taken ->
            val started = LocalSendServer(ReceiveDesk(receiver), tls = null, preferredPort = taken.localPort)
            server = started

            val bound = started.start()

            assertTrue(bound != null && bound > 0)
            assertNotEquals(taken.localPort, bound)
            assertEquals(bound, started.boundPort)
        }
    }
}
