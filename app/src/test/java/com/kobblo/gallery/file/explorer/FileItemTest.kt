package com.kobblo.gallery.file.explorer

import com.kobblo.gallery.file.explorer.CleanerData.Companion.totalOnly
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FileItemTest {
    private fun suggestion(name: String, vararg taken: String) = suggestNonConflictingName(name, taken.toSet())

    @Test
    fun `a suggested name keeps the extension`() {
        assertEquals("photo (1).jpg", suggestion("photo.jpg", "photo.jpg"))
        assertEquals("archive.tar (1).gz", suggestion("archive.tar.gz"))
        assertEquals("notes (1)", suggestion("notes"))
        assertEquals("ends with a dot (1).", suggestion("ends with a dot."))
    }

    @Test
    fun `a suggested name is the first that's free`() {
        assertEquals("photo (3).jpg", suggestion("photo.jpg", "photo.jpg", "photo (1).jpg", "photo (2).jpg", "photo (4).jpg"))
    }

    @Test
    fun `the dot that hides a file is no extension`() {
        assertEquals(".nomedia (1)", suggestion(".nomedia"))
        assertEquals(".config (1).json", suggestion(".config.json"))
        assertEquals("..odd (1)", suggestion("..odd"))
    }

    private fun item(path: String, size: Long) =
        FileItem(path.hashCode(), path, path, "file", "file", "", "", "Me", sizeBytes = size)

    private fun category(name: String, vararg files: FileItem) =
        CleanerCategoryData(name, files.toList().toImmutableList(), files.sumOf { it.sizeBytes })

    private val scan = CleanerData(
        documents = totalOnly("Documents", 10),
        images = totalOnly("Images", 20),
        videos = totalOnly("Videos"),
        audio = totalOnly("Audio"),
        apps = totalOnly("Apps"),
        emptyFolders = category("Empty folders", item("/e1", 0), item("/e2", 0)),
        duplicates = category("Duplicates", item("/d1", 100), item("/d2", 50)),
        otherBytes = 70,
        totalStorageBytes = 1000,
        freeStorageBytes = 300
    )

    @Test
    fun `what's deleted leaves the cleaner at once`() {
        val after = scan.removePaths(setOf("/d1", "/e2", "/not listed"))
        assertEquals(listOf("/d2"), after.duplicates.files.map { it.path })
        assertEquals(50L, after.duplicates.totalSizeBytes)
        assertEquals(listOf("/e1"), after.emptyFolders.files.map { it.path })
        assertEquals(400L, after.freeStorageBytes)
        assertEquals("Never below nothing", 0L, after.otherBytes)
        assertEquals("The rest waits for the next walk", 20L, after.images.totalSizeBytes)
    }

    @Test
    fun `no more is free than there is`() {
        assertEquals(1000L, scan.copy(freeStorageBytes = 950).removePaths(setOf("/d1")).freeStorageBytes)
    }

    @Test
    fun `deleting nothing changes nothing`() {
        assertSame(scan, scan.removePaths(emptySet()))
    }

    @Test
    fun `a scan without the later categories has them empty`() {
        assertEquals(CleanerCategoryData("Archives", emptyList<FileItem>().toImmutableList(), 0L), scan.archives)
        assertEquals("Downloads", scan.downloads.categoryName)
        assertEquals("Large", scan.large.categoryName)
    }
}
