package com.kobblo.gallery.file.explorer.data.webdav

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class WebDavConnectionTest {

    private fun connection(serverUrl: String) =
        WebDavConnection(id = "w1", displayName = "Home", serverUrl = serverUrl, username = "sam")

    @Test
    fun `a Nextcloud address is built from the server and the user`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/sam/",
            buildNextcloudUrl("cloud.example.com", "sam")
        )
    }

    @Test
    fun `the server may be given with a scheme and with slashes at the end`() {
        val expected = "https://cloud.example.com/remote.php/dav/files/sam/"

        assertEquals(expected, buildNextcloudUrl("https://cloud.example.com", "sam"))
        assertEquals(expected, buildNextcloudUrl("https://cloud.example.com/", "sam"))
        assertEquals(expected, buildNextcloudUrl("cloud.example.com///", "sam"))
    }

    @Test
    fun `only a server given as http is spoken to without encryption`() {
        assertEquals("http://nas.local/remote.php/dav/files/sam/", buildNextcloudUrl("http://nas.local/", "sam"))
        assertEquals("https://nas.local/remote.php/dav/files/sam/", buildNextcloudUrl("nas.local", "sam"))
    }

    @Test
    fun `a port and a folder the server lives under are kept`() {
        assertEquals(
            "https://example.com:8443/nextcloud/remote.php/dav/files/sam/",
            buildNextcloudUrl("example.com:8443/nextcloud/", "sam")
        )
    }

    @Test
    fun `the user name goes into the address as it was typed`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/sam@example.com/",
            buildNextcloudUrl("cloud.example.com", "sam@example.com")
        )
    }

    @Test
    fun `the address paths are added to ends in exactly one slash`() {
        assertEquals("https://example.com/dav/", connection("https://example.com/dav").normalizedUrl)
        assertEquals("https://example.com/dav/", connection("https://example.com/dav/").normalizedUrl)
        assertEquals("https://example.com/dav/", connection("https://example.com/dav///").normalizedUrl)
        assertEquals("http://nas.local/", connection("http://nas.local").normalizedUrl)
    }

    @Test
    fun `an address without a scheme is refused`() {
        listOf("example.com/dav", "ftp://example.com", "", "httpserver.local").forEach { address ->
            try {
                connection(address)
                fail("$address was taken for a web address")
            } catch (expected: IllegalArgumentException) {
                assertEquals("serverUrl must include a scheme", expected.message)
            }
        }
    }

    @Test
    fun `a saved connection is the six fields and nothing worked out from them`() {
        val home = WebDavConnection(
            "w1", "Home", "https://example.com/dav", "sam", isNextcloud = true, allowSelfSignedCert = true
        )

        val saved = Gson().toJson(home)

        assertEquals(
            """{"id":"w1","displayName":"Home","serverUrl":"https://example.com/dav","username":"sam",""" +
                """"isNextcloud":true,"allowSelfSignedCert":true}""",
            saved
        )
        assertFalse(saved.contains("normalizedUrl"))
        assertEquals(home, Gson().fromJson(saved, WebDavConnection::class.java))
    }
}
