package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.SortMode
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileListLogicTest {
    private fun file(id: Int, modified: Long = 0, type: String = "file", name: String = "f$id") =
        FileItem(id, "/storage/emulated/0/$name", name, type, type, "", "", "Me", lastModified = modified)

    // ── What a search puts in place of the list ──

    @Test
    fun `typing or a narrowing filter shows results`() {
        assertEquals(SearchPane.Results, searchPane(true, query = "a", filtersNarrow = false, fieldFocused = false, hasRecentSearches = false))
        assertEquals(SearchPane.Results, searchPane(true, query = "", filtersNarrow = true, fieldFocused = true, hasRecentSearches = true))
    }

    @Test
    fun `a focused, empty field offers recent searches when there are some`() {
        assertEquals(SearchPane.RecentSearches, searchPane(true, query = "", filtersNarrow = false, fieldFocused = true, hasRecentSearches = true))
        assertEquals(SearchPane.None, searchPane(true, query = "", filtersNarrow = false, fieldFocused = true, hasRecentSearches = false))
        assertEquals(SearchPane.None, searchPane(true, query = "", filtersNarrow = false, fieldFocused = false, hasRecentSearches = true))
    }

    @Test
    fun `away from the phone the list always stays`() {
        assertEquals(SearchPane.None, searchPane(false, query = "a", filtersNarrow = true, fieldFocused = true, hasRecentSearches = true))
    }

    // ── Recents ──

    private val zone = TimeZone.getTimeZone("UTC")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Calendar =
        GregorianCalendar(zone, Locale.US).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }

    @Test
    fun `recency turns over at midnight`() {
        val now = at(2024, 3, 10, 15)
        assertEquals(Recency.TODAY, recencyOf(at(2024, 3, 10, 0).timeInMillis, now))
        assertEquals(Recency.YESTERDAY, recencyOf(at(2024, 3, 9, 23, 59).timeInMillis, now))
        assertEquals(Recency.YESTERDAY, recencyOf(at(2024, 3, 9, 0).timeInMillis, now))
        assertEquals(Recency.WEEK, recencyOf(at(2024, 3, 8, 23, 59).timeInMillis, now))
        assertEquals(Recency.WEEK, recencyOf(at(2024, 3, 3, 0).timeInMillis, now))
        assertEquals(Recency.EARLIER, recencyOf(at(2024, 3, 2, 23, 59).timeInMillis, now))
        assertEquals(Recency.EARLIER, recencyOf(0, now))
    }

    @Test
    fun `asking for a recency leaves the calendar as it was`() {
        val now = at(2024, 3, 10, 15)
        val before = now.timeInMillis
        recencyOf(before - 1, now)
        assertEquals(before, now.timeInMillis)
    }

    @Test
    fun `recents are files only, newest first, in the order of their buckets`() {
        val now = at(2024, 3, 10, 15)
        val today = at(2024, 3, 10, 9).timeInMillis
        val lastYear = at(2023, 1, 1, 9).timeInMillis
        val files = listOf(
            file(1, lastYear),
            file(2, today),
            file(3, today + 1000),
            file(4, today, type = "folder"),
            file(5, today + 2000, name = ".secret")
        )
        val groups = recentGroups(files, showHidden = false, now = now)
        assertEquals(listOf(Recency.TODAY, Recency.EARLIER), groups.keys.toList())
        assertEquals(listOf(3, 2), groups.getValue(Recency.TODAY).map { it.id })
        assertEquals(listOf(1), groups.getValue(Recency.EARLIER).map { it.id })

        val withHidden = recentGroups(files, showHidden = true, now = now)
        assertEquals(listOf(5, 3, 2), withHidden.getValue(Recency.TODAY).map { it.id })
    }

    @Test
    fun `a file's position counts the headings above it`() {
        val groups = listOf(listOf(file(1), file(2)), listOf(file(3)), listOf(file(4), file(5)))
        fun positionOf(id: Int) = positionAmongGroups(groups, "/storage/emulated/0/f$id")
        assertEquals(1, positionOf(1))
        assertEquals(2, positionOf(2))
        assertEquals(4, positionOf(3))
        assertEquals(6, positionOf(4))
        assertEquals(7, positionOf(5))
        assertEquals(-1, positionAmongGroups(groups, "/elsewhere"))
        assertEquals(-1, positionAmongGroups(emptyList(), "/storage/emulated/0/f1"))
    }

    // ── Filters and sorting ──

    @Test
    fun `a filter is understood in either spelling`() {
        assertEquals(KindFilter.Images, KindFilter.of("image"))
        assertEquals(KindFilter.Images, KindFilter.of("images"))
        assertEquals(KindFilter.Audio, KindFilter.of("audio"))
        assertEquals(KindFilter.Documents, KindFilter.of("docs"))
        assertEquals(KindFilter.Apps, KindFilter.of("apk"))
        assertEquals(KindFilter.Apps, KindFilter.of("apps"))
        assertEquals(KindFilter.Archives, KindFilter.of("archives"))
        assertNull(KindFilter.of("all"))
        assertNull(KindFilter.of("documents"))
    }

    @Test
    fun `the filters to pick from are saved under these keys`() {
        assertEquals(listOf("image", "video", "audio", "doc", "apk", "archive"), KindFilter.entries.map { it.key })
    }

    @Test
    fun `picking another sort closes the sheet, and so does any pick away from the phone`() {
        assertTrue(closesAfterSort(SortMode.DATE, current = SortMode.ALPHABETICAL, location = "/storage/emulated/0"))
        assertTrue(closesAfterSort(SortMode.DATE, current = SortMode.DATE, location = "drive_id:1"))
    }

    @Test
    fun `picking the sort that is on keeps the sheet open on the phone, to show its new direction`() {
        assertFalse(closesAfterSort(SortMode.DATE, current = SortMode.DATE, location = "/storage/emulated/0/DCIM"))
    }

    // ── Categories ──

    @Test
    fun `a category is known by any of its keys, in any letter case`() {
        assertEquals(FileCategory.Images, FileCategory.of("images"))
        assertEquals(FileCategory.Documents, FileCategory.of("Documents"))
        assertEquals(FileCategory.Apps, FileCategory.of("APK"))
        assertEquals(FileCategory.Downloads, FileCategory.of("download"))
        assertEquals(FileCategory.LargeFiles, FileCategory.of("other files"))
        assertEquals(FileCategory.Duplicates, FileCategory.of("duplicate"))
        assertEquals(FileCategory.EmptyFolders, FileCategory.of("empty_folders"))
        assertNull(FileCategory.of("screenshots"))
    }

    @Test
    fun `an unknown category is titled by its key with a capital`() {
        assertEquals("Screenshots", titleFromKey("screenshots"))
        assertEquals("Screenshots", titleFromKey("Screenshots"))
        assertEquals("", titleFromKey(""))
    }

    @Test
    fun `cards fill lines, and the last line takes what is left`() {
        val files = List(7) { file(it) }
        assertEquals(3, cardRowCount(7, columns = 3))
        assertEquals(2, cardRowCount(6, columns = 3))
        assertEquals(0, cardRowCount(0, columns = 3))
        assertEquals(listOf(0, 1, 2), cardRow(files, row = 0, columns = 3).map { it.id })
        assertEquals(listOf(3, 4, 5), cardRow(files, row = 1, columns = 3).map { it.id })
        assertEquals(listOf(6), cardRow(files, row = 2, columns = 3).map { it.id })
    }

    @Test
    fun `every line of cards is found by its first file`() {
        val groups = listOf(List(3) { file(it) }, List(2) { file(10 + it) })
        val rows = cardRowsByKey(groups, columns = 2)
        assertEquals(setOf<Any>("cards_0", "cards_2", "cards_10"), rows.keys)
        assertEquals(listOf(0, 1), rows.getValue("cards_0").map { it.id })
        assertEquals(listOf(2), rows.getValue("cards_2").map { it.id })
        assertEquals(listOf(10, 11), rows.getValue("cards_10").map { it.id })
        assertEquals("cards_10", cardRowKey(file(10)))
    }
}
