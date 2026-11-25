package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPageTest {
    private val tabs = listOf(RootPage.Gallery, RootPage.Recents, RootPage.Locations, RootPage.Folder, RootPage.Send, RootPage.Settings)
    private val covers = listOf(RootPage.Cleaner, RootPage.Trash, RootPage.About, RootPage.Vault)
    private val images = RootPage.Category("images")

    private fun at(location: String) = emptyState().copy(location = location)

    private fun page(state: AppState, aboutOpen: Boolean = false) = state.page(aboutOpen)

    @Test
    fun eachLocationHasItsPage() {
        assertEquals(RootPage.Gallery, page(at("gallery")))
        assertEquals(RootPage.Recents, page(at("home")))
        assertEquals(RootPage.Locations, page(at("locations")))
        assertEquals(RootPage.Send, page(at("send")))
        assertEquals(images, page(at("category/images")))
        val folders = listOf("$PhoneRoot/Download", "drive", "mega_id:1", "smb_1:/", "dropbox")
        folders.forEach { assertEquals(it, RootPage.Folder, page(at(it))) }
    }

    @Test
    fun coveringScreensComeBeforeTheLocationInAnOrder() {
        val everything = at("gallery").copy(
            showSettingsScreen = true, showTrashScreen = true, showCleanerScreen = true, showVaultScreen = true
        )
        assertEquals(RootPage.About, page(everything, aboutOpen = true))
        assertEquals(RootPage.Settings, page(everything))
        assertEquals(RootPage.Trash, page(everything.copy(showSettingsScreen = false)))
        assertEquals(RootPage.Cleaner, page(everything.copy(showSettingsScreen = false, showTrashScreen = false)))
        assertEquals(RootPage.Vault, page(at("home").copy(showVaultScreen = true)))
    }

    @Test
    fun idsStayWhatPagesWereSavedUnder() {
        assertEquals(listOf("gallery", "home", "locations", "browse", "send", "settings"), tabs.map { it.id })
        assertEquals(listOf("cleaner", "trash", "about", "vault"), covers.map { it.id })
        assertEquals("category/images", images.id)
    }

    @Test
    fun tabsAndAboutKeepTheTabBar() {
        (tabs + RootPage.About).forEach { assertTrue(it.id, it.keepsTabBar) }
        listOf(RootPage.Cleaner, RootPage.Trash, RootPage.Vault, images).forEach { assertFalse(it.id, it.keepsTabBar) }
    }

    @Test
    fun settingsAndWhatCoversATabSayWhatBackDoes() {
        (covers + RootPage.Settings).forEach { assertTrue(it.id, it.ownsBack) }
        (tabs - RootPage.Settings + images).forEach { assertFalse(it.id, it.ownsBack) }
    }

    @Test
    fun tabsMoveAlongTheBar() {
        val order = listOf("gallery" to 0, "home" to 1, "locations" to 2, "browse" to 2, "send" to 3, "settings" to 4).toMap()
        for (from in tabs) for (to in tabs) {
            val (start, end) = order.getValue(from.id) to order.getValue(to.id)
            if (start == end) continue
            val expected = if (end > start) PageMove.TabAfter else PageMove.TabBefore
            assertEquals("${from.id} to ${to.id}", expected, pageMove(from, to))
        }
    }

    @Test
    fun aFolderLiesBelowTheListOfLocations() {
        assertEquals(PageMove.Deeper, pageMove(RootPage.Locations, RootPage.Folder))
        assertEquals(PageMove.Back, pageMove(RootPage.Folder, RootPage.Locations))
    }

    @Test
    fun openingOverATabGoesDeeperAndClosingComesBack() {
        tabs.forEach { tab ->
            covers.forEach { cover ->
                assertEquals("${tab.id} to ${cover.id}", PageMove.Deeper, pageMove(tab, cover))
                assertEquals("${cover.id} to ${tab.id}", PageMove.Back, pageMove(cover, tab))
            }
            assertEquals(PageMove.Deeper, pageMove(tab, images))
            assertEquals(PageMove.Back, pageMove(images, tab))
        }
    }

    @Test
    fun aCategoryLiesBelowTheCleanerItOpensFrom() {
        covers.forEach {
            assertEquals(PageMove.Deeper, pageMove(it, images))
            assertEquals(PageMove.Back, pageMove(images, it))
        }
    }

    @Test
    fun coversGoBetweenThemselvesInOrder() {
        covers.forEachIndexed { from, a ->
            covers.forEachIndexed { to, b ->
                assertEquals("${a.id} to ${b.id}", if (to >= from) PageMove.Deeper else PageMove.Back, pageMove(a, b))
            }
        }
    }

    @Test
    fun aCategoryWithinAnotherHasTheLongerName() {
        assertEquals(PageMove.Deeper, pageMove(images, RootPage.Category("images_screenshots")))
        assertEquals(PageMove.Back, pageMove(RootPage.Category("images_screenshots"), images))
        assertEquals(PageMove.Back, pageMove(images, RootPage.Category("videos")))
    }

    @Test
    fun stayingPutIsNotGoingDeeper() {
        (tabs + images).forEach { assertEquals(it.id, PageMove.Back, pageMove(it, it)) }
    }

    @Test
    fun everyPageBelongsToATab() {
        assertEquals(ExpressiveTab.GALLERY, tabOf(at("gallery"), aboutOpen = false))
        assertEquals(ExpressiveTab.RECENTS, tabOf(at("home"), aboutOpen = false))
        assertEquals(ExpressiveTab.SEND, tabOf(at("send"), aboutOpen = false))
        listOf("locations", PhoneRoot, "category/images", "drive").forEach { assertEquals(it, ExpressiveTab.BROWSE, tabOf(at(it), false)) }
        assertEquals(ExpressiveTab.SETTINGS, tabOf(at("gallery").copy(showSettingsScreen = true), aboutOpen = false))
        assertEquals(ExpressiveTab.SETTINGS, tabOf(at("gallery"), aboutOpen = true))
        // What covers Browse doesn't change which tab it's on.
        assertEquals(ExpressiveTab.BROWSE, tabOf(at("locations").copy(showVaultScreen = true), aboutOpen = false))
    }

    @Test
    fun browseReturnsToWhereItWasOnThePhone() {
        assertEquals("locations", browsePlaceAfter("locations", before = "$PhoneRoot/DCIM"))
        assertEquals("$PhoneRoot/DCIM", browsePlaceAfter("$PhoneRoot/DCIM", before = "locations"))
        assertEquals("category/images", browsePlaceAfter("category/images", before = "locations"))
        val before = "$PhoneRoot/DCIM"
        listOf("gallery", "home", "send").forEach { assertEquals(it, before, browsePlaceAfter(it, before)) }
        listOf("drive_id:1", "mega", "smb_1:/Photos").forEach { assertEquals(it, "locations", browsePlaceAfter(it, before)) }
    }

    @Test
    fun theFootHasABarOnPagesThatKeepTheTabsOrWithFilesInHand() {
        val idle = at("gallery")
        assertTrue(showsFootBar(RootPage.Gallery, idle))
        assertFalse(showsFootBar(images, idle))
        assertFalse(showsFootBar(RootPage.Vault, idle))
        assertTrue(showsFootBar(images, idle.copy(isSelectionMode = true)))
        assertTrue(showsFootBar(RootPage.Trash, idle.copy(clipboardPaths = kotlinx.collections.immutable.persistentListOf("/a"))))
    }
}
