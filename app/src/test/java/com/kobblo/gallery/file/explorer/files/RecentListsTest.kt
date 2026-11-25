package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RecentListsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private val words = object : RecentWords {
        override fun action(key: String) = "did: $key"
        override fun age(age: Age) = "${age.count} ${age.scale}"
    }

    private fun list(vararg entries: RecentEntry, marks: PathMarks = marks()) = list(sequenceOf(*entries), marks)

    private fun list(history: Sequence<RecentEntry>, marks: PathMarks = marks()): List<FileItem> =
        RecentLists({ history }, TestStorage(temp.root), testRows(), words, { "01:05" }, { now }).list(marks)

    private fun entry(file: File, action: String = "Opened", secondsAgo: Long = 0) = entry(file.path, action, secondsAgo)

    private fun entry(path: String, action: String = "Opened", secondsAgo: Long = 0) = RecentEntry(path, action, now - secondsAgo * 1000)

    // What's remembered

    @Test
    fun `the last thing done is first, and a path is there once`() {
        val history = sequenceOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), RecentRules.adding("b", history) { it })
        assertEquals(listOf("new", "a", "b", "c"), RecentRules.adding("new", history) { it })
        assertEquals(listOf("only"), RecentRules.adding("only", emptySequence()) { it })
    }

    @Test
    fun `thirty are kept, and nothing past them is even read`() {
        val read = ArrayList<Int>()
        val endless = generateSequence(1) { it + 1 }.onEach { read += it }
        val kept = RecentRules.adding(0, endless) { it.toString() }
        assertEquals((0..29).toList(), kept)
        assertEquals(29, read.size)
    }

    @Test
    fun `only the phone's own files are remembered, and never the Vault's`() {
        assertTrue(RecentRules.isWorthKeeping("/storage/emulated/0/a.txt"))
        assertFalse(RecentRules.isWorthKeeping("drive_id:abc"))
        assertFalse(RecentRules.isWorthKeeping("recent"))
        assertFalse(RecentRules.isWorthKeeping("/data/user/0/com.kobblo/files/vault_files/secret.jpg"))
    }

    // How long ago

    @Test
    fun `an age is told in one unit`() {
        assertEquals(Age(Age.Scale.Now, 0), Age.of(59_999))
        assertEquals(Age(Age.Scale.Now, 0), Age.of(-5_000_000))
        assertEquals(Age(Age.Scale.Minutes, 1), Age.of(60_000))
        assertEquals(Age(Age.Scale.Minutes, 59), Age.of(3_599_999))
        assertEquals(Age(Age.Scale.Hours, 1), Age.of(3_600_000))
        assertEquals(Age(Age.Scale.Hours, 23), Age.of(86_399_999))
        assertEquals(Age(Age.Scale.Days, 1), Age.of(86_400_000))
        assertEquals(Age(Age.Scale.Days, 400), Age.of(400L * 86_400_000))
    }

    // Rows

    @Test
    fun `a file's row shows its size, and a folder's what was done to it`() {
        val file = temp.root.with("notes.txt", "12345")
        val folder = temp.newFolder("Trip.zip")
        val rows = list(entry(file, secondsAgo = 120), entry(folder, "Visited", secondsAgo = 7200))
        assertEquals(listOf("notes.txt", "Trip.zip"), rows.map { it.name })
        assertEquals(listOf("5 B", "did: Visited"), rows.map { it.size })
        assertEquals(listOf("2 Minutes", "2 Hours"), rows.map { it.changed })
        assertEquals(listOf("doc", "folder"), rows.map { it.type })
        assertEquals(listOf("doc", "folder"), rows.map { it.kind })
        assertEquals(listOf(file.path.hashCode(), folder.path.hashCode()), rows.map { it.id })
        assertEquals("It sorts by when it was used", now - 120_000, rows[0].lastModified)
        assertNull(rows[1].folderItemCount)
        assertFalse(rows[1].isEmptyFolder)
    }

    @Test
    fun `only a video says how long it plays`() {
        val rows = list(entry(temp.root.with("a.mp4")), entry(temp.root.with("b.mp3")), entry(temp.newFolder("c.mp4")))
        assertEquals(listOf("01:05", null, null), rows.map { it.duration })
    }

    @Test
    fun `what's gone, and what's in the Vault, isn't shown`() {
        val vault = temp.root.with("files/vault_files/secret.jpg")
        val here = temp.root.with("here.txt")
        val rows = list(entry(temp.root.resolve("gone.txt")), entry(vault), entry(here), entry("drive_id:1AbC"))
        assertEquals(listOf(here.path, "drive_id:1AbC"), rows.map { it.path })
        assertEquals("did: Opened", rows[1].size)
    }

    @Test
    fun `stars and locks show`() {
        val file = temp.root.with("a.txt")
        val row = list(entry(file), marks = marks(favorites = setOf(file.path), locks = setOf(temp.root.path))).single()
        assertTrue(row.isPinned)
        assertTrue(row.isLocked)
    }

    @Test
    fun `an entry that can't be read keeps those before it`() {
        val file = temp.root.with("a.txt")
        val broken = sequence {
            yield(entry(file))
            throw IOException("damaged")
        }
        assertEquals(listOf(file.path), list(broken).map { it.path })
    }

    @Test
    fun `with nothing used yet, the newest files around stand in`() {
        repeat(20) { i -> temp.root.with("Download/file$i.txt").setLastModified(now + i * 1000L) }
        temp.root.with("Download/.hidden.txt").setLastModified(now + 99_000L)
        temp.root.with("top.txt").setLastModified(now + 50_000L)
        val rows = list()
        assertEquals(15, rows.size)
        assertEquals(listOf("top.txt", "file19.txt", "file18.txt"), rows.filter { it.type != "folder" }.take(3).map { it.name })
        assertTrue(rows.none { it.isHidden })
        assertEquals("doc", rows.first { it.name == "top.txt" }.kind)
    }

    // Favorites

    @Test
    fun `favorites are the files on the phone that are still there`() {
        val b = temp.root.with("b.txt")
        val a = temp.root.with("a.txt")
        val folder = temp.newFolder("folder")
        val starred = setOf(b.path, a.path, folder.path, temp.root.resolve("gone.txt").path, "drive_id:1AbC")
        val rows = FavoriteLists(testRows()).list(marks(favorites = starred))
        assertEquals(listOf("folder", "a.txt", "b.txt"), rows.map { it.name })
        assertTrue(rows.all { it.isPinned })
    }
}
