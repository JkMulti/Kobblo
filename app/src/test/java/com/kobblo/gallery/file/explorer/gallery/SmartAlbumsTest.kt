package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SmartAlbumsTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val now = at(2026, Calendar.NOVEMBER, 5)

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, 9, 0, 0)
        }.timeInMillis

    private fun file(
        id: Int,
        path: String,
        type: String = "image",
        moment: Long = now,
        pinned: Boolean = false,
        locked: Boolean = false,
        bytes: Long = 1L
    ) = FileItem(
        id = id,
        path = path,
        name = path.substringAfterLast('/'),
        type = type,
        kind = "media",
        size = "1 B",
        changed = "",
        owner = "Me",
        isPinned = pinned,
        isLocked = locked,
        lastModified = moment,
        sizeBytes = bytes
    )

    private fun matches(kind: SmartKind, f: FileItem, facts: MediaFacts? = null, flags: Int = 0) =
        matchesSmartAlbum(kind, f, facts, flags, SmartClock(now, zone))

    @Test
    fun `camera means the camera's own folders`() {
        assertTrue(isCameraFile("/sdcard/DCIM/Camera/a.jpg", "a.jpg"))
        assertTrue(isCameraFile("/sdcard/DCIM/a.jpg", "a.jpg"))
        assertTrue(isCameraFile("/sdcard/DCIM/100ANDRO/a.jpg", "a.jpg"))
        assertTrue(isCameraFile("/sdcard/Pictures/Camera/a.jpg", "a.jpg"))
        assertFalse(isCameraFile("/sdcard/DCIM/Screenshots/a.jpg", "a.jpg"))
        assertFalse(isCameraFile("/sdcard/DCIM/Facebook/a.jpg", "a.jpg"))
        assertFalse(isCameraFile("/sdcard/Download/a.jpg", "a.jpg"))
    }

    @Test
    fun `screenshots are found by folder or name`() {
        assertTrue(isScreenshotFile("/sdcard/Pictures/Screenshots/a.png", "a.png"))
        assertTrue(isScreenshotFile("/sdcard/DCIM/screen_shots/a.png", "a.png"))
        assertTrue(isScreenshotFile("/sdcard/Download/Screenshot_2026.png", "Screenshot_2026.png"))
        assertFalse(isScreenshotFile("/sdcard/Pictures/Holiday/beach.png", "beach.png"))
    }

    @Test
    fun `gifs and raw are found by type or extension`() {
        assertTrue(isGifFile("a.GIF", null))
        assertTrue(isGifFile("a", "image/gif"))
        assertFalse(isGifFile("a.png", "image/png"))
        assertTrue(isRawFile("a.dng", null))
        assertTrue(isRawFile("a.CR3", null))
        assertTrue(isRawFile("a.bin", "image/x-adobe-dng"))
        assertFalse(isRawFile("a.jpg", "image/jpeg"))
    }

    @Test
    fun `a panorama is declared or very wide`() {
        assertTrue(isPanorama(3000, 600, 0))
        assertTrue(isPanorama(4000, 2000, ProbeFlags.PANORAMA))
        assertFalse(isPanorama(4000, 2000, 0))
        // A small banner isn't a panorama, and neither is a tall screenshot.
        assertFalse(isPanorama(600, 100, 0))
        assertFalse(isPanorama(1080, 2400, 0))
    }

    @Test
    fun `a screenshot never counts as a panorama`() {
        val wide = file(1, "/sdcard/Pictures/Screenshots/wide.png")
        assertFalse(matches(SmartKind.PANORAMAS, wide, MediaFacts(3000, 600, 0, "image/png")))
    }

    @Test
    fun `motion photos are found by name or a look inside`() {
        assertTrue(isMotionPhotoName("MVIMG_2026.jpg"))
        assertTrue(isMotionPhotoName("PXL_2026.MP.jpg"))
        assertFalse(isMotionPhotoName("IMG_2026.jpg"))
        assertTrue(matches(SmartKind.MOTION_PHOTOS, file(1, "/sdcard/DCIM/IMG.jpg"), flags = ProbeFlags.MOTION_PHOTO))
        assertFalse(matches(SmartKind.MOTION_PHOTOS, file(2, "/sdcard/DCIM/IMG.jpg")))
    }

    @Test
    fun `recently added looks back thirty days`() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(isRecentlyAdded(now - 29 * day, now))
        assertFalse(isRecentlyAdded(now - 31 * day, now))
        assertFalse(isRecentlyAdded(0L, now))
    }

    @Test
    fun `on this day is the same date in an earlier year`() {
        val clock = SmartClock(now, zone)
        assertTrue(clock.isOnThisDay(at(2024, Calendar.NOVEMBER, 5)))
        assertFalse(clock.isOnThisDay(at(2026, Calendar.NOVEMBER, 5)))
        assertFalse(clock.isOnThisDay(at(2024, Calendar.NOVEMBER, 6)))
        assertFalse(clock.isOnThisDay(at(2024, Calendar.OCTOBER, 5)))
    }

    @Test
    fun `videos don't leak into photo albums`() {
        val clip = file(1, "/sdcard/Movies/clip.gif", type = "video")
        assertFalse(matches(SmartKind.GIFS, clip))
        assertTrue(matches(SmartKind.VIDEOS, clip))
        assertFalse(matches(SmartKind.VIDEOS, file(2, "/sdcard/a.jpg")))
    }

    @Test
    fun `the probe reads XMP and the end of the file`() {
        val empty = ByteArray(0)
        fun head(text: String) = text.toByteArray(Charsets.US_ASCII)
        val pano = head("xx<rdf:Description GPano:ProjectionType=\"equirectangular\"/>")
        assertEquals(ProbeFlags.PANORAMA, MediaProbe.inspect(pano, pano.size, empty, 0))
        val motion = head("<x GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"/>")
        assertEquals(ProbeFlags.MOTION_PHOTO, MediaProbe.inspect(motion, motion.size, empty, 0))
        val legacy = head("<x GCamera:MicroVideo=\"1\"/>")
        assertEquals(ProbeFlags.MOTION_PHOTO, MediaProbe.inspect(legacy, legacy.size, empty, 0))
        val tail = head("....MotionPhoto_Data")
        assertEquals(ProbeFlags.MOTION_PHOTO, MediaProbe.inspect(empty, 0, tail, tail.size))
        val notMotion = head("<x GCamera:MotionPhoto=\"0\"/>")
        assertEquals(0, MediaProbe.inspect(notMotion, notMotion.size, empty, 0))
    }

    @Test
    fun `the probe ignores bytes past the length it read`() {
        val data = "junkGPano:".toByteArray(Charsets.US_ASCII)
        assertEquals(0, MediaProbe.inspect(data, 5, ByteArray(0), 0))
    }

    @Test
    fun `folder albums count, cover and order`() {
        val files = listOf(
            file(1, "/sdcard/DCIM/Camera/new.jpg", moment = 300),
            file(2, "/sdcard/DCIM/Camera/old.jpg", moment = 100),
            file(3, "/sdcard/Pictures/Trips/a.jpg", moment = 200),
            file(4, "/sdcard/Pictures/Secret/a.jpg", moment = 400, locked = true)
        )
        val byDate = buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.DATE)
        assertEquals(listOf("Secret", "Camera", "Trips"), byDate.map { it.name })
        val camera = byDate.first { it.name == "Camera" }
        assertEquals(2, camera.count)
        assertEquals(1, camera.cover?.id)
        // A locked file is never the picture on an album.
        assertNull(byDate.first { it.name == "Secret" }.cover)

        val byName = buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.NAME)
        assertEquals(listOf("Camera", "Secret", "Trips"), byName.map { it.name })
        val byCount = buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.COUNT)
        assertEquals("Camera", byCount.first().name)
    }

    @Test
    fun `an album keeps a few newest files to fall back on for its cover`() {
        val files = (1..(MaxCoverCandidates + 4)).map { file(it, "/sdcard/DCIM/Camera/$it.jpg", moment = it.toLong()) }.shuffled(java.util.Random(3))
        val album = buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.DATE).single()
        val newest = (MaxCoverCandidates + 4 downTo 5).toList()
        assertEquals(newest, album.covers.map { it.id })
        assertEquals(newest.first(), album.cover?.id)
    }

    @Test
    fun `locked files are never a fallback cover either`() {
        val files = listOf(
            file(1, "/sdcard/DCIM/Camera/a.jpg", moment = 300, locked = true),
            file(2, "/sdcard/DCIM/Camera/b.jpg", moment = 200),
            file(3, "/sdcard/DCIM/Camera/c.jpg", moment = 100)
        )
        assertEquals(listOf(2, 3), buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.DATE).single().covers.map { it.id })
    }

    @Test
    fun `pinned albums lead and a new album shows while empty`() {
        val files = listOf(file(1, "/sdcard/DCIM/Camera/a.jpg", moment = 300), file(2, "/sdcard/Pictures/Trips/a.jpg", moment = 100))
        val albums = buildFolderAlbums(files, setOf("/sdcard/Pictures/Trips"), setOf("/sdcard/Pictures/New"), AlbumSort.DATE)
        assertEquals(listOf("Trips", "New", "Camera"), albums.map { it.name })
        assertEquals(0, albums[1].count)
        assertNull(albums[1].cover)
    }

    @Test
    fun `the storage root gets a real name`() {
        assertEquals("Internal Storage", albumNameFor("/storage/emulated/0", "Internal Storage"))
        assertEquals("Trips", albumNameFor("/storage/emulated/0/Pictures/Trips", "Internal Storage"))
    }

    @Test
    fun `smart albums only list what has something in it`() {
        val files = listOf(
            file(1, "/sdcard/DCIM/Camera/a.jpg", pinned = true),
            file(2, "/sdcard/Movies/b.mp4", type = "video"),
            file(3, "/sdcard/DCIM/Camera/c.jpg", locked = true)
        )
        val albums = buildSmartAlbums(files, emptyMap(), emptyMap(), now, zone)
        assertEquals(listOf(SmartKind.FAVORITES, SmartKind.CAMERA, SmartKind.VIDEOS), albums.map { it.kind })
        assertEquals(2, albums.first { it.kind == SmartKind.CAMERA }.count)
    }

    @Test
    fun `album names lose what a folder can't hold`() {
        assertEquals("Trips", sanitizeAlbumName("  Trips  "))
        assertEquals("ab", sanitizeAlbumName("a/b"))
        assertEquals("Hidden", sanitizeAlbumName("..Hidden"))
        assertNull(sanitizeAlbumName("   "))
        assertNull(sanitizeAlbumName("///"))
    }

    @Test
    fun `a taken name gets a number and nothing is overwritten`() {
        val taken = setOf("a.jpg", "a (1).jpg", "noext")
        assertEquals("b.jpg", freeFileName("b.jpg") { it in taken })
        assertEquals("a (2).jpg", freeFileName("a.jpg") { it in taken })
        assertEquals("noext (1)", freeFileName("noext") { it in taken })
    }

    @Test
    fun `the widget never shows locked or hidden files`() {
        val locked = setOf("/sdcard/DCIM/secret.jpg")
        assertTrue(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/sdcard/DCIM/a.jpg", locked))
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/sdcard/DCIM/secret.jpg", locked))
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/sdcard/DCIM/.a.jpg", locked))
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/sdcard/.private/a.jpg", locked))
        assertFalse(com.kobblo.gallery.file.explorer.gallery.widget.isWidgetEligible("/sdcard/.kobblo_trash/a.jpg", locked))
    }

    @Test
    fun `library stats add up by kind, type and folder`() {
        val files = listOf(
            file(1, "/sdcard/DCIM/a.jpg", bytes = 100),
            file(2, "/sdcard/DCIM/b.PNG", bytes = 50),
            file(3, "/sdcard/Movies/c.mp4", type = "video", bytes = 1000)
        )
        val stats = buildLibraryStats(files)
        assertEquals(2, stats.photos.count)
        assertEquals(150L, stats.photos.bytes)
        assertEquals(1000L, stats.videos.bytes)
        assertEquals(1150L, stats.totalBytes)
        assertEquals(listOf("MP4", "JPG", "PNG"), stats.types.map { it.label })
        assertEquals(listOf("Movies", "DCIM"), stats.folders.map { it.label })
    }
}
