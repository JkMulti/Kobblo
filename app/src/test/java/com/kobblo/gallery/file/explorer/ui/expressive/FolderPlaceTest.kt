package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPlaceTest {
    private val phoneRoot = "/storage/emulated/0"
    private val cardRoot = "/storage/1A2B-3C4D"

    private fun storage(id: String, location: String, name: String = id, space: StorageSpace? = null) =
        ConnectedStorage(id = id, name = name, summary = "", location = location, icon = AppIcons.Folder, space = space)

    private val phone = storage("internal", phoneRoot, "Internal storage", StorageSpace(100f, 40f))
    private val card = storage("sd", cardRoot, "SD card", StorageSpace(32f, 8f))
    private val secondCard = storage("sd_USB1", "/storage/USB1", "USB drive", StorageSpace(64f, 60f))
    private val driveA = storage("drive_a@x.org", "drive:a@x.org", "Google Drive")
    private val driveB = storage("drive_b@x.org", "drive:b@x.org", "Google Drive")
    private val nas = storage("smb_7", "smb_7:/", "Attic")

    private fun place(
        location: String,
        storageId: String,
        storages: List<ConnectedStorage> = listOf(phone, card, secondCard, driveA, driveB, nas),
        stack: List<Pair<String, String>> = emptyList(),
        folderName: String? = null,
        severalDriveAccounts: Boolean = false,
        knownCardRoot: String? = cardRoot
    ) = folderPlace(
        location = location,
        folderStack = stack,
        folderName = folderName,
        storageId = storageId,
        storages = storages,
        phoneRoot = phoneRoot,
        cardRoot = knownCardRoot,
        severalDriveAccounts = severalDriveAccounts,
        severalMegaAccounts = false,
        unnamedStorage = "Storage"
    )

    @Test
    fun `tabs and categories are not folders`() {
        for (location in listOf("locations", "home", "gallery", "send", "category/images")) {
            assertFalse(location, isFolderLocation(location))
        }
        for (location in listOf(phoneRoot, "$phoneRoot/DCIM", "drive", "drive_id:abc", "smb_7:/", "sd")) {
            assertTrue(location, isFolderLocation(location))
        }
    }

    @Test
    fun `depth follows the path on the phone and the opened folders elsewhere`() {
        assertEquals(3, pageDepth("/storage/emulated/0", foldersOpened = 9))
        assertEquals(5, pageDepth("/storage/emulated/0/DCIM/Camera", foldersOpened = 0))
        assertEquals(2, pageDepth("drive_id:abc", foldersOpened = 2))
        assertEquals(0, pageDepth("drive", foldersOpened = 0))
    }

    @Test
    fun `equal ids are the same storage`() {
        assertTrue(sameStorage("internal", "internal"))
        assertTrue(sameStorage("smb_7", "smb_7"))
        assertFalse(sameStorage("smb_7", "smb_8"))
        assertFalse(sameStorage("dropbox", "drive"))
    }

    @Test
    fun `an account is matched whatever its letter case, and a bare id matches any account`() {
        assertTrue(sameStorage("drive_A@x.org", "drive_a@X.ORG"))
        assertFalse(sameStorage("drive_a@x.org", "drive_b@x.org"))
        assertTrue(sameStorage("drive_a@x.org", "drive"))
        assertTrue(sameStorage("drive", "drive_b@x.org"))
        assertTrue(sameStorage("mega_a@x.org", "mega"))
        assertFalse(sameStorage("mega_a@x.org", "mega_b@x.org"))
        assertFalse(sameStorage("mega_a@x.org", "drive_a@x.org"))
    }

    @Test
    fun `a card is found by the path it holds, not by its shared id`() {
        val storages = listOf(phone, card, secondCard)
        assertSame(secondCard, storageHolding("/storage/USB1/Films", "sd", storages))
        assertSame(secondCard, storageHolding("/storage/USB1", "sd", storages))
        assertSame(card, storageHolding("$cardRoot/DCIM", "sd", storages))
        // A path that only begins like the card's is somewhere else.
        assertSame(phone, storageHolding("/storage/USB10/x", "internal", storages))
    }

    @Test
    fun `other storages are found by id, and the phone stands in when none is`() {
        val storages = listOf(phone, driveA, driveB, nas)
        assertSame(driveB, storageHolding("drive_id:abc", "drive_B@x.org", storages))
        assertSame(nas, storageHolding("smb_7:/films", "smb_7", storages))
        assertSame(phone, storageHolding("ftp_1:/", "ftp_1", storages))
        assertNull(storageHolding("anything", "internal", emptyList()))
    }

    @Test
    fun `the phone's root is named for its storage and leads back to Browse`() {
        val root = place(phoneRoot, "internal")
        assertEquals(0, root.depth)
        assertEquals("Internal storage", root.title)
        assertNull(root.backTitle)
        assertNull(root.parentPath)
        assertEquals(phoneRoot, root.root)
        assertTrue(root.isOnDevice)
    }

    @Test
    fun `a folder on the phone is named for itself and the way back for what is above it`() {
        val first = place("$phoneRoot/DCIM", "internal")
        assertEquals(1, first.depth)
        assertEquals("DCIM", first.title)
        assertEquals("Internal storage", first.backTitle)
        assertEquals(phoneRoot, first.parentPath)

        val second = place("$phoneRoot/DCIM/Camera", "internal")
        assertEquals(2, second.depth)
        assertEquals("Camera", second.title)
        assertEquals("DCIM", second.backTitle)
        assertEquals("$phoneRoot/DCIM", second.parentPath)
    }

    @Test
    fun `a card's folders are counted from the card's own root`() {
        val folder = place("$cardRoot/Music/Live", "sd")
        assertEquals(cardRoot, folder.root)
        assertEquals(2, folder.depth)
        assertEquals("SD card", folder.storageName)
        assertEquals("Music", folder.backTitle)
        assertTrue(folder.isOnDevice)
    }

    @Test
    fun `a card whose root isn't known falls back to its bare name`() {
        val folder = place("sd", "sd", knownCardRoot = null)
        assertEquals("sd", folder.root)
        assertEquals(0, folder.depth)
    }

    @Test
    fun `a storage without paths counts the folders that were opened`() {
        val root = place("drive:a@x.org", "drive_a@x.org")
        assertEquals(0, root.depth)
        assertEquals("Google Drive", root.title)
        assertEquals("drive:a@x.org", root.root)
        assertFalse(root.isOnDevice)

        val first = place("drive_id:1", "drive_a@x.org", folderName = "Work")
        assertEquals(1, first.depth)
        assertEquals("Work", first.title)
        assertEquals("Google Drive", first.backTitle)
        assertNull(first.parentPath)

        val second = place("drive_id:2", "drive_a@x.org", stack = listOf("drive_id:1" to "Work"), folderName = "2024")
        assertEquals(2, second.depth)
        assertEquals("2024", second.title)
        assertEquals("Work", second.backTitle)
    }

    @Test
    fun `with several accounts a cloud storage goes by its address`() {
        val one = place("drive:b@x.org", "drive_b@x.org", severalDriveAccounts = false)
        assertEquals("Google Drive", one.storageName)
        val several = place("drive:b@x.org", "drive_b@x.org", severalDriveAccounts = true)
        assertEquals("b@x.org", several.storageName)
        assertEquals("b@x.org", several.title)
    }

    @Test
    fun `with no storage listed the place still has a name`() {
        val nowhere = place("ftp_1:/", "ftp_1", storages = emptyList())
        assertEquals("Storage", nowhere.storageName)
        assertEquals(phoneRoot, nowhere.root)
    }

    @Test
    fun `free space is the phone's or the card's own, and unknown anywhere else`() {
        assertEquals(12.5f, place(phoneRoot, "internal").freeGb(phoneFreeGb = 12.5f, cardFreeGb = 1f))
        assertEquals(8f, place("$cardRoot/a", "sd").freeGb(phoneFreeGb = 12.5f, cardFreeGb = 1f))
        assertEquals(60f, place("/storage/USB1/a", "sd").freeGb(phoneFreeGb = 12.5f, cardFreeGb = 1f))
        assertEquals(1f, place("$cardRoot/a", "sd", storages = emptyList()).freeGb(phoneFreeGb = 12.5f, cardFreeGb = 1f))
        assertNull(place("smb_7:/", "smb_7").freeGb(phoneFreeGb = 12.5f, cardFreeGb = 1f))
    }

    private fun exit(location: String, storageId: String, noFoldersOpened: Boolean = true, knownCardRoot: String? = cardRoot) =
        folderExit(location, storageId, noFoldersOpened, phoneRoot, knownCardRoot)

    @Test
    fun `Back from the top of a storage leaves for the list of locations`() {
        val out = FolderExit.To("locations")
        assertEquals(out, exit(phoneRoot, "internal"))
        assertEquals(out, exit("home", "internal"))
        assertEquals(out, exit(cardRoot, "sd"))
        assertEquals(out, exit("sd", "sd", knownCardRoot = null))
        assertEquals(out, exit("drive", "drive_a@x.org", noFoldersOpened = false))
        assertEquals(out, exit("drive:a@x.org", "drive_a@x.org", noFoldersOpened = false))
        assertEquals(out, exit("mega:a@x.org", "mega_a@x.org", noFoldersOpened = false))
        assertEquals(out, exit("dropbox", "dropbox", noFoldersOpened = false))
        assertEquals(out, exit("smb_7:/", "smb_7", noFoldersOpened = false))
        assertEquals(out, exit("drive_id:9", "drive", noFoldersOpened = true))
    }

    @Test
    fun `Back from a path goes to the folder above`() {
        assertEquals(FolderExit.To("$phoneRoot/DCIM"), exit("$phoneRoot/DCIM/Camera", "internal"))
        assertEquals(FolderExit.To(phoneRoot), exit("$phoneRoot/DCIM", "internal"))
        assertEquals(FolderExit.To("$cardRoot/Music"), exit("$cardRoot/Music/Live", "sd"))
    }

    @Test
    fun `Back never goes above the storage's root`() {
        // A path shorter than the phone's root can only be above it.
        assertEquals(FolderExit.To(phoneRoot), exit("/storage/x", "internal"))
        // With no card root known, the phone's is the floor.
        assertEquals(FolderExit.To(phoneRoot), exit("/mnt/a", "sd", knownCardRoot = null))
    }

    @Test
    fun `Back inside a storage without paths steps back through what was opened`() {
        assertEquals(FolderExit.StepBack, exit("drive_id:9", "drive_a@x.org", noFoldersOpened = false))
        assertEquals(FolderExit.StepBack, exit("smb_7:/films", "smb_7", noFoldersOpened = false))
        assertEquals(FolderExit.StepBack, exit("dropbox_id:3", "dropbox", noFoldersOpened = false))
    }
}
