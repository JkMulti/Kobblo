package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.getStorageIdForLocation
import com.kobblo.gallery.file.explorer.isCloudPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationsTest {
    private val serverPlaces = listOf("smb_1:", "ftp_1:/", "sftp_a-b:/home", "webdav_9:/x", "nextcloud_9:/x")
    private val cloudFolders = listOf("drive_id:abc", "mega_id:12", "dropbox_id:/x")
    private val cloudTops = listOf("drive", "drive:me@x.org", "mega", "mega:me@x.org", "dropbox")
    private val elsewhere =
        listOf("/storage/emulated/0", "home", "gallery", "locations", "send", "recent", "pinned", "category/Large", "sd", "")

    @Test
    fun `a cloud path is a file or folder in a service or anything on a server, but no service's top`() {
        (serverPlaces + cloudFolders).forEach { assertTrue(it, isCloudPath(it)) }
        (cloudTops + elsewhere).forEach { assertFalse(it, isCloudPath(it)) }
    }

    @Test
    fun `being in the cloud takes in the tops as well`() {
        (serverPlaces + cloudFolders + cloudTops).forEach { assertTrue(it, Places.isCloud(it)) }
        elsewhere.forEach { assertFalse(it, Places.isCloud(it)) }
    }

    @Test
    fun `the way back begins again at the top of a service or a server`() {
        (cloudTops + listOf("smb_1:/", "ftp_1:/", "sftp_1:/", "webdav_1:/", "nextcloud_1:/")).forEach { assertTrue(it, Places.isCloudTop(it)) }
        (cloudFolders + listOf("smb_1:Photos", "ftp_1:/x", "dropbox:x", "/a:/") + elsewhere).forEach { assertFalse(it, Places.isCloudTop(it)) }
    }

    @Test
    fun `only the phone itself is searched through the disk`() {
        listOf("/storage/emulated/0/Music", "home", "gallery", "category/Images", "sd").forEach { assertTrue(it, Places.searchesPhone(it)) }
        (serverPlaces + cloudFolders + cloudTops + listOf("recent", "pinned")).forEach { assertFalse(it, Places.searchesPhone(it)) }
    }

    @Test
    fun `navigation keeps its place in storages and not in tabs`() {
        (serverPlaces + cloudFolders + cloudTops + listOf("/x", "sd", "dropbox:x")).forEach { assertTrue(it, Places.isStorage(it)) }
        listOf("home", "gallery", "locations", "send", "recent", "pinned", "category/Large", "sd:").forEach { assertFalse(it, Places.isStorage(it)) }
    }

    @Test
    fun `each server scheme has its kind, and ftp is not sftp`() {
        assertEquals(
            listOf(ServerKind.Smb, ServerKind.Ftp, ServerKind.Sftp, ServerKind.WebDav, ServerKind.WebDav),
            serverPlaces.map(ServerKind::of)
        )
        (cloudFolders + cloudTops + elsewhere).forEach { assertNull(it, ServerKind.of(it)) }
    }

    @Test
    fun `a connection's id is what comes between its scheme and the colon`() {
        assertEquals("41c7", ServerKind.connectionIn("sftp_41c7:/home/sam"))
        assertEquals("a_b", ServerKind.connectionIn("nextcloud_a_b:/x:y"))
        assertEquals("7", ServerKind.connectionIn("smb_7:Photos\\2024"))
        assertEquals("webdav_7:/", ServerKind.topOf("webdav", "7"))
    }

    @Test
    fun `a service's account is named after the colon`() {
        assertEquals("me@x.org", CloudService.Drive.accountIn("drive:me@x.org"))
        assertEquals("me@x.org", CloudService.Mega.accountIn("mega:me@x.org/folder"))
        assertEquals("", CloudService.Drive.accountIn("drive:"))
        assertNull(CloudService.Drive.accountIn("drive"))
        assertNull(CloudService.Drive.accountIn("drive_id:abc"))
        assertEquals("abc", CloudService.Drive.idIn("drive_id:abc"))
        assertEquals("me@x.org", CloudService.Mega.accountOfStorage("mega_me@x.org"))
        assertNull(CloudService.Mega.accountOfStorage("drive_me@x.org"))
        assertNull(CloudService.Mega.accountOfStorage("mega"))
    }

    @Test
    fun `the app's own screens count as the phone`() {
        listOf("home", "gallery", "locations", "send", "cleaner", "trash", "settings", "about", "recent", "category/Large", "")
            .forEach { assertEquals(it, "internal", getStorageIdForLocation(it)) }
        assertEquals("internal", getStorageIdForLocation("/storage/emulated/0/DCIM", "/storage/1A2B-3C4D"))
    }

    @Test
    fun `the card is known by its shorthand, its path and where cards are mounted`() {
        listOf("sd", "sd:", "sd/", "/storage/1A2B-3C4D", "/storage/1A2B-3C4D/DCIM", "/storage/OTHER")
            .forEach { assertEquals(it, "sd", getStorageIdForLocation(it, "/storage/1A2B-3C4D")) }
    }

    @Test
    fun `a service's id carries the account its location names, or else the one in use`() {
        fun id(location: String) = getStorageIdForLocation(location, null, "d@x.org", "m@x.org")
        assertEquals("drive_d@x.org", id("drive"))
        assertEquals("drive_d@x.org", id("drive_id:abc"))
        assertEquals("drive_d@x.org", id("drive:"))
        assertEquals("drive_other@x.org", id("drive:other@x.org/x"))
        assertEquals("mega_m@x.org", id("mega"))
        assertEquals("mega_m@x.org", id("mega_id:1"))
        assertEquals("mega_other@x.org", id("mega:other@x.org"))
        assertEquals("drive", getStorageIdForLocation("drive"))
        assertEquals("mega", getStorageIdForLocation("mega_id:1", null, "d@x.org", ""))
    }

    @Test
    fun `dropbox is one account and a server is its connection`() {
        listOf("dropbox", "dropbox_id:/x", "dropbox:x").forEach { assertEquals(it, "dropbox", getStorageIdForLocation(it)) }
        assertEquals("smb_1", getStorageIdForLocation("smb_1:Photos"))
        assertEquals("sftp_a-b", getStorageIdForLocation("sftp_a-b:/home"))
        assertEquals("nextcloud_9", getStorageIdForLocation("nextcloud_9:/x:y"))
    }

    @Test
    fun `categories are read out of their locations`() {
        assertEquals("Large", Places.categoryIn("category/Large"))
        assertNull(Places.categoryIn("/category/Large"))
    }
}
