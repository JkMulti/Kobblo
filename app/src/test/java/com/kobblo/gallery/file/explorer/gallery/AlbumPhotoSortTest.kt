package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPhotoSortTest {
    private val labels = SectionLabels("Today", "Yesterday")

    private fun file(name: String, moment: Long, bytes: Long = 1L, id: Int = name.hashCode()) = FileItem(
        id = id,
        path = "/sdcard/DCIM/$name",
        name = name,
        type = "image",
        kind = "media",
        size = "$bytes B",
        changed = "",
        owner = "Me",
        lastModified = moment,
        sizeBytes = bytes
    )

    private val newestFirst = listOf(
        file("img10.jpg", 3_000L, bytes = 5),
        file("IMG2.jpg", 2_000L, bytes = 900),
        file("img1.jpg", 1_000L, bytes = 5)
    )

    @Test
    fun `newest first keeps the list it was given`() {
        assertSame(newestFirst, orderedForAlbum(newestFirst, PhotoSort.NEWEST))
    }

    @Test
    fun `oldest first reverses by date`() {
        assertEquals(
            listOf("img1.jpg", "IMG2.jpg", "img10.jpg"),
            orderedForAlbum(newestFirst, PhotoSort.OLDEST).map { it.name }
        )
    }

    @Test
    fun `name sorts the way a person reads`() {
        assertEquals(
            listOf("img1.jpg", "IMG2.jpg", "img10.jpg"),
            orderedForAlbum(newestFirst, PhotoSort.NAME).map { it.name }
        )
    }

    @Test
    fun `size puts the largest first and settles ties by name`() {
        assertEquals(
            listOf("IMG2.jpg", "img1.jpg", "img10.jpg"),
            orderedForAlbum(newestFirst, PhotoSort.SIZE).map { it.name }
        )
    }

    @Test
    fun `date orders keep headings and the others have one untitled section`() {
        assertTrue(buildAlbumFeed(newestFirst, PhotoSort.OLDEST, labels).sections.all { it.title.isNotEmpty() })
        val flat = buildAlbumFeed(newestFirst, PhotoSort.NAME, labels)
        assertEquals(listOf(""), flat.sections.map { it.title })
        assertEquals(flat.files, flat.sections.single().files)
    }

    @Test
    fun `a flat feed maps grid positions like a dated one and has no repeated path`() {
        val repeat = file("img1.jpg", 1_000L)
        val flat = buildFlatFeed(newestFirst + repeat)
        assertEquals(3, flat.files.size)
        assertEquals(-1, flat.fileIndexForGridIndex(0))
        assertEquals(0, flat.fileIndexForGridIndex(1))
        assertEquals(3, flat.gridIndexForFileIndex(2))
        assertEquals(5, flat.gridItemCount)
    }

    @Test
    fun `an empty album stays empty`() {
        assertSame(GalleryFeed.Empty, buildAlbumFeed(emptyList(), PhotoSort.SIZE, labels))
    }

    @Test
    fun `a hundred thousand photos sort quickly enough to run on a background thread`() {
        val many = List(100_000) { file("IMG_${100_000 - it}.jpg", (100_000 - it).toLong(), id = it) }
        val started = System.nanoTime()
        val sorted = orderedForAlbum(many, PhotoSort.NAME)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("IMG_1.jpg", sorted.first().name)
        assertEquals("IMG_100000.jpg", sorted.last().name)
        assertTrue("took $tookMillis ms", tookMillis < 5_000)
    }
}
