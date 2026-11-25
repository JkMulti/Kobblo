package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.TransferEvents
import com.kobblo.gallery.file.explorer.files.drive.DriveTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransfersTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val index = TestIndex()
    private val followers = TestFollowers()
    private val failures = ArrayList<Exception>()
    private lateinit var from: File
    private lateinit var to: File
    private lateinit var appFolder: File

    @Before
    fun setUp() {
        from = temp.newFolder("from")
        to = temp.newFolder("to")
        appFolder = temp.root.resolve("Android/data/com.app").also { it.mkdirs() }
    }

    private fun transfers(shell: ElevatedShell = TestShell(isReady = false)) =
        Transfers(TestStorage(temp.root), shell, DriveTransfer({ null }, shell) { temp.root }, index, followers, failures::add)

    private fun copy(vararg files: File, into: String = to.path, shell: ElevatedShell = TestShell(isReady = false)) = runBlocking {
        transfers(shell).run(files.map { it.path }, into, TransferRequest(move = false))
    }

    private fun move(vararg files: File, into: String = to.path, shell: ElevatedShell = TestShell(isReady = false)) = runBlocking {
        transfers(shell).run(files.map { it.path }, into, TransferRequest(move = true))
    }

    // By the file system

    @Test
    fun `a copy leaves the original, and what it made is announced`() {
        val a = from.with("a.txt", "a")
        val album = from.resolve("album").also { it.with("b.jpg", "b") }
        val report = copy(a, album)
        assertTrue(report.ok)
        assertEquals(2, report.done)
        assertEquals("a", to.resolve("a.txt").readText())
        assertEquals("b", to.resolve("album/b.jpg").readText())
        assertTrue(a.exists())
        assertEquals(listOf(to.resolve("a.txt").path, to.resolve("album").path), index.announced)
        assertEquals(
            listOf("copied ${a.path} to ${to.resolve("a.txt").path}", "copied ${album.path} to ${to.resolve("album").path}"),
            followers.heard
        )
    }

    @Test
    fun `a move takes the original, its lock goes along, and both places are announced`() {
        val a = from.with("a.txt", "a")
        val report = move(a)
        assertTrue(report.ok)
        assertFalse(a.exists())
        assertEquals("a", to.resolve("a.txt").readText())
        assertEquals(listOf(to.resolve("a.txt").path, a.path), index.announced)
        assertEquals(listOf("moved ${a.path} to ${to.resolve("a.txt").path}"), followers.heard)
    }

    @Test
    fun `nothing is overwritten`() {
        to.with("a.txt", "precious")
        val report = move(from.with("a.txt", "new"))
        assertEquals("precious", to.resolve("a.txt").readText())
        assertEquals("new", to.resolve("a (1).txt").readText())
        assertEquals(1, report.renamed)
        assertEquals(listOf("moved ${from.resolve("a.txt").path} to ${to.resolve("a (1).txt").path}"), followers.heard)
    }

    @Test
    fun `one that fails doesn't stop the others`() {
        val a = from.with("a.txt", "a")
        val missing = from.resolve("missing.txt")
        val b = from.with("b.txt", "b")
        val report = copy(a, missing, b)
        assertEquals(2, report.done)
        assertEquals(listOf(missing.path to FailReason.MISSING), report.failed.map { it.path to it.reason })
        assertEquals("b", to.resolve("b.txt").readText())
        assertFalse(report.ok)
    }

    @Test
    fun `progress only reaches the end when all of it worked`() = runBlocking {
        val progress = ArrayList<Float>()
        val a = from.with("a.txt", "x".repeat(1000))
        val b = from.with("b.txt", "x".repeat(3000))
        transfers().run(listOf(a.path, b.path), to.path, TransferRequest(move = false, onProgress = progress::add))
        assertEquals(listOf(0.25f, 1f, 1f), progress)
        progress.clear()
        transfers().run(listOf(from.resolve("missing").path), to.path, TransferRequest(move = false, onProgress = progress::add))
        assertEquals(emptyList<Float>(), progress)
    }

    @Test
    fun `words stand for folders`() {
        copy(from.with("a.txt", "a"), into = "home")
        assertEquals("a", temp.root.resolve("a.txt").readText())
        copy(from.with("b.txt", "b"), into = "recent")
        assertEquals("b", temp.root.resolve("Download/b.txt").readText())
    }

    @Test
    fun `whoever's listening hears each item`() = runBlocking {
        val heard = ArrayList<String>()
        val events = object : TransferEvents {
            override fun started(source: File, dest: File) {
                heard += "started ${source.name}"
            }

            override fun finished(source: File, dest: File) {
                heard += "finished ${dest.name}"
            }
        }
        val a = from.with("a.txt")
        transfers().run(listOf(a.path), to.path, TransferRequest(move = true, events = events))
        assertEquals(listOf("started a.txt", "finished a.txt"), heard)
    }

    @Test
    fun `a cancelled copy takes back what it made`() = runBlocking {
        val files = (1..3).map { from.with("$it.txt", "x") }
        var checks = 0
        val stop = TransferRequest(move = false, checkPause = { if (++checks > 4) throw CancellationException("stop") })
        val report = transfers().run(files.map { it.path }, to.path, stop)
        assertTrue(report.cancelled)
        assertEquals(emptyList<String>(), to.list()!!.toList())
        assertTrue(files.all { it.exists() })
    }

    // Through the shell

    @Test
    fun `the shell copies out of an app's folder, and into one`() {
        val shell = TestShell()
        val theirs = appFolder.with("it's \$(theirs).txt", "theirs")
        assertTrue(copy(theirs, shell = shell).ok)
        assertEquals("theirs", to.resolve("it's \$(theirs).txt").readText())
        assertTrue(theirs.exists())
        val ours = from.resolve("ours").also { it.with("deep/a.txt", "ours") }
        assertTrue(copy(ours, into = appFolder.path, shell = shell).ok)
        assertEquals("ours", appFolder.resolve("ours/deep/a.txt").readText())
        assertTrue(shell.commands.any { "cp -r" in it })
    }

    @Test
    fun `without a shell the app tries by itself`() {
        val theirs = appFolder.with("a.txt", "theirs")
        assertTrue(copy(theirs).ok)
        assertEquals("theirs", to.resolve("a.txt").readText())
    }

    @Test
    fun `the shell moves, and what it took away is said`() {
        val theirs = appFolder.with("a.txt", "theirs")
        val report = move(theirs, shell = TestShell())
        assertFalse(theirs.exists())
        assertEquals("theirs", to.resolve("a.txt").readText())
        assertEquals(listOf(theirs.path), report.movedFrom)
        assertEquals(listOf(to.resolve("a.txt").path, theirs.path), index.announced)
        assertEquals(listOf("moved ${theirs.path} to ${to.resolve("a.txt").path}"), followers.heard)
    }

    @Test
    fun `the shell doesn't overwrite either`() {
        to.with("a.txt", "precious")
        val report = move(appFolder.with("a.txt", "new"), shell = TestShell())
        assertEquals("precious", to.resolve("a.txt").readText())
        assertEquals("new", to.resolve("a (1).txt").readText())
        assertEquals(1, report.renamed)
    }

    @Test
    fun `a folder can't go into itself, and stays where it is when it's there already`() {
        val shell = TestShell()
        val folder = appFolder.resolve("album").also { it.with("a.jpg", "a") }
        val inside = copy(folder, into = folder.resolve("sub").path, shell = shell)
        assertEquals(listOf(FailReason.INTO_ITSELF), inside.failed.map { it.reason })
        val itself = move(folder, into = folder.path, shell = shell)
        assertEquals(listOf(FailReason.INTO_ITSELF), itself.failed.map { it.reason })
        val there = move(folder, into = appFolder.path, shell = shell)
        assertTrue(there.ok)
        assertEquals(listOf(folder.path), there.outputs)
        assertEquals(emptyList<String>(), there.movedFrom)
        assertEquals("a", folder.resolve("a.jpg").readText())
        assertEquals("Nothing was run for any of it", emptyList<String>(), shell.commands)
    }

    @Test
    fun `a copy the shell couldn't finish is cleared away`() {
        val shell = TestShell()
        val source = appFolder.with("a.txt", "theirs")
        shell.refuses = { command ->
            ("cp -r" in command).also { if (it) to.with("a.txt", "half") }
        }
        val report = copy(source, shell = shell)
        assertEquals(listOf(FailReason.DENIED), report.failed.map { it.reason })
        assertEquals(emptyList<String>(), to.list()!!.toList())
        assertEquals("theirs", source.readText())
        assertEquals("A failure has nothing to follow", emptyList<String>(), followers.heard)
    }

    @Test
    fun `a move that broke off after taking the source keeps what it made`() {
        val shell = TestShell()
        val source = appFolder.with("a.txt", "theirs")
        shell.refuses = { command ->
            ("mv --" in command).also { if (it) source.renameTo(to.resolve("a.txt")) }
        }
        val report = move(source, shell = shell)
        assertEquals(listOf(FailReason.DENIED), report.failed.map { it.reason })
        assertEquals("It's all that's left", "theirs", to.resolve("a.txt").readText())
    }

    @Test
    fun `a move that never began leaves the source`() {
        val shell = TestShell()
        val source = appFolder.with("a.txt", "theirs")
        shell.refuses = { "mv --" in it }
        move(source, shell = shell)
        assertEquals("theirs", source.readText())
        assertEquals(emptyList<String>(), to.list()!!.toList())
    }

    @Test
    fun `cancelling between items stops there and keeps what's done`() = runBlocking {
        val shell = TestShell()
        val first = appFolder.with("1.txt", "1")
        val second = appFolder.with("2.txt", "2")
        val plain = from.with("3.txt", "3")
        var checks = 0
        val stop = TransferRequest(move = true, checkPause = { if (++checks == 2) throw CancellationException("stop") })
        val report = transfers(shell).run(listOf(first.path, plain.path, second.path), to.path, stop)
        assertTrue(report.cancelled)
        assertEquals(1, report.done)
        assertEquals(listOf("1.txt"), to.list()!!.toList())
        assertTrue("What was left for the file system isn't started", plain.exists())
        assertTrue(second.exists())
    }

    // Drive

    @Test
    fun `without Drive, what needs it fails and the rest goes on`() {
        val plain = from.with("a.txt", "a")
        val report = runBlocking { transfers().run(listOf("drive_id:1", plain.path), to.path, TransferRequest(move = false)) }
        assertEquals(listOf("drive_id:1" to FailReason.NOT_POSSIBLE), report.failed.map { it.path to it.reason })
        assertEquals("a", to.resolve("a.txt").readText())
        assertEquals(1, failures.size)
        val upload = copy(plain, into = "drive_id:folder")
        assertEquals(listOf(FailReason.NOT_POSSIBLE), upload.failed.map { it.reason })
        assertTrue(plain.exists())
    }
}
