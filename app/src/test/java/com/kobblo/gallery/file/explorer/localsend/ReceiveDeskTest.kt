package com.kobblo.gallery.file.explorer.localsend

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Method
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * What this phone answers to each call a sender can make, with a stand-in for the phone and no socket. The
 * codes and words here are what other LocalSend apps and older builds of this one are written against.
 */
class ReceiveDeskTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var host: FakeReceiveHost
    private lateinit var desk: ReceiveDesk

    private val sender = "10.0.0.2"
    private val modified = "2024-05-01T10:00:00Z"

    @Before fun setUp() {
        root = temp.newFolder("Received")
        host = FakeReceiveHost(root)
        desk = ReceiveDesk(host)
    }

    private fun call(
        method: Method,
        path: String,
        body: String = "",
        from: String = sender,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ) = desk.answer(FakeCall(method, path, body.toByteArray(), from, query, headers, QuietConnection()))

    private fun file(
        id: String,
        name: String = "$id.bin",
        size: Long = 4,
        type: String? = "application/octet-stream",
        modified: String? = null,
        sha256: String? = null,
        preview: String? = null
    ) = FileDto(id, name, size, type, sha256, preview, modified?.let { FileMetadataDto(modified = it) })

    private fun offer(
        vararg files: FileDto,
        from: String = sender,
        fingerprint: String = "SENDER",
        alias: String = "Asha",
        signed: Boolean = false
    ): NanoHTTPD.Response {
        val body = PrepareUploadRequestDto(DeviceDto(alias = alias, fingerprint = fingerprint), files.associateBy { it.id })
        val headers = if (signed) mapOf("x-kobblo-proof" to "signed") else emptyMap()
        return call(Method.POST, LocalSendWire.PREPARE_PATH, LocalSendWire.write(body), from, headers = headers)
    }

    private fun terms(response: NanoHTTPD.Response): PrepareUploadResponseDto {
        assertEquals(200, response.code())
        return LocalSendWire.readAnswer(response.text())!!
    }

    private fun upload(
        terms: PrepareUploadResponseDto,
        fileId: String,
        bytes: ByteArray,
        from: String = sender,
        token: String? = terms.files[fileId],
        declared: Long = bytes.size.toLong(),
        more: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): NanoHTTPD.Response {
        val query = buildMap {
            put("sessionId", terms.sessionId)
            put("fileId", fileId)
            token?.let { put("token", it) }
            putAll(more)
        }
        return desk.answer(
            FakeCall(
                Method.POST, LocalSendWire.UPLOAD_PATH, from = from, query = query,
                headers = mapOf("content-length" to declared.toString()) + headers,
                connection = ByteArrayInputStream(bytes)
            )
        )
    }

    private fun trustSigned() {
        host.proof = { header, _ -> header == "signed" }
    }

    // ---- info and register ----

    @Test fun infoSaysWhoThisIs() {
        val response = call(Method.GET, LocalSendWire.INFO_PATH)

        assertEquals(200, response.code())
        assertEquals("application/json", response.mimeType)
        assertEquals(LocalSendWire.write(host.device), response.text())
    }

    @Test fun aDeviceThatRegistersIsListedAtTheAddressItCalledFrom() {
        val response = call(
            Method.POST, LocalSendWire.REGISTER_PATH,
            """{"alias":"Desk\u202Etop","version":"2.0","deviceModel":"Linux","deviceType":"desktop","fingerprint":"C0FFEE","port":53317,"protocol":"http","download":false}""",
            from = "192.168.1.7"
        )

        assertEquals(200, response.code())
        // The reply is this phone, with no word about announcing.
        assertEquals(LocalSendWire.write(host.device.copy(announce = null)), response.text())
        val peer = host.heardPeers.single()
        assertEquals("192.168.1.7", peer.ip)
        assertEquals(53317, peer.port)
        assertEquals("Desktop", peer.alias)
        assertEquals("desktop", peer.deviceType)
        assertEquals("http", peer.protocol)
        assertEquals("C0FFEE", peer.fingerprint)
    }

    @Test fun aDeviceThatDoesNotSayItsProtocolIsTakenToSpeakTls() {
        call(Method.POST, LocalSendWire.REGISTER_PATH, """{"alias":"Tablet","fingerprint":"AA","protocol":""}""")

        assertEquals("https", host.heardPeers.single().protocol)
    }

    @Test fun aCallFromThisPhonesOwnAddressIsAnsweredButNotListed() {
        val response = call(Method.POST, LocalSendWire.REGISTER_PATH, """{"alias":"Tunnel","fingerprint":"AA"}""", from = "127.0.0.1")

        assertEquals(200, response.code())
        assertTrue(host.heardPeers.isEmpty())
    }

    @Test fun registeringWithNothingStillHearsWhoThisIs() {
        val response = call(Method.POST, LocalSendWire.REGISTER_PATH)

        assertEquals(200, response.code())
        assertTrue(host.heardPeers.isEmpty())
    }

    @Test fun aRegisterThatMakesNoSenseIsABadRequest() {
        val rubbish = call(Method.POST, LocalSendWire.REGISTER_PATH, "{ not json")
        assertEquals(400, rubbish.code())
        assertEquals("Invalid body", rubbish.text())

        assertEquals(400, call(Method.POST, LocalSendWire.REGISTER_PATH, """{"alias":"x","fingerprint":null}""").code())
        assertEquals(400, call(Method.POST, LocalSendWire.REGISTER_PATH, """{"alias":"x","protocol":null}""").code())
        assertTrue(host.heardPeers.isEmpty())
    }

    @Test fun aRegisterTooBigToBeADeviceIsTurnedAwayUnread() {
        val response = call(Method.POST, LocalSendWire.REGISTER_PATH, "{}", headers = mapOf("content-length" to "65537"))

        assertEquals(413, response.code())
        assertTrue(response.isCloseConnection)
    }

    @Test fun anythingElseIsNotFound() {
        assertEquals(404, call(Method.GET, "/").code())
        assertEquals(404, call(Method.GET, LocalSendWire.REGISTER_PATH).code())
        assertEquals(404, call(Method.POST, LocalSendWire.INFO_PATH).code())
        assertEquals(404, call(Method.POST, "/api/localsend/v1/register").code())
        assertEquals("Not found", call(Method.PUT, LocalSendWire.UPLOAD_PATH).text())
    }

    // ---- prepare-upload: what's refused before anyone is asked ----

    @Test fun anOfferWithNothingInItIsABadRequest() {
        val empty = call(Method.POST, LocalSendWire.PREPARE_PATH)
        assertEquals(400, empty.code())
        assertEquals("Empty body", empty.text())

        val nothing = call(Method.POST, LocalSendWire.PREPARE_PATH, "null")
        assertEquals(400, nothing.code())
        assertEquals("Invalid JSON", nothing.text())

        val noFiles = call(Method.POST, LocalSendWire.PREPARE_PATH, """{"info":{"alias":"x"},"files":{}}""")
        assertEquals(400, noFiles.code())
        assertEquals("No files provided", noFiles.text())

        val badSize = offer(file("a", size = -1))
        assertEquals(400, badSize.code())
        assertEquals("Bad file size", badSize.text())
        assertTrue(host.asked.isEmpty())
    }

    @Test fun anOfferThatCannotBeMadeSenseOfIsTheServersError() {
        val rubbish = call(Method.POST, LocalSendWire.PREPARE_PATH, "{ not json")
        assertEquals(500, rubbish.code())
        assertEquals("Server error", rubbish.text())

        val holes = call(Method.POST, LocalSendWire.PREPARE_PATH, """{"info":{"alias":"x","fingerprint":null},"files":{"a":{"id":"a","fileName":"a","size":1}}}""")
        assertEquals(500, holes.code())
        val noFile = call(Method.POST, LocalSendWire.PREPARE_PATH, """{"info":{"alias":"x"},"files":{"a":null}}""")
        assertEquals(500, noFile.code())
        assertTrue(host.asked.isEmpty())
    }

    @Test fun anOfferTooBigToHoldIsTurnedAwayUnread() {
        val response = call(Method.POST, LocalSendWire.PREPARE_PATH, "{}", headers = mapOf("content-length" to "${16L * 1024 * 1024 + 1}"))

        assertEquals(413, response.code())
        assertTrue(response.isCloseConnection)
    }

    @Test fun whileSomethingElseIsGoingOnAnOfferIsTurnedAwayAsBusy() {
        host.busy = true

        val response = offer(file("a"))

        assertEquals(409, response.code())
        assertEquals("Blocked by another session", response.text())
        assertTrue(host.asked.isEmpty())
    }

    @Test fun anOfferThereIsNoRoomForIsRefusedWithoutAsking() {
        val response = offer(file("huge", size = Long.MAX_VALUE / 2))

        assertEquals(500, response.code())
        assertEquals("insufficient-storage", response.text())
        assertEquals(listOf("no space"), host.told)
    }

    // ---- prepare-upload: asking ----

    @Test fun anAcceptedOfferGetsASessionAndATokenForEachFile() {
        val response = offer(file("a", "Camera/a.jpg", 10, "image/jpeg"), file("b", size = 30, type = null))

        assertEquals(200, response.code())
        assertEquals("application/json", response.mimeType)
        // The connection was read from while waiting, so it can't carry another request.
        assertTrue(response.isCloseConnection)
        val json = response.text()
        val terms = LocalSendWire.readAnswer(json)!!
        assertEquals(setOf("a", "b"), terms.files.keys)
        assertNotEquals(terms.files["a"], terms.files["b"])
        assertTrue(terms.sessionId.isNotBlank())
        // A sender that proved nothing is told nothing beyond the protocol.
        assertFalse("alreadyHave" in json || "uploadSlots" in json || "resume" in json)

        val question = host.asked.single()
        assertEquals(terms.sessionId, question.sessionId)
        assertEquals(PromptKind.FILES, question.kind)
        assertEquals("Asha", question.sender.alias)
        assertEquals(sender, question.senderIp)
        assertEquals(listOf("Camera/a.jpg", "b.bin"), question.files.map { it.fileName })
        assertEquals(listOf("image/jpeg", "application/octet-stream"), question.files.map { it.fileType })
        assertEquals(40L, question.totalBytes)
        assertFalse(question.proven)
        assertEquals(listOf("asked"), host.told)
    }

    @Test fun aSenderWithNoNameIsGivenOne() {
        call(Method.POST, LocalSendWire.PREPARE_PATH, """{"files":{"a":{"id":"a","fileName":"a.bin","size":1}}}""")
        assertEquals(words(R.string.share_localsend_device), host.asked.single().sender.alias)
    }

    @Test fun aDeclinedOfferIsForbiddenAndTheSenderMustWaitBeforeAskingAgain() {
        host.answer = PromptAnswer.REJECT

        val declined = offer(file("a"))
        assertEquals(403, declined.code())
        assertEquals("Rejected by user", declined.text())
        assertTrue(declined.isCloseConnection)

        val again = offer(file("a"))
        assertEquals(429, again.code())
        assertEquals("Try again shortly", again.text())
        assertEquals(1, host.asked.size)

        // Somebody else isn't made to wait for it.
        host.answer = PromptAnswer.ACCEPT
        assertEquals(200, offer(file("a"), from = "10.0.0.9", fingerprint = "OTHER").code())
    }

    @Test fun anOfferNobodyAnsweredSaysSoAndMayBeMadeAgainAtOnce() {
        host.answer = PromptAnswer.UNANSWERED

        val unanswered = offer(file("a"))
        assertEquals(403, unanswered.code())
        assertEquals("not-answered", unanswered.text())

        host.answer = PromptAnswer.ACCEPT
        assertEquals(200, offer(file("a")).code())
    }

    @Test fun aSenderThatHangsUpWhileWaitingTakesItsQuestionAway() {
        host.answer = null
        val line = QuietConnection()
        val body = LocalSendWire.write(PrepareUploadRequestDto(DeviceDto(alias = "Asha", fingerprint = "SENDER"), mapOf("a" to file("a"))))
        val response = AtomicReference<NanoHTTPD.Response>()
        val waiting = thread {
            response.set(desk.answer(FakeCall(Method.POST, LocalSendWire.PREPARE_PATH, body.toByteArray(), sender, connection = line)))
        }
        assertTrue(host.awaitQuestion())

        line.close()
        waiting.join(10_000)

        assertEquals(listOf("asked", "asker left"), host.told)
        assertEquals(403, response.get().code())
        // Nobody said no, so nobody is made to wait.
        host.answer = PromptAnswer.ACCEPT
        assertEquals(200, offer(file("a")).code())
    }

    @Test fun withAskingTurnedOffAnOfferIsTakenStraightAway() {
        host.asksFirst = false

        val response = offer(file("a"))

        assertEquals(200, response.code())
        assertFalse(response.isCloseConnection)
        assertEquals(listOf("accepted unasked"), host.told)
    }

    @Test fun onlyOneSenderAtATime() {
        terms(offer(file("a")))

        val second = offer(file("b"), from = "10.0.0.9", fingerprint = "OTHER")

        assertEquals(409, second.code())
        assertEquals(1, host.asked.size)
    }

    @Test fun theSameDeviceOfferingAgainReplacesWhatItWasSending() {
        val first = terms(offer(file("a"), file("b")))

        val second = terms(offer(file("a2"), file("b2")))

        assertTrue("superseded" in host.told)
        assertNotEquals(first.sessionId, second.sessionId)
        // The old session's tokens are worth nothing now.
        assertEquals(403, upload(first, "a", byteArrayOf(1, 2, 3, 4)).code())
        assertEquals(200, upload(second, "a2", byteArrayOf(1, 2, 3, 4)).code())
    }

    // ---- prepare-upload: notes ----

    @Test fun aNoteIsHandedOverWithoutASessionOnceItIsAccepted() {
        val response = offer(file("n", "Note.txt", 5, "text/plain", preview = "hello"))

        assertEquals(204, response.code())
        assertTrue(response.isCloseConnection)
        val question = host.asked.single()
        assertEquals(PromptKind.NOTE, question.kind)
        assertEquals(5L, question.totalBytes)
        assertEquals("Note.txt", question.files.single().fileName)
        assertEquals(listOf("asked", "note from Asha: hello"), host.told)
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun withAskingTurnedOffANoteJustArrives() {
        host.asksFirst = false

        val response = offer(file("n", "Note.txt", 5, "text/plain", preview = "hello"))

        assertEquals(204, response.code())
        assertFalse(response.isCloseConnection)
        assertEquals(listOf("note from Asha: hello"), host.told)
    }

    @Test fun notesCannotBeFiredOffOneAfterAnother() {
        host.asksFirst = false
        offer(file("n", "Note.txt", 5, "text/plain", preview = "one"))

        val second = offer(file("n", "Note.txt", 5, "text/plain", preview = "two"))

        assertEquals(429, second.code())
        assertEquals(listOf("note from Asha: one"), host.told)
    }

    @Test fun aDeclinedNoteIsForbidden() {
        host.answer = PromptAnswer.REJECT

        val response = offer(file("n", "Note.txt", 5, "text/plain", preview = "hello"))

        assertEquals(403, response.code())
        assertEquals(listOf("asked"), host.told)
    }

    @Test fun aTextFileWithoutItsWordsIsAFileLikeAnyOther() {
        val response = offer(file("n", "Note.txt", 5, "text/plain"))

        assertEquals(200, response.code())
        assertEquals(PromptKind.FILES, host.asked.single().kind)
    }

    // ---- prepare-upload: what a sender that proved itself is told ----

    @Test fun aSignedSenderIsToldHowManyFilesItMaySendAtOnceAndIsRemembered() {
        trustSigned()

        val response = offer(file("a"), signed = true)

        val json = response.text()
        assertEquals(4, LocalSendWire.readAnswer(json)!!.uploadSlots)
        assertTrue(host.asked.single().proven)
        assertEquals(listOf("SENDER@$sender"), host.remembered)
    }

    @Test fun whatIsAlreadyHereIsOnlySaidToADeviceThisPhoneKnows() {
        val here = File(root, "a.bin").apply {
            writeBytes(ByteArray(4))
            setLastModified(Instant.parse(modified).toEpochMilli())
        }
        assertTrue(here.exists())
        trustSigned()

        // A stranger, even one that signs, learns nothing: it's offered the lot.
        val stranger = terms(offer(file("a", modified = modified), file("b", modified = modified), signed = true))
        assertEquals(setOf("a", "b"), stranger.files.keys)
        assertNull(stranger.alreadyHave)
        desk.cancel(stranger.sessionId)

        host.knownDevices["SENDER"] = SavedPeer(fingerprint = "SENDER", alias = "Asha", host = sender, port = 53318)
        val known = terms(offer(file("a", modified = modified), file("b", modified = modified), signed = true))
        assertEquals(setOf("b"), known.files.keys)
        assertEquals(listOf("a"), known.alreadyHave)
        assertEquals(1, host.asked.last().alreadyHere)
        assertEquals(listOf("b.bin"), host.asked.last().files.map { it.fileName })
    }

    @Test fun anOfferOfOnlyWhatIsAlreadyHereNeedsNothing() {
        File(root, "a.bin").apply {
            writeBytes(ByteArray(4))
            setLastModified(Instant.parse(modified).toEpochMilli())
        }
        trustSigned()
        host.knownDevices["SENDER"] = SavedPeer(fingerprint = "SENDER", alias = "Asha", host = sender, port = 53318)

        val response = offer(file("a", modified = modified), signed = true)

        assertEquals(204, response.code())
        assertEquals(listOf("nothing new of 1"), host.told)
    }

    @Test fun aKnownDeviceCanBeLetOffBeingAskedButOnlyIfItSigns() {
        host.knownDevices["SENDER"] = SavedPeer(fingerprint = "SENDER", alias = "Asha", host = sender, port = 53318, autoAccept = true)
        trustSigned()

        // Saying the fingerprint isn't enough.
        val claimed = terms(offer(file("a")))
        assertEquals(listOf("asked"), host.told)
        desk.cancel(claimed.sessionId)
        host.told.clear()

        terms(offer(file("a"), signed = true))
        assertEquals(listOf("accepted unasked"), host.told)
    }

    // ---- upload ----

    @Test fun anUploadIsSavedUnderTheReceiveFolderAndTheLastOneCompletesTheSession() {
        val hello = "hello world".toByteArray()
        val photo = ByteArray(200_000) { (it % 251).toByte() }
        val terms = terms(
            offer(file("a", "notes/hello.txt", hello.size.toLong(), "text/plain"), file("b", "photo.jpg", photo.size.toLong(), modified = modified))
        )

        val first = upload(terms, "a", hello)
        assertEquals(200, first.code())
        assertEquals("", first.text())
        assertFalse(first.isCloseConnection)
        assertFalse("completed 2" in host.told)

        assertEquals(200, upload(terms, "b", photo).code())

        assertArrayEquals(hello, File(root, "notes/hello.txt").readBytes())
        assertArrayEquals(photo, File(root, "photo.jpg").readBytes())
        assertEquals(Instant.parse(modified).toEpochMilli(), File(root, "photo.jpg").lastModified())
        assertEquals(listOf("asked", "saved a", "saved b", "completed 2"), host.told)
        assertEquals(photo.size.toLong(), host.arrived["b"])
        assertEquals(setOf(File(root, "notes/hello.txt").absolutePath, File(root, "photo.jpg").absolutePath), host.scanned.toSet())
        // It's over, so its tokens are too.
        assertEquals(403, upload(terms, "a", hello).code())
    }

    @Test fun aSenderCannotChooseWhereItsFilesLand() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val terms = terms(
            offer(file("a", "../../escape.txt"), file("b", "/etc/passwd"), file("c", "..\\..\\windows.ini"), file("d", ".nomedia"), file("e", "a/../../b.txt"))
        )

        for (id in listOf("a", "b", "c", "d", "e")) assertEquals(200, upload(terms, id, bytes).code())

        val landed = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()
        assertEquals(setOf("escape.txt", "etc/passwd", "windows.ini", "nomedia", "a/b.txt"), landed)
        assertEquals(listOf(root.name), temp.root.list()!!.toList())
        assertTrue(host.savedFiles.all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
    }

    @Test fun aFileThatIsAlreadyThereIsKeptAndTheNewOneNumbered() {
        File(root, "photo.jpg").writeText("mine")
        val terms = terms(offer(file("a", "photo.jpg")))

        upload(terms, "a", byteArrayOf(1, 2, 3, 4))

        assertEquals("mine", File(root, "photo.jpg").readText())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(root, "photo (2).jpg").readBytes())
        assertEquals(File(root, "photo (2).jpg"), host.savedFiles.single())
    }

    @Test fun anUploadNeedsItsSessionItsFileAndItsToken() {
        val terms = terms(offer(file("a"), file("b")))
        val bytes = byteArrayOf(1, 2, 3, 4)

        val noToken = upload(terms, "a", bytes, token = null)
        assertEquals(400, noToken.code())
        assertEquals("Missing parameters", noToken.text())
        assertTrue(noToken.isCloseConnection)

        val noSession = upload(terms.copy(sessionId = "made-up"), "a", bytes)
        assertEquals(403, noSession.code())
        assertEquals("Invalid or expired session", noSession.text())

        val wrongToken = upload(terms, "a", bytes, token = "guess")
        assertEquals(403, wrongToken.code())
        assertEquals("Invalid token", wrongToken.text())
        assertEquals(403, upload(terms, "a", bytes, token = terms.files["b"]).code())
        assertEquals(403, upload(terms, "never-offered", bytes, token = terms.files["a"]).code())

        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun aSessionBelongsToTheAddressThatOpenedIt() {
        val terms = terms(offer(file("a")))

        val elsewhere = upload(terms, "a", byteArrayOf(1, 2, 3, 4), from = "10.0.0.66")

        assertEquals(403, elsewhere.code())
        assertEquals("Invalid IP address", elsewhere.text())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun anUploadMustBeExactlyAsLongAsWasAccepted() {
        val terms = terms(offer(file("a", size = 4)))

        val longer = upload(terms, "a", ByteArray(9))
        assertEquals(400, longer.code())
        assertEquals("Length differs from what was accepted", longer.text())

        val chunked = upload(terms, "a", ByteArray(4), headers = mapOf("transfer-encoding" to "Chunked"))
        assertEquals(411, chunked.code())
        assertEquals("Length required", chunked.text())

        val fromNowhere = upload(terms, "a", ByteArray(2), more = mapOf("offset" to "2"))
        assertEquals(400, fromNowhere.code())
        assertEquals("Not where this file stopped", fromNowhere.text())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun aFileIsSavedOnceHoweverManyTimesItIsSent() {
        val terms = terms(offer(file("a"), file("b")))
        upload(terms, "a", byteArrayOf(1, 2, 3, 4))

        val again = upload(terms, "a", byteArrayOf(1, 2, 3, 4))

        assertEquals(409, again.code())
        assertEquals("Already received", again.text())
        assertEquals(listOf("a.bin"), root.list()!!.toList())
    }

    @Test fun anUploadThatStopsShortLeavesNothingBehindAndEndsTheSession() {
        val terms = terms(offer(file("a", size = 10), file("b", size = 10)))

        val cut = upload(terms, "a", ByteArray(6), declared = 10)

        assertEquals(400, cut.code())
        assertEquals("Upload was cut short", cut.text())
        assertTrue(cut.isCloseConnection)
        assertEquals("failed " + words(R.string.transfer_sender_stopped, "Asha"), host.told.last())
        assertTrue(root.walkTopDown().none { it.isFile })
        assertEquals(403, upload(terms, "b", ByteArray(10)).code())
    }

    @Test fun anUploadThatDoesNotMatchItsChecksumIsThrownAway() {
        val good = "hello".toByteArray()
        val sum = MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }
        val terms = terms(offer(file("a", size = 5, sha256 = sum), file("b", size = 5, sha256 = sum)))

        assertEquals(200, upload(terms, "a", good).code())
        val damaged = upload(terms, "b", "hellx".toByteArray())

        assertEquals(422, damaged.code())
        assertEquals("422 Unprocessable Entity", damaged.status.description)
        assertEquals("Checksum mismatch", damaged.text())
        assertEquals("failed " + words(R.string.transfer_damaged), host.told.last())
        assertEquals(listOf("a.bin"), root.list()!!.toList())
    }

    // ---- ending a session ----

    @Test fun theSenderCanCallTheRestOff() {
        val terms = terms(offer(file("a"), file("b")))
        upload(terms, "a", byteArrayOf(1, 2, 3, 4))

        val response = call(Method.POST, LocalSendWire.CANCEL_PATH, query = mapOf("sessionId" to terms.sessionId))

        assertEquals(200, response.code())
        assertEquals("cancelled", host.told.last())
        assertEquals(403, upload(terms, "b", byteArrayOf(1, 2, 3, 4)).code())
        // What had arrived stays.
        assertTrue(File(root, "a.bin").exists())
    }

    @Test fun nobodyElseCanCallASessionOff() {
        val terms = terms(offer(file("a")))

        val response = call(Method.POST, LocalSendWire.CANCEL_PATH, from = "10.0.0.66", query = mapOf("sessionId" to terms.sessionId))

        assertEquals(200, response.code())
        assertFalse("cancelled" in host.told)
        assertEquals(200, call(Method.POST, LocalSendWire.CANCEL_PATH, query = mapOf("sessionId" to "made-up")).code())
        assertEquals(200, upload(terms, "a", byteArrayOf(1, 2, 3, 4)).code())
    }

    @Test fun thePersonHereCanStopWhatIsArriving() {
        val terms = terms(offer(file("a"), file("b")))

        desk.cancel(terms.sessionId)
        desk.cancel(terms.sessionId)

        assertEquals(1, host.told.count { it == "cancelled" })
        val late = upload(terms, "a", byteArrayOf(1, 2, 3, 4))
        assertEquals(403, late.code())
        // Having been stopped here, the same files coming again is a new question.
        host.told.clear()
        terms(offer(file("a"), file("b")))
        assertEquals(listOf("asked"), host.told)
    }

    @Test fun aSenderThatGoesQuietIsGivenUpOn() {
        val terms = terms(offer(file("a"), file("b")))
        upload(terms, "a", byteArrayOf(1, 2, 3, 4))

        desk.endStalled(System.currentTimeMillis() + 59_000)
        assertFalse(host.told.any { it.startsWith("failed") })

        desk.endStalled(System.currentTimeMillis() + 61_000)
        assertEquals("failed " + words(R.string.transfer_sender_stopped, "Asha"), host.told.last())
        assertEquals(403, upload(terms, "b", byteArrayOf(1, 2, 3, 4)).code())
    }

    @Test fun closingStopsEverythingWithoutAWord() {
        val terms = terms(offer(file("a")))
        host.told.clear()

        desk.close()

        assertTrue(host.told.isEmpty())
        assertEquals(403, upload(terms, "a", byteArrayOf(1, 2, 3, 4)).code())
    }

    // ---- carrying on after a dropped connection ----

    @Test fun aSignedSenderCanCarryOnAFileWhereItStopped() {
        trustSigned()
        val whole = ByteArray(100_000) { (it * 7 % 253).toByte() }
        val cutAt = 60_000

        val first = terms(offer(file("try1", "video.mp4", whole.size.toLong(), modified = modified), signed = true))
        assertNull(first.resume)
        assertEquals(400, upload(first, "try1", whole.copyOf(cutAt), declared = whole.size.toLong()).code())
        // Nothing with the file's name yet, only the hidden part.
        assertFalse(File(root, "video.mp4").exists())

        // The same files again, from the same device, needn't be asked about twice.
        host.told.clear()
        val second = terms(offer(file("try2", "video.mp4", whole.size.toLong(), modified = modified), signed = true))
        assertEquals(listOf("accepted unasked"), host.told)
        assertEquals(mapOf("try2" to cutAt.toLong()), second.resume)

        val tail = ReceiveSink.tailHash(ByteArrayInputStream(whole), cutAt.toLong())!!
        val rest = whole.copyOfRange(cutAt, whole.size)
        val carriedOn = upload(second, "try2", rest, more = mapOf("offset" to "$cutAt", "tail" to tail))

        assertEquals(200, carriedOn.code())
        assertArrayEquals(whole, File(root, "video.mp4").readBytes())
        assertEquals(listOf("video.mp4"), root.list()!!.toList())
    }

    @Test fun aPartThatDoesNotMatchIsThrownAwayAndTheFileStartsOver() {
        trustSigned()
        val whole = ByteArray(50_000) { (it % 97).toByte() }
        val first = terms(offer(file("try1", "video.mp4", whole.size.toLong(), modified = modified), signed = true))
        upload(first, "try1", whole.copyOf(20_000), declared = whole.size.toLong())
        val second = terms(offer(file("try2", "video.mp4", whole.size.toLong(), modified = modified), signed = true))

        val mismatched = upload(second, "try2", whole.copyOfRange(20_000, whole.size), more = mapOf("offset" to "20000", "tail" to "not-the-hash"))

        assertEquals(412, mismatched.code())
        assertEquals("Start this file over", mismatched.text())
        assertTrue(root.list()!!.isEmpty())
        assertEquals(200, upload(second, "try2", whole).code())
        assertArrayEquals(whole, File(root, "video.mp4").readBytes())
    }

    @Test fun aSenderThatProvedNothingStartsEveryFileFromNothing() {
        val whole = ByteArray(50_000) { (it % 97).toByte() }
        val first = terms(offer(file("try1", "video.mp4", whole.size.toLong(), modified = modified)))
        upload(first, "try1", whole.copyOf(20_000), declared = whole.size.toLong())

        // Nothing is kept of it.
        assertTrue(root.walkTopDown().none { it.isFile })
        val second = terms(offer(file("try2", "video.mp4", whole.size.toLong(), modified = modified)))
        assertNull(second.resume)
    }
}
