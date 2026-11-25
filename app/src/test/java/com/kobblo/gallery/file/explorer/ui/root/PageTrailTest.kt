package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.NavTab
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTrailTest {
    private fun back(location: String, cleanerWaiting: Boolean = false) = backFrom(location, cleanerWaiting, PhoneRoot)

    private fun at(location: String) = emptyState().copy(location = location)

    private fun tap(tab: ExpressiveTab, state: AppState, aboutOpen: Boolean = false, browsePlace: String = "$PhoneRoot/DCIM") =
        tabMove(tab, state, aboutOpen, browsePlace)

    private val covered = at("locations").copy(showVaultScreen = true, showTrashScreen = true, showCleanerScreen = true)
    private val uncovering = listOf(
        AppAction.SetShowVaultScreen(false), AppAction.SetTrashScreenVisible(false), AppAction.SetCleanerScreenVisible(false)
    )

    @Test
    fun backGoesUpOneFolderAndThenToTheLocations() {
        assertEquals(BackStep.To("$PhoneRoot/DCIM"), back("$PhoneRoot/DCIM/Camera"))
        assertEquals(BackStep.To(PhoneRoot), back("$PhoneRoot/DCIM"))
        assertEquals(BackStep.To("locations"), back(PhoneRoot))
        assertEquals(BackStep.To("locations"), back("/storage"))
        assertEquals(BackStep.To("locations"), back("/"))
    }

    @Test
    fun backFromACategoryGoesToWhereItWasOpenedFrom() {
        assertEquals(BackStep.To("locations"), back("category/images"))
        assertEquals(BackStep.ToCleaner, back("category/images", cleanerWaiting = true))
        // Only a category is gone back from to the Cleaner.
        assertEquals(BackStep.To("$PhoneRoot/DCIM"), back("$PhoneRoot/DCIM/Camera", cleanerWaiting = true))
    }

    @Test
    fun backInACloudAccountIsTheViewModelsToAnswer() {
        listOf("drive", "drive_id:1", "mega", "mega_id:1", "dropbox", "dropbox_id:/a").forEach {
            assertEquals(it, BackStep.UpInCloud, back(it))
        }
    }

    @Test
    fun backFromAnywhereElseGoesHome() {
        listOf("home", "send", "locations", "drive:me@example.com", "smb_1:/a").forEach {
            assertEquals(it, BackStep.To("gallery"), back(it))
        }
    }

    @Test
    fun goingBackToTheCleanerPutsItOverTheLocations() {
        val sent = mutableListOf<AppAction>()
        val trail = PageTrail().apply { cleanerWaiting = true }
        trail.take(BackStep.ToCleaner) { sent += it }
        assertEquals(listOf(AppAction.SetLocation("locations"), AppAction.SetCleanerScreenVisible(true)), sent)
        assertFalse(trail.cleanerWaiting)
    }

    @Test
    fun otherStepsAreOneActionEach() {
        val sent = mutableListOf<AppAction>()
        val trail = PageTrail().apply { cleanerWaiting = true }
        trail.take(BackStep.To("gallery")) { sent += it }
        trail.take(BackStep.UpInCloud) { sent += it }
        assertEquals(listOf(AppAction.SetLocation("gallery"), AppAction.NavigateBackInDrive), sent)
        assertTrue(trail.cleanerWaiting)
    }

    @Test
    fun aTabLeadsToItsPage() {
        assertEquals(TabMove(listOf(AppAction.SetLocation("gallery")), destination = "gallery"), tap(ExpressiveTab.GALLERY, at("home")))
        assertEquals(TabMove(listOf(AppAction.SelectNavTab(NavTab.HOME)), destination = "home"), tap(ExpressiveTab.RECENTS, at("gallery")))
        assertEquals(TabMove(listOf(AppAction.SetLocation("send")), destination = "send"), tap(ExpressiveTab.SEND, at("gallery")))
        assertEquals(TabMove(listOf(AppAction.SetShowSettingsScreen(true))), tap(ExpressiveTab.SETTINGS, at("gallery")))
    }

    @Test
    fun browseGoesBackToWhereItWasAndThenToItsRoot() {
        val camera = "$PhoneRoot/DCIM/Camera"
        val fromHome = tap(ExpressiveTab.BROWSE, at("gallery"), browsePlace = camera)
        assertEquals(TabMove(listOf(AppAction.SetLocation(camera)), destination = camera), fromHome)
        val again = tap(ExpressiveTab.BROWSE, at(camera), browsePlace = camera)
        assertEquals(TabMove(listOf(AppAction.SetLocation("locations")), destination = "locations"), again)
    }

    @Test
    fun goingToAnotherTabClosesWhatCoversBrowse() {
        listOf(ExpressiveTab.GALLERY, ExpressiveTab.RECENTS, ExpressiveTab.SEND).forEach {
            assertEquals(it.name, uncovering, tap(it, covered).actions.dropLast(1))
        }
    }

    @Test
    fun browseTappedAgainClosesWhatCoversIt() {
        assertEquals(uncovering + AppAction.SetLocation("locations"), tap(ExpressiveTab.BROWSE, covered).actions)
    }

    @Test
    fun settingsLeavesWhatCoversBrowseForComingBack() {
        assertEquals(listOf(AppAction.SetShowSettingsScreen(true)), tap(ExpressiveTab.SETTINGS, covered).actions)
        val inSettings = covered.copy(showSettingsScreen = true)
        assertEquals(
            listOf(AppAction.SetShowSettingsScreen(false), AppAction.SetLocation("locations")),
            tap(ExpressiveTab.BROWSE, inSettings, browsePlace = "locations").actions
        )
        // Any other tab closes them along with it.
        assertEquals(
            listOf(AppAction.SetShowSettingsScreen(false)) + uncovering + AppAction.SetLocation("gallery"),
            tap(ExpressiveTab.GALLERY, inSettings).actions
        )
    }

    @Test
    fun aboutClosesForAnotherTabAndForSettingsTappedAgain() {
        val inSettings = at("gallery").copy(showSettingsScreen = true)
        val again = tap(ExpressiveTab.SETTINGS, inSettings, aboutOpen = true)
        assertEquals(TabMove(emptyList(), closesAbout = true), again)
        assertNull(again.destination)
        assertEquals(TabMove(emptyList()), tap(ExpressiveTab.SETTINGS, inSettings))
        val away = tap(ExpressiveTab.GALLERY, inSettings, aboutOpen = true)
        assertTrue(away.closesAbout)
        assertEquals(listOf(AppAction.SetShowSettingsScreen(false), AppAction.SetLocation("gallery")), away.actions)
    }

    @Test
    fun goingToSendTakesTheSelectionInTheOrderItWasPicked() {
        val a = testFile("a.txt", id = 1)
        val b = testFile("b.txt", id = 2)
        val state = at(PhoneRoot).holding(listOf(a, b, testFile("c.txt", id = 3)), selected = listOf(b, a))
        assertEquals(
            listOf(AppAction.NearbyShareAction.StageFiles(listOf(b, a)), AppAction.ClearSelection, AppAction.SetLocation("send")),
            tap(ExpressiveTab.SEND, state).actions
        )
        // Selecting, with nothing selected yet, takes nothing.
        assertEquals(listOf(AppAction.SetLocation("send")), tap(ExpressiveTab.SEND, at(PhoneRoot).copy(isSelectionMode = true)).actions)
    }

    @Test
    fun aSelectionCanBeHadInEitherOrder() {
        val a = testFile("a.txt", id = 1)
        val b = testFile("b.txt", id = 2)
        val gone = testFile("gone.txt", id = 9)
        val state = emptyState().holding(listOf(a, b), selected = listOf(b, gone, a))
        assertEquals(listOf(b, a), state.selectionAsPicked())
        assertEquals(listOf(a, b), state.selectionAsListed())
    }
}
