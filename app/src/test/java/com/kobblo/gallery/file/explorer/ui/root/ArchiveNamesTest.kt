package com.kobblo.gallery.file.explorer.ui.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveNamesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun folderWithAFile(name: String) = folder.newFolder(name).also { File(it, "a.txt").writeText("a") }

    @Test
    fun aNameNotTakenIsUsedAsItIs() {
        assertEquals(File(folder.root, "photos"), freeFolder(folder.root, "photos"))
    }

    @Test
    fun anEmptyFolderWithTheNameWillDo() {
        folder.newFolder("photos")
        assertEquals(File(folder.root, "photos"), freeFolder(folder.root, "photos"))
    }

    @Test
    fun aFolderInUseGetsANumberUntilOneIsFree() {
        folderWithAFile("photos")
        assertEquals(File(folder.root, "photos (1)"), freeFolder(folder.root, "photos"))
        folderWithAFile("photos (1)")
        folder.newFolder("photos (2)")
        assertEquals(File(folder.root, "photos (2)"), freeFolder(folder.root, "photos"))
    }

    @Test
    fun anArchiveIsNamedAfterTheFirstFileInIt() {
        assertEquals("a.zip", archiveNameFor(listOf(testFile("a.txt"))))
        assertEquals("a_archive.zip", archiveNameFor(listOf(testFile("a.txt"), testFile("b.txt"))))
        assertEquals("README.zip", archiveNameFor(listOf(testFile("README"))))
        assertEquals("v1.0.zip", archiveNameFor(listOf(testFile("v1.0", type = "directory"))))
    }

    @Test
    fun aMadeArchiveIsAnItemLikeAnyOther() {
        val file = folder.newFile("a.zip").apply { writeText("zip") }
        val item = madeArchive(file, sizeLabel = "3 B")
        assertEquals(file.absolutePath, item.path)
        assertEquals(file.absolutePath.hashCode(), item.id)
        assertEquals("a.zip", item.name)
        assertEquals("zip", item.type)
        assertEquals("Archive", item.kind)
        assertEquals("3 B", item.size)
        assertEquals(3L, item.sizeBytes)
        assertEquals(file.lastModified(), item.lastModified)
        assertEquals("rar", madeArchive(File(folder.root, "b.RAR"), "").type)
    }

    @Test
    fun aRarIsToldByItsNameAtFirst() {
        assertTrue(looksLikeRar("a.rar"))
        assertTrue(looksLikeRar("A.RAR"))
        assertFalse(looksLikeRar("a.zip"))
    }

    @Test
    fun lookingInsideLeadsStraightToChoosingWhereItGoes() {
        val zip = testFile("a.zip")
        val desk = ArchiveDesk()
        desk.offer(zip)
        desk.browse(zip)
        assertNull(desk.offered)
        desk.extractBrowsed("secret")
        assertNull(desk.browsed)
        assertEquals(zip, desk.homeless)
        assertEquals("secret", desk.password)
        desk.endFindingHome()
        assertNull(desk.homeless)
        assertNull(desk.password)
    }

    @Test
    fun choosingWhereItGoesLeavesTheOfferUnderIt() {
        val zip = testFile("a.zip")
        val desk = ArchiveDesk()
        desk.offer(zip)
        desk.findHome(zip)
        assertEquals(zip, desk.offered)
        assertNull(desk.password)
    }
}
