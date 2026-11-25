package com.kobblo.gallery.file.explorer.fileops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RecycleBinTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var binDir: File
    private lateinit var files: File
    private lateinit var fallback: File
    private var clock = 1_000_000_000_000L
    private var nextRandom = 1L

    private fun store() = BinStore(binDir) { clock }
    private fun bin(shell: PrivilegedShell? = null, restricted: (String) -> Boolean = { false }) =
        RecycleBin(store(), shell, restricted, now = { clock }, random = { nextRandom++ })

    @Before
    fun setUp() {
        binDir = File(temp.root, ".kobblo_trash")
        files = temp.newFolder("files")
        fallback = temp.newFolder("Download")
    }

    private fun file(relative: String, text: String = "contents of $relative"): File =
        files.resolve(relative).also { it.parentFile?.mkdirs(); it.writeText(text) }

    private fun named(entries: List<BinEntry>, original: File) = entries.single { it.originalPath == original.path }

    // Names

    @Test
    fun `an ordinary path keeps the self-describing name`() {
        val path = "/storage/emulated/0/DCIM/IMG_1.jpg"
        val name = BinNames.allocate(path, { 1L }) { false }
        assertEquals(BinNames.selfDescribing(path), name)
        assertEquals(path, BinNames.pathIn(name))
    }

    @Test
    fun `the encoding matches what older versions wrote`() {
        // Android's Base64 with URL_SAFE and NO_WRAP, from a device, so existing Bin entries keep working.
        assertEquals("L3N0b3JhZ2UvZW11bGF0ZWQvMC9ETUkvYS5qcGc=", BinNames.selfDescribing("/storage/emulated/0/DMI/a.jpg"))
        assertEquals("/storage/emulated/0/DMI/a.jpg", BinNames.pathIn("L3N0b3JhZ2UvZW11bGF0ZWQvMC9ETUkvYS5qcGc="))
    }

    @Test
    fun `a name that isn't self-describing says so`() {
        assertNull(BinNames.pathIn("bin-1a2b-photo.jpg"))
        assertNull(BinNames.pathIn("not base64 at all"))
        assertNull(BinNames.pathIn("aGVsbG8="))
        assertNull(BinNames.pathIn(".nomedia"))
    }

    @Test
    fun `a long path gets a name that fits, keeping its end`() {
        val path = "/storage/emulated/0/" + "deep/".repeat(60) + "holiday photo.jpg"
        assertTrue(BinNames.selfDescribing(path).length > 255)
        val name = BinNames.allocate(path, { 0x1234L }) { false }
        assertTrue(name.toByteArray().size <= 255)
        assertEquals("holiday_photo.jpg", BinNames.displayNameOf(name))
    }

    @Test
    fun `a very long file name also fits`() {
        val path = "/x/" + "日本語".repeat(28) + ".jpg"
        val name = BinNames.allocate(path, { 1L }) { false }
        assertTrue(name.toByteArray().size <= 255)
    }

    @Test
    fun `a taken name is never reused`() {
        val path = "/a/b.jpg"
        val plain = BinNames.selfDescribing(path)
        val second = BinNames.allocate(path, { 5L }) { it == plain }
        assertNotEquals(plain, second)
    }

    // Moving in

    @Test
    fun `a file goes in with a record of where it was`() = runBlocking {
        val photo = file("DCIM/a.jpg")
        val report = bin().moveIn(listOf(photo.path))
        assertEquals(listOf(photo.path), report.moved)
        assertFalse(photo.exists())
        val entry = named(bin().list(0L), photo)
        assertEquals("a.jpg", entry.originalName)
        assertEquals(clock, entry.deletedAt)
        assertTrue(binDir.resolve(entry.encodedName).exists())
        assertTrue(binDir.resolve(".nomedia").exists())
    }

    @Test
    fun `a folder goes in whole and comes back whole`() = runBlocking {
        file("album/a.jpg", "a")
        file("album/sub/b.jpg", "b")
        val album = files.resolve("album")
        bin().moveIn(listOf(album.path))
        assertFalse(album.exists())
        val entry = named(bin().list(0L), album)
        assertTrue(entry.isDirectory)
        bin().restore(listOf(entry.encodedName), fallback)
        assertEquals("a", album.resolve("a.jpg").readText())
        assertEquals("b", album.resolve("sub/b.jpg").readText())
    }

    @Test
    fun `an empty folder goes in and comes back too`() = runBlocking {
        val folder = files.resolve("nothing-here").apply { mkdirs() }
        bin().moveIn(listOf(folder.path))
        assertFalse(folder.exists())
        val entry = named(bin().list(0L), folder)
        assertTrue(entry.isDirectory)
        bin().restore(listOf(entry.encodedName), fallback)
        assertTrue(folder.isDirectory)
    }

    @Test
    fun `a path past the old limit works`() = runBlocking {
        val deep = file("deep/".repeat(40) + "photo.jpg", "deep one")
        assertTrue(BinNames.selfDescribing(deep.path).length > 255)
        val report = bin().moveIn(listOf(deep.path))
        assertTrue(report.ok)
        assertFalse(deep.exists())
        val entry = named(bin().list(0L), deep)
        assertEquals("photo.jpg", entry.originalName)
        bin().restore(listOf(entry.encodedName), fallback)
        assertEquals("deep one", deep.readText())
    }

    @Test
    fun `deleting a second file from one path doesn't destroy the first`() = runBlocking {
        val path = file("a.txt", "first version")
        bin().moveIn(listOf(path.path))
        path.writeText("second version")
        clock += 1000
        bin().moveIn(listOf(path.path))
        val entries = bin().list(0L)
        assertEquals(2, entries.size)
        val contents = entries.map { binDir.resolve(it.encodedName).readText() }.toSet()
        assertEquals(setOf("first version", "second version"), contents)
    }

    @Test
    fun `names differing only in case don't collide on storage that ignores case`() = runBlocking {
        // Taken is compared in lowercase, so a name the folder couldn't tell apart gets a different one.
        val path = "/a/b.jpg"
        val plain = BinNames.selfDescribing(path)
        val name = BinNames.allocate(path, { 9L }) { it.lowercase() in setOf(plain.lowercase()) }
        assertNotEquals(plain, name)
    }

    @Test
    fun `a missing file is reported and the rest still go`() = runBlocking {
        val good = file("good.txt")
        val missing = files.resolve("missing.txt")
        val report = bin().moveIn(listOf(missing.path, good.path))
        assertEquals(listOf(good.path), report.moved)
        assertEquals(listOf(BinFailure(missing.path, FailReason.MISSING)), report.failed)
    }

    @Test
    fun `the bin can't be put in itself, or the folder holding it`() = runBlocking {
        binDir.mkdirs()
        val report = bin().moveIn(listOf(binDir.path, temp.root.path))
        assertEquals(listOf(FailReason.INTO_ITSELF, FailReason.INTO_ITSELF), report.failed.map { it.reason })
        assertTrue(files.exists())
    }

    @Test
    fun `progress reaches the total, failures included`() = runBlocking {
        val good = file("good.txt")
        val missing = files.resolve("missing.txt")
        val seen = ArrayList<Int>()
        bin().moveIn(listOf(missing.path, good.path)) { seen += it }
        assertEquals(2, seen.last())
    }

    // Crashes

    @Test
    fun `a kill after moving but before the list is written leaves nothing orphaned`() = runBlocking {
        val photo = file("DCIM/a.jpg")
        val other = file("DCIM/b.jpg")
        // What moveIn does up to the point of the kill: plan, journal, move. The list is never written.
        val store = store()
        binDir.mkdirs()
        val planned = listOf(photo, other).map {
            BinEntry(BinNames.selfDescribing(it.path), it.path, clock, it.name, false, it.length())
        }
        store.writeJournal(planned)
        assertTrue(photo.renameTo(store.fileOf(planned[0])))
        // Dead here. The second file never moved.

        val entries = bin().list(0L)
        assertEquals("a.jpg", named(entries, photo).originalName)
        assertEquals("Only what really moved is listed", 1, entries.size)
        assertTrue(other.exists())
        assertFalse(registryMentions(other))

        // And the journal's done its job once it's in the list.
        assertFalse(binDir.resolve(BinStore.JOURNAL).exists())
        assertTrue(registryMentions(photo))
    }

    private fun registryMentions(file: File) = binDir.resolve(BinStore.REGISTRY).readText().contains(file.name)

    @Test
    fun `two deletes at once from separate instances keep every record`() = runBlocking {
        // Each screen makes its own FileRepository, so each of these has its own store. Without one lock
        // shared between them, the second to save would write over the first's entries.
        val first = (1..300).map { file("x/a$it.txt").path }
        val second = (1..300).map { file("y/b$it.txt").path }
        listOf(first, second).map { paths ->
            launch(Dispatchers.IO) { assertEquals(300, bin().moveIn(paths).moved.size) }
        }.joinAll()

        val onDisk = BinCodec.decode(binDir.resolve(BinStore.REGISTRY).readText(), clock)
        assertFalse(onDisk.damaged)
        assertEquals("The list itself, before any repair from the folder", 600, onDisk.entries.size)
        assertEquals(600, bin().list(0L).size)
    }

    @Test
    fun `a delete, a restore and an emptying at once lose nothing that wasn't meant to go`() = runBlocking {
        val toRestore = (1..50).map { file("r/a$it.txt") }
        val toEmpty = (1..50).map { file("e/b$it.txt") }
        bin().moveIn((toRestore + toEmpty).map { it.path })
        val listed = bin().list(0L)
        val restoreNames = toRestore.map { named(listed, it).encodedName }
        val emptyNames = toEmpty.map { named(listed, it).encodedName }
        val toAdd = (1..50).map { file("n/c$it.txt").path }

        listOf<suspend () -> Unit>(
            { bin().restore(restoreNames, fallback) },
            { bin().deleteForever(emptyNames) },
            { bin().moveIn(toAdd) }
        ).map { job -> launch(Dispatchers.IO) { job() } }.joinAll()

        toRestore.forEach { assertTrue(it.exists()) }
        val left = bin().list(0L)
        assertEquals(toAdd.toSet(), left.map { it.originalPath }.toSet())
    }

    @Test
    fun `a temporary list left by an earlier build isn't listed as someone's file`() = runBlocking {
        binDir.mkdirs()
        binDir.resolve(BinStore.OLD_TEMP).writeText("[]")
        assertTrue(bin().list(0L).isEmpty())
        assertFalse(binDir.resolve(BinStore.OLD_TEMP).exists())
    }

    @Test
    fun `leaving partway through a large delete loses track of nothing`() = runBlocking {
        val paths = (1..200).map { file("many/f$it.txt").path }
        var job: Job? = null
        job = launch(Dispatchers.IO) {
            bin().moveIn(paths) { done -> if (done >= 32) job?.cancel() }
        }
        job.join()

        val listed = bin().list(0L)
        val left = files.resolve("many").list()!!.toSet()
        assertTrue("Stopped partway: ${listed.size}", listed.size in 1 until 200)
        assertEquals("Every file is in exactly one place", 200, listed.size + left.size)
        for (entry in listed) {
            assertFalse(File(entry.originalPath!!).name in left)
            assertTrue(binDir.resolve(entry.encodedName).exists())
        }
        assertFalse(binDir.resolve(BinStore.JOURNAL).exists())
    }

    @Test
    fun `a delete after a leaving keeps both batches`() = runBlocking {
        val first = (1..100).map { file("a/f$it.txt").path }
        var job: Job? = null
        job = launch(Dispatchers.IO) {
            bin().moveIn(first) { done -> if (done >= 32) job?.cancel() }
        }
        job.join()
        val second = file("b/other.txt")
        bin().moveIn(listOf(second.path))
        val listed = bin().list(0L)
        assertEquals(100 + 1, listed.size + files.resolve("a").list()!!.size)
        assertEquals("other.txt", named(listed, second).originalName)
    }

    @Test
    fun `leftovers of an interrupted copy are cleared and the source is untouched`() = runBlocking {
        binDir.mkdirs()
        val part = SafeFiles.partFileIn(binDir).apply { writeText("half") }
        assertTrue(bin().list(0L).isEmpty())
        assertFalse(part.exists())
    }

    @Test
    fun `a file with no record at all still shows, with its path if the name holds one`() = runBlocking {
        binDir.mkdirs()
        val original = files.resolve("Pictures/lost.jpg")
        binDir.resolve(BinNames.selfDescribing(original.path)).writeText("lost photo")
        binDir.resolve("mystery-file.jpg").writeText("no idea")

        val entries = bin().list(7 * DAY)
        assertEquals(2, entries.size)
        val known = named(entries, original)
        assertEquals("lost.jpg", known.originalName)
        val unknown = entries.single { it.originalPath == null }
        assertEquals("mystery-file.jpg", unknown.originalName)

        // Both can be got back.
        val report = bin().restore(entries.map { it.encodedName }, fallback)
        assertEquals("lost photo", original.readText())
        assertEquals("no idea", fallback.resolve("mystery-file.jpg").readText())
        assertEquals(1, report.relocated)
    }

    @Test
    fun `a found file gets a full stay, whatever its own date says`() = runBlocking {
        binDir.mkdirs()
        val stray = binDir.resolve(BinNames.selfDescribing("/x/old.jpg")).apply {
            writeText("old photo")
            setLastModified(clock - 400 * DAY)
        }
        assertEquals(1, bin().list(7 * DAY).size)
        assertTrue(stray.exists())

        // Its time runs from when it was found, and doesn't start over at each look.
        clock += 6 * DAY
        assertEquals(1, bin().list(7 * DAY).size)
        clock += 2 * DAY
        assertEquals(0, bin().list(7 * DAY).size)
        assertFalse(stray.exists())
    }

    @Test
    fun `a truncated list keeps what's readable and finds the rest by their files`() = runBlocking {
        val one = file("one.jpg")
        val two = file("two.jpg")
        bin().moveIn(listOf(one.path, two.path))
        val registry = binDir.resolve(BinStore.REGISTRY)
        val whole = registry.readText()
        // A kill partway through an old-style in-place write.
        registry.writeText(whole.substring(0, whole.indexOf("two.jpg") - 5))

        val entries = bin().list(0L)
        assertEquals(2, entries.size)
        assertEquals("one.jpg", named(entries, one).originalName)
        assertEquals("two.jpg", named(entries, two).originalName)
        assertTrue("A copy is kept for anyone working out what happened", binDir.resolve(BinStore.DAMAGED).exists())
        assertFalse("The damaged copy isn't listed as a file", entries.any { it.encodedName == BinStore.DAMAGED })
    }

    @Test
    fun `a list that's complete garbage loses nothing`() = runBlocking {
        val one = file("one.jpg")
        bin().moveIn(listOf(one.path))
        binDir.resolve(BinStore.REGISTRY).writeText("%%% not json {{{")
        assertEquals("one.jpg", named(bin().list(0L), one).originalName)
    }

    @Test
    fun `an empty list file is fine`() = runBlocking {
        binDir.mkdirs()
        binDir.resolve(BinStore.REGISTRY).writeText("")
        assertTrue(bin().list(0L).isEmpty())
    }

    @Test
    fun `entries in the old format read as they always did`() = runBlocking {
        binDir.mkdirs()
        val path = files.resolve("old.jpg").path
        val name = BinNames.selfDescribing(path)
        binDir.resolve(name).writeText("old contents")
        binDir.resolve(BinStore.REGISTRY).writeText(
            """[{"encodedName":"$name","originalPath":"$path","deletedAt":$clock,"originalName":"old.jpg","isDirectory":false}]"""
        )
        val entry = bin().list(7 * DAY).single()
        assertEquals(path, entry.originalPath)
        assertEquals(clock, entry.deletedAt)
        assertEquals(-1L, entry.sizeBytes)
        bin().restore(listOf(name), fallback)
        assertEquals("old contents", File(path).readText())
    }

    @Test
    fun `not being able to see the folder isn't the same as it being empty`() = runBlocking {
        val one = file("one.jpg")
        bin().moveIn(listOf(one.path))
        val registry = binDir.resolve(BinStore.REGISTRY)
        val before = registry.readText()
        // What lost storage access looks like: the folder is there and can't be listed.
        binDir.setReadable(false)
        try {
            if (binDir.list() != null) return@runBlocking // Running as a user the permission doesn't bind.
            assertTrue(bin().list(0L).isEmpty())
        } finally {
            binDir.setReadable(true)
        }
        assertEquals("The record survives", before, registry.readText())
        assertEquals(1, bin().list(0L).size)
    }

    // Restoring

    @Test
    fun `restoring never overwrites what's there now`() = runBlocking {
        val path = file("a.txt", "the deleted one")
        bin().moveIn(listOf(path.path))
        path.writeText("made since")
        val entry = bin().list(0L).single()
        val report = bin().restore(listOf(entry.encodedName), fallback)
        assertEquals("made since", path.readText())
        assertEquals("the deleted one", files.resolve("a (1).txt").readText())
        assertEquals(1, report.renamed)
        assertEquals(listOf(files.resolve("a (1).txt").path), report.restored)
        assertTrue(bin().list(0L).isEmpty())
    }

    @Test
    fun `restoring recreates a missing folder`() = runBlocking {
        val path = file("gone/deep/a.txt", "x")
        bin().moveIn(listOf(path.path))
        files.resolve("gone").deleteRecursively()
        bin().restore(listOf(bin().list(0L).single().encodedName), fallback)
        assertEquals("x", path.readText())
    }

    @Test
    fun `a file that can't be restored stays in the bin and is reported`() = runBlocking {
        val path = file("folder/a.txt", "x")
        bin().moveIn(listOf(path.path))
        // Something now stands where the folder has to be recreated.
        files.resolve("folder").deleteRecursively()
        files.resolve("folder").writeText("a file, not a folder")
        val entry = bin().list(0L).single()
        val report = bin().restore(listOf(entry.encodedName), fallback)
        assertEquals(1, report.failed.size)
        assertEquals(listOf(entry), bin().list(0L))
        assertTrue(binDir.resolve(entry.encodedName).exists())
    }

    // Emptying and purging

    @Test
    fun `deleting for good takes the file and its record`() = runBlocking {
        val one = file("one.jpg")
        val two = file("two.jpg")
        bin().moveIn(listOf(one.path, two.path))
        val target = named(bin().list(0L), one)
        val report = bin().deleteForever(listOf(target.encodedName))
        assertEquals(BinDeleteReport(1, 0), report)
        assertFalse(binDir.resolve(target.encodedName).exists())
        assertEquals("two.jpg", bin().list(0L).single().originalName)
    }

    @Test
    fun `emptying leaves the bin's own files`() = runBlocking {
        val one = file("one.jpg")
        bin().moveIn(listOf(one.path))
        bin().deleteForever(bin().list(0L).map { it.encodedName })
        assertTrue(bin().list(0L).isEmpty())
        assertTrue(binDir.resolve(".nomedia").exists())
    }

    @Test
    fun `emptying doesn't follow a link out of a binned folder`() = runBlocking {
        val outside = temp.newFolder("outside").apply { resolve("precious.txt").writeText("keep") }
        val folder = files.resolve("folder").apply { mkdirs() }
        Files.createSymbolicLink(folder.resolve("away").toPath(), outside.toPath())
        bin().moveIn(listOf(folder.path))
        bin().deleteForever(bin().list(0L).map { it.encodedName })
        assertEquals("keep", outside.resolve("precious.txt").readText())
    }

    @Test
    fun `only what's past its time is purged`() = runBlocking {
        val old = file("old.jpg")
        bin().moveIn(listOf(old.path))
        clock += 6 * DAY
        val recent = file("recent.jpg")
        bin().moveIn(listOf(recent.path))
        clock += 2 * DAY
        val left = bin().list(7 * DAY)
        assertEquals(listOf("recent.jpg"), left.map { it.originalName })
    }

    @Test
    fun `a keep period of zero never purges`() = runBlocking {
        val one = file("one.jpg")
        bin().moveIn(listOf(one.path))
        clock += 3650 * DAY
        assertEquals(1, bin().list(0L).size)
        assertEquals(1, bin().list(-5L).size)
    }

    // From another volume, such as a memory card

    private fun acrossVolumes(freeSpace: (File) -> Long = { Long.MAX_VALUE }, stubborn: (File) -> Boolean = { false }) =
        RecycleBin(store(), freeSpace = freeSpace, removeStubborn = stubborn, now = { clock }, random = { nextRandom++ }, rename = { _, _ -> false })

    @Test
    fun `a file from another volume arrives whole before the original goes`() = runBlocking {
        val photo = file("card/a.jpg", "from the card")
        val report = acrossVolumes().moveIn(listOf(photo.path))
        assertEquals(listOf(photo.path), report.moved)
        assertFalse(photo.exists())
        val entry = named(bin().list(0L), photo)
        assertEquals("from the card", binDir.resolve(entry.encodedName).readText())
        assertEquals("No temporary files left", 3, binDir.list()!!.size)
    }

    @Test
    fun `not enough room for it in the bin leaves the original`() = runBlocking {
        val photo = file("card/a.jpg", "x".repeat(4096))
        val report = acrossVolumes(freeSpace = { 1024L }).moveIn(listOf(photo.path))
        assertEquals(listOf(BinFailure(photo.path, FailReason.NO_SPACE)), report.failed)
        assertEquals(4096L, photo.length())
        assertTrue(bin().list(0L).isEmpty())
    }

    @Test
    fun `an original that won't delete isn't left in the bin as well`() = runBlocking {
        val locked = files.resolve("locked").apply { mkdirs() }
        val photo = locked.resolve("a.jpg").apply { writeText("x") }
        locked.setWritable(false)
        try {
            if (locked.canWrite()) return@runBlocking // Running as a user this doesn't bind.
            val report = acrossVolumes().moveIn(listOf(photo.path))
            assertEquals(listOf(BinFailure(photo.path, FailReason.DENIED)), report.failed)
            assertEquals("x", photo.readText())
        } finally {
            locked.setWritable(true)
        }
        assertTrue(bin().list(0L).isEmpty())
    }

    @Test
    fun `restoring to another volume checks the copy before forgetting the bin's`() = runBlocking {
        val photo = file("card/a.jpg", "from the card")
        acrossVolumes().moveIn(listOf(photo.path))
        val entry = named(bin().list(0L), photo)
        val report = acrossVolumes().restore(listOf(entry.encodedName), fallback)
        assertEquals(listOf(photo.path), report.restored)
        assertEquals("from the card", photo.readText())
        assertFalse(binDir.resolve(entry.encodedName).exists())
    }

    // Through the shell

    private class RecordingShell : PrivilegedShell {
        val commands = ArrayList<String>()
        var result = true

        override fun run(command: String): Boolean {
            commands += command
            return result
        }
    }

    @Test
    fun `a restricted path goes through the shell, quoted`() = runBlocking {
        val shell = RecordingShell()
        val hostile = "/storage/emulated/0/Android/data/x/\$(touch p).jpg"
        val report = bin(shell) { it.contains("Android/data") }.moveIn(listOf(hostile))
        assertEquals(listOf(hostile), report.moved)
        val quoted = "'/storage/emulated/0/Android/data/x/\$(touch p).jpg'"
        assertTrue(shell.commands[0], shell.commands[0].startsWith("cp -r -- $quoted "))
        assertEquals("rm -rf -- $quoted", shell.commands[1])
    }

    @Test
    fun `a failed shell copy keeps the original and reports it`() = runBlocking {
        val shell = RecordingShell().apply { result = false }
        val path = "/storage/emulated/0/Android/data/x/a.jpg"
        val report = bin(shell) { it.contains("Android/data") }.moveIn(listOf(path))
        assertEquals(listOf(BinFailure(path, FailReason.DENIED)), report.failed)
        assertEquals("It never asked to delete", 1, shell.commands.size)
    }

    // Size

    @Test
    fun `ten thousand files finish in reasonable time`() = runBlocking {
        val paths = (1..10_000).map { file("many/f$it.txt", "x").path }
        val start = System.nanoTime()
        val report = bin().moveIn(paths)
        val movedMillis = (System.nanoTime() - start) / 1_000_000
        println("Moved 10,000 files into the Bin in $movedMillis ms")
        assertEquals(10_000, report.moved.size)
        assertEquals(10_000, bin().list(0L).size)

        val names = bin().list(0L).map { it.encodedName }
        val restoreStart = System.nanoTime()
        val restored = bin().restore(names, fallback)
        println("Restored 10,000 files in ${(System.nanoTime() - restoreStart) / 1_000_000} ms")
        assertEquals(10_000, restored.restored.size)
        // The old code's per-file rewrite and search made this minutes, not seconds. Generous for a slow machine.
        assertTrue("Took $movedMillis ms", movedMillis < 30_000)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
