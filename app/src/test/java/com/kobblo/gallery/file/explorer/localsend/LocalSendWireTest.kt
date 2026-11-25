package com.kobblo.gallery.file.explorer.localsend

import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What leaves this phone and what it makes of what arrives, byte for byte. Other apps and older builds of this
 * one are on the other end, so a change that turns one of these red is a change to the protocol.
 */
class LocalSendWireTest {
    private val phone = DeviceDto(
        alias = "Asha's phone",
        version = "2.0",
        deviceModel = "Pixel 8",
        deviceType = "mobile",
        fingerprint = "AB12",
        port = 53318,
        protocol = "https",
        download = false,
        announce = true
    )

    @Test fun theAddressesAndPathsAreTheProtocols() {
        assertEquals("224.0.0.167", LocalSendWire.GROUP_ADDRESS)
        assertEquals(53317, LocalSendWire.GROUP_PORT)
        assertEquals(53318, LocalSendServer.DEFAULT_HTTP_PORT)
        assertEquals("/api/localsend/v2/info", LocalSendWire.INFO_PATH)
        assertEquals("/api/localsend/v2/register", LocalSendWire.REGISTER_PATH)
        assertEquals("/api/localsend/v2/prepare-upload", LocalSendWire.PREPARE_PATH)
        assertEquals("/api/localsend/v2/upload", LocalSendWire.UPLOAD_PATH)
        assertEquals("/api/localsend/v2/cancel", LocalSendWire.CANCEL_PATH)
        assertEquals(listOf("sessionId", "fileId", "token"), listOf(LocalSendWire.SESSION_PARAM, LocalSendWire.FILE_PARAM, LocalSendWire.TOKEN_PARAM))
        assertEquals(listOf("offset", "tail"), listOf(LocalSendWire.OFFSET_PARAM, LocalSendWire.TAIL_PARAM))
        assertEquals("insufficient-storage", LocalSendWire.STORAGE_FULL)
        assertEquals("not-answered", LocalSendWire.NOT_ANSWERED)
    }

    @Test fun anAnnouncementIsTheDeviceAsJsonSayingWhetherToAnswer() {
        val asking = String(LocalSendWire.announcement(phone, wantsReply = true), Charsets.UTF_8)
        assertEquals(
            """{"alias":"Asha\u0027s phone","version":"2.0","deviceModel":"Pixel 8","deviceType":"mobile",""" +
                """"fingerprint":"AB12","port":53318,"protocol":"https","download":false,"announce":true}""",
            asking
        )
        val answering = String(LocalSendWire.announcement(phone, wantsReply = false), Charsets.UTF_8)
        assertTrue(answering.endsWith(""""download":false,"announce":false}"""))
    }

    @Test fun whatIsNotSaidIsLeftOutRatherThanSentAsNull() {
        val reply = LocalSendWire.write(phone.copy(announce = null, deviceModel = null))
        assertEquals(
            """{"alias":"Asha\u0027s phone","version":"2.0","deviceType":"mobile","fingerprint":"AB12","port":53318,"protocol":"https","download":false}""",
            reply
        )
    }

    @Test fun aDeviceThatLeavesThingsOutGetsTheProtocolsDefaults() {
        val device = LocalSendWire.readDevice("""{"alias":"Laptop","fingerprint":"FF"}""")!!
        assertEquals("Laptop", device.alias)
        assertEquals("2.0", device.version)
        assertEquals("mobile", device.deviceType)
        assertEquals(53317, device.port)
        assertEquals("https", device.protocol)
        assertEquals(false, device.download)
        assertNull(device.announce)
        assertNull(device.deviceModel)
    }

    @Test fun anEmptyBodyIsNoDeviceAndRubbishIsAnError() {
        assertNull(LocalSendWire.readDevice(""))
        assertNull(LocalSendWire.readDevice(null))
        assertThrows(JsonParseException::class.java) { LocalSendWire.readDevice("not json at all {") }
        assertThrows(JsonParseException::class.java) { LocalSendWire.readOffer("[1, 2]") }
    }

    @Test fun aDeviceBecomesAPeerAtTheAddressItWasHeardFrom() {
        val device = LocalSendWire.readDevice(
            """{"alias":"  Desk\u202Etop  ","deviceModel":"Mac\u0000Book","deviceType":"desktop","fingerprint":"C0FFEE","port":53317,"protocol":"http"}"""
        )!!
        val peer = LocalSendWire.peer(device, "192.168.1.20", unknownName = "Unknown device", nowMs = 77)!!
        assertEquals("192.168.1.20", peer.ip)
        assertEquals(53317, peer.port)
        // The name can't carry the character that makes text read backwards, or any that can't be seen.
        assertEquals("Desktop", peer.alias)
        assertEquals("MacBook", peer.deviceModel)
        assertEquals("desktop", peer.deviceType)
        assertEquals("http", peer.protocol)
        assertEquals("C0FFEE", peer.fingerprint)
        assertEquals(77L, peer.lastSeenMs)
        assertEquals("192.168.1.20:53317", peer.id)
        assertEquals("http://192.168.1.20:53317", peer.baseUrl)
    }

    @Test fun aNamelessDeviceIsCalledWhatTheCallerSays() {
        val peer = LocalSendWire.peer(LocalSendWire.readDevice("""{"alias":null,"deviceType":null}""")!!, "10.0.0.2", "Unknown device", 0)!!
        assertEquals("Unknown device", peer.alias)
        assertEquals("mobile", peer.deviceType)
        assertNull(peer.deviceModel)
    }

