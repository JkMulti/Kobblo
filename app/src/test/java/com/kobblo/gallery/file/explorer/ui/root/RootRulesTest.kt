package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRulesTest {
    private val photo = testFile("a.jpg", id = 1, type = "image")
    private val hidden = testFile(".b.jpg", id = 2, type = "image")
    private val empty = testFile("New Folder", id = 3, type = "folder", emptyFolder = true)

    // --- Tapping a file ---

    @Test
    fun aLockComesBeforeSelectingAndSelectingBeforeOpening() {
        val locked = testFile("a.jpg", locked = true)
        assertEquals(FileTap.Unlock, fileTap(locked, selecting = false))
        assertEquals(FileTap.Unlock, fileTap(locked, selecting = true))
        assertEquals(FileTap.Select, fileTap(photo, selecting = true))
        assertEquals(FileTap.Open, fileTap(photo, selecting = false))
    }

    // --- Asking before deleting ---

    private fun title(files: List<FileItem>, selected: Int = files.size) = deleteTitle(Doomed.Selection, files, selected)

    @Test
    fun oneFileIsNamed() {
        assertEquals(DeleteTitle.Named("a.jpg"), deleteTitle(Doomed.One(photo), listOf(photo), selectedCount = 0))
        assertEquals(DeleteTitle.Named("a.jpg"), deleteTitle(Doomed.One(photo), listOf(photo), selectedCount = 5))
        assertEquals(DeleteTitle.Named("a.jpg"), title(listOf(photo)))
    }

    @Test
    fun severalAreCountedByWhatIsSelected() {
        assertEquals(DeleteTitle.Counted(2), title(listOf(photo, hidden)))
        assertEquals(DeleteTitle.Counted(3), title(listOf(photo, hidden), selected = 3))
        assertEquals(DeleteTitle.Counted(2), title(emptyList(), selected = 2))
    }

    @Test
    fun aSelectionTheListHasLostIsStillAskedAbout() {
        assertEquals(DeleteTitle.OneItem, title(emptyList(), selected = 1))
        assertEquals(DeleteTitle.SelectedItems, title(emptyList(), selected = 0))
        // The list may still have one of the two.
        assertEquals(DeleteTitle.Named("a.jpg"), title(listOf(photo), selected = 2))
    }

    private fun note(files: List<FileItem>, location: String = PhoneRoot, binOn: Boolean = true) = deleteNote(location, binOn, files)

    @Test
    fun deletingWithNoBinIsForGood() {
        assertEquals(DeleteNote.GoneForGood, note(listOf(photo), binOn = false))
        assertEquals(DeleteNote.GoneForGood, note(listOf(photo), location = "drive"))
        assertEquals(DeleteNote.GoneForGood, note(listOf(empty), location = "category/empty", binOn = false))
    }

    @Test
    fun theCleanersEmptyFoldersSkipTheBin() {
        assertEquals(DeleteNote.EmptyFolders, note(listOf(empty), location = "category/empty"))
        assertEquals(DeleteNote.TheyGoToBin, note(listOf(empty, photo), location = "category/empty"))
        assertEquals(DeleteNote.ItGoesToBin, note(listOf(empty)))
        assertEquals(DeleteNote.TheyGoToBin, note(emptyList(), location = "category/empty"))
    }

    @Test
    fun theRestGoToTheBin() {
        assertEquals(DeleteNote.ItGoesToBin, note(listOf(photo)))
        assertEquals(DeleteNote.TheyGoToBin, note(listOf(photo, hidden)))
    }

    @Test
    fun aDeleteQuestionIsAboutWhatWasAskedLast() {
        val question = DeleteQuestion()
        assertNull(question.about)
        question.ask(photo)
        assertEquals(Doomed.One(photo), question.about)
        question.askAboutSelection()
        assertEquals(Doomed.Selection, question.about)
        question.settle()
        assertNull(question.about)
    }

    // --- Hiding ---

    @Test
    fun filesAreHiddenWhenEveryOneIs() {
        assertTrue(allHidden(listOf(hidden)))
        assertFalse(allHidden(listOf(hidden, photo)))
        assertFalse(allHidden(emptyList()))
    }

    @Test
    fun theFirstHidingIsHeldBackToBeExplained() {
        val sent = mutableListOf<AppAction>()
        val hiding = Hiding()
        assertFalse(hiding.toggle(listOf(photo, hidden), explained = false) { sent += it })
        assertEquals(listOf(photo, hidden), hiding.heldBack)
        assertEquals(emptyList<AppAction>(), sent)
        hiding.drop()
        assertNull(hiding.heldBack)
    }

    @Test
    fun hidingExplainedBeforeAndShowingAgainGoAhead() {
        val sent = mutableListOf<AppAction>()
        val hiding = Hiding()
        assertTrue(hiding.toggle(listOf(photo), explained = true) { sent += it })
        assertTrue(hiding.toggle(listOf(hidden), explained = false) { sent += it })
        assertNull(hiding.heldBack)
        assertEquals(listOf(AppAction.ToggleHideFiles(listOf(photo)), AppAction.ToggleHideFiles(listOf(hidden))), sent)
    }

    // --- Names ---

    @Test
    fun renamingPicksOutTheNameWithoutItsExtension() {
        assertEquals(1, nameEnd("a.jpg"))
        assertEquals(5, nameEnd("a.tar.gz"))
        assertEquals(6, nameEnd("README"))
        assertEquals(10, nameEnd(".gitignore"))
        assertEquals(1, nameEnd("a."))
    }

    // --- The foot of the screen ---

    @Test
    fun messagesClearTheBarAndTheSystemsButtons() {
        assertEquals(93.dp, toastLift(0.dp, selecting = false))
        assertEquals(93.dp, toastLift(24.dp, selecting = false))
        assertEquals(89.dp, toastLift(24.dp, selecting = true))
        assertEquals(143.dp, toastLift(48.dp, selecting = false))
        assertEquals(139.dp, toastLift(48.dp, selecting = true))
    }

    @Test
    fun onlyASelectionOfNothingButAppsCanBeInstalled() {
        val app = testFile("a.apk")
        val bundle = testFile("b.APKS")
        assertTrue(Selection(listOf(app, bundle), selecting = true).isInstallable)
        assertFalse(Selection(listOf(app), selecting = false).isInstallable)
        assertFalse(Selection(listOf(app, photo), selecting = true).isInstallable)
        assertFalse(Selection(emptyList(), selecting = true).isInstallable)
        assertEquals(listOf(app), Selection(listOf(photo, app), selecting = true).apps)
    }

    @Test
    fun anArchiveIsOfferedOnlyWhenItIsSelectedAlone() {
        val zip = testFile("a.zip")
        assertEquals(zip, Selection(listOf(zip), selecting = true).archive)
        assertNull(Selection(listOf(zip, photo), selecting = true).archive)
        assertNull(Selection(listOf(photo), selecting = true).archive)
    }

    @Test
    fun theInstallButtonOpensItsCardAndPutsItAway() {
        val desk = InstallDesk()
        val one = listOf(testFile("a.apk"))
        val two = one + testFile("b.apk")
        desk.toggleCard(one)
        assertEquals(AppCount.One, desk.card)
        desk.toggleCard(two)
        assertEquals(AppCount.Several, desk.card)
        desk.toggleCard(two)
        assertNull(desk.card)
    }
}
