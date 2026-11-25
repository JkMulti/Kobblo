package com.kobblo.gallery.file.explorer

import com.kobblo.gallery.file.explorer.ui.expressive.groupByDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class StorageScanTest {

    @Test
    fun `a file counts in one category, and large on top of that`() {
        val tally = StorageTally()
        tally.add("/sdcard/DCIM/a.JPG", 100)
        tally.add("/sdcard/Movies/b.mp4", 60L * 1024 * 1024)
        tally.add("/sdcard/Music/c.flac", 10)
        tally.add("/sdcard/Documents/d.pdf", 20)
        tally.add("/sdcard/Download/e.apk", 30)
        tally.add("/sdcard/Download/f.tar.gz", 40)
        tally.add("/sdcard/Download/no.dot.folder/plain", 70L * 1024 * 1024)
        assertEquals(100L, tally.images)
        assertEquals(60L * 1024 * 1024, tally.videos)
        assertEquals(10L, tally.audio)
        assertEquals(20L, tally.documents)
        assertEquals(30L, tally.apps)
        assertEquals(40L, tally.archives)
        assertEquals(130L * 1024 * 1024, tally.large)
    }

    @Test
    fun `what isn't in a category is left over as other`() {
        val tally = StorageTally()
        tally.add("a.jpg", 300)
        tally.add("b.bin", 999)
        assertEquals(700L, tally.otherBytes(1000))
        assertEquals(0L, tally.otherBytes(100))
    }

    @Test
    fun `a name without a category has none`() {
        assertNull(StorageCategory.of("/sdcard/a.folder/readme"))
        assertNull(StorageCategory.of("noextension"))
        assertEquals(StorageCategory.Videos, StorageCategory.of("/sdcard/Movies/clip.MKV"))
    }

    @Test
    fun `an index query and a file's name agree on its category`() {
        val all = StorageCategory.entries.flatMap { it.extensions }
        assertEquals("no extension may count twice", all.size, all.toSet().size)
        StorageCategory.entries.forEach { category ->
            category.extensions.forEach { ext ->
                assertEquals(category, StorageCategory.of("/sdcard/a/file.$ext"))
                assertTrue(category.indexSelection().contains("_data LIKE '%.$ext'"))
            }
            assertTrue(category.indexSelection().contains("_data NOT LIKE '%/.%'"))
        }
    }

    @Test
    fun `the system's word beats the name for media, and the name decides the rest`() {
        // MediaStore's numbers: 1 is an image, 2 is audio, 3 is video.
        assertEquals(StorageCategory.Audio, StorageCategory.ofRow(2, "/sdcard/voice.3gp"))
        assertEquals(StorageCategory.Videos, StorageCategory.ofRow(3, "/sdcard/clip.mp4"))
        assertEquals(StorageCategory.Videos, StorageCategory.ofRow(0, "/sdcard/clip.3gp"))
        assertEquals(StorageCategory.Images, StorageCategory.ofRow(0, "/sdcard/art.tga"))
        assertEquals(StorageCategory.Documents, StorageCategory.ofRow(6, "/sdcard/notes.txt"))
        assertNull(StorageCategory.ofRow(0, "/sdcard/plain.bin"))
        assertTrue(StorageCategory.Images.indexSelection().contains("media_type = 1 OR"))
        assertTrue(StorageCategory.Audio.indexSelection().contains("media_type = 2 OR"))
        assertTrue(StorageCategory.Videos.indexSelection().contains("media_type = 3 OR"))
        assertTrue(!StorageCategory.Documents.indexSelection().contains("media_type ="))
    }

    @Test
    fun `only shared sizes are duplicate candidates`() {
        val candidates = DuplicateCandidates()
        // More than the array starts with, so growing it is covered too.
        repeat(5_000) { candidates.add("/unique/$it", 10_000L + it) }
        candidates.add("/a", 5_000)
        candidates.add("/b", 7_000)
        candidates.add("/c", 5_000)
        candidates.add("/d", 7_000)
        candidates.add("/e", 7_000)
        val seen = ArrayList<Pair<Long, Set<String>>>()
        candidates.forEachSharedSize { size, paths -> seen.add(size to paths.toSet()) }
        assertEquals(listOf(5_000L to setOf("/a", "/c"), 7_000L to setOf("/b", "/d", "/e")), seen)
    }

    @Test
    fun `no candidates means no calls`() {
        DuplicateCandidates().forEachSharedSize { _, _ -> throw AssertionError("nothing to compare") }
    }

    @Test
    fun `one day shares one label`() {
        val labels = DayLabels()
        val noon = 1_700_000_000_000L
        val morning = labels.labelFor(noon)
        assertSame(morning, labels.labelFor(noon + 1000))
        assertEquals(com.kobblo.gallery.file.explorer.data.core.DisplayDates.date().format(java.util.Date(noon)), morning)
    }

    private fun file(id: Int, modified: Long) =
        FileItem(id, "/f$id", "f$id", "file", "file", "", "", "Me", lastModified = modified)

    @Test
    fun `files group by day, keeping their order`() {
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        val files = listOf(
            file(1, now),
            file(2, now - 1000),
            file(3, now - day),
            file(4, now - 40 * day),
            file(5, now - 40 * day - 1000),
            file(6, 0)
        )
        val groups = groupByDay(files, now, "Today", "Yesterday", "Earlier")
        val keys = groups.keys.toList()
        assertEquals("Today", keys[0])
        assertEquals("Yesterday", keys[1])
        assertEquals("Earlier", keys.last())
        assertEquals(listOf(1, 2), groups.getValue("Today").map { it.id })
        assertEquals(listOf(3), groups.getValue("Yesterday").map { it.id })
        assertEquals(listOf(4, 5), groups.getValue(keys[2]).map { it.id })
    }

    @Test
    fun `grouping a huge list is quick`() {
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        val files = List(100_000) { file(it, now - it * (day / 30)) }
        val started = System.nanoTime()
        val groups = groupByDay(files, now, "Today", "Yesterday", "Earlier")
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(100_000, groups.values.sumOf { it.size })
        assertTrue("took $millis ms", millis < 2_000)
    }
}
