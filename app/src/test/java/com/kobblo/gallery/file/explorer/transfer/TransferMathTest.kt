package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.FolderWalker
import com.kobblo.gallery.file.explorer.transfer.core.LocalAddresses
import com.kobblo.gallery.file.explorer.transfer.core.ProgressGate
import com.kobblo.gallery.file.explorer.transfer.core.SpeedMeter
import com.kobblo.gallery.file.explorer.transfer.core.TransferMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class TransferMathTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun speedNeedsTwoReadings() {
        val meter = SpeedMeter()
        meter.update(0, 0)
        assertEquals(0, meter.bytesPerSecond)
        meter.update(1000, 1000)
        assertEquals(1000, meter.bytesPerSecond)
    }

    @Test fun speedIgnoresReadingsTooCloseTogether() {
        val meter = SpeedMeter()
        meter.update(0, 0)
        meter.update(1000, 1000)
        meter.update(1001, 900_000)
        assertEquals(1000, meter.bytesPerSecond)
    }

    @Test fun speedIsSmoothed() {
        val meter = SpeedMeter(smoothing = 0.5)
        meter.update(0, 0)
        meter.update(1000, 1000)
        meter.update(2000, 4000) // 3000/s against 1000/s
        assertEquals(2000, meter.bytesPerSecond)
    }

    @Test fun etaRoundsUp() {
        assertEquals(2L, TransferMath.etaSeconds(1001, 1000))
        assertEquals(1L, TransferMath.etaSeconds(1000, 1000))
        assertEquals(0L, TransferMath.etaSeconds(0, 1000))
    }

    @Test fun etaIsUnknownWithoutASpeed() {
        assertNull(TransferMath.etaSeconds(1000, 0))
    }

    @Test fun percentOnlyReachesAHundredAtTheEnd() {
        assertEquals(99, TransferMath.percent(999_999, 1_000_000))
        assertEquals(100, TransferMath.percent(1_000_000, 1_000_000))
        assertEquals(0, TransferMath.percent(0, 0))
        assertEquals(50, TransferMath.percent(5_000_000_000L, 10_000_000_000L))
    }

    @Test fun gateLimitsButForceGoesThrough() {
        val gate = ProgressGate(100)
        assertTrue(gate.allow(1000))
        assertFalse(gate.allow(1050))
        assertTrue(gate.allow(1060, force = true))
        assertFalse(gate.allow(1100))
        assertTrue(gate.allow(1200))
    }

    @Test fun addressesSkipWhatNobodyCanReach() {
        val ranked = LocalAddresses.rank(
            listOf(
                LocalAddresses.Candidate("rmnet0", "100.64.1.1"),
                LocalAddresses.Candidate("lo", "127.0.0.1"),
                LocalAddresses.Candidate("wlan0", "192.168.1.5"),
                LocalAddresses.Candidate("eth0", "10.0.2.15"),
                LocalAddresses.Candidate("wlan0", "169.254.1.1"),
                LocalAddresses.Candidate("tun0", "10.8.0.1")
            )
        )
        assertEquals(listOf("192.168.1.5", "10.0.2.15"), ranked)
    }

    @Test fun hotspotComesFirstWhenAskedFor() {
        val candidates = listOf(
            LocalAddresses.Candidate("wlan0", "192.168.1.5"),
            LocalAddresses.Candidate("swlan0", "192.168.43.1")
        )
        assertEquals("192.168.1.5", LocalAddresses.rank(candidates).first())
        assertEquals("192.168.43.1", LocalAddresses.rank(candidates, preferHotspot = true).first())
    }

    @Test fun walkKeepsRelativePathsFromTheFolderItself() {
        val root = temp.newFolder("Camera")
        File(root, "a.jpg").writeText("1")
        File(root, "trip/deep").mkdirs()
        File(root, "trip/deep/b.jpg").writeText("2")
        val paths = FolderWalker.walk(root).map { it.relativePath }
        assertEquals(listOf("Camera/a.jpg", "Camera/trip/deep/b.jpg"), paths)
    }

    @Test fun walkSkipsSymlinks() {
        val root = temp.newFolder("Camera")
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("x")
        File(root, "a.jpg").writeText("1")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "filelink").toPath(), File(outside, "secret.txt").toPath())
        assertEquals(listOf("Camera/a.jpg"), FolderWalker.walk(root).map { it.relativePath })
    }

    @Test fun walkStopsAtTheLimit() {
        val root = temp.newFolder("Many")
        (1..10).forEach { File(root, "f$it").writeText("x") }
        assertEquals(3, FolderWalker.walk(root, limit = 3).size)
    }
}
