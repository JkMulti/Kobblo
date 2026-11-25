package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.transfer.core.PairingCode
import com.kobblo.gallery.file.explorer.transfer.core.PairingParse
import com.kobblo.gallery.file.explorer.transfer.core.PairingWifi
import com.kobblo.gallery.file.explorer.transfer.core.PeerMerge
import com.kobblo.gallery.file.explorer.transfer.core.QrCodes
import com.kobblo.gallery.file.explorer.transfer.core.QrMatrix
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QrCodesTest {
    @get:Rule val temp = TemporaryFolder()

    private val fp = "CD".repeat(32)

    /** Paints the matrix the way the screen does, as pixels, so decoding is a real round trip. */
    private fun render(matrix: QrMatrix, scale: Int = 8, margin: Int = 4, dark: Int = 0xFF000000.toInt(), light: Int = 0xFFFFFFFF.toInt()): Triple<Int, Int, IntArray> {
        val side = (matrix.size + margin * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - margin
            val y = (i / side) / scale - margin
            val on = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (on) dark else light
        }
        return Triple(side, side, pixels)
    }

    @Test fun roundTripsThroughPixels() {
        val code = PairingCode(listOf("192.168.1.5"), 53318, "https", fp, "Pixel 8")
        val (w, h, px) = render(QrCodes.encode(code.encode()))
        val decoded = QrCodes.decode(w, h, px)
        assertEquals(code, (PairingCode.parse(decoded!!) as PairingParse.Ok).code)
    }

    @Test fun roundTripsTheLargestRealCode() {
        val code = PairingCode(
            hosts = listOf("192.168.49.1", "192.168.100.200"),
            port = 53318,
            fingerprint = fp,
            alias = "写真の電話 with a long name".take(64),
            wifi = PairingWifi("DIRECT-abcdefghijklmnopqrstuvwxy", "p@ss word&=+#1".repeat(4))
        )
        val (w, h, px) = render(QrCodes.encode(code.encode()))
        assertEquals(code, (PairingCode.parse(QrCodes.decode(w, h, px)!!) as PairingParse.Ok).code)
    }

    @Test fun readsLightOnDark() {
        val (w, h, px) = render(QrCodes.encode("hello"), dark = 0xFFFFFFFF.toInt(), light = 0xFF000000.toInt())
        assertEquals("hello", QrCodes.decode(w, h, px))
    }

    @Test fun readsACodeThatFillsPartOfALargerImage() {
        val (w, h, px) = render(QrCodes.encode("hello"), scale = 4)
        val big = 600
        val canvas = IntArray(big * big) { 0xFF888888.toInt() }
        for (y in 0 until h) for (x in 0 until w) canvas[(y + 100) * big + x + 150] = px[y * w + x]
        assertEquals("hello", QrCodes.decode(big, big, canvas))
    }

    @Test fun anImageWithNoCodeReadsAsNothing() {
        val blank = IntArray(200 * 200) { 0xFFFFFFFF.toInt() }
        assertNull(QrCodes.decode(200, 200, blank))
    }

    @Test fun savedPeersPersistAndForget() {
        val file = File(temp.root, "peers.json")
        val store = SavedPeerStore(file)
        store.save(SavedPeer(fp, "Mac", host = "10.0.0.2", port = 53317))
        assertEquals("Mac", SavedPeerStore(file).all().single().alias)
        store.forget(fp.lowercase())
        assertTrue(SavedPeerStore(file).all().isEmpty())
    }

    @Test fun aSavedPeerStaysVerifiedOnceItWas() {
        val store = SavedPeerStore(File(temp.root, "peers.json"))
        store.save(SavedPeer(fp, "Mac", host = "10.0.0.2", port = 1, verified = true))
        val updated = store.save(SavedPeer(fp, "Renamed", host = "10.0.0.3", port = 2, verified = false)).single()
        assertTrue(updated.verified)
        assertEquals("Renamed", updated.alias)
        assertEquals("10.0.0.3", updated.host)
    }

    @Test fun aSavedPeerFileCantInjectAnAddress() {
        val file = File(temp.root, "peers.json").apply {
            writeText("""[{"fingerprint":"$fp","alias":"x","host":"evil.com/path","port":1},{"fingerprint":"$fp","alias":"y","host":"10.0.0.1","port":1}]""")
        }
        assertEquals(listOf("y"), SavedPeerStore(file).all().map { it.alias })
    }

    @Test fun aDamagedSavedPeerFileIsJustEmpty() {
        val file = File(temp.root, "peers.json").apply { writeText("not json") }
        assertTrue(SavedPeerStore(file).all().isEmpty())
    }

    private fun peer(ip: String, alias: String, fingerprint: String, verified: Boolean = false) =
        NearbyPeer(ip, 1, alias, null, "mobile", "https", fingerprint, 0, verified)

    @Test fun mergeShowsADeviceOnce() {
        val merged = PeerMerge.merge(
            discovered = listOf(peer("1.1.1.1", "Mac", "AA")),
            direct = listOf(peer("10.0.0.5", "Mac", "aa")),
            saved = emptyList()
        )
        assertEquals(1, merged.size)
        assertEquals("10.0.0.5", merged.single().ip)
    }

    @Test fun mergeKeepsDifferentDevicesSorted() {
        val merged = PeerMerge.merge(
            listOf(peer("1.1.1.1", "zebra", "AA")),
            listOf(peer("1.1.1.2", "Apple", "BB")),
            emptyList()
        )
        assertEquals(listOf("Apple", "zebra"), merged.map { it.alias })
    }

    @Test fun mergeMarksScannedDevicesVerifiedHoweverFound() {
        val merged = PeerMerge.merge(listOf(peer("1.1.1.1", "Mac", "aa")), emptyList(), listOf(saved("AA", verified = true)))
        assertTrue(merged.single().verified)
    }

    @Test fun mergeDoesntVerifyOthers() {
        val merged = PeerMerge.merge(listOf(peer("1.1.1.1", "Mac", "BB")), emptyList(), listOf(saved("AA", verified = true)))
        assertFalse(merged.single().verified)
    }

    @Test fun mergeKeepsDevicesWithNoFingerprintApart() {
        val merged = PeerMerge.merge(listOf(peer("1.1.1.1", "A", ""), peer("1.1.1.2", "B", "")), emptyList(), emptyList())
        assertEquals(2, merged.size)
    }

    private fun saved(fingerprint: String, verified: Boolean = false, lastUsedMs: Long = 0) =
        SavedPeer(fingerprint, "Mac", host = "10.0.0.5", port = 1, verified = verified, lastUsedMs = lastUsedMs)
}
