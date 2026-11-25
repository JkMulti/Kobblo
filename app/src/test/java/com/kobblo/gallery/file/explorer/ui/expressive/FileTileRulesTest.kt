package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.explorer.PathTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTileRulesTest {
    private fun file(
        name: String,
        type: String = "file",
        path: String = "/storage/emulated/0/$name",
        bytes: Long = 10_000,
        locked: Boolean = false,
        thumbnail: String? = null
    ) = FileItem(
        id = name.hashCode(),
        path = path,
        name = name,
        type = type,
        kind = type,
        size = "",
        changed = "",
        owner = "Me",
        isLocked = locked,
        sizeBytes = bytes,
        thumbnailLink = thumbnail
    )

    // ── Which tiles show a picture ──

    @Test
    fun `pictures, videos and documents with a first page are drawn`() {
        for (name in listOf("a.jpg", "b.MP4", "c.pdf", "d.docx", "e.apk", "f.txt", "g.kt")) {
            assertTrue(name, hasThumbnail(file(name)))
        }
        assertTrue(hasThumbnail(file("no extension", type = "image")))
        assertTrue(hasThumbnail(file("clip", type = "video")))
    }

    @Test
    fun `a folder, a locked file and anything named for passwords never are`() {
        assertFalse(hasThumbnail(file("Camera", type = "folder")))
        assertFalse(hasThumbnail(file("a.jpg", locked = true)))
        assertFalse(hasThumbnail(file("My Passwords.txt")))
        assertFalse(hasThumbnail(file("a.jpg", locked = true, thumbnail = "https://example.org/t.jpg")))
    }

    @Test
    fun `kinds whose first page says little keep their glyph`() {
        for (name in listOf("a.html", "b.doc", "c.xls", "d.ppt", "e.csv", "f.epub", "g.rtf")) {
            assertFalse(name, hasThumbnail(file(name)))
        }
        assertFalse(hasThumbnail(file("a.html", thumbnail = "https://example.org/t.jpg")))
    }

    @Test
    fun `a text file too short to fill a page keeps its glyph`() {
        assertFalse(hasThumbnail(file("note.txt", bytes = 511)))
        assertTrue(hasThumbnail(file("note.txt", bytes = 512)))
        // A thumbnail someone else made is shown whatever the size.
        assertTrue(hasThumbnail(file("note.txt", bytes = 3, thumbnail = "https://example.org/t.jpg")))
    }

    @Test
    fun `a file with nowhere to read it from is drawn only from a supplied thumbnail`() {
        assertFalse(hasThumbnail(file("a.jpg", path = "")))
        assertTrue(hasThumbnail(file("a.jpg", path = "", thumbnail = "https://example.org/t.jpg")))
        assertTrue(hasThumbnail(file("data.bin", thumbnail = "https://example.org/t.jpg")))
        assertFalse(hasThumbnail(file("data.bin")))
    }

    // ── Which category a name falls in ──

    @Test
    fun `a name's category comes from what it ends in`() {
        mapOf(
            "a.JPG" to "images",
            "b.mp4" to "videos",
            "c.mp3" to "audio",
            "d.pdf" to "docs",
            "e.md" to "docs",
            "f.vcf" to "docs",
            "g.m3u" to "audio",
            "h.apk" to "apps",
            "i.apks" to "apps",
            "j.zip" to "archives",
            "k.bin" to "large",
            "no extension" to "large"
        ).forEach { (name, category) -> assertEquals(name, category, fileCategoryForName(name)) }
    }

    // ── The path bar ──

    private val root = "/storage/emulated/0"

    @Test
    fun `on the phone the steps are the storage and then each folder of the path`() {
        val crumbs = breadcrumbs("$root/DCIM/Camera", root, "Internal storage", emptyList(), null)
        assertEquals(
            listOf(
                Breadcrumb("Internal storage", opens = root, path = root, isCurrent = false),
                Breadcrumb("DCIM", opens = "$root/DCIM", path = "$root/DCIM", isCurrent = false),
                Breadcrumb("Camera", opens = "$root/DCIM/Camera", path = "$root/DCIM/Camera", isCurrent = true)
            ),
            crumbs
        )
        assertEquals(PathTools.segments("$root/DCIM/Camera", root).map { it.path }, crumbs.drop(1).map { it.path })
    }

    @Test
    fun `elsewhere the steps are the folders that were opened, and the last has nowhere to lead`() {
        val crumbs = breadcrumbs(
            location = "drive_id:3",
            root = "drive:a@x.org",
            storageName = "Google Drive",
            folderStack = listOf("drive_id:1" to "Work", "drive_id:2" to "2024"),
            folderName = "Invoices"
        )
        assertEquals(
            listOf(
                Breadcrumb("Google Drive", opens = "drive:a@x.org", path = null, isCurrent = false),
                Breadcrumb("Work", opens = "drive_id:1", path = null, isCurrent = false),
                Breadcrumb("2024", opens = "drive_id:2", path = null, isCurrent = false),
                Breadcrumb("Invoices", opens = null, path = null, isCurrent = true)
            ),
            crumbs
        )
    }

    @Test
    fun `a storage's root is a single step`() {
        assertEquals(1, breadcrumbs(root, root, "Internal storage", emptyList(), null).size)
        assertEquals(1, breadcrumbs("drive:a@x.org", "drive:a@x.org", "Google Drive", emptyList(), null).size)
    }
}
