package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FtpState
import com.kobblo.gallery.file.explorer.MegaAccount
import com.kobblo.gallery.file.explorer.SftpState
import com.kobblo.gallery.file.explorer.SmbState
import com.kobblo.gallery.file.explorer.WebDavState
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.tools.storage.VolumeInfo
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.util.Locale
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectedStoragesTest {
    private val phoneRoot = "/storage/emulated/0"
    private val words = StorageWords(phone = "Internal storage", connected = "Connected", gigabytesFree = { "$it GB free" })
    private val gib = 1024L * 1024 * 1024
    private lateinit var locale: Locale

    @Before
    fun useEnglishNumbers() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
    }

    /** A state as the app has it before anything is connected. The browse location is given so nothing asks Android for it. */
    private fun state() = AppState(lastBrowseLocation = phoneRoot, storageTotalGb = 128f, storageFreeGb = 40.26f)

    private fun volume(id: String, path: String, name: String, usb: Boolean = false, primary: Boolean = false) =
        VolumeInfo(id, name, path, isPrimary = primary, isUsb = usb, isReadOnly = false, totalBytes = 32 * gib, freeBytes = 8 * gib)

    private fun list(state: AppState, volumes: List<VolumeInfo> = emptyList()) = connectedStorages(state, volumes, phoneRoot, words)

    @Test
    fun `the phone is always listed, and first`() {
        val storages = list(state())
        assertEquals(1, storages.size)
        val phone = storages.single()
        assertEquals("internal", phone.id)
        assertEquals("Internal storage", phone.name)
        assertEquals("40.3 GB free", phone.summary)
        assertEquals(phoneRoot, phone.location)
        assertTrue(phone.isOnDevice)
        assertFalse(phone.canDisconnect)
    }

    @Test
    fun `a phone that reports no size still has one to divide by`() {
        val phone = list(AppState(lastBrowseLocation = phoneRoot, storageTotalGb = 0f, storageFreeGb = -1f)).single()
        assertEquals(1f, phone.space!!.totalGb)
        assertEquals(0f, phone.space!!.freeGb)
    }

    @Test
    fun `the first card is sd, and each further one carries its volume's id`() {
        val volumes = listOf(
            volume("primary", phoneRoot, "Phone", primary = true),
            volume("1A2B-3C4D", "/storage/1A2B-3C4D", "SD card"),
            volume("USB1", "/storage/USB1", "Stick", usb = true)
        )
        val storages = list(state(), volumes)
        assertEquals(listOf("internal", "sd", "sd_USB1"), storages.map { it.id })
        assertEquals("/storage/1A2B-3C4D", storages[1].location)
        assertEquals("8.0 GB free", storages[1].summary)
        assertEquals(AppIcons.SdCard, storages[1].icon)
        assertEquals(AppIcons.Usb, storages[2].icon)
        assertEquals(32f, storages[2].space!!.totalGb)
        // They are all opened as "sd": the location says which one.
        assertEquals(listOf("internal", "sd", "sd"), storages.map { it.switchId })
        // Only the first card is spared the offer to disconnect.
        assertEquals(listOf(false, false, true), storages.map { it.canDisconnect })
    }

    @Test
    fun `each cloud account is listed under its address`() {
        val state = state().copy(
            googleDriveAccounts = persistentListOf("a@x.org", "b@x.org"),
            megaAccounts = persistentListOf(MegaAccount("m@x.org", "session"))
        )
        val cloud = list(state).drop(1)
        assertEquals(listOf("drive_a@x.org", "drive_b@x.org", "mega_m@x.org"), cloud.map { it.id })
        assertEquals(listOf("drive:a@x.org", "drive:b@x.org", "mega:m@x.org"), cloud.map { it.location })
        assertEquals(listOf("Google Drive", "Google Drive", "Mega"), cloud.map { it.name })
        assertEquals(listOf("a@x.org", "b@x.org", "m@x.org"), cloud.map { it.summary })
        assertTrue(cloud.none { it.isOnDevice })
        assertTrue(cloud.all { it.canDisconnect })
        assertTrue(cloud.none { it.logsOut })
    }

    @Test
    fun `a service that is only known to be signed in gets one entry under its bare name`() {
        val state = state().copy(
            isGoogleDriveAuthenticated = true,
            googleDriveAccountEmail = "a@x.org",
            isMegaAuthenticated = true,
            isDropboxAuthenticated = true,
            dropboxAccountEmail = "d@x.org"
        )
        val cloud = list(state).drop(1)
        assertEquals(listOf("drive", "mega", "dropbox"), cloud.map { it.id })
        assertEquals(listOf("drive", "mega", "dropbox"), cloud.map { it.location })
        assertEquals(listOf("a@x.org", "Connected", "d@x.org"), cloud.map { it.summary })
        assertTrue(cloud.all { it.logsOut })
    }

    @Test
    fun `nothing is listed for a service nobody is signed in to`() {
        assertEquals(listOf("internal"), list(state().copy(googleDriveAccountEmail = "a@x.org")).map { it.id })
    }

    @Test
    fun `saved servers follow, each opening at its root`() {
        val state = state().copy(
            smbState = SmbState(persistentListOf(SmbConnection("1", "Attic", "nas.local", shareName = "films", username = "me", savedAt = 0))),
            ftpState = FtpState(persistentListOf(FtpConnection(id = "2", displayName = "", host = "ftp.example.org", username = "me", savedAt = 0))),
            sftpState = SftpState(persistentListOf(SftpConnection(id = "3", displayName = "Box", host = "10.0.0.2", username = "me", savedAt = 0))),
            webDavState = WebDavState(
                persistentListOf(
                    WebDavConnection("4", "", "https://dav.example.org/", "me"),
                    WebDavConnection("5", "", "https://cloud.example.org/", "me", isNextcloud = true)
                )
            )
        )
        val servers = list(state).drop(1)
        assertEquals(listOf("smb_1", "ftp_2", "sftp_3", "webdav_4", "nextcloud_5"), servers.map { it.id })
        assertEquals(listOf("smb_1:/", "ftp_2:/", "sftp_3:/", "webdav_4:/", "nextcloud_5:/"), servers.map { it.location })
        // A server with no name of its own goes by its host, or by its kind where the address is a long link.
        assertEquals(listOf("Attic", "ftp.example.org", "Box", "WebDAV", "Nextcloud"), servers.map { it.name })
        assertEquals(
            listOf("SMB · nas.local", "FTP · ftp.example.org", "SFTP · 10.0.0.2", "WebDAV · https://dav.example.org/", "Nextcloud · https://cloud.example.org/"),
            servers.map { it.summary }
        )
        assertTrue(servers.all { it.space == null && it.canDisconnect && !it.logsOut })
    }

    @Test
    fun `how full a volume is`() {
        assertEquals(0.75f, StorageSpace(totalGb = 32f, freeGb = 8f).usedFraction)
        assertEquals(0f, StorageSpace(totalGb = 32f, freeGb = 32f).usedFraction)
        // Too small to measure counts as a tenth of a gigabyte, so nothing divides by zero.
        assertEquals(1f, StorageSpace(totalGb = 0f, freeGb = 0f).usedFraction)
    }

    @Test
    fun `a small volume keeps a decimal in its size and a large one doesn't`() {
        assertEquals("7.5", StorageSpace(totalGb = 7.46f, freeGb = 1f).totalText)
        assertEquals("128", StorageSpace(totalGb = 127.6f, freeGb = 1f).totalText)
        assertEquals("10", StorageSpace(totalGb = 10f, freeGb = 1f).totalText)
        assertEquals("3.3", StorageSpace(totalGb = 64f, freeGb = 3.26f).freeText)
    }

    @Test
    fun `leaving a cloud account signs it out`() {
        assertEquals(
            listOf<AppAction>(AppAction.SetGoogleDriveAuthStatus(false, "b@x.org")),
            disconnectActions("drive_b@x.org", activeDriveAccount = "a@x.org", activeMegaAccount = null)
        )
        assertEquals(
            listOf<AppAction>(AppAction.SetGoogleDriveAuthStatus(false, "a@x.org")),
            disconnectActions("drive", activeDriveAccount = "a@x.org", activeMegaAccount = null)
        )
        assertEquals(
            listOf<AppAction>(AppAction.SetMegaAuthStatus(false, "m@x.org", null)),
            disconnectActions("mega_m@x.org", activeDriveAccount = null, activeMegaAccount = "other@x.org")
        )
        assertEquals(
            listOf<AppAction>(AppAction.SetMegaAuthStatus(false, null, null)),
            disconnectActions("mega", activeDriveAccount = null, activeMegaAccount = null)
        )
        assertEquals(
            listOf<AppAction>(AppAction.SetDropboxAuthStatus(false, null)),
            disconnectActions("dropbox", activeDriveAccount = null, activeMegaAccount = null)
        )
    }

    @Test
    fun `leaving a server forgets it, then disconnects from it`() {
        fun leaving(id: String) = disconnectActions(id, activeDriveAccount = null, activeMegaAccount = null)
        assertEquals(listOf<AppAction>(AppAction.SmbAction.DeleteConnection("1"), AppAction.SmbAction.Disconnect("1")), leaving("smb_1"))
        assertEquals(listOf<AppAction>(AppAction.FtpAction.DeleteConnection("2"), AppAction.FtpAction.Disconnect("2")), leaving("ftp_2"))
        assertEquals(listOf<AppAction>(AppAction.SftpAction.DeleteConnection("3"), AppAction.SftpAction.Disconnect("3")), leaving("sftp_3"))
        assertEquals(listOf<AppAction>(AppAction.WebDavAction.DeleteConnection("4"), AppAction.WebDavAction.Disconnect("4")), leaving("webdav_4"))
        assertEquals(listOf<AppAction>(AppAction.WebDavAction.DeleteConnection("5"), AppAction.WebDavAction.Disconnect("5")), leaving("nextcloud_5"))
    }

    @Test
    fun `there is nothing to send for storage on the phone`() {
        for (id in listOf("internal", "sd", "sd_USB1")) {
            assertTrue(id, disconnectActions(id, activeDriveAccount = "a@x.org", activeMegaAccount = "m@x.org").isEmpty())
        }
    }

    @Test
    fun `a listed storage keeps what it was given`() {
        val storage = list(state().copy(isDropboxAuthenticated = true)).last()
        assertNull(storage.space)
        assertNotNull(storage.icon)
        assertEquals("Dropbox", storage.name)
    }
}
