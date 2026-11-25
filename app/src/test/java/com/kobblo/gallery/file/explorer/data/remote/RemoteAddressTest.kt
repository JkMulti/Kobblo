package com.kobblo.gallery.file.explorer.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAddressTest {

    private val address = RemoteAddress("ftp", "4f2a")

    @Test
    fun `an address is the scheme, the connection and the server's path`() {
        assertEquals("ftp_4f2a:", address.prefix)
        assertEquals("ftp_4f2a:/pub/notes.txt", address.of("/pub/notes.txt"))
        assertEquals("smb_s1:Photos\\beach.jpg", RemoteAddress("smb", "s1").of("Photos\\beach.jpg"))
    }

    @Test
    fun `the server's path comes back out of its own address`() {
        assertEquals("/pub/notes.txt", address.pathIn("ftp_4f2a:/pub/notes.txt"))
        assertEquals("/", address.pathIn("ftp_4f2a:/"))
        assertEquals("", address.pathIn("ftp_4f2a:"))
    }

    @Test
    fun `an address of another connection is left as it is`() {
        assertEquals("ftp_other:/pub", address.pathIn("ftp_other:/pub"))
        assertEquals("sftp_4f2a:/pub", address.pathIn("sftp_4f2a:/pub"))
        assertEquals("/already/cut/down", address.pathIn("/already/cut/down"))
    }

    @Test
    fun `any address gives up its path at the first colon`() {
        assertEquals("/pub/notes.txt", serverPathOf("ftp_4f2a:/pub/notes.txt"))
        assertEquals("Photos\\beach.jpg", serverPathOf("smb_s1:Photos\\beach.jpg"))
        assertEquals("/", serverPathOf("nextcloud_n1:/"))
        assertEquals("", serverPathOf("webdav_w1:"))
        // Only the first colon is the address's own.
        assertEquals("/talks/10:30 keynote.mp4", serverPathOf("sftp_x:/talks/10:30 keynote.mp4"))
    }

    @Test
    fun `a path with no colon in it passes for one already cut down`() {
        assertEquals("/pub/notes.txt", serverPathOf("/pub/notes.txt"))
        assertEquals("Photos\\beach.jpg", serverPathOf("Photos\\beach.jpg"))
        assertEquals("", serverPathOf(""))
    }

    @Test
    fun `nothing at all is the top folder on a server with slash paths`() {
        assertEquals("/", SlashPaths.folder(""))
        assertEquals("/", SlashPaths.folder("/"))
        assertEquals("/pub", SlashPaths.folder("/pub"))
        assertEquals("pub", SlashPaths.folder("pub"))
    }

    @Test
    fun `a name is joined to its folder by one slash`() {
        assertEquals("/notes.txt", SlashPaths.child("/", "notes.txt"))
        assertEquals("/pub/notes.txt", SlashPaths.child("/pub", "notes.txt"))
        assertEquals("/pub/notes.txt", SlashPaths.child("/pub/", "notes.txt"))
        assertEquals("/notes.txt", SlashPaths.child("", "notes.txt"))
        assertEquals("/pub/a b#c.txt", SlashPaths.child("/pub", "a b#c.txt"))
    }

    @Test
    fun `a path splits into its folder and its name`() {
        assertEquals("/pub/2024" to "new", SlashPaths.split("/pub/2024/new"))
        assertEquals("" to "new", SlashPaths.split("/new"))
        assertEquals("/" to "new", SlashPaths.split("new"))
        assertEquals("" to "", SlashPaths.split("/"))
        assertEquals("/" to "", SlashPaths.split(""))
    }

    @Test
    fun `splitting and joining give the same path back`() {
        listOf("/pub/2024/new", "/new", "/a/b").forEach { path ->
            val (folder, name) = SlashPaths.split(path)
            assertEquals(path, SlashPaths.child(folder, name))
        }
    }

    @Test
    fun `the top of a share is the empty path`() {
        assertEquals("", SharePaths.folder("/"))
        assertEquals("", SharePaths.folder(""))
        assertEquals("Photos\\2024", SharePaths.folder("Photos\\2024"))
        // Only the one slash the explorer puts there is taken off.
        assertEquals("Photos", SharePaths.folder("/Photos"))
    }

    @Test
    fun `names in a share are joined by backslashes with none in front`() {
        assertEquals("Photos", SharePaths.child("", "Photos"))
        assertEquals("Photos\\2024", SharePaths.child("Photos", "2024"))
        assertEquals("Photos\\2024\\beach.jpg", SharePaths.child("Photos\\2024", "beach.jpg"))
    }

    @Test
    fun `a file is put into a folder without doubling the separator`() {
        assertEquals("/pub/notes.txt", pathInFolder("/pub", "notes.txt", '/'))
        assertEquals("/pub/notes.txt", pathInFolder("/pub/", "notes.txt", '/'))
        assertEquals("/notes.txt", pathInFolder("/", "notes.txt", '/'))
        assertEquals("Photos\\beach.jpg", pathInFolder("Photos", "beach.jpg", '\\'))
        assertEquals("Photos\\beach.jpg", pathInFolder("Photos\\", "beach.jpg", '\\'))
    }

    @Test
    fun `a file put into no folder is left as a bare name`() {
        assertEquals("notes.txt", pathInFolder("", "notes.txt", '/'))
        assertEquals("beach.jpg", pathInFolder("", "beach.jpg", '\\'))
    }
}