    @Test fun aNullWhereTheProtocolOrFingerprintBelongsIsNoPeerAtAll() {
        val noProtocol = LocalSendWire.readDevice("""{"alias":"x","protocol":null,"fingerprint":"AA"}""")!!
        val noFingerprint = LocalSendWire.readDevice("""{"alias":"x","fingerprint":null}""")!!
        assertNull(noProtocol.statedProtocol)
        assertNull(noFingerprint.statedFingerprint)
        assertNull(LocalSendWire.peer(noProtocol, "10.0.0.2", "Unknown device", 0))
        assertNull(LocalSendWire.peer(noFingerprint, "10.0.0.2", "Unknown device", 0))
        // Unless whoever asks knows better than what the device said.
        assertNotNull(LocalSendWire.peer(noProtocol, "10.0.0.2", "Unknown device", 0, protocol = "https"))
    }

    @Test fun whereAPeerIsReachedCanBeGivenInsteadOfBelieved() {
        val answered = LocalSendWire.readDevice("""{"alias":"Tablet","fingerprint":"","port":1,"protocol":"http"}""")!!
        val peer = LocalSendWire.peer(
            answered, "192.168.1.9", "Unknown device", 5,
            port = 53318,
            protocol = "https",
            fingerprint = answered.statedFingerprint?.ifBlank { "ANNOUNCED" }
        )!!
        assertEquals(53318, peer.port)
        assertEquals("https", peer.protocol)
        assertEquals("ANNOUNCED", peer.fingerprint)
    }

    @Test fun onlyPlainHttpIsNotTls() {
        assertEquals("http", LocalSendWire.scheme("http"))
        assertEquals("https", LocalSendWire.scheme("https"))
        assertEquals("https", LocalSendWire.scheme("HTTP"))
        assertEquals("https", LocalSendWire.scheme(""))
        assertEquals("https", LocalSendWire.scheme("ftp"))
    }

    @Test fun anOfferListsEveryFileByItsIdWithWhatIsKnownOfIt() {
        val items = listOf(
            TransferFileItem(fileId = "a", fileName = "Camera/a.jpg", size = 12, fileType = "image/jpeg", modifiedMs = 1_700_000_000_000),
            TransferFileItem(fileId = "n", fileName = "Note.txt", size = 2, fileType = "text/plain", preview = "hi")
        )
        assertEquals(
            """{"info":{"alias":"Asha\u0027s phone","version":"2.0","deviceModel":"Pixel 8","deviceType":"mobile","fingerprint":"AB12",""" +
                """"port":53318,"protocol":"https","download":false},""" +
                """"files":{"a":{"id":"a","fileName":"Camera/a.jpg","size":12,"fileType":"image/jpeg","metadata":{"modified":"2023-11-14T22:13:20Z"}},""" +
                """"n":{"id":"n","fileName":"Note.txt","size":2,"fileType":"text/plain","preview":"hi"}}}""",
            LocalSendWire.write(LocalSendWire.offer(phone, items))
        )
    }

    @Test fun anOfferFromAnotherAppReadsBack() {
        val offer = LocalSendWire.readOffer(
            """{"info":{"alias":"Nice Orange","version":"2.1","deviceModel":"Samsung","deviceType":"mobile","fingerprint":"abc","port":53317,"protocol":"https","download":true},
               "files":{"id1":{"id":"id1","fileName":"another image.jpg","size":324242,"fileType":"image/jpeg","sha256":"aa","preview":null,
               "metadata":{"modified":"2021-01-01T12:34:56Z","accessed":"2021-01-01T12:34:56Z"}}},"somethingNew":true}"""
        )!!
        assertEquals("Nice Orange", offer.info!!.alias)
        assertEquals("2.1", offer.info!!.version)
        val file = offer.files!!.getValue("id1")
        assertEquals("another image.jpg", file.fileName)
        assertEquals(324242L, file.size)
        assertEquals("aa", file.sha256)
        assertNull(file.preview)
        assertEquals("2021-01-01T12:34:56Z", file.metadata!!.modified)
    }

    @Test fun aPlainAnswerHasOnlyWhatTheProtocolHas() {
        val plain = PrepareUploadResponseDto(sessionId = "s1", files = linkedMapOf("a" to "t1", "b" to "t2"))
        assertEquals("""{"sessionId":"s1","files":{"a":"t1","b":"t2"}}""", LocalSendWire.write(plain))
    }

    @Test fun anAnswerToAProvenSenderSaysMore() {
        val more = PrepareUploadResponseDto(
            sessionId = "s1",
            files = mapOf("b" to "t2"),
            alreadyHave = listOf("a"),
            uploadSlots = 4,
            resume = mapOf("b" to 1024L)
        )
        assertEquals(
            """{"sessionId":"s1","files":{"b":"t2"},"alreadyHave":["a"],"uploadSlots":4,"resume":{"b":1024}}""",
            LocalSendWire.write(more)
        )
        val back = LocalSendWire.readAnswer(LocalSendWire.write(more))!!
        assertEquals(more, back)
    }

    @Test fun anAnswerFromAnotherAppHasNoneOfTheExtras() {
        val answer = LocalSendWire.readAnswer("""{"sessionId":"mySessionId","files":{"someFileId":"someFileToken"}}""")!!
        assertEquals("mySessionId", answer.statedSessionId)
        assertEquals(mapOf("someFileId" to "someFileToken"), answer.statedFiles)
        assertNull(answer.alreadyHave)
        assertNull(answer.uploadSlots)
        assertNull(answer.resume)
        assertNull(LocalSendWire.readAnswer("""{"sessionId":null,"files":null}""")!!.statedSessionId)
        assertNull(LocalSendWire.readAnswer("""{"sessionId":null,"files":null}""")!!.statedFiles)
    }
}
