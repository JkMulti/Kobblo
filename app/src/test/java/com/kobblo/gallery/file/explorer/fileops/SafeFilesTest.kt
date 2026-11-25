package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class SafeFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun folder(name: String) = temp.newFolder(name)

    @Test
    fun `kotlin's own recursive delete follows a link out of the folder, which is why this exists`() {
        val outside = folder("outside").apply { resolve("precious.txt").writeText("keep me") }
        val selected = folder("selected")
        Files.createSymbolicLink(selected.resolve("link").toPath(), outside.toPath())

        selected.deleteRecursively()

        assertFalse("This documents the library. If it passes one day, the guard is merely spare", outside.resolve("precious.txt").exists())
    }

    @Test
    fun `deleting a folder leaves what a link in it points at`() {
        val outside = folder("outside").apply { resolve("precious.txt").writeText("keep me") }
        val selected = folder("selected")
        selected.resolve("own.txt").writeText("goes")
        selected.resolve("sub").mkdirs()
        selected.resolve("sub/deep.txt").writeText("goes")
        Files.createSymbolicLink(selected.resolve("sub/link").toPath(), outside.toPath())
        Files.createSymbolicLink(selected.resolve("filelink").toPath(), outside.resolve("precious.txt").toPath())

        assertTrue(SafeFiles.deleteTree(selected))

        assertFalse(selected.exists())
        assertEquals("keep me", outside.resolve("precious.txt").readText())
    }

    @Test
    fun `deleting a link itself leaves its target`() {
        val outside = folder("outside").apply { resolve("precious.txt").writeText("keep me") }
        val link = File(temp.root, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(SafeFiles.deleteTree(link))
        assertFalse(SafeFiles.existsAtAll(link))
        assertTrue(outside.resolve("precious.txt").exists())
    }

    @Test
    fun `a dangling link still gets deleted, though File can't see it`() {
        val link = File(temp.root, "dangling")
        Files.createSymbolicLink(link.toPath(), File(temp.root, "nothing").toPath())
        assertFalse(link.exists())
        assertTrue(SafeFiles.existsAtAll(link))
        assertTrue(SafeFiles.deleteTree(link))
        assertFalse(SafeFiles.existsAtAll(link))
    }

    @Test
    fun `size doesn't follow links, so a loop can't make it hang`() {
        val root = folder("root")
        root.resolve("a.txt").writeBytes(ByteArray(10))
        Files.createSymbolicLink(root.resolve("loop").toPath(), root.toPath())
        val size = SafeFiles.sizeOf(root)
        assertEquals(10L, size.bytes)
        assertEquals(1, size.files)
    }

    @Test
    fun `the inside of a folder is recognised, and only the inside`() {
        val parent = folder("parent")
        val child = parent.resolve("child/deep").apply { mkdirs() }
        assertTrue(SafeFiles.isSameOrInside(parent, parent))
        assertTrue(SafeFiles.isSameOrInside(parent, child))
        assertFalse(SafeFiles.isSameOrInside(child, parent))
        // A shared prefix isn't being inside.
        assertFalse(SafeFiles.isSameOrInside(parent, folder("parent-two")))
        // Nor does a link fool it.
        val link = File(temp.root, "shortcut")
        Files.createSymbolicLink(link.toPath(), child.toPath())
        assertTrue(SafeFiles.isSameOrInside(parent, link))
    }

    @Test
    fun `a free name keeps the extension and counts up`() {
        val taken = setOf("photo.jpg", "photo (1).jpg", "archive.tar.gz", ".env", "v1.2")
        fun free(name: String, isFolder: Boolean = false) = SafeFiles.freeName(name, isFolder) { it in taken }
        assertEquals("other.jpg", free("other.jpg"))
        assertEquals("photo (2).jpg", free("photo.jpg"))
        assertEquals("archive (1).tar.gz", free("archive.tar.gz"))
        assertEquals(".env (1)", free(".env"))
        assertEquals("v1.2 (1)", free("v1.2", isFolder = true))
    }

    @Test
    fun `a free name for a name already at the limit stays within it`() {
        val longName = "x".repeat(251) + ".jpg"
        val free = SafeFiles.freeName(longName) { it == longName }
        assertTrue(SafeFiles.utf8Length(free) <= SafeFiles.MaxNameBytes)
        assertTrue(free.endsWith(" (1).jpg"))
        // Multibyte characters aren't cut in half.
        val wide = "日".repeat(84) + ".jpg"
        val freeWide = SafeFiles.freeName(wide) { it == wide }
        assertTrue(SafeFiles.utf8Length(freeWide) <= SafeFiles.MaxNameBytes)
        assertFalse(freeWide.contains('�'))
    }

    @Test
    fun `saving atomically replaces the contents`() {
        val target = temp.newFile("a.txt").apply { writeText("old") }
        SafeFiles.writeAtomically(target, "new")
        assertEquals("new", target.readText())
        assertEquals(listOf("a.txt"), temp.root.list()!!.toList())
    }

    @Test
    fun `a failure partway through a save leaves the original and no litter`() {
        val target = temp.newFile("a.txt").apply { writeText("original") }
        assertThrows(IOException::class.java) {
            SafeFiles.writeAtomically(target) { out ->
                out.write("half".toByteArray())
                throw IOException("No space left on device")
            }
        }
        assertEquals("original", target.readText())
        assertEquals(listOf("a.txt"), temp.root.list()!!.toList())
    }

    @Test
    fun `saving through a link updates the file and keeps the link`() {
        val real = temp.newFile("real.txt").apply { writeText("old") }
        val link = File(temp.root, "link.txt")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        SafeFiles.writeAtomically(link, "new")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("new", real.readText())
    }

    @Test
    fun `saving keeps a file's permissions`() {
        val target = temp.newFile("secret.txt").apply { writeText("old") }
        val modes = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(target.toPath(), modes)
        SafeFiles.writeAtomically(target, "new")
        assertEquals(modes, Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `an old temporary file is cleared, and one that could still be in use isn't`() {
        val folder = folder("f")
        val now = System.currentTimeMillis()
        val old = SafeFiles.partFileIn(folder).apply { writeText("x"); setLastModified(now - 24 * 60 * 60 * 1000) }
        val recent = File(folder, old.name + "b").apply { writeText("y") }
        val someoneElse = File(folder, "photo.jpg").apply { writeText("z"); setLastModified(now - 24 * 60 * 60 * 1000) }
        SafeFiles.clearStaleParts(folder, now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(someoneElse.exists())
    }

    @Test
    fun `reasons are read out of what the system says`() {
        assertEquals(FailReason.NO_SPACE, reasonFor(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(FailReason.DENIED, reasonFor(IOException("open failed: EACCES (Permission denied)")))
        assertEquals(FailReason.NAME_TOO_LONG, reasonFor(IOException("open failed: ENAMETOOLONG (File name too long)")))
        assertEquals(FailReason.MISSING, reasonFor(IOException("open failed: ENOENT (No such file or directory)")))
        assertEquals(FailReason.NO_SPACE, reasonFor(IOException("outer", IOException("No space left on device"))))
        assertEquals(FailReason.INTO_ITSELF, reasonFor(FileOpException(FailReason.INTO_ITSELF, "x")))
        assertEquals(FailReason.UNKNOWN, reasonFor(IOException("what")))
    }

    // Copying

    @Test
    fun `copying a tree brings everything and keeps dates`() = runBlocking {
        val source = folder("src").apply {
            resolve("a.txt").writeText("a")
            resolve("sub/deep").mkdirs()
            resolve("sub/deep/b.bin").writeBytes(ByteArray(1024 * 1024) { it.toByte() })
            resolve("empty").mkdirs()
        }
        source.resolve("a.txt").setLastModified(1_600_000_000_000L)
        val dest = File(temp.root, "dest")
        TreeCopier(verify = true).copy(source, dest)
        assertEquals("a", dest.resolve("a.txt").readText())
        assertArrayEquals(source.resolve("sub/deep/b.bin").readBytes(), dest.resolve("sub/deep/b.bin").readBytes())
        assertTrue(dest.resolve("empty").isDirectory)
        assertEquals(1_600_000_000_000L, dest.resolve("a.txt").lastModified())
    }

    @Test
    fun `copying won't write over anything`() = runBlocking {
        val source = temp.newFile("a.txt").apply { writeText("new") }
        val dest = temp.newFile("b.txt").apply { writeText("precious") }
        val thrown = try {
            TreeCopier(verify = false).copy(source, dest)
            null
        } catch (e: FileOpException) {
            e
        }
        assertEquals(FailReason.NOT_POSSIBLE, thrown?.reason)
        assertEquals("precious", dest.readText())
    }

    @Test
    fun `copying recreates a link instead of following it`() = runBlocking {
        val outside = folder("outside").apply { resolve("huge.bin").writeBytes(ByteArray(4096)) }
        val source = folder("src").apply { resolve("own.txt").writeText("x") }
        Files.createSymbolicLink(source.resolve("away").toPath(), outside.toPath())
        Files.createSymbolicLink(source.resolve("loop").toPath(), source.toPath())
        val dest = File(temp.root, "dest")
        TreeCopier(verify = false).copy(source, dest)
        assertTrue(Files.isSymbolicLink(dest.resolve("away").toPath()))
        assertTrue(Files.isSymbolicLink(dest.resolve("loop").toPath()))
        assertEquals(1, SafeFiles.sizeOf(dest).files)
    }

    private class Failing(private val out: OutputStream, private val failAfter: Int, private val message: String) : OutputStream() {
        private var written = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > failAfter) throw IOException(message)
            out.write(b, off, len)
            written += len
        }

        override fun close() = out.close()
    }

    @Test
    fun `running out of space leaves no file behind`() = runBlocking {
        val source = temp.newFile("big.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
        val destFolder = folder("dest")
        val copier = TreeCopier(
            verify = true,
            openOutput = { Failing(it.outputStream(), 300 * 1024, "write failed: ENOSPC (No space left on device)") }
        )
        val thrown = try {
            copier.copy(source, destFolder.resolve("big.bin"))
            null
        } catch (e: IOException) {
            e
        }
        assertEquals(FailReason.NO_SPACE, reasonFor(thrown!!))
        assertEquals("Nothing half-written, under any name", 0, destFolder.list()!!.size)
        assertEquals(1024 * 1024L, source.length())
    }

    @Test
    fun `a short read is caught rather than called complete`() = runBlocking {
        val source = temp.newFile("big.bin").apply { writeBytes(ByteArray(1024 * 1024)) }
        val destFolder = folder("dest")
        val copier = TreeCopier(
            verify = true,
            openInput = { object : InputStream() {
                private var given = 0

                override fun read(): Int = if (given++ < 1000) 7 else -1
            } }
        )
        val thrown = try {
            copier.copy(source, destFolder.resolve("big.bin"))
            null
        } catch (e: FileOpException) {
            e
        }
        assertEquals(FailReason.CHECK_FAILED, thrown?.reason)
        assertEquals(0, destFolder.list()!!.size)
    }

    @Test
    fun `a copy that reads back different is refused`() = runBlocking {
        val content = ByteArray(1024 * 1024) { it.toByte() }
        val source = temp.newFile("big.bin").apply { writeBytes(content) }
        val destFolder = folder("dest")
        // Bytes change on their way to the disk. The size comes out right, so only reading back can tell.
        val corrupting = TreeCopier(
            verify = true,
            openOutput = { file ->
                val real = file.outputStream()
                object : OutputStream() {
                    override fun write(b: Int) = real.write(b xor 1)

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val changed = b.copyOfRange(off, off + len)
                        changed[0] = (changed[0] + 1).toByte()
                        real.write(changed)
                    }

                    override fun close() = real.close()
                }
            }
        )
        val thrown = try {
            corrupting.copy(source, destFolder.resolve("big.bin"))
            null
        } catch (e: FileOpException) {
            e
        }
        assertEquals(FailReason.CHECK_FAILED, thrown?.reason)
        assertEquals(0, destFolder.list()!!.size)
    }

    @Test
    fun `cancelling a copy leaves no partial file`() = runBlocking {
        val source = temp.newFile("big.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024)) }
        val destFolder = folder("dest")
        var steps = 0
        val copier = TreeCopier(verify = false, checkpoint = {
            if (++steps > 3) throw CancellationException("stop")
        })
        try {
            copier.copy(source, destFolder.resolve("big.bin"))
            throw AssertionError("Should have been cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(0, destFolder.list()!!.size)
        assertEquals(4 * 1024 * 1024L, source.length())
    }
}
