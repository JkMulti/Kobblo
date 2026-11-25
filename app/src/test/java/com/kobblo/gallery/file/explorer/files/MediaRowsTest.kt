package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date
import java.util.Locale

class MediaRowsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val locale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.US)

    @After
    fun tearDown() = Locale.setDefault(locale)

    // Playing times

    @Test
    fun `a tile's playing time is as short as it can be`() {
        assertEquals("0:05", PlayingTime.short(5_999))
        assertEquals("1:05", PlayingTime.short(65_000))
        assertEquals("59:59", PlayingTime.short(3_599_000))
        assertEquals("1:02:05", PlayingTime.short(3_725_000))
        assertNull(PlayingTime.short(0))
        assertNull(PlayingTime.short(-1))
    }

    @Test
    fun `details write a playing time in full`() {
        assertEquals("00:00", PlayingTime.padded(0))
        assertEquals("01:05", PlayingTime.padded(65_000))
        assertEquals("01:02:05", PlayingTime.padded(3_725_000))
        assertEquals("100:00:00", PlayingTime.padded(360_000_000))
    }

    // Details

    private fun reader(opened: MutableList<String> = ArrayList()) = DetailsReader(
        ofPicture = { opened += "picture"; MediaFacts(resolution = "4032 x 3024") },
        ofRecording = { opened += "recording"; MediaFacts(resolution = "1920 x 1080", duration = "01:05") }
    )

    @Test
    fun `a picture's details`() {
        val file = temp.root.with("Photo.JPG", "12345")
        val details = reader().read(file.path)
        assertEquals("Photo.JPG", details.name)
        assertEquals(file.path, details.path)
        assertEquals("5 B", details.size)
        assertEquals(DisplayDates.dateTime().format(Date(file.lastModified())), details.changed)
        assertEquals("4032 x 3024", details.resolution)
        assertEquals("JPG", details.format)
        assertNull(details.duration)
        assertNull(details.itemCount)
        assertFalse(details.isFolder)
    }

    @Test
    fun `only what says something is opened`() {
        val opened = ArrayList<String>()
        for (name in listOf("a.png", "b.mkv", "c.ogg", "d.txt", "e.gif", "noextension")) reader(opened).read(temp.root.with(name).path)
        assertEquals(listOf("picture", "recording", "recording"), opened)
        assertNull(reader().read(temp.root.resolve("noextension").path).format)
    }

    @Test
    fun `a folder's details count everything in it, and never open it`() {
        val folder = temp.newFolder("Trip.mp4")
        folder.with("a.txt")
        folder.with(".hidden")
        val opened = ArrayList<String>()
        val details = reader(opened).read(folder.path)
        assertTrue(details.isFolder)
        assertEquals(2, details.itemCount)
        assertEquals("0 B", details.size)
        assertEquals("MP4", details.format)
        assertEquals(emptyList<String>(), opened)
    }

    // The gallery's feed

    private fun row(
        path: String,
        taken: Long = 0L,
        modified: Long = 0L,
        added: Long = 0L,
        name: String? = path.substringAfterLast('/'),
        plays: Long = 0L,
        id: Int = path.hashCode()
    ) = MediaRow(id, path, name, 2048, taken, modified, added, plays)

    private fun feed(showHidden: Boolean = false, marks: PathMarks = marks()) = GalleryFeedRows(showHidden, marks)

    @Test
    fun `the feed is the newest first, by when a photo was taken if that's known`() {
        val feed = feed()
        feed.add(row("/s/modified.jpg", modified = 1_500_000_000), "image")
        feed.add(row("/s/taken.jpg", taken = 1_600_000_000_000, modified = 1_000), "image")
        feed.add(row("/s/new.jpg", added = 1_700_000_000), "image")
        val items = feed.newestFirst()
        assertEquals(listOf("new.jpg", "taken.jpg", "modified.jpg"), items.map { it.name })
        assertEquals(listOf(1_700_000_000_000, 1_600_000_000_000, 1_500_000_000_000), items.map { it.lastModified })
        assertEquals("Only what has no date needs a scan", listOf("/s/new.jpg"), feed.unscanned)
    }

    @Test
    fun `a row says what it is`() {
        val feed = feed(marks = marks(favorites = setOf("/s/a.mp4"), locks = setOf("/s")))
        feed.add(row("/s/a.mp4", taken = 1_600_000_000_000, plays = 65_000, id = 42), "video")
        val item = feed.newestFirst().single()
        assertEquals(42, item.id)
        assertEquals("video", item.type)
        assertEquals("media", item.kind)
        assertEquals("2.0 kB", item.size)
        assertEquals("1:05", item.duration)
        assertTrue(item.isPinned)
        assertTrue(item.isLocked)
    }

    @Test
    fun `hidden folders stay out, and the Bin always does`() {
        fun pathsWith(showHidden: Boolean) = feed(showHidden).let { feed ->
            feed.add(row("/s/a.jpg"), "image")
            feed.add(row("/s/.hidden/b.jpg"), "image")
            feed.add(row("/s/.c.jpg"), "image")
            feed.add(row("/s/.kobblo_trash/d.jpg"), "image")
            feed.newestFirst().map { it.path }.toSet()
        }
        assertEquals(setOf("/s/a.jpg"), pathsWith(showHidden = false))
        assertEquals(setOf("/s/a.jpg", "/s/.hidden/b.jpg", "/s/.c.jpg"), pathsWith(showHidden = true))
    }

    @Test
    fun `a path is there once`() {
        val feed = feed()
        feed.add(row("/s/a.jpg", id = 1), "image")
        feed.add(row("/s/a.jpg", id = 2), "image")
        assertEquals(1, feed.newestFirst().single().id)
    }

    @Test
    fun `TypeScript isn't a video`() {
        val feed = feed()
        feed.add(row(temp.root.with("app.ts", "export const answer = 42").path), "video")
        feed.add(row(temp.root.resolve("unreadable.ts").path), "video")
        assertEquals(listOf("unreadable.ts"), feed.newestFirst().map { it.name })
    }

    @Test
    fun `a table of anything goes by name`() {
        val feed = feed()
        feed.add(row("/s/art.tga"), null)
        feed.add(row("/s/old.rmvb"), null)
        feed.add(row("/s/unnamed.tga", name = null), null)
        assertEquals(mapOf("art.tga" to "image", "old.rmvb" to "video", "unnamed.tga" to "image"), feed.newestFirst().associate { it.name to it.type })
    }

    @Test
    fun `photos of one day share its label`() {
        val feed = feed()
        feed.add(row("/s/a.jpg", taken = 1_600_000_000_000), "image")
        feed.add(row("/s/b.jpg", taken = 1_600_000_001_000), "image")
        val (b, a) = feed.newestFirst()
        assertSame(a.changed, b.changed)
        assertEquals(DisplayDates.date().format(Date(1_600_000_000_000)), a.changed)
    }
}
