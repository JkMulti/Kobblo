package com.kobblo.gallery.file.explorer.vault

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class VaultListingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var vault: File
    private lateinit var store: VaultStore
    private val vaultKey = ByteArray(32) { (it + 3).toByte() }

    @Before
    fun createVault() {
        vault = folder.newFolder("vault_files")
        File(vault, ".nomedia").createNewFile()
        store = VaultStore(vault, vaultKey)
    }

    private fun seal(name: String, size: Int, modified: Long): File =
        store.add(name, ByteArrayInputStream(ByteArray(size))).also { it.setLastModified(modified) }

    private fun list(store: VaultStore? = this.store): List<FileItem> = VaultListing.list(
        folder = vault,
        store = store,
        unreadableName = "Unreadable file",
        sizeLabel = { "$it bytes" },
        dateLabel = { "at $it" }
    )

    @Test
    fun type_followsTheExtensionWhateverItsCase() {
        assertEquals("image", VaultListing.typeOf("holiday.jpeg"))
        assertEquals("image", VaultListing.typeOf("HOLIDAY.PNG"))
        assertEquals("video", VaultListing.typeOf("clip.mp4"))
        assertEquals("audio", VaultListing.typeOf("song.mp3"))
        assertEquals("doc", VaultListing.typeOf("contract.pdf"))
        assertEquals("doc", VaultListing.typeOf("notes.md"))
        assertEquals("archive", VaultListing.typeOf("backup.tar.gz"))
        assertEquals("archive", VaultListing.typeOf("photos.zip"))
        assertEquals("apk", VaultListing.typeOf("installer.apk"))
    }

    @Test
    fun type_isPlainFileForEverythingElse() {
        assertEquals("file", VaultListing.typeOf("data.bin"))
        assertEquals("file", VaultListing.typeOf("no extension"))
        assertEquals("file", VaultListing.typeOf("ends with a dot."))
        assertEquals("file", VaultListing.typeOf(""))
    }

    @Test
    fun type_ofAFolderIgnoresItsName() {
        assertEquals("folder", VaultListing.typeOf("Photos.jpg", isFolder = true))
    }

    @Test
    fun sealedFiles_areListedUnderTheirRealNamesAndSizes() {
        val file = seal("passport.jpg", size = 1_234, modified = 5_000)

        val row = list().single()

        assertEquals("passport.jpg", row.name)
        assertEquals(file.absolutePath, row.path)
        assertEquals("image", row.type)
        assertEquals("file", row.kind)
        assertEquals(1_234L, row.sizeBytes)
        assertEquals("1234 bytes", row.size)
        assertEquals(5_000L, row.lastModified)
        assertEquals("at 5000", row.changed)
        assertTrue(row.isLocked)
        assertFalse(row.isPinned)
        assertFalse(row.isEmptyFolder)
        assertEquals("", row.owner)
    }

    @Test
    fun withoutTheKey_sealedFilesGiveNothingAway() {
        val file = seal("passport.jpg", size = 1_234, modified = 5_000)

        val row = list(store = null).single()

        assertEquals("Unreadable file", row.name)
        assertEquals("file", row.type)
        assertEquals("All that can be told is what the file takes up on disk", file.length(), row.sizeBytes)
        assertEquals(file.absolutePath, row.path)
    }

    @Test
    fun aDifferentKey_readsNoNamesEither() {
        seal("passport.jpg", size = 100, modified = 5_000)

        val row = list(store = VaultStore(vault, ByteArray(32) { 9 })).single()

        assertEquals("Unreadable file", row.name)
    }

    @Test
    fun unencryptedFiles_areListedAsTheyAre() {
        File(vault, "old video.mp4").apply {
            writeBytes(ByteArray(77))
            setLastModified(9_000)
        }

        val row = list().single()

        assertEquals("old video.mp4", row.name)
        assertEquals("video", row.type)
        assertEquals(77L, row.sizeBytes)
        assertEquals(File(vault, "old video.mp4").absolutePath, row.path)
        assertTrue(row.isLocked)
    }

    @Test
    fun theVaultsOwnHiddenFiles_areNotListed() {
        File(vault, ".hidden-note").writeText("not shown")
        seal("shown.txt", size = 10, modified = 1_000)

        assertEquals(listOf("shown.txt"), list().map { it.name })
    }

    @Test
    fun aFolderLeftInTheVault_isListedAsOne() {
        File(vault, "Empty").mkdirs()
        File(vault, "Full").mkdirs()
        File(vault, "Full/inside.txt").writeText("x")

        val rows = list().associateBy { it.name }

        assertEquals("folder", rows.getValue("Empty").type)
        assertEquals("folder", rows.getValue("Empty").kind)
        assertTrue(rows.getValue("Empty").isEmptyFolder)
        assertFalse(rows.getValue("Full").isEmptyFolder)
    }

    @Test
    fun rows_comeNewestFirst() {
        seal("middle.txt", size = 1, modified = 20_000)
        seal("oldest.txt", size = 1, modified = 10_000)
        File(vault, "newest.txt").apply {
            writeText("x")
            setLastModified(30_000)
        }

        assertEquals(listOf("newest.txt", "middle.txt", "oldest.txt"), list().map { it.name })
    }

    @Test
    fun rowIds_areDistinctAndClearOfTheFileBrowsers() {
        seal("a.txt", size = 1, modified = 10_000)
        seal("b.txt", size = 1, modified = 20_000)
        File(vault, "c.txt").writeText("x")

        val ids = list().map { it.id }

        assertEquals(3, ids.toSet().size)
        assertEquals(setOf(900000, 900001, 900002), ids.toSet())
    }

    @Test
    fun anEmptyVault_listsNothing() {
        assertEquals(emptyList<FileItem>(), list())
        assertEquals(emptyList<FileItem>(), list(store = null))
    }
}
