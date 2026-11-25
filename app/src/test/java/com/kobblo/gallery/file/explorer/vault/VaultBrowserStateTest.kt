package com.kobblo.gallery.file.explorer.vault

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultBrowserStateTest {
    private var nextId = 900000

    private fun file(name: String, type: String, sizeBytes: Long = 100) = FileItem(
        id = nextId++,
        path = "/vault/.encrypted/$name",
        name = name,
        type = type,
        kind = if (type == "folder") "folder" else "file",
        size = "$sizeBytes B",
        changed = "",
        owner = "",
        isLocked = true,
        sizeBytes = sizeBytes
    )

    private val photo = file("beach.jpg", "image", sizeBytes = 3_000)
    private val film = file("wedding.mp4", "video", sizeBytes = 90_000)
    private val letter = file("letter.pdf", "doc", sizeBytes = 400)
    private val oddment = file("keys.kdbx", "file", sizeBytes = 50)

    private fun browserShowing(vararg files: FileItem) = VaultBrowserState().apply { show(files.toList()) }

    @Test
    fun itStartsOutReadingTheVault() {
        val browser = VaultBrowserState()

        assertTrue(browser.isLoading)
        assertEquals(emptyList<FileItem>(), browser.files)
        assertEquals(VaultFilter.All, browser.filter)
        assertNull(browser.menuFile)
        assertNull(browser.fileToDelete)
    }

    @Test
    fun theFirstReading_endsTheWaitEvenWhenTheVaultIsEmpty() {
        val browser = VaultBrowserState()

        browser.show(emptyList())

        assertFalse(browser.isLoading)
        assertEquals(emptyList<FileItem>(), browser.shownFiles)
    }

    @Test
    fun everythingIsShown_untilAKindIsChosen() {
        val browser = browserShowing(photo, film, letter)

        assertEquals(listOf(photo, film, letter), browser.shownFiles)
    }

    @Test
    fun choosingAKind_showsOnlyThatKind_andAllBringsTheRestBack() {
        val browser = browserShowing(photo, film, letter)

        browser.choose(VaultFilter.Videos)
        assertEquals(listOf(film), browser.shownFiles)

        browser.choose(VaultFilter.All)
        assertEquals(listOf(photo, film, letter), browser.shownFiles)
    }

    @Test
    fun onlyKindsTheVaultHolds_areOffered_afterAll_inTheirSetOrder() {
        val browser = browserShowing(letter, film, photo)

        assertEquals(
            listOf(VaultFilter.All, VaultFilter.Images, VaultFilter.Videos, VaultFilter.Documents),
            browser.filters
        )
    }

    @Test
    fun anEmptyVault_offersOnlyAll() {
        assertEquals(listOf(VaultFilter.All), browserShowing().filters)
    }

    @Test
    fun everyKindAtOnce_isOfferedInChipOrder() {
        val browser = browserShowing(
            oddment,
            file("a.zip", "archive"),
            file("a.apk", "apk"),
            letter,
            file("a.mp3", "audio"),
            film,
            photo
        )

        assertEquals(VaultFilter.entries.toList(), browser.filters)
        assertEquals(
            listOf(
                VaultFilter.All, VaultFilter.Images, VaultFilter.Videos, VaultFilter.Audio,
                VaultFilter.Documents, VaultFilter.Apps, VaultFilter.Archives, VaultFilter.Other
            ),
            browser.filters
        )
    }

    @Test
    fun eachNamedKind_acceptsItsOwnTypeAndNoOther() {
        val typeOf = mapOf(
            VaultFilter.Images to "image",
            VaultFilter.Videos to "video",
            VaultFilter.Audio to "audio",
            VaultFilter.Documents to "doc",
            VaultFilter.Apps to "apk",
            VaultFilter.Archives to "archive"
        )
        for ((kind, type) in typeOf) {
            for (other in typeOf.values + listOf("file", "folder")) {
                assertEquals("$kind and a file of type $other", other == type, kind.accepts(file("x", other)))
            }
        }
    }

    @Test
    fun other_isWhateverNoNamedKindClaims() {
        assertTrue(VaultFilter.Other.accepts(oddment))
        assertTrue(VaultFilter.Other.accepts(file("Old Folder", "folder")))
        assertTrue(VaultFilter.Other.accepts(file("mystery", "something new")))
        assertFalse(VaultFilter.Other.accepts(photo))
        assertFalse(VaultFilter.Other.accepts(file("a.apk", "apk")))
    }

    @Test
    fun all_acceptsEverything() {
        for (item in listOf(photo, film, letter, oddment, file("Old Folder", "folder"))) {
            assertTrue(VaultFilter.All.accepts(item))
        }
    }

    @Test
    fun whenTheLastFileOfTheChosenKindLeaves_theListIsEmptyButTheVaultIsNot() {
        val browser = browserShowing(photo, letter)
        browser.choose(VaultFilter.Images)

        browser.show(listOf(letter))

        assertEquals(emptyList<FileItem>(), browser.shownFiles)
        assertEquals(listOf(letter), browser.files)
        assertEquals("The choice stands until it's changed", VaultFilter.Images, browser.filter)
        assertEquals("But its chip has gone", listOf(VaultFilter.All, VaultFilter.Documents), browser.filters)

        browser.choose(VaultFilter.All)
        assertEquals(listOf(letter), browser.shownFiles)
    }

    @Test
    fun aNewReading_keepsTheChosenKind() {
        val browser = browserShowing(photo, letter)
        browser.choose(VaultFilter.Images)
        val another = file("hills.png", "image")

        browser.show(listOf(another, photo, letter))

        assertEquals(listOf(another, photo), browser.shownFiles)
    }

    @Test
    fun theTotal_countsEveryFileWhateverIsShown() {
        val browser = browserShowing(photo, film, letter)
        browser.choose(VaultFilter.Documents)

        assertEquals(3_000L + 90_000L + 400L, browser.totalBytes)
        assertEquals(0L, browserShowing().totalBytes)
    }

    @Test
    fun aFilesMenu_opensAndCloses() {
        val browser = browserShowing(photo, letter)

        browser.openMenu(letter)
        assertEquals(letter, browser.menuFile)

        browser.closeMenu()
        assertNull(browser.menuFile)
    }

    @Test
    fun askingToDelete_closesTheMenu_andWaitsForAnAnswer() {
        val browser = browserShowing(photo, letter)
        browser.openMenu(letter)

        browser.askToDelete(letter)

        assertNull(browser.menuFile)
        assertEquals(letter, browser.fileToDelete)
        assertEquals("Nothing is gone until the answer is yes", listOf(photo, letter), browser.files)

        browser.doneDeleting()
        assertNull(browser.fileToDelete)
    }
}
