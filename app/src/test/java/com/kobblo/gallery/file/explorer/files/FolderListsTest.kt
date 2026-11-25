package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class FolderListsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val noShell = TestShell(isReady = false)

    private fun lists(
        root: File = temp.root,
        card: String? = null,
        shell: ElevatedShell = noShell,
        indexed: (String) -> List<FileItem> = { emptyList() }
    ) = FolderLists(TestStorage(root, card), testRows(), ShellFolders(shell), indexed) { IOException("no way into $it") }

    private fun names(items: List<FileItem>) = items.map { it.name }

    // Rows

    @Test
    fun `a file's row says what it is`() {
        val file = temp.root.with("Song.MP3", "12345")
        file.setLastModified(1_700_000_000_000L)
        val row = lists().list(temp.root.path, marks()).single()
        assertEquals(file.path, row.path)
        assertEquals("Song.MP3", row.name)
        assertEquals("audio", row.type)
        assertEquals("media", row.kind)
        assertEquals("5 B", row.size)
        assertEquals(5L, row.sizeBytes)
        assertEquals(1_700_000_000_000L, row.lastModified)
        assertEquals("Me", row.owner)
        assertNull(row.folderItemCount)
        assertNull(row.duration)
        assertFalse(row.isEmptyFolder)
    }

    @Test
    fun `a folder's row counts what isn't hidden`() {
        temp.root.with("album/a.jpg")
        temp.root.with("album/b.jpg")
        temp.root.with("album/.nomedia")
        temp.root.with("hiding/.secret")
        val rows = lists().list(temp.root.path, marks()).associateBy { it.name }
        assertEquals("2 items", rows.getValue("album").size)
        assertEquals(2, rows.getValue("album").folderItemCount)
        assertFalse(rows.getValue("album").isEmptyFolder)
        assertEquals("folder", rows.getValue("album").kind)
        assertEquals(0L, rows.getValue("album").sizeBytes)
        assertTrue("Nothing in it shows", rows.getValue("hiding").isEmptyFolder)
        assertEquals(0, rows.getValue("hiding").folderItemCount)
    }

    @Test
    fun `hidden files are listed, for the screen to leave out`() {
        temp.root.with(".hidden.txt")
        val row = lists().list(temp.root.path, marks()).single()
        assertTrue(row.isHidden)
        assertEquals("doc", row.type)
    }

    @Test
    fun `stars and locks show on the rows they're on`() {
        val starred = temp.root.with("a.txt")
        temp.root.with("private/b.txt")
        val marks = marks(favorites = setOf(starred.path), locks = setOf(temp.root.resolve("private").path))
        val rows = lists().list(temp.root.path, marks).associateBy { it.name }
        assertTrue(rows.getValue("a.txt").isPinned)
        assertFalse(rows.getValue("a.txt").isLocked)
        assertTrue(rows.getValue("private").isLocked)
        val inside = lists().list(temp.root.resolve("private").path, marks).single()
        assertTrue("A lock covers everything in its folder", inside.isLocked)
    }

    @Test
    fun `a folder starts with favorites, then folders, then by name as a person reads it`() {
        for (name in listOf("file10.txt", "file2.txt", "File1.txt", "zebra.txt")) temp.root.with(name)
        temp.root.resolve("sub").mkdirs()
        temp.root.resolve("Archive").mkdirs()
        val rows = lists().list(temp.root.path, marks(favorites = setOf(temp.root.resolve("zebra.txt").path)))
        assertEquals(listOf("zebra.txt", "Archive", "sub", "File1.txt", "file2.txt", "file10.txt"), names(rows))
    }

    @Test
    fun `a long folder's rows are the same ones, made side by side`() {
        repeat(600) { temp.root.with("file$it.txt", "x".repeat(it % 7)) }
        val rows = lists().list(temp.root.path, marks())
        assertEquals((0 until 600).map { "file$it.txt" }, names(rows))
        assertEquals(600, rows.map { it.id }.toSet().size)
        assertTrue(rows.all { it.sizeBytes == it.name.filter(Char::isDigit).toLong() % 7 })
        val again = lists().list(temp.root.path, marks()).associate { it.path to it.id }
        assertTrue("A reload gives a file the same id", rows.all { again[it.path] == it.id })
    }

    // Which route

    @Test
    fun `words stand for the storages`() {
        val card = temp.newFolder("card")
        card.with("on card.txt")
        val root = temp.newFolder("root")
        root.with("on phone.txt")
        val lists = lists(root = root, card = card.path)
        assertEquals(listOf("on phone.txt"), names(lists.list("home", marks())))
        for (word in listOf("sd", "sd/", "sd:")) assertEquals(listOf("on card.txt"), names(lists.list(word, marks())))
    }

    @Test
    fun `an empty folder is empty, whatever else could be asked`() {
        val empty = temp.newFolder("empty")
        val rows = lists(indexed = { throw AssertionError("It could be opened") }).list(empty.path, marks())
        assertEquals(emptyList<FileItem>(), rows)
    }

    @Test
    fun `a folder no route can see is an error`() {
        val missing = temp.root.resolve("missing").path
        val error = assertThrows(IOException::class.java) { lists().list(missing, marks()) }
        assertEquals("no way into $missing", error.message)
        assertThrows("There's no card", IOException::class.java) { lists().list("sd", marks()) }
    }

    @Test
    fun `the index stands in for a folder that can't be opened`() {
        val known = FileItem(1, "/x/a.txt", "a.txt", "doc", "doc", "", "", "Me")
        val rows = lists(indexed = { listOf(known) }).list(temp.root.resolve("missing").path, marks())
        assertSame(known, rows.single())
    }

    @Test
    fun `without a shell an app's folder is shown empty, even if it could be opened`() {
        val restricted = temp.root.resolve("Android/data").also { it.mkdirs() }
        restricted.with("com.app/file.txt")
        assertEquals(emptyList<FileItem>(), lists().list(restricted.path, marks()))
    }

    @Test
    fun `with a shell an app's folder is what the shell sees`() {
        val restricted = temp.root.resolve("Android/data").also { it.mkdirs() }
        restricted.with("com.app/file.txt")
        restricted.with("it's \$(odd).jpg")
        val rows = lists(shell = TestShell()).list(restricted.path + "/", marks()).associateBy { it.name }
        val folder = rows.getValue("com.app")
        assertEquals("folder", folder.type)
        assertEquals(restricted.path + "/com.app", folder.path)
        assertEquals("", folder.size)
        assertTrue(folder.isEmptyFolder)
        val file = rows.getValue("it's \$(odd).jpg")
        assertEquals("image", file.type)
        assertEquals("media", file.kind)
        assertEquals("The shell doesn't say how big it is", "0 B", file.size)
        assertEquals("", file.changed)
        assertEquals(0L, file.lastModified)
        assertNull(file.folderItemCount)
        assertEquals(2, rows.size)
    }

    @Test
    fun `a shell that breaks off says nothing`() {
        val broken = object : ElevatedShell by TestShell() {
            override fun linesOf(command: String): List<String> = throw IOException("gone")
        }
        assertEquals(emptyList<FileItem>(), ShellFolders(broken).list("/storage/emulated/0/Android/data"))
        val silent = object : ElevatedShell by TestShell() {
            override fun linesOf(command: String): List<String>? = null
        }
        assertEquals(emptyList<FileItem>(), ShellFolders(silent).list("/storage/emulated/0/Android/data"))
    }

    @Test
    fun `the shell's lines become rows`() {
        val rows = ShellLines.rows("/a/Android/data/", listOf("./", "../", "", "com.app/", ".hidden", "b.zip"))
        assertEquals(listOf("com.app", ".hidden", "b.zip"), names(rows))
        assertEquals(listOf("/a/Android/data/com.app", "/a/Android/data/.hidden", "/a/Android/data/b.zip"), rows.map { it.path })
        assertEquals(listOf("folder", "file", "archive"), rows.map { it.kind })
    }

    // The index's word for a folder

    private val dates = SimpleDateFormat("yyyy", Locale.US)

    @Test
    fun `a folder is pieced together from the paths below it`() {
        val folder = IndexedFolder("/storage/1A2B-3C4D/My_Files", dates)
        assertEquals("/storage/1A2B-3C4D/My_Files/%", folder.pattern)
        folder.add("/storage/1A2B-3C4D/My_Files/z.jpg", "z.jpg", 2048, 1_700_000_000)
        folder.add("/storage/1A2B-3C4D/My_Files/b/deep/er.mp3", "er.mp3", 10, 1_600_000_000)
        folder.add("/storage/1A2B-3C4D/My_Files/b/other.mp3", "other.mp3", 10, 1)
        folder.add("/storage/1A2B-3C4D/My_Files/A/one.txt", "one.txt", 10, 0)
        // What an underscore lets through.
        folder.add("/storage/1A2B-3C4D/MyxFiles/no.txt", "no.txt", 10, 0)
        folder.add("/storage/1A2B-3C4D/My_Files/", null, 10, 0)
        val rows = folder.rows()
        assertEquals(listOf("A", "b", "z.jpg"), names(rows))
        assertEquals(listOf("folder", "folder", "image"), rows.map { it.type })
        assertEquals(listOf("", "", "2.0 kB"), rows.map { it.size })
        assertEquals(listOf("", "2020", "2023"), rows.map { it.changed })
        assertEquals(listOf(0L, 0L, 2048L), rows.map { it.sizeBytes })
        assertEquals(listOf(30002, 30001, 30000), rows.map { it.id })
        assertEquals("/storage/1A2B-3C4D/My_Files/b", rows[1].path)
    }

    @Test
    fun `a file without a name in the index goes by its path`() {
        val folder = IndexedFolder("/x/", dates)
        folder.add("/x/a.pdf", null, 1, 0)
        assertEquals("a.pdf", folder.rows().single().name)
        assertEquals("/x/a.pdf", folder.rows().single().path)
    }
}
