package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.FileItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenKindTest {
    private fun file(id: Int, name: String) =
        FileItem(id = id, path = "drive_id:$name", name = name, type = "file", kind = "", size = "", changed = "", owner = "")

    @Test
    fun aListingsPhotosAndVideosAreWhatItSaysTheyAre() {
        assertEquals(OpenKind.Photo, openKind("scan.pdf", "image"))
        assertEquals(OpenKind.Video, openKind("clip.ts", "video"))
        assertEquals(OpenKind.Video, openKind("notes.txt", "video"))
    }

    @Test
    fun typeScriptThatIsNotAVideoIsADocument() {
        assertEquals(OpenKind.Document, openKind("main.ts", "doc"))
        assertEquals(OpenKind.Document, openKind("main.ts", "file"))
    }

    @Test
    fun documentsAreKnownByNameOrByType() {
        assertEquals(OpenKind.Document, openKind("Report.PDF", "file"))
        assertEquals(OpenKind.Document, openKind("letter.docx", "file"))
        assertEquals(OpenKind.Document, openKind("deck.pptx", "doc"))
        assertEquals(OpenKind.Document, openKind("no extension", "document"))
        // A name that can be read comes before a type that would play it.
        assertEquals(OpenKind.Document, openKind("playlist.log", "audio"))
    }

    @Test
    fun whateverIsLeftIsASongAnAppOrUnknown() {
        assertEquals(OpenKind.Song, openKind("song.mp3", "audio"))
        assertEquals(OpenKind.App, openKind("Kobblo.APK", "apk"))
        assertEquals(OpenKind.Unknown, openKind("bundle.apks", "apk"))
        assertEquals(OpenKind.Unknown, openKind("archive.zip", "archive"))
        assertEquals(OpenKind.Unknown, openKind("apk", "file"))
    }

    @Test
    fun everyKindOfTextTheViewerReads() {
        val readable = listOf(
            "pdf", "docx", "doc", "txt", "json", "md", "csv", "xml", "log", "kt", "java", "py", "html", "htm", "css", "js",
            "ts", "yaml", "yml", "ini", "conf", "properties", "sh"
        )
        readable.forEach { assertTrue(it, readsAsDocument("a.$it", "file")) }
        listOf("c", "rtf", "epub", "toml", "zip", "mp3").forEach { assertFalse(it, readsAsDocument("a.$it", "file")) }
    }

    @Test
    fun aDocumentIsHandedOverUnderItsOwnType() {
        assertEquals("application/pdf", documentMime("a.pdf"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", documentMime("a.docx"))
        assertEquals("application/msword", documentMime("a.DOC"))
        assertEquals("text/html", documentMime("a.htm"))
        assertEquals("text/xml", documentMime("a.xml"))
        assertEquals("application/json", documentMime("a.json"))
        assertEquals("text/plain", documentMime("a.csv"))
        assertEquals("text/plain", documentMime("a.yaml"))
    }

    @Test
    fun aTellingNameWinsOverWhatTheCallerTookItFor() {
        assertEquals("application/pdf", handOverMime("a.PDF", "*/*"))
        assertEquals("text/html", handOverMime("a.html", "*/*"))
        assertEquals("text/csv", handOverMime("a.csv", documentMime("a.csv")))
        assertEquals("application/json", handOverMime("a.json", "text/plain"))
        assertEquals("text/xml", handOverMime("a.xml", "*/*"))
        listOf("txt", "md", "log", "kt", "java", "py").forEach { assertEquals(it, "text/plain", handOverMime("a.$it", "*/*")) }
    }

    @Test
    fun otherwiseTheCallersTypeStands() {
        assertEquals("audio/*", handOverMime("a.mp3", "audio/*"))
        assertEquals("application/msword", handOverMime("a.doc", documentMime("a.doc")))
        assertEquals("*/*", handOverMime("readme", "*/*"))
    }

    @Test
    fun typesForWhenTheSystemHasNone() {
        assertEquals("application/zip", mimeForExtension("zip"))
        assertEquals("application/x-7z-compressed", mimeForExtension("7z"))
        assertEquals("application/vnd.ms-powerpoint", mimeForExtension("ppt"))
        assertEquals("text/plain", mimeForExtension("sh"))
        assertEquals("audio/*", mimeForExtension("flac"))
        assertEquals("video/*", mimeForExtension("mkv"))
        assertEquals("image/*", mimeForExtension("heic"))
        assertEquals("*/*", mimeForExtension("xyz"))
        assertEquals("*/*", mimeForExtension(""))
    }

    @Test
    fun aDownloadedCopyIsNamedAfterItsIdAndASafeName() {
        val cache = File("/cache")
        assertEquals(File("/cache/cloud_cache/7_My_r_sum_.pdf"), downloadedCopy(cache, file(7, "My résumé.pdf")))
        // A negative id is the unsigned number with the same bits.
        assertEquals(File("/cache/cloud_cache/4294967295_a-b_c.1.txt"), downloadedCopy(cache, file(-1, "a-b_c.1.txt")))
    }

    @Test
    fun remotePlacesAreEveryAccountAndServer() {
        val remote = listOf(
            "drive", "drive:me@example.com", "drive_id:1", "mega", "mega_id:2", "dropbox", "dropbox_id:/a",
            "smb_1:/", "ftp_1:/", "sftp_1:/", "webdav_1:/", "nextcloud_1:/"
        )
        remote.forEach { assertTrue(it, isRemotePlace(it)) }
        val near = listOf("gallery", "home", "locations", "send", "category/images", "/storage/emulated/0")
        near.forEach { assertFalse(it, isRemotePlace(it)) }
    }
}
