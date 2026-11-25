package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.StorageNavState
import com.kobblo.gallery.file.explorer.explorer.ResolvedFolderView
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTest {
    private val start = appState().copy(location = "$PhoneRoot/Music", sdCardPath = "/storage/CARD")

    private fun AppState.goTo(
        location: String,
        name: String? = null,
        card: String? = null,
        look: ResolvedFolderView? = null,
        listElsewhere: Boolean = false,
        carriesSelection: Boolean = false
    ): AppState = Arrival(location, name, card, look, listElsewhere, carriesSelection, unnamedCloud = "Cloud").from(this)

    @Test
    fun `a folder that was shown before shows at once, and any other keeps the last list up while it's read`() {
        val old = persistentListOf(fileAt("$PhoneRoot/Music/a.mp3"))
        val known = persistentListOf(fileAt("$PhoneRoot/DCIM/a.jpg"))
        val cache = persistentMapOf("$PhoneRoot/DCIM" to known, "$PhoneRoot/Empty" to persistentListOf<FileItem>())
        val state = start.copy(files = old, folderCache = cache)
        val back = state.goTo("$PhoneRoot/DCIM")
        assertSame(known, back.files)
        assertFalse(back.isLoading)
        val new = state.goTo("$PhoneRoot/Download")
        assertSame(old, new.files)
        assertTrue(new.isLoading)
        // One that was empty is read as if it were new, so a file that has arrived isn't hidden by it.
        val emptied = state.goTo("$PhoneRoot/Empty")
        assertSame(old, emptied.files)
        assertTrue(emptied.isLoading)
    }

    @Test
    fun `a new place starts with no search and nothing said`() {
        val state = start.copy(query = "x", errorMessage = "!", currentFolderName = "Old").goTo("drive", "Drive")
        assertEquals("", state.query)
        assertNull(state.errorMessage)
        assertEquals("Drive", state.currentFolderName)
        assertNull(state.goTo("$PhoneRoot/DCIM").currentFolderName)
    }

    @Test
    fun `the filter is dropped on the phone, at Recents and at Home, and kept elsewhere`() {
        val state = start.copy(filter = "image")
        listOf("$PhoneRoot/DCIM", "home", "gallery").forEach { assertEquals(it, "all", state.goTo(it).filter) }
        listOf("drive", "smb_1:/", "category/Large", "recent", "locations").forEach { assertEquals(it, "image", state.goTo(it).filter) }
    }

    @Test
    fun `only folders on the phone become the folder Browse offers again`() {
        assertEquals("$PhoneRoot/DCIM", start.goTo("$PhoneRoot/DCIM").lastBrowseLocation)
        listOf("home", "gallery", "drive", "smb_1:/", "locations").forEach { assertEquals(it, PhoneRoot, start.goTo(it).lastBrowseLocation) }
    }

    @Test
    fun `a selection stays only in the folder it was made in, or with a drag`() {
        val state = start.copy(selectedFiles = persistentSetOf(1, 2), isSelectionMode = true)
        val left = state.goTo("$PhoneRoot/DCIM")
        assertTrue(left.selectedFiles.isEmpty())
        assertFalse(left.isSelectionMode)
        val again = state.goTo(state.location)
        assertEquals(setOf(1, 2), again.selectedFiles)
        assertTrue(again.isSelectionMode)
        assertEquals(setOf(1, 2), state.goTo("$PhoneRoot/DCIM", carriesSelection = true).selectedFiles)
    }

    @Test
    fun `a folder looks how it remembers, and elsewhere keeps the sort with the one choice of layout`() {
        val state = start.copy(sortMode = SortMode.SIZE, isListMode = true)
        val remembered = ResolvedFolderView(SortMode.DATE, reversed = false, list = false)
        val folder = state.goTo("$PhoneRoot/DCIM", look = remembered, listElsewhere = true)
        assertEquals(SortMode.DATE, folder.sortMode)
        assertFalse(folder.isListMode)
        val cloud = state.goTo("drive", listElsewhere = false)
        assertEquals(SortMode.SIZE, cloud.sortMode)
        assertFalse(cloud.isListMode)
        assertTrue(state.goTo("drive", listElsewhere = true).isListMode)
    }

    @Test
    fun `going into folders in the cloud keeps the way back, under their names`() {
        val top = start.goTo("drive:me@x.org", "Drive")
        assertTrue(top.driveFolderStack.isEmpty())
        val photos = top.goTo("drive_id:1", "Photos")
        assertEquals(listOf("drive:me@x.org" to "Drive"), photos.driveFolderStack)
        val trip = photos.goTo("drive_id:2", "Trip")
        assertEquals(listOf("drive:me@x.org" to "Drive", "drive_id:1" to "Photos"), trip.driveFolderStack)
        // The top again, and the way back starts over.
        assertTrue(trip.goTo("drive:me@x.org").driveFolderStack.isEmpty())
    }

    @Test
    fun `a folder without a name is called after the cloud on the way back`() {
        assertEquals(listOf("mega" to "Cloud"), start.goTo("mega").goTo("mega_id:7", "x").driveFolderStack)
    }

    @Test
    fun `a server's folders are gone into from its top, and reaching its top from the phone starts over`() {
        val top = start.copy(driveFolderStack = listOf("old" to "Old")).goTo("smb_1:/", "Attic")
        assertTrue(top.driveFolderStack.isEmpty())
        val folder = top.goTo("smb_1:Films", "Films")
        assertEquals(listOf("smb_1:/" to "Attic"), folder.driveFolderStack)
    }

    @Test
    fun `the way back is left alone by tabs and by the phone`() {
        val state = start.copy(driveFolderStack = listOf("a" to "A"))
        listOf("home", "$PhoneRoot/DCIM", "locations", "category/Large").forEach {
            assertEquals(it, state.driveFolderStack, state.goTo(it).driveFolderStack)
        }
    }

    @Test
    fun `a storage remembers where it was left, and keeps how it was arranged`() {
        val state = start.copy(
            googleDriveAccountEmail = "me@x.org",
            sortMode = SortMode.SIZE,
            filter = "image",
            storageStates = persistentMapOf("internal" to StorageNavState("$PhoneRoot/Old", SortMode.DATE, "video"))
        )
        assertEquals(
            StorageNavState("$PhoneRoot/DCIM", SortMode.DATE, "video"),
            state.goTo("$PhoneRoot/DCIM").storageStates["internal"]
        )
        assertEquals(
            StorageNavState("drive_id:1", SortMode.SIZE, "image", emptyList(), "Photos"),
            state.goTo("drive_id:1", "Photos").storageStates["drive_me@x.org"]
        )
        assertEquals("/storage/CARD/DCIM", state.goTo("/storage/CARD/DCIM").storageStates["sd"]?.lastLocation)
        listOf("home", "gallery", "locations", "send", "recent", "category/Large").forEach {
            assertSame(it, state.storageStates, state.goTo(it).storageStates)
        }
    }

    @Test
    fun `a card that has just been found is kept, and one that was known isn't replaced`() {
        assertEquals("/storage/NEW", start.copy(sdCardPath = null).goTo("home", card = "/storage/NEW").sdCardPath)
        assertEquals("/storage/CARD", start.goTo("home", card = "/storage/NEW").sdCardPath)
        assertNull(start.copy(sdCardPath = null).goTo("home").sdCardPath)
    }

    @Test
    fun `the first cloud that's signed in to is the one to go to`() {
        assertNull(appState().signedInCloud())
        val dropbox = appState().copy(isDropboxAuthenticated = true)
        val mega = dropbox.copy(isMegaAuthenticated = true)
        val drive = mega.copy(isGoogleDriveAuthenticated = true)
        assertEquals(CloudService.Dropbox, dropbox.signedInCloud())
        assertEquals(CloudService.Mega, mega.signedInCloud())
        assertEquals(CloudService.Drive, drive.signedInCloud())
    }
}
