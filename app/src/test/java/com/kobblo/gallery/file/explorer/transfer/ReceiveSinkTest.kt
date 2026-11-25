package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.KeptPart
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveResult
import com.kobblo.gallery.file.explorer.transfer.core.ReceiveSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class ReceiveSinkTest {
    @get:Rule val temp = TemporaryFolder()

    private fun everyFile(root: File) = root.walkTopDown().filter { it.isFile }.toList()

    private fun dropping(after: Int, data: ByteArray) = object : InputStream() {
        private var position = 0
        override fun read(): Int {
            if (position >= after) throw IOException("Connection reset")
            return data[position++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= after) throw IOException("Connection reset")
            val count = minOf(len, after - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
    }

    @Test fun aKeptPartSurvivesADropAndNeverLooksLikeTheFile() {
        val root = temp.newFolder()
        val data = ByteArray(2_000_000) { (it % 253).toByte() }
        val name = ReceiveSink.keptPartName("AA", "a.bin", data.size.toLong(), 5)
        val result = ReceiveSink(root, "a.bin").receive(dropping(700_000, data), data.size.toLong(), keep = KeptPart(name))
        assertTrue(result is ReceiveResult.Truncated)
        assertFalse(File(root, "a.bin").exists())
        val part = File(root, name)
        assertEquals(700_000, part.length())
        assertTrue(part.name.startsWith("."))
        assertTrue(part.name.endsWith(ReceiveSink.PART_SUFFIX))
    }

    @Test fun carryingOnMakesTheWholeFile() {
        val root = temp.newFolder()
        val data = ByteArray(2_000_000) { (it % 253).toByte() }
        val name = ReceiveSink.keptPartName("AA", "a.bin", data.size.toLong(), 5)
        ReceiveSink(root, "a.bin").receive(dropping(700_000, data), data.size.toLong(), keep = KeptPart(name))

        val rest = ByteArrayInputStream(data, 700_000, data.size - 700_000)
        val seen = mutableListOf<Long>()
        val result = ReceiveSink(root, "a.bin").receive(rest, data.size.toLong(), keep = KeptPart(name, 700_000), onProgress = { seen += it })
        result as ReceiveResult.Saved
        assertArrayEquals(data, result.file.readBytes())
        assertFalse("nothing left over", File(root, name).exists())
        assertTrue("progress counts the whole file", seen.first() > 700_000)
        assertEquals(data.size.toLong(), seen.last())
    }

    @Test fun carryingOnStillChecksAChecksum() {
        val root = temp.newFolder()
        val data = ByteArray(300_000) { (it % 253).toByte() }
        val name = ReceiveSink.keptPartName("AA", "a.bin", data.size.toLong(), 5)
        ReceiveSink(root, "a.bin").receive(dropping(100_000, data), data.size.toLong(), keep = KeptPart(name))
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val rest = ByteArrayInputStream(data, 100_000, data.size - 100_000)
        assertTrue(ReceiveSink(root, "a.bin").receive(rest, data.size.toLong(), sha, keep = KeptPart(name, 100_000)) is ReceiveResult.Saved)
    }

    @Test fun aCancelledKeptPartIsRemoved() {
        val root = temp.newFolder()
        val data = ByteArray(300_000)
        val name = ReceiveSink.keptPartName("AA", "a.bin", data.size.toLong(), 5)
        var reads = 0
        ReceiveSink(root, "a.bin").receive(
            ByteArrayInputStream(data),
            data.size.toLong(),
            isCancelled = { reads++ > 1 },
            keep = KeptPart(name)
        )
        assertTrue(everyFile(root).isEmpty())
    }

    @Test fun aKeptPartBelongsToOneFileFromOneSender() {
        val base = ReceiveSink.keptPartName("AA", "a.bin", 10, 5)
        assertFalse(base == ReceiveSink.keptPartName("BB", "a.bin", 10, 5))
        assertFalse(base == ReceiveSink.keptPartName("AA", "b.bin", 10, 5))
        assertFalse(base == ReceiveSink.keptPartName("AA", "a.bin", 11, 5))
        assertFalse(base == ReceiveSink.keptPartName("AA", "a.bin", 10, 6))
        assertEquals(base, ReceiveSink.keptPartName("AA", "a.bin", 10, 5))
    }

    @Test fun theTailHashComparesTheBytesBeforeTheJoin() {
        val data = ByteArray(3_000_000) { (it % 251).toByte() }
        val same = ReceiveSink.tailHash(ByteArrayInputStream(data), 2_500_000)
        val copy = data.copyOf()
        assertEquals(same, ReceiveSink.tailHash(ByteArrayInputStream(copy), 2_500_000))
        copy[2_499_000] = 99
        assertFalse(same == ReceiveSink.tailHash(ByteArrayInputStream(copy), 2_500_000))
        assertEquals(null, ReceiveSink.tailHash(ByteArrayInputStream(ByteArray(10)), 2_500_000))
    }

    @Test fun savesExactlyWhatArrived() {
        val root = temp.newFolder()
        val data = ByteArray(200_000) { it.toByte() }
        val result = ReceiveSink(root, "a.bin").receive(ByteArrayInputStream(data), data.size.toLong())
        result as ReceiveResult.Saved
        assertEquals("a.bin", result.file.name)
        assertArrayEquals(data, result.file.readBytes())
        assertEquals(1, everyFile(root).size)
    }

    @Test fun emptyFileIsFine() {
        val root = temp.newFolder()
        val result = ReceiveSink(root, "empty.txt").receive(ByteArrayInputStream(ByteArray(0)), 0)
        result as ReceiveResult.Saved
        assertEquals(0, result.file.length())
    }

    @Test fun doesNotReadPastTheFile() {
        val root = temp.newFolder()
        val stream = ByteArrayInputStream(ByteArray(1000))
        ReceiveSink(root, "a.bin").receive(stream, 400)
        assertEquals(600, stream.available())
    }

    @Test fun keepsFolders() {
        val root = temp.newFolder()
        val result = ReceiveSink(root, "Camera/trip/a.jpg").receive(ByteArrayInputStream(byteArrayOf(1)), 1)
        result as ReceiveResult.Saved
        assertEquals(File(root, "Camera/trip/a.jpg").canonicalPath, result.file.canonicalPath)
    }

    @Test fun droppedConnectionLeavesNothing() {
        val root = temp.newFolder()
        val result = ReceiveSink(root, "folder/a.bin").receive(ByteArrayInputStream(ByteArray(10)), 100)
        assertEquals(ReceiveResult.Truncated(10, 100), result)
        assertTrue(everyFile(root).isEmpty())
        assertFalse("the folder it made is gone too", File(root, "folder").exists())
    }

    @Test fun socketErrorLeavesNothing() {
        val root = temp.newFolder()
        val failing = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 100) 1 else throw IOException("Connection reset")
        }
        val result = ReceiveSink(root, "a.bin").receive(failing, 1000)
        assertTrue(result is ReceiveResult.Truncated)
        assertTrue(everyFile(root).isEmpty())
    }

    @Test fun diskFullIsReportedAsSuch() {
        val root = temp.newFolder()
        val full: (File) -> OutputStream = { part ->
            object : FileOutputStream(part) {
                var written = 0
                override fun write(b: ByteArray, off: Int, len: Int) {
                    written += len
                    if (written > 64 * 1024) throw IOException("write failed: ENOSPC (No space left on device)")
                    super.write(b, off, len)
                }
            }
        }
        val result = ReceiveSink(root, "a.bin", full).receive(ByteArrayInputStream(ByteArray(500_000)), 500_000)
        assertEquals(ReceiveResult.DiskFull, result)
        assertTrue(everyFile(root).isEmpty())
    }

    @Test fun runningOutOfQuotaCountsAsDiskFull() {
        val root = temp.newFolder()
        val full: (File) -> OutputStream = { part ->
            object : FileOutputStream(part) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    throw IOException("write failed: EDQUOT (Disk quota exceeded)")
                }
            }
        }
        val result = ReceiveSink(root, "a.bin", full).receive(ByteArrayInputStream(ByteArray(1000)), 1000)
        assertEquals(ReceiveResult.DiskFull, result)
        assertTrue(everyFile(root).isEmpty())
    }

    @Test fun cancelLeavesNothing() {
        val root = temp.newFolder()
        var reads = 0
        val result = ReceiveSink(root, "a.bin").receive(
            ByteArrayInputStream(ByteArray(1_000_000)),
            1_000_000,
            isCancelled = { reads++ > 2 }
        )
        assertEquals(ReceiveResult.Cancelled, result)
        assertTrue(everyFile(root).isEmpty())
    }

    @Test fun checksumIsVerified() {
        val root = temp.newFolder()
        val data = "hello".toByteArray()
        val good = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertTrue(ReceiveSink(root, "good.txt").receive(ByteArrayInputStream(data), 5, good) is ReceiveResult.Saved)
        val bad = ReceiveSink(root, "bad.txt").receive(ByteArrayInputStream(data), 5, "00".repeat(32))
        assertEquals(ReceiveResult.ChecksumMismatch, bad)
        assertFalse(File(root, "bad.txt").exists())
    }

    @Test fun neverOverwrites() {
        val root = temp.newFolder()
        File(root, "a.txt").writeText("original")
        val result = ReceiveSink(root, "a.txt").receive(ByteArrayInputStream("new".toByteArray()), 3)
        result as ReceiveResult.Saved
        assertEquals("a (2).txt", result.file.name)
        assertEquals("original", File(root, "a.txt").readText())
        assertEquals("new", result.file.readText())
    }

    @Test fun traversalLandsInsideRoot() {
        val parent = temp.newFolder()
        val root = File(parent, "root").apply { mkdirs() }
        val result = ReceiveSink(root, "../../escape.txt").receive(ByteArrayInputStream(byteArrayOf(1)), 1)
        result as ReceiveResult.Saved
        assertEquals(File(root, "escape.txt").canonicalPath, result.file.canonicalPath)
        assertFalse(File(parent, "escape.txt").exists())
    }

    @Test fun oldPartsAreCleanedAndYoungOnesAreLeft() {
        val root = temp.newFolder()
        val old = File(root, ".old${ReceiveSink.PART_SUFFIX}").apply { writeText("x"); setLastModified(1000L) }
        val young = File(root, ".young${ReceiveSink.PART_SUFFIX}").apply { writeText("x") }
        val kept = File(root, "real.jpg").apply { writeText("x"); setLastModified(1000L) }
        assertEquals(1, ReceiveSink.deleteStaleParts(root))
        assertFalse(old.exists())
        assertTrue(young.exists())
        assertTrue(kept.exists())
    }
}
