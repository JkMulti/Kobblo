package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.fileops.RenameProblem
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FileEditsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val noShell = TestShell(isReady = false)
    private val followers = TestFollowers()
    private val index = TestIndex()

    private fun edits(shell: ElevatedShell = noShell, card: String? = null) = FileEdits(TestStorage(temp.root, card), shell, followers)

    private fun deletion(shell: ElevatedShell = noShell, root: File = temp.root) = FileDeletion(TestStorage(root), shell, index, followers)

    private fun restricted(): File = temp.root.resolve("Android/data").also { it.mkdirs() }

    // Making

    @Test
    fun `a folder is made once`() {
        assertTrue(edits().createFolder(temp.root.path, "New folder"))
        assertTrue(temp.root.resolve("New folder").isDirectory)
        assertFalse("It's already there", edits().createFolder(temp.root.path, "New folder"))
        assertFalse("A file has the name", edits().createFolder(temp.root.path, temp.root.with("file").name))
    }

    @Test
    fun `a file is made once, and never over another`() {
        assertTrue(edits().createFile(temp.root.path, "a.txt"))
        assertEquals(0L, temp.root.resolve("a.txt").length())
        temp.root.with("b.txt", "precious")
        assertFalse(edits().createFile(temp.root.path, "b.txt"))
        assertEquals("precious", temp.root.resolve("b.txt").readText())
        assertFalse("There's no such folder", edits().createFile(temp.root.resolve("missing").path, "a.txt"))
    }

    @Test
    fun `words stand for the storages`() {
        val card = temp.newFolder("card")
        val edits = edits(card = card.path)
        assertTrue(edits.createFolder("home", "on phone"))
        assertTrue(temp.root.resolve("on phone").isDirectory)
        assertTrue(edits.createFolder("sd", "one"))
        assertTrue(edits.createFile("sd/", "two.txt"))
        assertEquals(setOf("one", "two.txt"), card.list()!!.toSet())
    }

    @Test
    fun `the shell makes folders where only it can`() {
        val shell = TestShell()
        val folder = restricted()
        assertTrue(edits(shell).createFolder(folder.path + "/", "it's new"))
        assertEquals(listOf(ShellCommand.makeFolders(folder.path + "/it's new")), shell.commands.map { it.unwrapped() })
        assertTrue(folder.resolve("it's new").isDirectory)
    }

    @Test
    fun `without a shell the app tries by itself`() {
        val folder = restricted()
        assertTrue(edits().createFolder(folder.path, "new"))
        assertTrue(folder.resolve("new").isDirectory)
    }

    // Renaming

    @Test
    fun `a rename moves the lock and the star along`() {
        val file = temp.root.with("old.txt", "text")
        assertNull(edits().rename(file.path, "new.txt"))
        assertEquals("text", temp.root.resolve("new.txt").readText())
        assertFalse(file.exists())
        assertEquals(listOf("moved ${file.path} to ${temp.root.resolve("new.txt").path}"), followers.heard)
    }

    @Test
    fun `a rename never replaces another file`() {
        val file = temp.root.with("a.txt", "mine")
        temp.root.with("b.txt", "precious")
        assertEquals(RenameProblem.NAME_TAKEN, edits().rename(file.path, "b.txt"))
        assertEquals("precious", temp.root.resolve("b.txt").readText())
        assertEquals("mine", file.readText())
        assertEquals(emptyList<String>(), followers.heard)
    }

    @Test
    fun `a link that leads nowhere still has its name`() {
        val file = temp.root.with("a.txt")
        Files.createSymbolicLink(temp.root.toPath().resolve("link"), temp.root.toPath().resolve("nowhere"))
        assertEquals(RenameProblem.NAME_TAKEN, edits().rename(file.path, "link"))
        assertNull("And it can be renamed itself", edits().rename(temp.root.resolve("link").path, "link 2"))
    }

    @Test
    fun `a name only changing case isn't taken by the file itself`() {
        val file = temp.root.with("photo.jpg", "x")
        assertNull(edits().rename(file.path, "PHOTO.jpg"))
        assertEquals("x", temp.root.resolve("PHOTO.jpg").readText())
        assertEquals(1, temp.root.list()!!.size)
    }

    @Test
    fun `names that would end up elsewhere are refused`() {
        val file = temp.root.with("a.txt")
        for (name in listOf("", " ", "..", "x/y", "../up.txt")) assertEquals(name, RenameProblem.BAD_NAME, edits().rename(file.path, name))
        assertTrue(file.exists())
    }

    @Test
    fun `what isn't there can't be renamed`() {
        assertEquals(RenameProblem.FAILED, edits().rename(temp.root.resolve("missing").path, "a"))
        assertEquals(RenameProblem.FAILED, edits().rename("no-parent", "a"))
    }

    @Test
    fun `the shell renames where only it can, and doesn't replace either`() {
        val folder = restricted()
        val file = folder.with("a.txt", "mine")
        folder.with("b.txt", "precious")
        val edits = edits(TestShell())
        assertEquals(RenameProblem.NAME_TAKEN, edits.rename(file.path, "b.txt"))
        assertEquals("precious", folder.resolve("b.txt").readText())
        assertNull(edits.rename(file.path, "c.txt"))
        assertEquals("mine", folder.resolve("c.txt").readText())
        assertEquals(RenameProblem.FAILED, edits.rename(file.path, "d.txt"))
    }

    // Deleting

    @Test
    fun `a folder goes with what's in it, and the index hears`() {
        val folder = temp.root.resolve("folder").also { it.with("a/b/c.txt") }
        assertTrue(deletion().delete(folder.path))
        assertFalse(folder.exists())
        assertEquals(listOf(folder.path), index.rescanned)
        assertEquals("It can be asked for one path without forgetting its lock", emptyList<String>(), followers.heard)
    }

    @Test
    fun `a link goes, and what it points at stays`() {
        val kept = temp.newFolder("kept").also { it.with("photo.jpg") }
        val folder = temp.newFolder("folder")
        Files.createSymbolicLink(folder.toPath().resolve("link"), kept.toPath())
        assertTrue(deletion().delete(folder.path))
        assertTrue(kept.resolve("photo.jpg").exists())
    }

    @Test
    fun `the top of a storage can't be deleted`() {
        val root = temp.newFolder("root").also { it.with("photo.jpg") }
        val shell = TestShell()
        val deletion = deletion(shell, root)
        for (path in listOf(root.path, root.path + "/", "/storage/emulated", "/storage/emulated/", "/storage", "/", "", "///")) {
            assertFalse(path, deletion.delete(path))
        }
        assertTrue(root.resolve("photo.jpg").exists())
        assertEquals(emptyList<String>(), shell.commands)
        assertEquals(emptyList<String>(), index.deletedThrough)
    }

    @Test
    fun `the shell deletes where only it can`() {
        val shell = TestShell()
        val file = restricted().with("it's.txt")
        assertTrue(deletion(shell).delete(file.path))
        assertFalse(file.exists())
        assertEquals(listOf(ShellCommand.deleteTree(file.path)), shell.commands.map { it.unwrapped() })
    }

    @Test
    fun `what the app can't delete, the index may`() {
        val file = temp.root.with("stubborn/a.txt")
        val folder = file.parentFile!!
        folder.setWritable(false)
        try {
            assertFalse("Nobody can", deletion().delete(file.path))
            assertEquals(listOf(file.path), index.deletedThrough)
            index.onDelete = {
                folder.setWritable(true)
                file.delete()
            }
            assertTrue(deletion().delete(file.path))
        } finally {
            folder.setWritable(true)
        }
    }

    @Test
    fun `deleting many says which went, and tells about those alone`() = runBlocking {
        val files = (1..3).map { temp.root.with("$it.txt") }
        val progress = ArrayList<Int>()
        val paths = files.map { it.path } + temp.root.path
        val gone = deletion().deleteAll(paths, { false }, progress::add)
        assertEquals(files.map { it.path }, gone)
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertEquals(files.map { it.path }, index.forgotten)
        assertEquals(files.map { it.path }, index.announced)
        assertEquals(files.map { "forgot ${it.path}" }, followers.heard)
        assertTrue(temp.root.exists())
    }

    @Test
    fun `deleting many can be stopped`() = runBlocking {
        val files = (1..3).map { temp.root.with("$it.txt") }
        var done = 0
        val gone = deletion().deleteAll(files.map { it.path }, { done == 2 }, { done = it })
        assertEquals(files.take(2).map { it.path }, gone)
        assertTrue(files[2].exists())
    }

    @Test
    fun `deleting many ends when it's cancelled`() {
        val files = (1..3).map { temp.root.with("$it.txt") }
        assertThrows(CancellationException::class.java) {
            runBlocking(Job()) {
                val job = currentCoroutineContext()
                deletion().deleteAll(files.map { it.path }, { false }, { job.cancel() })
            }
        }
        assertFalse(files[0].exists())
        assertTrue(files[1].exists())
    }

    @Test
    fun `a great many are only forgotten, not announced one by one`() {
        index.gone((1..201).map { "/x/$it" })
        assertEquals(201, index.forgotten.size)
        assertEquals(emptyList<String>(), index.announced)
        index.gone((1..200).map { "/y/$it" })
        assertEquals(200, index.announced.size)
    }

    // What's announced

    @Test
    fun `a folder is announced with what's in it`() {
        val folder = temp.newFolder("folder").also { it.with("a/b.txt") }
        val file = temp.root.with("c.txt")
        val all = withContents(listOf(file.path, folder.path, "/gone"), limit = 100)
        assertEquals(listOf(file.path, folder.path, "/gone", folder.resolve("a").path, folder.resolve("a/b.txt").path), all)
    }

    @Test
    fun `no more is announced than the limit`() {
        val folder = temp.newFolder("folder")
        repeat(20) { folder.with("$it.txt") }
        assertEquals(10, withContents(listOf(folder.path, "/gone"), limit = 10).size)
        assertEquals(listOf("/1", "/2"), withContents(listOf("/1", "/2", "/3"), limit = 2))
    }

    private fun String.unwrapped() = removePrefix("( ").removeSuffix(" ) >/dev/null 2>&1")
}
