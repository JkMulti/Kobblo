package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.sftp.SftpAuthType
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Domain
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Host
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Name
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Password
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Port
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Server
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Share
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Username
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFormsTest {
    private fun <C : Any> ConnectionForm<C>.filled(vararg typed: Pair<ConnectionField, String>, switchOn: Boolean = false): ConnectionEntries =
        typed.fold(blank()) { entries, (field, text) -> entries.with(field, text) }.copy(switchOn = switchOn)

    private fun <C : Any> ConnectionForm<C>.made(entries: ConnectionEntries): C? = connection(entries, id = "id-1", savedAt = 1_000L)

    // ── What each form asks for ──────────────────────────────────────────────────

    @Test
    fun eachFormAsksForItsFieldsInOrder() {
        assertEquals(listOf(Name, Host, Port, Username, Password), FtpForm.fields)
        assertEquals(listOf(Name, Host, Port, Username, Password), SftpForm.fields)
        assertEquals(listOf(Name, Host, Port, Share, Domain, Username, Password), SmbForm.fields)
        assertEquals(listOf(Name, Server, Username, Password), WebDavForm.fields)
        assertEquals(listOf(Server, Username, Password), NextcloudForm.fields)
    }

    @Test
    fun aFormOnlyRequiresFieldsItHas() {
        for (form in listOf(FtpForm, SftpForm, SmbForm, WebDavForm, NextcloudForm)) {
            assertTrue(form.fields.containsAll(form.required))
            assertEquals("no field is asked twice", form.fields.size, form.fields.toSet().size)
        }
    }

    @Test
    fun aNewFormIsEmptyButForTheUsualPort() {
        assertEquals("21", FtpForm.blank()[Port])
        assertEquals("22", SftpForm.blank()[Port])
        assertEquals("445", SmbForm.blank()[Port])
        assertEquals("", WebDavForm.blank()[Port])
        for (form in listOf(FtpForm, SftpForm, SmbForm, WebDavForm, NextcloudForm)) {
            val blank = form.blank()
            assertFalse(blank.switchOn)
            for (field in ConnectionField.entries - Port) assertEquals("", blank[field])
        }
    }

    @Test
    fun onlyFtpAndWebDavEndWithASwitch() {
        assertEquals(R.string.use_ftps_label, FtpForm.switchLabel)
        assertEquals(R.string.polish_allow_self_signed, WebDavForm.switchLabel)
        assertNull(SftpForm.switchLabel)
        assertNull(SmbForm.switchLabel)
        assertNull(NextcloudForm.switchLabel)
    }

    @Test
    fun serversSavedButUrlServersConnected() {
        assertEquals(R.string.save_action, FtpForm.confirm)
        assertEquals(R.string.save_action, SftpForm.confirm)
        assertEquals(R.string.save_action, SmbForm.confirm)
        assertEquals(R.string.connect_action, WebDavForm.confirm)
        assertEquals(R.string.connect_action, NextcloudForm.confirm)
    }

    @Test
    fun eachFormSaysWhatIsMissingInItsOwnWords() {
        assertEquals(R.string.polish_server_missing, FtpForm.incomplete)
        assertEquals(R.string.polish_server_missing, SftpForm.incomplete)
        assertEquals(R.string.polish_smb_missing, SmbForm.incomplete)
        assertEquals(R.string.polish_all_required, WebDavForm.incomplete)
        assertEquals(R.string.polish_all_required, NextcloudForm.incomplete)
    }

    @Test
    fun urlFormsShowAnAddressOfTheRightShape() {
        assertEquals("https://example.com/dav/", WebDavForm.serverExample)
        assertEquals("cloud.example.com", NextcloudForm.serverExample)
        assertEquals(R.string.polish_nextcloud_tip, NextcloudForm.footnote)
        assertNull(WebDavForm.footnote)
    }

    // ── What's been typed ────────────────────────────────────────────────────────

    @Test
    fun typingInOneFieldLeavesTheOthersAlone() {
        val entries = ConnectionEntries().with(Host, "nas.local").with(Username, "ada").with(Host, "nas.lan")
        assertEquals("nas.lan", entries[Host])
        assertEquals("ada", entries[Username])
        assertEquals("", entries[Password])
    }

    @Test
    fun aPortThatIsNotANumberFallsBack() {
        assertEquals(2121, ConnectionEntries().with(Port, "2121").port(21))
        assertEquals(21, ConnectionEntries().with(Port, "").port(21))
        assertEquals(21, ConnectionEntries().with(Port, "ftp").port(21))
        assertEquals(21, ConnectionEntries().with(Port, " 2121").port(21))
        assertEquals(21, ConnectionEntries().with(Port, "99999999999").port(21))
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    fun ftpAndSftpNeedAHostAndAUsername() {
        for (form in listOf(FtpForm, SftpForm)) {
            assertNull(form.made(form.blank()))
            assertNull(form.made(form.filled(Host to "nas.local")))
            assertNull(form.made(form.filled(Username to "ada")))
            assertNull(form.made(form.filled(Host to "   ", Username to "ada")))
            assertNotNull(form.made(form.filled(Host to "nas.local", Username to "ada")))
        }
    }

    @Test
    fun smbAlsoNeedsAShare() {
        assertNull(SmbForm.made(SmbForm.filled(Host to "nas.local", Username to "ada")))
        assertNull(SmbForm.made(SmbForm.filled(Host to "nas.local", Share to "media")))
        assertNotNull(SmbForm.made(SmbForm.filled(Host to "nas.local", Share to "media", Username to "ada")))
    }

    @Test
    fun webDavAndNextcloudNeedEverythingButAName() {
        for (form in listOf(WebDavForm, NextcloudForm)) {
            assertNull(form.made(form.filled(Server to "cloud.example.com", Username to "ada")))
            assertNull(form.made(form.filled(Server to "cloud.example.com", Password to "pw")))
            assertNull(form.made(form.filled(Username to "ada", Password to "pw")))
            assertNotNull(form.made(form.filled(Server to "cloud.example.com", Username to "ada", Password to "pw")))
        }
    }

    @Test
    fun aPasswordIsNotNeededForFtpSftpOrSmb() {
        assertFalse(Password in FtpForm.required)
        assertFalse(Password in SftpForm.required)
        assertFalse(Password in SmbForm.required)
    }

    // ── The connections they make ────────────────────────────────────────────────

    @Test
    fun ftpConnectionCarriesWhatWasTyped() {
        val made = FtpForm.made(
            FtpForm.filled(Name to "Attic", Host to "192.168.1.9", Port to "2121", Username to "ada", Password to "pw", switchOn = true)
        )!!
        assertEquals("id-1", made.id)
        assertEquals("Attic", made.displayName)
        assertEquals("192.168.1.9", made.host)
        assertEquals(2121, made.port)
        assertEquals("ada", made.username)
        assertTrue(made.useFtps)
        assertEquals(1_000L, made.savedAt)
        // Left as the app has always saved them.
        assertFalse(made.useImplicitFtps)
        assertEquals("/", made.initialPath)
        assertTrue(made.passiveMode)
    }

    @Test
    fun aConnectionWithNoNameGoesByItsHost() {
        assertEquals("nas.local", FtpForm.made(FtpForm.filled(Host to "nas.local", Username to "ada"))!!.displayName)
        assertEquals("nas.local", SftpForm.made(SftpForm.filled(Name to "  ", Host to "nas.local", Username to "ada"))!!.displayName)
        assertEquals("nas.local", SmbForm.made(SmbForm.filled(Host to "nas.local", Share to "media", Username to "ada"))!!.displayName)
    }

    @Test
    fun anUnreadablePortBecomesTheUsualOne() {
        assertEquals(21, FtpForm.made(FtpForm.filled(Host to "h", Port to "", Username to "u"))!!.port)
        assertEquals(22, SftpForm.made(SftpForm.filled(Host to "h", Port to "ssh", Username to "u"))!!.port)
        assertEquals(445, SmbForm.made(SmbForm.filled(Host to "h", Port to "-", Share to "s", Username to "u"))!!.port)
    }

    @Test
    fun ftpIsPlainUnlessTheSwitchIsOn() {
        assertFalse(FtpForm.made(FtpForm.filled(Host to "h", Username to "u"))!!.useFtps)
    }

    @Test
    fun sftpConnectionSignsInWithAPasswordAndPinsNoKeyYet() {
        val made = SftpForm.made(SftpForm.filled(Name to "Box", Host to "box.lan", Username to "ada"))!!
        assertEquals("id-1", made.id)
        assertEquals("Box", made.displayName)
        assertEquals("box.lan", made.host)
        assertEquals(22, made.port)
        assertEquals("ada", made.username)
        assertEquals(1_000L, made.savedAt)
        assertEquals(SftpAuthType.PASSWORD, made.authType)
        assertNull(made.hostKeyFingerprint)
        assertEquals("/", made.initialPath)
    }

    @Test
    fun smbConnectionCarriesItsShareAndDomain() {
        val made = SmbForm.made(
            SmbForm.filled(Host to "nas.local", Share to "media", Domain to "HOME", Username to "ada", Password to "pw")
        )!!
        assertEquals("id-1", made.id)
        assertEquals("nas.local", made.host)
        assertEquals(445, made.port)
        assertEquals("media", made.shareName)
        assertEquals("HOME", made.domain)
        assertEquals("ada", made.username)
        assertEquals(1_000L, made.savedAt)
        assertEquals("", SmbForm.made(SmbForm.filled(Host to "h", Share to "s", Username to "u"))!!.domain)
    }

    @Test
    fun whatIsTypedIsKeptExactly() {
        val made = FtpForm.made(FtpForm.filled(Host to " nas.local ", Username to " ada "))!!
        assertEquals(" nas.local ", made.host)
        assertEquals(" ada ", made.username)
    }

    @Test
    fun webDavAddressGetsHttpWhenTypedWithoutAScheme() {
        assertEquals("http://nas.local/dav", webDavUrl("nas.local/dav"))
        assertEquals("https://nas.local/dav", webDavUrl("https://nas.local/dav"))
        assertEquals("http://nas.local/dav", webDavUrl("http://nas.local/dav"))
    }

    @Test
    fun webDavConnectionIsNamedAfterItsAddressWhenNotNamed() {
        val unnamed = WebDavForm.made(WebDavForm.filled(Server to "https://nas.local/dav/", Username to "ada", Password to "pw"))!!
        assertEquals("nas.local/dav", unnamed.displayName)
        assertEquals("https://nas.local/dav/", unnamed.serverUrl)
        assertEquals("ada", unnamed.username)
        assertFalse(unnamed.isNextcloud)
        assertFalse(unnamed.allowSelfSignedCert)

        val named = WebDavForm.made(
            WebDavForm.filled(Name to "Attic", Server to "nas.local", Username to "ada", Password to "pw", switchOn = true)
        )!!
        assertEquals("Attic", named.displayName)
        assertEquals("http://nas.local", named.serverUrl)
        assertTrue(named.allowSelfSignedCert)
    }

    @Test
    fun nextcloudConnectionWorksOutItsAddress() {
        val made = NextcloudForm.made(NextcloudForm.filled(Server to "cloud.example.com", Username to "ada", Password to "pw"))!!
        assertEquals("cloud.example.com", made.displayName)
        assertEquals("https://cloud.example.com/remote.php/dav/files/ada/", made.serverUrl)
        assertEquals("ada", made.username)
        assertTrue(made.isNextcloud)
        assertFalse(made.allowSelfSignedCert)
    }

    @Test
    fun nextcloudKeepsAnHttpAddressAndDropsTheSchemeFromItsName() {
        val plain = NextcloudForm.made(NextcloudForm.filled(Server to "http://cloud.lan/", Username to "ada", Password to "pw"))!!
        assertEquals("cloud.lan", plain.displayName)
        assertEquals("http://cloud.lan/remote.php/dav/files/ada/", plain.serverUrl)

        val secure = NextcloudForm.made(NextcloudForm.filled(Server to "https://cloud.example.com/", Username to "ada", Password to "pw"))!!
        assertEquals("cloud.example.com", secure.displayName)
        assertEquals("https://cloud.example.com/remote.php/dav/files/ada/", secure.serverUrl)
    }

    @Test
    fun eachSaveGetsItsOwnId() {
        val entries = FtpForm.filled(Host to "h", Username to "u")
        val first = FtpForm.connection(entries)!!
        val second = FtpForm.connection(entries)!!
        assertNotEquals(first.id, second.id)
        assertTrue(first.savedAt > 0)
    }
}
