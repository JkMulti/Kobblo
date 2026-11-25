package com.kobblo.gallery.file.explorer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    @Test
    fun `the top of Drive can name an account`() {
        assertEquals(DriveLocation(account = null, folderId = null), DriveLocation.of("drive"))
        assertEquals(DriveLocation(account = "a@b.com", folderId = null), DriveLocation.of("drive:a@b.com"))
        assertEquals(DriveLocation(account = "a@b.com", folderId = null), DriveLocation.of("drive:a@b.com/more"))
        assertEquals("root", DriveLocation.of("drive:a@b.com")?.parentId)
        assertEquals(true, DriveLocation.of("drive")?.isTop)
    }

    @Test
    fun `a folder on Drive goes by its id`() {
        val folder = DriveLocation.of("drive_id:1AbC")
        assertEquals(DriveLocation(account = null, folderId = "1AbC"), folder)
        assertEquals("1AbC", folder?.parentId)
        assertEquals(false, folder?.isTop)
        assertEquals("1AbC", DriveLocation.idIn("drive_id:1AbC"))
        assertEquals("drive_id:1AbC", DriveLocation.pathOf("1AbC"))
    }

    @Test
    fun `nothing else is on Drive`() {
        for (location in listOf("", "drives", "/storage/emulated/0/drive", "mega", "home", "recent", "sd:", "ftp_c1:/drive")) {
            assertNull(location, DriveLocation.of(location))
            assertNull(location, DriveLocation.idIn(location))
        }
        assertNull("The top of Drive has no id", DriveLocation.idIn("drive:a@b.com"))
    }

    @Test
    fun `the card has more spellings to open it by than to make something in it`() {
        for (word in listOf("sd", "sd/")) {
            assertTrue(Places.isCard(word))
            assertTrue(Places.isOpenableCard(word))
        }
        assertFalse(Places.isCard("sd:"))
        assertTrue(Places.isOpenableCard("sd:"))
        assertFalse(Places.isOpenableCard("/storage/1A2B-3C4D"))
    }

    @Test
    fun `other apps' folders are restricted wherever they are`() {
        assertTrue(isRestrictedPath("/storage/emulated/0/Android/data"))
        assertTrue(isRestrictedPath("/storage/emulated/0/Android/data/com.app/files"))
        assertTrue(isRestrictedPath("/storage/1A2B-3C4D/Android/obb/com.app"))
        assertFalse(isRestrictedPath("/storage/emulated/0/Android"))
        assertFalse(isRestrictedPath("/storage/emulated/0/Android/media"))
        assertFalse(isRestrictedPath("/storage/emulated/0/Download"))
    }
}
