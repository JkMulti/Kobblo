package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.PhotoActions
import com.kobblo.gallery.file.explorer.viewer.metadata.ArrayByteSource
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegSegments
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaFormatting
import com.kobblo.gallery.file.explorer.viewer.metadata.Xmp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AlbumOptionsTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L

    private fun file(id: Int, path: String, moment: Long = now, locked: Boolean = false, type: String = "image") = FileItem(
        id = id,
        path = path,
        name = path.substringAfterLast('/'),
        type = type,
        kind = "media",
        size = "",
        changed = "",
        owner = "Me",
        isLocked = locked,
        lastModified = moment
    )

    private val camera = file(1, "/s/DCIM/Camera/a.jpg")
    private val chat = file(2, "/s/WhatsApp/Images/b.jpg")
    private val chatSent = file(3, "/s/WhatsApp/Images/Sent/c.jpg")
    private val shot = file(4, "/s/Pictures/Screenshots/Screenshot_d.png")
    private val all = listOf(camera, chat, chatSent, shot)

    @Test
    fun `an album kept off Home says so, and so does a folder inside it`() {
        val albums = buildFolderAlbums(all, emptySet(), emptySet(), AlbumSort.NAME, hidden = setOf("/s/WhatsApp/Images"))
        val hidden = albums.filter { it.isHiddenFromHome }.map { it.path }.toSet()
        assertEquals(setOf("/s/WhatsApp/Images", "/s/WhatsApp/Images/Sent"), hidden)
    }

    @Test
    fun `a folder whose name only starts the same isn't hidden`() {
        val other = file(9, "/s/WhatsApp/Images2/e.jpg")
        val albums = buildFolderAlbums(listOf(other), emptySet(), emptySet(), AlbumSort.NAME, hidden = setOf("/s/WhatsApp/Images"))
        assertFalse(albums.single().isHiddenFromHome)
    }

    @Test
    fun `recently added and on this day leave out what's off Home, and asking by kind doesn't`() {
        val facts = mapOf(
            1 to MediaFacts(100, 100, now, "image/jpeg"),
            2 to MediaFacts(100, 100, now, "image/jpeg"),
            3 to MediaFacts(100, 100, now, "image/jpeg"),
            4 to MediaFacts(100, 100, now, "image/png")
        )
        val hidden = setOf("/s/WhatsApp/Images", "/s/Pictures/Screenshots")
        val recent = smartAlbumFiles(SmartKind.RECENTLY_ADDED, all, facts, emptyMap(), now, zone, hidden)
        assertEquals(listOf(1), recent.map { it.id })
        val screenshots = smartAlbumFiles(SmartKind.SCREENSHOTS, all, facts, emptyMap(), now, zone, hidden)
        assertEquals(listOf(4), screenshots.map { it.id })

        val counts = buildSmartAlbums(all, facts, emptyMap(), now, zone, hidden).associate { it.kind to it.count }
        assertEquals(1, counts[SmartKind.RECENTLY_ADDED])
        assertEquals(1, counts[SmartKind.SCREENSHOTS])
    }

    @Test
    fun `a chosen cover comes first and the rest stay behind it`() {
        val a = file(1, "/s/A/a.jpg", now)
        val b = file(2, "/s/A/b.jpg", now - 1000)
        val c = file(3, "/s/A/c.jpg", now - 2000)
        val album = buildFolderAlbums(listOf(a, b, c), emptySet(), emptySet(), AlbumSort.NAME, chosenCovers = mapOf("/s/A" to c.path)).single()
        assertEquals(listOf(3, 1, 2), album.covers.map { it.id })
    }

    @Test
    fun `a locked file can't be a cover, even if it was chosen`() {
        val a = file(1, "/s/A/a.jpg", now)
        val b = file(2, "/s/A/b.jpg", now - 1000, locked = true)
        val album = buildFolderAlbums(listOf(a, b), emptySet(), emptySet(), AlbumSort.NAME, chosenCovers = mapOf("/s/A" to b.path)).single()
        assertEquals(listOf(1), album.covers.map { it.id })
    }

    @Test
    fun `a chosen cover that has gone falls back to the newest`() {
        val a = file(1, "/s/A/a.jpg", now)
        val album = buildFolderAlbums(listOf(a), emptySet(), emptySet(), AlbumSort.NAME, chosenCovers = mapOf("/s/A" to "/s/A/gone.jpg")).single()
        assertEquals(listOf(1), album.covers.map { it.id })
    }

    @Test
    fun `tiles name what they can't show`() {
        assertEquals("GIF", tileBadge(file(1, "/s/party.gif")))
        assertEquals("RAW", tileBadge(file(1, "/s/shot.DNG")))
        assertNull(tileBadge(file(1, "/s/photo.jpg")))
        assertNull(tileBadge(file(1, "/s/clip.gif", type = "video")))
    }

    @Test
    fun `keywords come out one by one`() {
        val xmp = """
            <rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">A title</rdf:li></rdf:Alt></dc:title>
              <dc:subject><rdf:Bag><rdf:li>Beach, sunset</rdf:li><rdf:li>R&amp;R</rdf:li><rdf:li> </rdf:li></rdf:Bag></dc:subject>
            </rdf:Description>
        """.trimIndent()
        assertEquals(listOf("Beach, sunset", "R&R"), Xmp.keywords(xmp))
        assertEquals(emptyList<String>(), Xmp.keywords("<x:xmpmeta></x:xmpmeta>"))
    }

    @Test
    fun `a camera's name doesn't repeat its make`() {
        assertEquals("Canon EOS R5", MediaFormatting.cameraName("Canon", "Canon EOS R5"))
        assertEquals("Google Pixel 8", MediaFormatting.cameraName(" Google ", "Pixel 8"))
        assertEquals("Pixel 8", MediaFormatting.cameraName(null, "Pixel 8"))
        assertEquals("Google", MediaFormatting.cameraName("Google", "  "))
        assertNull(MediaFormatting.cameraName(null, null))
    }

    @Test
    fun `a partly filled array reads only what's in it`() {
        // Starts like a JPEG, with a segment that claims more than was read into the array.
        val data = ByteArray(64)
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00).copyInto(data)
        val source = ArrayByteSource(data, 6)
        assertEquals(6L, source.size)
        val into = ByteArray(10)
        assertEquals(6, source.read(0, into, 0, 10))
        assertEquals(0, source.read(6, into, 0, 10))
        assertTrue(JpegSegments.isJpeg(source))
        // Nothing past the end of what was read is believed.
        assertNull(JpegSegments.xmp(source))
    }

    @Test
    fun `frames are named for where they came from`() {
        assertEquals("clip_frame_7s.jpg", PhotoActions.frameName("clip", 7_400))
        assertEquals("clip_frame_1m05s.jpg", PhotoActions.frameName("clip", 65_000))
    }
}
