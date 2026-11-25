package com.kobblo.gallery.file.explorer.data.remote

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RemoteRowsTest {

    private val startingLocale = Locale.getDefault()

    /** One fixed way of writing a date, so the rows read the same wherever the tests run. */
    private val dates = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val rows = RemoteRows(dates)
    private val ftp = RemoteAddress("ftp", "c1")

    @Before
    fun readInEnglish() = Locale.setDefault(Locale.US)

    @After
    fun readAsBefore() = Locale.setDefault(startingLocale)

    private fun file(name: String, size: Long = 10, modifiedAt: Long = 0) = RemoteEntry(name, false, size, modifiedAt)
    private fun folder(name: String) = RemoteEntry(name, true, 0, 0)

    private fun inPub(vararg entries: RemoteEntry): List<FileItem> =
        rows.of(entries.toList(), ftp, owner = "FTP") { SlashPaths.child("/pub", it) }

    @Test
    fun `a file becomes a row carrying everything the explorer shows`() {
        val row = inPub(file("Beach.JPG", size = 2_048_576, modifiedAt = 1_700_000_000_000)).single()

        assertEquals(
            FileItem(
                id = "/pub/Beach.JPG".hashCode(),
                path = "ftp_c1:/pub/Beach.JPG",
                name = "Beach.JPG",
                type = "image",
                kind = "Image",
                size = "2.0 MB",
                changed = "2023-11-14 22:13",
                owner = "FTP",
                sizeBytes = 2_048_576,
                lastModified = 1_700_000_000_000
            ),
            row
        )
    }

    @Test
    fun `a folder becomes a row with no size to read`() {
        val row = inPub(RemoteEntry("Photos", isFolder = true, sizeBytes = 4096, modifiedAt = 86_400_000)).single()

        assertEquals(
            FileItem(
                id = "/pub/Photos".hashCode(),
                path = "ftp_c1:/pub/Photos",
                name = "Photos",
                type = "folder",
                kind = "Folder",
                size = "",
                changed = "1970-01-02 00:00",
                owner = "FTP",
                sizeBytes = 4096,
                lastModified = 86_400_000
            ),
            row
        )
    }

    @Test
    fun `a server that gives no time leaves the row dated at the start of 1970`() {
        val row = inPub(file("notes.txt")).single()

        assertEquals(0L, row.lastModified)
        assertEquals("1970-01-01 00:00", row.changed)
    }

    @Test
    fun `the id comes from the server's path and the path adds the connection`() {
        val entry = listOf(file("notes.txt"))
        val asWebDav = rows.of(entry, RemoteAddress("webdav", "w1"), "WebDAV") { "/docs/$it" }.single()
        val asNextcloud = rows.of(entry, RemoteAddress("nextcloud", "w1"), "Nextcloud") { "/docs/$it" }.single()

        assertEquals("webdav_w1:/docs/notes.txt", asWebDav.path)
        assertEquals("nextcloud_w1:/docs/notes.txt", asNextcloud.path)
        assertEquals("/docs/notes.txt".hashCode(), asWebDav.id)
        assertEquals(asWebDav.id, asNextcloud.id)
        assertEquals("WebDAV", asWebDav.owner)
        assertEquals("Nextcloud", asNextcloud.owner)
    }

    @Test
    fun `rows in a share are addressed with backslashes`() {
        val smb = RemoteAddress("smb", "s1")
        val atTop = rows.of(listOf(folder("Photos")), smb, "SMB") { SharePaths.child("", it) }.single()
        val below = rows.of(listOf(file("beach.jpg")), smb, "SMB") { SharePaths.child("Photos\\2024", it) }.single()

        assertEquals("smb_s1:Photos", atTop.path)
        assertEquals("Photos".hashCode(), atTop.id)
        assertEquals("smb_s1:Photos\\2024\\beach.jpg", below.path)
        assertEquals("Photos\\2024\\beach.jpg".hashCode(), below.id)
    }

    @Test
    fun `folders come first and names are in order whatever their capitals`() {
        val listed = inPub(file("b.txt"), folder("Zeta"), file("a.txt"), folder("alpha"), file("C.txt"))

        assertEquals(listOf("alpha", "Zeta", "a.txt", "b.txt", "C.txt"), listed.map { it.name })
    }

    @Test
    fun `names that differ only in capitals stay in the order the server gave`() {
        val oneWay = inPub(file("Read.me"), file("read.me"), file("READ.ME"))
        val another = inPub(file("read.me"), file("READ.ME"), file("Read.me"))

        assertEquals(listOf("Read.me", "read.me", "READ.ME"), oneWay.map { it.name })
        assertEquals(listOf("read.me", "READ.ME", "Read.me"), another.map { it.name })
    }

    @Test
    fun `a file's type goes by the end of its name`() {
        mapOf(
            "holiday.png" to "image",
            "raw-shot.DNG" to "image",
            "clip.MKV" to "video",
            "song.mp3" to "music",
            "song.Flac" to "music",
            "paper.pdf" to "document",
            "config.json" to "document",
            "letter.docx" to "document",
            "backup.tar.gz" to "archive",
            "bundle.7z" to "archive",
            "app.apk" to "apk",
            // Kinds the explorer knows on the phone but a server's listing doesn't sort out.
            "song.opus" to "unknown",
            "sheet.xlsx" to "unknown",
            "split.apks" to "unknown",
            "README" to "unknown",
            "ends-in-a-dot." to "unknown",
            ".bashrc" to "unknown"
        ).forEach { (name, type) -> assertEquals("for $name", type, RemoteFileTypes.typeOf(name, isFolder = false)) }
    }

    @Test
    fun `a folder is a folder whatever its name ends in`() {
        assertEquals("folder", RemoteFileTypes.typeOf("Albums.zip", isFolder = true))
        assertEquals("folder", RemoteFileTypes.typeOf("mixtape.mp3", isFolder = true))
    }

    @Test
    fun `the kind is the type with a capital`() {
        mapOf(
            "folder" to "Folder",
            "image" to "Image",
            "video" to "Video",
            "music" to "Music",
            "document" to "Document",
            "archive" to "Archive",
            "apk" to "Apk",
            "unknown" to "Unknown"
        ).forEach { (type, kind) -> assertEquals(kind, RemoteFileTypes.kindOf(type)) }
    }

    @Test
    fun `the capital is the one the language in use writes`() {
        Locale.setDefault(Locale.forLanguageTag("tr"))

        assertEquals("İmage", RemoteFileTypes.kindOf("image"))
        assertEquals("Video", RemoteFileTypes.kindOf("video"))
    }

    @Test
    fun `the folder itself and the one above it are not part of a listing`() {
        val listed = listOf(folder("."), folder(".."), folder(".git"), file("..."), file("notes.txt"))

        assertEquals(listOf(".git", "...", "notes.txt"), listed.withoutDotEntries().map { it.name })
    }
}
