package com.kobblo.gallery.file.explorer.data.remote

import com.kobblo.gallery.file.explorer.data.ftp.FtpStorageProvider
import com.kobblo.gallery.file.explorer.data.sftp.SftpStorageProvider
import com.kobblo.gallery.file.explorer.data.smb.SmbStorageProvider
import com.kobblo.gallery.file.explorer.data.webdav.WebDavStorageProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * What the four providers and their file sources do with no server behind them. The explorer leans on
 * exactly this: it asks for a listing first and connects only when that fails.
 */
class DisconnectedProvidersTest {

    private val local = File("notes.txt")

    private fun reasons(results: List<Result<*>>): List<String?> = results.map { it.exceptionOrNull()?.message }

    private val notConnected = "Not connected"

    @Test
    fun `a provider starts with no connection open`() {
        assertNull(FtpStorageProvider().currentConnectionId)
        assertNull(SftpStorageProvider().currentConnectionId)
        assertNull(SmbStorageProvider().currentConnectionId)
        assertNull(WebDavStorageProvider().currentConnectionId)
    }

    @Test
    fun `disconnecting with nothing open does nothing`(): Unit = runBlocking {
        FtpStorageProvider().disconnect()
        SftpStorageProvider().disconnect()
        SmbStorageProvider().disconnect()
        WebDavStorageProvider().disconnect()
    }

    @Test
    fun `FTP calls with nothing open fail as not connected`(): Unit = runBlocking {
        val ftp = FtpStorageProvider()

        assertEquals(
            List(5) { notConnected },
            reasons(
                listOf(
                    ftp.listFiles("ftp_c1:/", "c1"),
                    ftp.download("ftp_c1:/a.txt", local),
                    ftp.upload(local, "ftp_c1:/a.txt"),
                    ftp.delete("ftp_c1:/a.txt", isDirectory = false),
                    ftp.createFolder("ftp_c1:/", "new")
                )
            )
        )
    }

    @Test
    fun `SFTP calls with nothing open fail as not connected`(): Unit = runBlocking {
        val sftp = SftpStorageProvider()

        assertEquals(
            List(5) { notConnected },
            reasons(
                listOf(
                    sftp.listFiles("sftp_c1:/", "c1"),
                    sftp.download("sftp_c1:/a.txt", local),
                    sftp.upload(local, "sftp_c1:/a.txt"),
                    sftp.delete("sftp_c1:/a", isDirectory = true),
                    sftp.createFolder("sftp_c1:/", "new")
                )
            )
        )
    }

    @Test
    fun `SMB calls with nothing open fail as not connected`(): Unit = runBlocking {
        val smb = SmbStorageProvider()

        assertEquals(
            List(5) { notConnected },
            reasons(
                listOf(
                    smb.listFiles("smb_c1:/", "c1"),
                    smb.download("smb_c1:a.txt", local),
                    smb.upload(local, "smb_c1:a.txt"),
                    smb.delete("smb_c1:a.txt"),
                    smb.createFolder("smb_c1:new")
                )
            )
        )
    }

    @Test
    fun `WebDAV calls with nothing open fail as not connected`(): Unit = runBlocking {
        val webDav = WebDavStorageProvider()

        assertEquals(
            List(6) { notConnected },
            reasons(
                listOf(
                    webDav.listFiles("webdav_c1:/", "c1"),
                    webDav.listFiles("nextcloud_c1:/", "c1"),
                    webDav.download("webdav_c1:/a.txt", local),
                    webDav.upload(local, "webdav_c1:/a.txt"),
                    webDav.delete("webdav_c1:/a.txt"),
                    webDav.createFolder("webdav_c1:/", "new")
                )
            )
        )
    }
}
