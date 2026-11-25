package com.kobblo.gallery.file.explorer.data.remote

import com.google.gson.Gson
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpAuthType
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The saved form of each kind of connection, spelled out. Connections already on people's phones are
 * stored under these names, so a field that is renamed or reordered here is a connection that comes back
 * with a hole in it.
 */
class SavedConnectionShapesTest {

    private val gson = Gson()

    private inline fun <reified C> assertSavedAs(json: String, connection: C) {
        assertEquals(json, gson.toJson(connection))
        assertEquals(connection, gson.fromJson(json, C::class.java))
    }

    @Test
    fun `an FTP connection is saved under these names`() {
        assertSavedAs(
            """{"id":"a1","displayName":"Attic NAS","host":"192.168.1.9","port":2121,"username":"sam",""" +
                """"useFtps":true,"useImplicitFtps":false,"initialPath":"/pub","passiveMode":false,"savedAt":77}""",
            FtpConnection(
                "a1", "Attic NAS", "192.168.1.9", 2121, "sam",
                useFtps = true, useImplicitFtps = false, initialPath = "/pub", passiveMode = false, savedAt = 77
            )
        )
    }

    @Test
    fun `an SFTP connection that signs in with a key is saved under these names`() {
        assertSavedAs(
            """{"id":"s1","displayName":"Box","host":"example.com","port":2222,"username":"sam","authType":"KEY",""" +
                """"privateKeyPem":"-----BEGIN KEY-----","privateKeyPassphrase":"open sesame",""" +
                """"initialPath":"/home/sam","hostKeyFingerprint":"6d:3c:aa:01","savedAt":5}""",
            SftpConnection(
                "s1", "Box", "example.com", 2222, "sam", SftpAuthType.KEY,
                "-----BEGIN KEY-----", "open sesame", "/home/sam", "6d:3c:aa:01", 5
            )
        )
    }

    @Test
    fun `an SFTP connection whose server has not been seen yet is saved without a fingerprint`() {
        val json = """{"id":"s2","displayName":"New","host":"h","port":22,"username":"u","authType":"PASSWORD",""" +
            """"privateKeyPem":"","privateKeyPassphrase":"","initialPath":"/","savedAt":1}"""

        assertSavedAs(json, SftpConnection(id = "s2", displayName = "New", host = "h", username = "u", savedAt = 1))
        assertNull(gson.fromJson(json, SftpConnection::class.java).hostKeyFingerprint)
    }

    @Test
    fun `an SMB connection is saved under these names`() {
        assertSavedAs(
            """{"id":"m1","displayName":"Bedroom NAS","host":"nas.local","port":4450,"shareName":"media",""" +
                """"domain":"WORKGROUP","username":"sam","savedAt":9}""",
            SmbConnection("m1", "Bedroom NAS", "nas.local", 4450, "media", "WORKGROUP", "sam", 9)
        )
    }

    @Test
    fun `a connection made from only what its form asks for gets the usual settings`() {
        val ftp = FtpConnection(id = "a", displayName = "A", host = "h", username = "u", savedAt = 0)
        val sftp = SftpConnection(id = "s", displayName = "S", host = "h", username = "u", savedAt = 0)
        val smb = SmbConnection(id = "m", displayName = "M", host = "h", shareName = "s", username = "u", savedAt = 0)

        assertEquals(
            ftp.copy(port = 21, useFtps = false, useImplicitFtps = false, initialPath = "/", passiveMode = true),
            ftp
        )
        assertEquals(
            sftp.copy(
                port = 22, authType = SftpAuthType.PASSWORD, privateKeyPem = "", privateKeyPassphrase = "",
                initialPath = "/", hostKeyFingerprint = null
            ),
            sftp
        )
        assertEquals(smb.copy(port = 445, domain = ""), smb)
    }
}
