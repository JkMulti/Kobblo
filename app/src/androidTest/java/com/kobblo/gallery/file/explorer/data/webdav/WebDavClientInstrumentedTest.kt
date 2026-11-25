package com.kobblo.gallery.file.explorer.data.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kobblo.gallery.file.explorer.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * [WebDavClient] against a real server. Nothing on the phone can stand in for one: the point is whether a
 * server understands what the client sends it.
 *
 * The server is named in `local.properties`, under `testWebdavUrl`, `testWebdavUser`, `testWebdavPassword`
 * and `testWebdavAllowSelfSigned`, and reaches the tests through [BuildConfig]. With no address there the
 * tests are skipped. Point them at a server with nothing on it worth keeping.
 *
 * Each test works in a folder of its own, made before it and removed after it, so no test leans on another
 * and none leaves anything behind.
 */
@RunWith(AndroidJUnit4::class)
class WebDavClientInstrumentedTest {

    private val folder = "kobblo-test-${UUID.randomUUID()}"
    private val made = mutableListOf<File>()

    private lateinit var connection: WebDavConnection
    private lateinit var client: WebDavClient

    @Before
    fun openFolder(): Unit = runBlocking {
        assumeTrue("No WebDAV test server is set in local.properties", BuildConfig.TEST_WEBDAV_URL.isNotBlank())
        connection = WebDavConnection(
            id = "device-test",
            displayName = "Test server",
            serverUrl = BuildConfig.TEST_WEBDAV_URL,
            username = BuildConfig.TEST_WEBDAV_USER,
            allowSelfSignedCert = BuildConfig.TEST_WEBDAV_ALLOW_SELF_SIGNED
        )
        client = WebDavClient(connection, BuildConfig.TEST_WEBDAV_PASSWORD)
        client.createFolder(folder)
    }

    @After
    fun removeFolder() {
        made.forEach { it.delete() }
        if (!::client.isInitialized) return
        // A server may refuse to remove a folder, and the test that ran has had its say by now.
        runCatching { runBlocking { client.delete(folder) } }
    }

    @Test
    fun theRightPasswordIsAccepted(): Unit = runBlocking {
        assertTrue(client.testConnection())
    }

    @Test
    fun aWrongPasswordIsToldApartFromOtherFailures(): Unit = runBlocking {
        val stranger = WebDavClient(connection.copy(id = "device-test-wrong-password"), "not-the-password")
        try {
            stranger.testConnection()
            fail("The server let a wrong password in")
        } catch (expected: WebDavException.AuthFailed) {
            // The case the connection screen has its own words for.
        }
    }

    @Test
    fun aNewFolderIsListedAsAFolder(): Unit = runBlocking {
        val listed = client.list("").firstOrNull { it.name == folder }
        assertNotNull("The folder made for this test isn't at the top of the server", listed)
        assertTrue(listed!!.isDirectory)
    }

    @Test
    fun anUploadIsListedWithItsSize(): Unit = runBlocking {
        val local = localFile("Kobblo was here at ${System.currentTimeMillis()}")
        client.upload(local, "$folder/note.txt", mimeType = "text/plain")

        val listed = client.list(folder).firstOrNull { it.name == "note.txt" }
        assertNotNull("The uploaded file isn't in its folder", listed)
        assertFalse(listed!!.isDirectory)
        assertEquals(local.length(), listed.sizeBytes)
    }

    @Test
    fun aDownloadBringsBackWhatWasUploaded(): Unit = runBlocking {
        val text = "Round trip ${UUID.randomUUID()}\nwith a second line, and an é"
        client.upload(localFile(text), "$folder/round-trip.txt", mimeType = "text/plain")

        val back = localFile("")
        assertEquals(back, client.download("$folder/round-trip.txt", back))
        assertEquals(text, back.readText())
    }

    @Test
    fun aMovedFileIsOnlyUnderItsNewName(): Unit = runBlocking {
        client.upload(localFile("on the move"), "$folder/before.txt", mimeType = "text/plain")
        client.move("$folder/before.txt", "$folder/after.txt")

        assertEquals(listOf("after.txt"), client.list(folder).map { it.name })
    }

    @Test
    fun aDeletedFileIsGone(): Unit = runBlocking {
        client.upload(localFile("not for long"), "$folder/short-lived.txt", mimeType = "text/plain")
        client.delete("$folder/short-lived.txt")

        assertTrue(client.list(folder).isEmpty())
    }

    @Test
    fun aMissingFileIsToldApartFromOtherFailures(): Unit = runBlocking {
        try {
            client.download("$folder/never-uploaded.txt", localFile(""))
            fail("The server produced a file that was never uploaded")
        } catch (expected: WebDavException.NotFound) {
            // Not a general failure, and not a server that can't be reached.
        }
    }

    /** A file on the phone holding [text], removed again when the test is over. */
    private fun localFile(text: String): File =
        File.createTempFile("kobblo-webdav", ".txt").also {
            it.writeText(text)
            made += it
        }
}
