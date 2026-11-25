package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerListingTest {
    private var nextId = 0

    private fun item(name: String, type: String = "file", kind: String = type, locked: Boolean = false) = FileItem(
        id = nextId++,
        path = "/storage/emulated/0/$name",
        name = name,
        type = type,
        kind = kind,
        size = "",
        changed = "",
        owner = "Me",
        isLocked = locked
    )

    private fun names(items: List<FileItem>) = items.map { it.name }

    @Test
    fun foldersAndFilesAreToldApart() {
        val listing = PickerListing.of(listOf(item("notes.txt"), item("Trips", type = "folder"), item("a.jpg", type = "image")))
        assertEquals(listOf("Trips"), names(listing.folders))
        assertEquals(listOf("a.jpg", "notes.txt"), names(listing.files))
    }

    @Test
    fun eitherMarkMakesAFolder() {
        val listing = PickerListing.of(
            listOf(item("ByType", type = "folder", kind = "directory"), item("ByKind", type = "dir", kind = "folder"), item("x.bin"))
        )
        assertEquals(listOf("ByKind", "ByType"), names(listing.folders))
        assertEquals(listOf("x.bin"), names(listing.files))
    }

    @Test
    fun eachPartIsInOrderOfNameWhateverItsCase() {
        val listing = PickerListing.of(
            listOf(item("banana.txt"), item("Cherry.txt"), item("apple.txt"), item("zoo", type = "folder"), item("Attic", type = "folder"))
        )
        assertEquals(listOf("Attic", "zoo"), names(listing.folders))
        assertEquals(listOf("apple.txt", "banana.txt", "Cherry.txt"), names(listing.files))
    }

    @Test
    fun namesThatDifferOnlyInCaseKeepTheOrderTheyCameIn() {
        val listing = PickerListing.of(listOf(item("b.txt"), item("A.txt"), item("a.txt")))
        assertEquals(listOf("A.txt", "a.txt", "b.txt"), names(listing.files))
    }

    @Test
    fun hiddenAndLockedItemsAreNotOffered() {
        val listing = PickerListing.of(
            listOf(
                item(".nomedia"),
                item(".thumbnails", type = "folder"),
                item("secret.pdf", locked = true),
                item("Private", type = "folder", locked = true),
                item("open.pdf")
            )
        )
        assertTrue(listing.folders.isEmpty())
        assertEquals(listOf("open.pdf"), names(listing.files))
    }

    @Test
    fun aFolderWithNothingToOfferIsEmpty() {
        assertTrue(PickerListing.of(emptyList()).isEmpty)
        assertTrue(PickerListing.of(listOf(item(".hidden"))).isEmpty)
        assertTrue(PickerListing.Empty.isEmpty)
        assertFalse(PickerListing.of(listOf(item("a.txt"))).isEmpty)
        assertFalse(PickerListing.of(listOf(item("Trips", type = "folder"))).isEmpty)
    }

    @Test
    fun thePhonesStorageIsShownByName() {
        val root = "/storage/emulated/0"
        assertEquals("Internal storage", pickerPathLabel(root, root, "Internal storage"))
        assertEquals("Internal storage/Download/Trips", pickerPathLabel("$root/Download/Trips", root, "Internal storage"))
    }

    @Test
    fun aPathElsewhereIsShownAsItIs() {
        assertEquals("/storage/1A2B-3C4D/Music", pickerPathLabel("/storage/1A2B-3C4D/Music", "/storage/emulated/0", "Internal storage"))
    }
}
