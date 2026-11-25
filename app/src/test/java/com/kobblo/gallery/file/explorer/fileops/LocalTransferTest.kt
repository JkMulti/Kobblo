package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class LocalTransferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun folder(name: String) = temp.newFolder(name)

    private fun File.withFile(name: String, text: String): File = resolve(name).also { it.parentFile?.mkdirs(); it.writeText(text) }

    @Test
    fun `a file pasted into its own folder doesn't destroy itself`() = runBlocking {
        val here = folder("here")
        val file = here.withFile("note.txt", "precious")
        val report = LocalTransfer().copyInto(listOf(file), here)
        assertEquals("precious", file.readText())
        assertEquals("precious", here.resolve("note (1).txt").readText())
        assertEquals(1, report.done)
        assertEquals(1, report.renamed)
    }

    @Test
    fun `pasting over a name that's taken keeps both`() = runBlocking {
        val from = folder("from")
        val to = folder("to")
        val incoming = from.withFile("a.txt", "new")
        val existing = to.withFile("a.txt", "old")
        val report = LocalTransfer().copyInto(listOf(incoming), to)
        assertEquals("old", existing.readText())
        assertEquals("new", to.resolve("a (1).txt").readText())
        assertEquals(1, report.renamed)
    }

    @Test
    fun `pasting a folder over one with the same name doesn't merge into it`() = runBlocking {
        val from = folder("from")
        val to = folder("to")
        from.withFile("album/a.jpg", "new")
        to.withFile("album/a.jpg", "old")
        val report = LocalTransfer().copyInto(listOf(from.resolve("album")), to)
        assertEquals("old", to.resolve("album/a.jpg").readText())
        assertEquals("new", to.resolve("album (1)/a.jpg").readText())
        assertTrue(report.ok)
    }

    @Test
    fun `moving over a name that's taken keeps both`() = runBlocking {
        val from = folder("from")
        val to = folder("to")
        val incoming = from.withFile("a.txt", "new")
        val existing = to.withFile("a.txt", "old")
        val report = LocalTransfer().moveInto(listOf(incoming), to)
        assertEquals("old", existing.readText())
        assertEquals("new", to.resolve("a (1).txt").readText())
        assertFalse(incoming.exists())
        assertEquals(listOf(incoming.path), report.movedFrom)
    }

    @Test
    fun `moving a file to where it already is does nothing and says so calmly`() = runBlocking {
        val here = folder("here")
        val file = here.withFile("a.txt", "precious")
        val report = LocalTransfer().moveInto(listOf(file), here)
        assertEquals("precious", file.readText())
        assertTrue(report.ok)
        assertEquals(1, here.list()!!.size)
        assertEquals(emptyList<String>(), report.movedFrom)
    }

    @Test
    fun `a folder can't be copied or moved into itself`() = runBlocking {
        val folder = folder("folder").apply { withFile("sub/a.txt", "precious") }
        val inside = folder.resolve("sub")

        for (report in listOf(
            LocalTransfer().copyInto(listOf(folder), inside),
            LocalTransfer().copyInto(listOf(folder), folder),
            LocalTransfer().moveInto(listOf(folder), inside)
        )) {
            assertEquals(listOf(FailReason.INTO_ITSELF), report.failed.map { it.reason })
        }
        assertEquals("precious", inside.resolve("a.txt").readText())
        assertEquals(listOf("sub"), folder.list()!!.toList())
        assertEquals(listOf("a.txt"), inside.list()!!.toList())
    }

    /** What a move between volumes is: the rename always fails, so it has to copy. */
    private val noRename: (File, File) -> Boolean = { _, _ -> false }

    /** Fails on the second file it's asked to write, the way a disk filling up would. */
    private fun fillsUpOnSecondFile(): (File) -> OutputStream {
        var opened = 0
        return { file ->
            opened++
            val fails = opened > 1
            val real = file.outputStream()
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (fails) throw IOException("write failed: ENOSPC (No space left on device)")
                    real.write(b, off, len)
                }

                override fun close() = real.close()
            }
        }
    }

    @Test
    fun `a move between volumes copies, checks, and only then removes the source`() = runBlocking {
        val from = folder("from")
        from.withFile("album/a.txt", "a").setLastModified(1_600_000_000_000L)
        from.withFile("album/sub/b.txt", "b")
        val to = folder("to")
        val report = LocalTransfer(rename = noRename).moveInto(listOf(from.resolve("album")), to)
        assertTrue(report.ok)
        assertEquals("a", to.resolve("album/a.txt").readText())
        assertEquals("b", to.resolve("album/sub/b.txt").readText())
        assertEquals(1_600_000_000_000L, to.resolve("album/a.txt").lastModified())
        assertFalse(from.resolve("album").exists())
    }

    @Test
    fun `a failed copy during a move leaves the source whole and removes the partial`() = runBlocking {
        val from = folder("from")
        val first = from.withFile("album/a.bin", "x".repeat(1000))
        val second = from.withFile("album/b.bin", "y".repeat(1000))
        val to = folder("to")
        val report = LocalTransfer(openOutput = fillsUpOnSecondFile(), rename = noRename).moveInto(listOf(from.resolve("album")), to)
        assertEquals(listOf(FailReason.NO_SPACE), report.failed.map { it.reason })
        assertEquals("Nothing partial at the destination", 0, to.list()!!.size)
        assertEquals("x".repeat(1000), first.readText())
        assertEquals("y".repeat(1000), second.readText())
        assertEquals(emptyList<String>(), report.movedFrom)
    }

    @Test
    fun `a move whose copy reads back different keeps the source`() = runBlocking {
        val from = folder("from")
        val source = from.withFile("photo.jpg", "x".repeat(1000))
        val to = folder("to")
        val corrupting: (File) -> OutputStream = { file ->
            val real = file.outputStream()
            object : OutputStream() {
                override fun write(b: Int) = real.write(b)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    real.write(b.copyOfRange(off, off + len).also { it[0] = 0 })
                }

                override fun close() = real.close()
            }
        }
        val report = LocalTransfer(openOutput = corrupting, rename = noRename).moveInto(listOf(source), to)
        assertEquals(listOf(FailReason.CHECK_FAILED), report.failed.map { it.reason })
        assertEquals("x".repeat(1000), source.readText())
        assertEquals(0, to.list()!!.size)
    }

    @Test
    fun `cancelling a move between volumes keeps the source and removes the partial`() = runBlocking {
        val from = folder("from")
        val source = from.withFile("big.bin", "x".repeat(4 * 1024 * 1024))
        val to = folder("to")
        var checks = 0
        val transfer = LocalTransfer(rename = noRename, checkpoint = { if (++checks > 2) throw CancellationException("stop") })
        val report = transfer.moveInto(listOf(source), to)
        assertTrue(report.cancelled)
        assertEquals(4 * 1024 * 1024L, source.length())
        assertEquals(0, to.list()!!.size)
    }

    @Test
    fun `a move that can't remove its source says so and keeps both`() = runBlocking {
        val from = folder("from")
        val readOnlyFolder = from.resolve("locked").apply { mkdirs() }
        val source = readOnlyFolder.withFile("a.txt", "x")
        val to = folder("to")
        readOnlyFolder.setWritable(false)
        try {
            if (source.canWrite() && readOnlyFolder.canWrite()) return@runBlocking // Running as a user this doesn't bind.
            val report = LocalTransfer(rename = noRename).moveInto(listOf(source), to)
            assertEquals(listOf(FailReason.DENIED), report.failed.map { it.reason })
            assertEquals("x", to.resolve("a.txt").readText())
            assertEquals("x", source.readText())
        } finally {
            readOnlyFolder.setWritable(true)
        }
    }

    @Test
    fun `not enough room is refused before a byte is written`() = runBlocking {
        val from = folder("from")
        val source = from.withFile("big.bin", "x".repeat(2048))
        val to = folder("to")
        val report = LocalTransfer(freeSpace = { 1024L }).copyInto(listOf(source), to)
        assertEquals(listOf(FailReason.NO_SPACE), report.failed.map { it.reason })
        assertEquals(0, to.list()!!.size)
        assertTrue(source.exists())
    }

    @Test
    fun `an unknown amount of free space doesn't stop a copy`() = runBlocking {
        val from = folder("from")
        val source = from.withFile("a.bin", "x".repeat(2048))
        val to = folder("to")
        val report = LocalTransfer(freeSpace = { 0L }).copyInto(listOf(source), to)
        assertTrue(report.ok)
        assertTrue(to.resolve("a.bin").exists())
    }

    @Test
    fun `one failure doesn't stop the rest, and is reported`() = runBlocking {
        val from = folder("from")
        val good = from.withFile("good.txt", "x")
        val missing = from.resolve("missing.txt")
        val alsoGood = from.withFile("alsogood.txt", "y")
        val to = folder("to")
        val report = LocalTransfer().copyInto(listOf(good, missing, alsoGood), to)
        assertEquals(2, report.done)
        assertEquals(listOf(TransferFailure(missing.path, FailReason.MISSING)), report.failed)
        assertTrue(to.resolve("good.txt").exists())
        assertTrue(to.resolve("alsogood.txt").exists())
    }

    @Test
    fun `cancelling a copy removes only what the copy made`() = runBlocking {
        val from = folder("from")
        val first = from.withFile("1.bin", "x".repeat(1000))
        val second = from.withFile("2.bin", "y".repeat(1000))
        val to = folder("to")
        val existing = to.withFile("existing/keep.txt", "precious")
        var checks = 0
        val transfer = LocalTransfer(checkpoint = {
            // Lets the first file finish, then stops in the second.
            if (++checks > 5) throw CancellationException("stop")
        })
        val report = transfer.copyInto(listOf(first, second), to)
        assertTrue(report.cancelled)
        assertEquals("precious", existing.readText())
        assertEquals(listOf("existing"), to.list()!!.toList())
        assertTrue(first.exists() && second.exists())
    }

    @Test
    fun `cancelling a move never deletes what already moved`() = runBlocking {
        // The old code removed everything it had made on cancel, including files already renamed out of
        // their folder, which were by then the only copy.
        val from = folder("from")
        val first = from.withFile("1.txt", "first")
        val second = from.withFile("2.txt", "second")
        val to = folder("to")
        var checks = 0
        val transfer = LocalTransfer(checkpoint = {
            if (++checks == 2) throw CancellationException("stop")
        })
        val report = transfer.moveInto(listOf(first, second), to)
        assertTrue(report.cancelled)
        assertEquals("first", to.resolve("1.txt").readText())
        assertFalse(first.exists())
        assertEquals("second", second.readText())
        assertEquals(listOf(first.path), report.movedFrom)
    }

    @Test
    fun `a folder that can't be made is a reported failure`() = runBlocking {
        val from = folder("from")
        val source = from.withFile("a.txt", "x")
        val blocker = temp.newFile("not-a-folder")
        val report = LocalTransfer().copyInto(listOf(source), blocker.resolve("child"))
        assertEquals(1, report.failed.size)
        assertTrue(source.exists())
        assertEquals(0L, blocker.length())
    }

    @Test
    fun `a folder pasted with a link in it doesn't pull in what the link points at`() = runBlocking {
        val outside = folder("outside").apply { withFile("huge.bin", "x".repeat(4096)) }
        val from = folder("from")
        from.withFile("album/a.jpg", "a")
        java.nio.file.Files.createSymbolicLink(from.resolve("album/away").toPath(), outside.toPath())
        val to = folder("to")
        val report = LocalTransfer().copyInto(listOf(from.resolve("album")), to)
        assertTrue(report.ok)
        assertEquals(1, SafeFiles.sizeOf(to).files)
    }

    @Test
    fun `reports add up`() {
        val one = TransferReport(done = 1, renamed = 1, outputs = listOf("a"))
        val two = TransferReport(done = 2, failed = listOf(TransferFailure("b", FailReason.DENIED)), cancelled = true)
        val sum = one + two
        assertEquals(3, sum.done)
        assertEquals(1, sum.renamed)
        assertEquals(1, sum.failed.size)
        assertTrue(sum.cancelled)
        assertFalse(sum.ok)
    }
}
