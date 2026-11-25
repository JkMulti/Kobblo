package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class GalleryFeedTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val labels = SectionLabels("Today", "Yesterday")

    /** Wednesday 4 November 2026, 12:00 UTC. */
    private val now = at(2026, Calendar.NOVEMBER, 4, 12)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    private fun file(id: Int, moment: Long, type: String = "image", locked: Boolean = false) = FileItem(
        id = id,
        path = "/sdcard/DCIM/$id.jpg",
        name = "$id.jpg",
        type = type,
        kind = "media",
        size = "1 B",
        changed = "",
        owner = "Me",
        isLocked = locked,
        lastModified = moment
    )

    private fun build(vararg files: FileItem) =
        buildGalleryFeed(files.toList(), labels, now, Locale.US, zone)

    @Test
    fun `recent days get their own names and older files group by month`() {
        val feed = build(
            file(1, at(2026, Calendar.NOVEMBER, 4)),
            file(2, at(2026, Calendar.NOVEMBER, 3)),
            file(3, at(2026, Calendar.NOVEMBER, 1)), // Sunday, three days ago
            file(4, at(2026, Calendar.OCTOBER, 29)), // Thursday, six days ago
            file(5, at(2026, Calendar.OCTOBER, 28)), // a week ago, so its month
            file(6, at(2026, Calendar.MARCH, 2)),
            file(7, at(2025, Calendar.MARCH, 2))
        )
        assertEquals(
            listOf("Today", "Yesterday", "Sunday", "Thursday", "October", "March", "March 2025"),
            feed.sections.map { it.title }
        )
    }

    @Test
    fun `a month split by the recent days keeps one heading`() {
        val feed = build(
            file(1, at(2026, Calendar.NOVEMBER, 4)),
            file(2, at(2026, Calendar.OCTOBER, 28)),
            file(3, at(2026, Calendar.OCTOBER, 2))
        )
        assertEquals(listOf("Today", "October"), feed.sections.map { it.title })
        assertEquals(listOf(2, 3), feed.sections[1].files.map { it.id })
    }

    @Test
    fun `files out of order still land under one heading each`() {
        val feed = build(
            file(1, at(2025, Calendar.MARCH, 2)),
            file(2, at(2026, Calendar.MARCH, 2)),
            file(3, at(2025, Calendar.MARCH, 20))
        )
        assertEquals(listOf("March 2025", "March"), feed.sections.map { it.title })
        assertEquals(listOf(1, 3, 2), feed.files.map { it.id })
    }

    @Test
    fun `grid indexes map to files and back`() {
        // Grid: [Today] 1 2 [Yesterday] 3 [October] 4 5 [footer]
        val feed = build(
            file(1, at(2026, Calendar.NOVEMBER, 4)),
            file(2, at(2026, Calendar.NOVEMBER, 4)),
            file(3, at(2026, Calendar.NOVEMBER, 3)),
            file(4, at(2026, Calendar.OCTOBER, 2)),
            file(5, at(2026, Calendar.OCTOBER, 1))
        )
        assertEquals(9, feed.gridItemCount)
        assertEquals(listOf(-1, 0, 1, -1, 2, -1, 3, 4, -1), (0 until 9).map { feed.fileIndexForGridIndex(it) })
        assertEquals(listOf(1, 2, 4, 6, 7), feed.files.indices.map { feed.gridIndexForFileIndex(it) })
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2, 2, 2), (0 until 9).map { feed.sectionIndexForGridIndex(it) })
    }

    @Test
    fun `an empty feed answers safely`() {
        val feed = buildGalleryFeed(emptyList(), labels, now, Locale.US, zone)
        assertEquals(-1, feed.sectionIndexForGridIndex(0))
        assertEquals(-1, feed.fileIndexForGridIndex(0))
    }

    @Test
    fun `dragging selects a run and restores what it leaves`() {
        val files = (0 until 6).map { file(it, now) }
        val base = setOf(0)
        assertEquals(setOf(0, 2, 3, 4), rangeSelection(base, files, anchorIndex = 2, currentIndex = 4, selecting = true))
        // Moving back to before the anchor gives up the far side.
        assertEquals(setOf(0, 1, 2), rangeSelection(base, files, anchorIndex = 2, currentIndex = 1, selecting = true))
        // Starting on a selected file clears instead.
        assertEquals(setOf(3), rangeSelection(setOf(0, 1, 2, 3), files, anchorIndex = 0, currentIndex = 2, selecting = false))
    }

    @Test
    fun `a drag skips locked files`() {
        val files = listOf(file(0, now), file(1, now, locked = true), file(2, now))
        assertEquals(setOf(0, 2), rangeSelection(emptySet(), files, 0, 2, selecting = true))
    }

    @Test
    fun `a header selects its section, then clears it`() {
        val feed = build(
            file(1, at(2026, Calendar.NOVEMBER, 4)),
            file(2, at(2026, Calendar.NOVEMBER, 4)),
            file(3, at(2026, Calendar.NOVEMBER, 4), locked = true),
            file(4, at(2026, Calendar.NOVEMBER, 3))
        )
        val today = feed.sections[0]
        val selected = toggleSection(setOf(4), today)
        assertEquals(setOf(1, 2, 4), selected)
        assertTrue(isSectionSelected(selected, today))
        assertFalse(isSectionSelected(setOf(1), today))
        assertEquals(setOf(4), toggleSection(selected, today))
    }

    @Test
    fun `thumbnail sizes come in three steps`() {
        assertEquals(540, thumbnailPx(1080, 2))
        assertEquals(360, thumbnailPx(1080, 3))
        assertEquals(360, thumbnailPx(1080, 4))
        assertEquals(216, thumbnailPx(1080, 5))
        assertEquals(216, thumbnailPx(1080, 6))
    }

    @Test
    fun `a burst fires after a pause or at the deadline`() {
        assertEquals(1700L, burstFireTime(1000, 1000, quietMillis = 700, maxWaitMillis = 4000))
        assertEquals(3700L, burstFireTime(1000, 3000, quietMillis = 700, maxWaitMillis = 4000))
        assertEquals(5000L, burstFireTime(1000, 4900, quietMillis = 700, maxWaitMillis = 4000))
    }

    @Test
    fun `a large library leaves a gap between reloads`() {
        assertEquals(1700L, burstFireTime(1000, 1000, 700, 4000, lastFire = 0, gapMillis = 0))
        assertEquals(20500L, burstFireTime(1000, 1000, 700, 4000, lastFire = 500, gapMillis = reloadGapMillis(100_000)))
        assertEquals(0L, reloadGapMillis(50))
        assertEquals(20_000L, reloadGapMillis(100_000))
    }

    @Test
    fun `no feed can hold a path twice`() {
        val photo = file(1, at(2026, Calendar.NOVEMBER, 4))
        // The same photo after being saved over: same path, new id, and possibly a new date and section.
        val savedOver = photo.copy(id = 2, lastModified = at(2026, Calendar.OCTOBER, 2))
        val feed = build(photo, savedOver, file(3, at(2026, Calendar.NOVEMBER, 3)), photo)
        assertEquals(listOf(1, 3), feed.files.map { it.id })
        assertEquals(feed.files.size, feed.files.map { it.path }.toSet().size)
        assertEquals(feed.files.size + feed.sections.size + 1, feed.gridItemCount)
    }

    @Test
    fun `the viewer's list holds each path once, and always the open photo`() {
        val open = file(1, now)
        val savedOver = open.copy(id = 2)
        val other = file(3, now)
        // The open item has an old id, and the list has its replacement. This used to make two tiles.
        assertEquals(listOf(2, 3), GalleryScope.viewerList(open, listOf(savedOver, other)).map { it.id })
        // Not in the list at all, so it's added.
        assertEquals(listOf(1, 3), GalleryScope.viewerList(open, listOf(other)).map { it.id })
        // Repeats in the list itself go too.
        assertEquals(listOf(3), GalleryScope.viewerList(other, listOf(other, other)).map { it.id })
    }

    @Test
    fun `swiping in the viewer doesn't reach a locked photo`() {
        val now = System.currentTimeMillis()
        val open = file(1, now)
        val locked = file(2, now, locked = true)
        val other = file(3, now)
        assertEquals(listOf(1, 3), GalleryScope.viewerList(open, listOf(open, locked, other)).map { it.id })
        // Opened with its password, it's there.
        assertEquals(listOf(1, 2, 3), GalleryScope.viewerList(locked, listOf(open, locked, other)).map { it.id })
    }

    @Test
    fun `a folder of unscanned files is one request, and nothing is asked twice`() {
        val asked = HashSet<String>()
        val many = (1..100).map { "/sdcard/Bulk/$it.jpg" }
        val few = listOf("/sdcard/DCIM/a.jpg", "/sdcard/DCIM/b.jpg")
        assertEquals(listOf("/sdcard/Bulk") + few, ScanRequests.next(many + few, asked))
        assertTrue(ScanRequests.next(many + few, asked).isEmpty())
    }
}
