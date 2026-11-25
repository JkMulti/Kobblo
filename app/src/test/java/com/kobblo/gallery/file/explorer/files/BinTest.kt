package com.kobblo.gallery.file.explorer.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BinTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val index = TestIndex()
    private val followers = TestFollowers()

    private fun bin(shell: ElevatedShell = TestShell(isReady = false), removeStubborn: (File) -> Boolean = { false }) =
        Bin(File(temp.root, ".kobblo_trash"), TestStorage(temp.root), shell, index, followers, removeStubborn)

    private suspend fun Bin.take(vararg files: File) = moveIn(files.map { it.path }, { false }, {})

    @Test
    fun `what goes in is listed with where it came from`() = runBlocking {
        val file = temp.root.with("DCIM/photo.jpg", "12345")
        val bin = bin()
        assertTrue(bin.take(file).ok)
        assertFalse(file.exists())
        val row = bin.list(0L, marks()).single()
        assertEquals("photo.jpg", row.name)
        assertEquals(file.path, row.originalPath)
        assertEquals(bin.pathOf(row.encodedTrashName!!), row.path)
        assertEquals("12345", File(row.path).readText())
        assertEquals("image", row.type)
        assertEquals("The Bin has no coarser word", "image", row.kind)
        assertEquals("5 B", row.size)
        assertEquals(5L, row.sizeBytes)
        assertEquals("me", row.owner)
        assertFalse(row.isPinned)
    }

    @Test
    fun `a folder in the Bin has no size, and says if it's empty`() = runBlocking {
        val full = temp.root.with("full/a.txt").parentFile!!
        val empty = temp.newFolder("empty")
        val bin = bin()
        bin.take(full, empty)
        val rows = bin.list(0L, marks()).associateBy { it.name }
        assertEquals("folder", rows.getValue("full").type)
        assertEquals("", rows.getValue("full").size)
        assertFalse(rows.getValue("full").isEmptyFolder)
        assertTrue(rows.getValue("empty").isEmptyFolder)
        assertEquals(2, rows.values.map { it.id }.toSet().size)
    }

    @Test
    fun `it keeps its lock in the Bin, and nothing forgets it yet`() = runBlocking {
        val file = temp.root.with("private/a.jpg")
        val bin = bin()
        bin.take(file)
        assertEquals(emptyList<String>(), followers.heard)
        assertTrue(bin.list(0L, marks(locks = setOf(file.parent!!))).single().isLocked)
        assertFalse(bin.list(0L, marks()).single().isLocked)
    }

    @Test
    fun `the index hears what left, and only that`() = runBlocking {
        val file = temp.root.with("a.txt")
        val progress = ArrayList<Int>()
        val report = bin().moveIn(listOf(file.path, temp.root.resolve("missing").path), { false }, progress::add)
        assertEquals(listOf(file.path), report.moved)
        assertEquals(1, report.failed.size)
        assertEquals(listOf(file.path), index.forgotten)
        assertEquals(listOf(file.path), index.announced)
        assertEquals(listOf(2), progress)
    }

    @Test
    fun `what comes back goes where it was, and is announced`() = runBlocking {
        val file = temp.root.with("DCIM/photo.jpg", "mine")
        val bin = bin()
        bin.take(file)
        val report = bin.restore(names(bin), { false }, {})
        assertEquals("mine", file.readText())
        assertEquals(listOf(file.path), report.restored)
        assertTrue(index.announced.contains(file.path))
        assertEquals("It's where it was, so nothing has to follow", emptyList<String>(), followers.heard)
        assertEquals(0, bin.list(0L, marks()).size)
    }

    @Test
    fun `a lock follows a file that comes back beside another`() = runBlocking {
        val file = temp.root.with("photo.jpg", "mine")
        val bin = bin()
        bin.take(file)
        file.writeText("another")
        bin.restore(names(bin), { false }, {})
        assertEquals("another", file.readText())
        assertEquals("mine", temp.root.resolve("photo (1).jpg").readText())
        assertEquals(listOf("moved ${file.path} to ${temp.root.resolve("photo (1).jpg").path}"), followers.heard)
    }

    @Test
    fun `deleting for good forgets the lock`() = runBlocking {
        val file = temp.root.with("a.txt")
        val bin = bin()
        bin.take(file)
        val report = bin.deleteForever(names(bin), { false }, {})
        assertEquals(1, report.deleted)
        assertEquals(listOf("forgot ${file.path}"), followers.heard)
        assertEquals(0, bin.list(0L, marks()).size)
    }

    @Test
    fun `old things are cleared out, unless they're kept for ever`() = runBlocking {
        val bin = bin()
        bin.take(temp.root.with("a.txt"))
        Thread.sleep(5)
        assertEquals(1, bin.list(0L, marks()).size)
        assertEquals(1, bin.list(60_000L, marks()).size)
        assertEquals(0, bin.list(1L, marks()).size)
    }

    @Test
    fun `the shell takes what only it can, and the rest goes without it`() = runBlocking {
        val shell = TestShell()
        val app = temp.root.with("Android/data/com.app/it's.txt", "app's")
        val own = temp.root.with("own.txt", "own")
        val bin = bin(shell)
        assertTrue(bin.take(app, own).ok)
        assertFalse(app.exists())
        assertFalse(own.exists())
        assertTrue(shell.commands.isNotEmpty())
        assertTrue(shell.commands.none { "own.txt" in it })
        bin.restore(names(bin), { false }, {})
        assertEquals("app's", app.readText())
        assertEquals("own", own.readText())
    }

    @Test
    fun `a file found in the Bin without a record still shows`() = runBlocking {
        val bin = bin()
        bin.take(temp.root.with("a.txt"))
        bin.folder.with("bin-12-lost.jpg")
        val lost = bin.list(0L, marks()).single { it.name == "lost.jpg" }
        assertNull(lost.originalPath)
        assertFalse(lost.isLocked)
    }

    private suspend fun names(bin: Bin) = bin.list(0L, marks()).map { it.encodedTrashName!! }
}
