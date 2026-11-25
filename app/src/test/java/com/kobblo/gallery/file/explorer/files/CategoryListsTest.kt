package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.CleanerData
import com.kobblo.gallery.file.explorer.IndexConditions
import com.kobblo.gallery.file.explorer.LARGE_FILE_BYTES
import com.kobblo.gallery.file.explorer.StorageCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import java.io.RandomAccessFile

class CategoryListsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val index = TestIndex()
    private val storage by lazy { TestStorage(temp.root, space = StorageSpace(1000L, 400L)) }

    private fun lists(marks: PathMarks = marks()) = CategoryLists(index, storage) { marks }

    private fun scan(canDelete: (String) -> Boolean = { true }, storage: PhoneStorage = this.storage) =
        CleanerScan(storage, { marks() }, canDelete)

    private fun large(name: String): File = temp.root.resolve(name).also { file ->
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(LARGE_FILE_BYTES) }
    }

    // Keys

    @Test
    fun `every key a screen opens a category with`() {
        assertEquals(CategoryList.Of(StorageCategory.Images), CategoryList.of("Images"))
        assertEquals(CategoryList.Of(StorageCategory.Images), CategoryList.of("image"))
        assertEquals(CategoryList.Of(StorageCategory.Audio), CategoryList.of("music"))
        assertEquals(CategoryList.Of(StorageCategory.Apps), CategoryList.of("apk"))
        assertEquals(CategoryList.Of(StorageCategory.Documents), CategoryList.of("doc"))
        assertEquals(CategoryList.Of(StorageCategory.Archives), CategoryList.of("archives"))
        assertEquals(CategoryList.Of(StorageCategory.Videos), CategoryList.of("VIDEOS"))
        assertEquals(CategoryList.Downloads, CategoryList.of("download"))
        for (key in listOf("large", "largefiles", "Large files", "other", "other files")) assertEquals(CategoryList.Large, CategoryList.of(key))
        assertNull(CategoryList.of("something else"))
    }

    @Test
    fun `only duplicates and empty folders take a walk`() {
        for (key in listOf("duplicates", "Duplicate", "empty folders", "EMPTY_FOLDERS")) assertTrue(key, CategoryList.takesAWalk(key))
        for (key in listOf("images", "large", "downloads", "something else", "")) assertFalse(key, CategoryList.takesAWalk(key))
    }

    // Lists

    @Test
    fun `the index picks a category's files, and the rows say what it knows`() = runBlocking {
        index.files = listOf(
            IndexedFile(7, "/s/a.jpg", 2048, 1_700_000_000_000L, mediaType = 1, scanned = true),
            IndexedFile(8, "/s/voice.3gp", 10, 5, mediaType = 2, scanned = true),
            IndexedFile(9, "/s/art.tga", 10, 5, mediaType = 0, scanned = false)
        )
        val rows = lists(marks(favorites = setOf("/s/a.jpg"), locks = setOf("/s"))).files("images", null)
        assertEquals(listOf("/s/a.jpg", "/s/art.tga"), rows.map { it.path })
        assertEquals(listOf(7, 9), rows.map { it.id })
        assertEquals("a.jpg", rows[0].name)
        assertEquals("image", rows[0].type)
        assertEquals("file", rows[0].kind)
        assertEquals("2.0 kB", rows[0].size)
        assertEquals(1_700_000_000_000L, rows[0].lastModified)
        assertTrue(rows[0].isPinned)
        assertTrue(rows[0].isLocked)
        assertFalse(rows[1].isPinned)
        assertEquals(listOf(StorageCategory.Images.indexSelection()), index.conditions)
    }

    @Test
    fun `large files are asked for by size, with those not measured yet`() = runBlocking {
        index.files = listOf(
            IndexedFile(1, "/s/big.bin", LARGE_FILE_BYTES, 5, mediaType = 0, scanned = true),
            IndexedFile(2, "/s/small.bin", LARGE_FILE_BYTES - 1, 5, mediaType = 0, scanned = false)
        )
        assertEquals(listOf("big.bin"), lists().files("large", null).map { it.name })
        assertEquals(listOf(IndexConditions.LargeOrUnscanned), index.conditions)
    }

    @Test
    fun `without an index the disk is walked`() = runBlocking {
        temp.root.with("DCIM/a.jpg")
        temp.root.with("DCIM/b.jpg")
        temp.root.with("DCIM/.hidden.jpg")
        temp.root.with("Android/data/com.app/c.jpg")
        temp.root.with("notes.txt")
        val rows = lists().files("images", null)
        assertEquals(setOf("a.jpg", "b.jpg"), rows.map { it.name }.toSet())
        assertEquals(listOf(300000, 300001), rows.map { it.id })
        large("huge.bin")
        assertEquals(listOf("huge.bin"), lists().files("large", null).map { it.name })
    }

    @Test
    fun `an index that's there but empty isn't a reason to walk`() = runBlocking {
        temp.root.with("a.jpg")
        index.files = emptyList()
        assertEquals(0, lists().files("images", null).size)
    }

    @Test
    fun `a ts file is looked into`() = runBlocking {
        val code = temp.root.with("app.ts", "export const answer = 42")
        index.files = listOf(IndexedFile(1, code.path, 10, 5, mediaType = 3, scanned = true))
        assertEquals("doc", lists().files("videos", null).single().type)
    }

    @Test
    fun `downloads are the newest first, folders and all`() = runBlocking {
        temp.root.with("Download/old.zip", "12345").setLastModified(1_600_000_000_000L)
        temp.root.with("Download/new.txt").setLastModified(1_700_000_000_000L)
        temp.root.with("Download/.hidden")
        temp.root.resolve("Download/empty").mkdirs()
        temp.root.resolve("Download/empty").setLastModified(1_650_000_000_000L)
        val rows = lists().files("downloads", null)
        assertEquals(listOf("new.txt", "empty", "old.zip"), rows.map { it.name })
        assertEquals(listOf("doc", "folder", "archive"), rows.map { it.type })
        assertEquals(listOf("file", "folder", "file"), rows.map { it.kind })
        assertEquals("--", rows[1].size)
        assertTrue(rows[1].isEmptyFolder)
        assertEquals("5 B", rows[2].size)
        assertEquals(3, rows.map { it.id }.toSet().size)
    }

    @Test
    fun `what a walk found is listed from it`() = runBlocking {
        temp.root.resolve("empty").mkdirs()
        val found = scan().run {}
        assertSame(found.emptyFolders.files, lists().files("empty folders", found))
        assertSame(found.duplicates.files, lists().files("duplicates", found))
        assertEquals(0, lists().files("duplicates", null).size)
        assertEquals(0, lists().files("something else", found).size)
    }

    // Totals

    @Test
    fun `totals are counted on the disk where the index can't be asked`() = runBlocking {
        temp.root.with("a.jpg", "x".repeat(100))
        temp.root.with("b.mp4", "x".repeat(200))
        temp.root.with("Download/c.pdf", "x".repeat(50))
        temp.root.with("Download/.hidden", "x")
        temp.root.with("d.bin", "x")
        val totals = lists().totals()
        assertEquals(100L, totals.imageBytes)
        assertEquals(200L, totals.videoBytes)
        assertEquals(50L, totals.docBytes)
        assertEquals(0L, totals.largeBytes)
        assertEquals(250L, totals.otherBytes)
        assertEquals(1, totals.downloadsCount)
    }

    // The Cleaner's walk

    @Test
    fun `the walk's totals come before its duplicates`() = runBlocking {
        temp.root.with("a.jpg", "x".repeat(2000))
        temp.root.with("copy/a.jpg", "x".repeat(2000))
        temp.root.with("Download/b.zip", "x".repeat(30))
        temp.root.resolve("Download/folder/empty").mkdirs()
        large("big.bin")
        var walked: CleanerData? = null
        val found = scan().run { walked = it }
        val first = walked!!
        assertEquals(4000L, first.images.totalSizeBytes)
        assertEquals(30L, first.archives.totalSizeBytes)
        assertEquals(LARGE_FILE_BYTES, first.large.totalSizeBytes)
        assertEquals(LARGE_FILE_BYTES, first.largeFilesSizeBytes)
        assertEquals(2, first.downloadsCount)
        assertEquals(30L, first.downloadsSizeBytes)
        assertEquals(30L, first.downloads.totalSizeBytes)
        assertEquals(1000L, first.totalStorageBytes)
        assertEquals(400L, first.freeStorageBytes)
        assertEquals(0L, first.otherBytes)
        assertEquals(0, first.duplicates.files.size)
        assertEquals(listOf("empty"), first.emptyFolders.files.map { it.name })
        val empty = first.emptyFolders.files.single()
        assertEquals("--", empty.size)
        assertTrue(empty.isEmptyFolder)
        assertEquals(100000, empty.id)

        assertEquals(1, found.duplicates.files.size)
        assertEquals(2000L, found.duplicates.totalSizeBytes)
        assertEquals("Ids go on from the empty folders'", 100001, found.duplicates.files.single().id)
        assertEquals(first.copy(duplicates = found.duplicates), found)
    }

    @Test
    fun `the oldest of some copies is the one that's kept`() = runBlocking {
        val text = "x".repeat(2000)
        val newer = temp.root.with("a.bin", text).also { it.setLastModified(2_000_000_000L) }
        val oldest = temp.root.with("b.bin", text).also { it.setLastModified(1_000_000_000L) }
        val newest = temp.root.with("c.bin", text).also { it.setLastModified(3_000_000_000L) }
        val extras = scan().run {}.duplicates.files
        assertEquals(setOf(newer.path, newest.path), extras.map { it.path }.toSet())
        assertTrue(extras.all { it.duplicateOf == oldest.path })
        assertTrue(extras.all { it.kind == "file" && it.sizeBytes == 2000L })
    }

    @Test
    fun `a copy that can't be deleted is the one that's kept`() = runBlocking {
        val text = "x".repeat(2000)
        val fixed = temp.root.with("fixed.bin", text).also { it.setLastModified(3_000_000_000L) }
        val alsoFixed = temp.root.with("fixed too.bin", text).also { it.setLastModified(3_500_000_000L) }
        val old = temp.root.with("old.bin", text).also { it.setLastModified(1_000_000_000L) }
        val found = scan(canDelete = { "fixed" !in it.substringAfterLast('/') }).run {}
        assertEquals(listOf(old.path), found.duplicates.files.map { it.path })
        assertEquals(fixed.path, found.duplicates.files.single().duplicateOf)
        assertTrue(alsoFixed.exists())
    }

    @Test
    fun `files that only share a size, or are tiny, are no duplicates`() = runBlocking {
        temp.root.with("a.bin", "a".repeat(2000))
        temp.root.with("b.bin", "b".repeat(2000))
        temp.root.with("tiny.bin", "x".repeat(1024))
        temp.root.with("tiny too.bin", "x".repeat(1024))
        assertEquals(0, scan().run {}.duplicates.files.size)
    }

    @Test
    fun `the largest duplicates are first`() = runBlocking {
        for (size in listOf(2000, 5000, 3000)) {
            temp.root.with("a$size.bin", "x".repeat(size))
            temp.root.with("b$size.bin", "x".repeat(size))
        }
        val found = scan().run {}.duplicates
        assertEquals(listOf(5000L, 3000L, 2000L), found.files.map { it.sizeBytes })
        assertEquals(10_000L, found.totalSizeBytes)
    }

    @Test
    fun `a walk that breaks off keeps what it found, and a cancelled one ends`() = runBlocking {
        fun stopping(with: Exception) = object : PhoneStorage by storage {
            override suspend fun walk(
                onFile: (path: String, size: Long, modifiedMillis: Long) -> Unit,
                onEmptyFolder: (path: String, modifiedMillis: Long) -> Unit
            ) {
                onFile("/s/a.jpg", 100, 0)
                throw with
            }
        }
        assertEquals(100L, scan(storage = stopping(IllegalStateException("gone"))).run {}.images.totalSizeBytes)
        assertThrows(CancellationException::class.java) {
            runBlocking { scan(storage = stopping(CancellationException("stop"))).run {} }
        }
        Unit
    }
}
