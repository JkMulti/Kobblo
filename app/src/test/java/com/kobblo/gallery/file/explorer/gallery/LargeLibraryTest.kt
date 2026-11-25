package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.similar.PhotoPrint
import com.kobblo.gallery.file.explorer.gallery.similar.groupPhotos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * The work that runs over the whole library, at the size it has to survive. The limits are generous,
 * because this is a laptop and not a phone: they catch something going quadratic, not a slow day.
 */
class LargeLibraryTest {
    private val count = 100_000
    private val zone = TimeZone.getTimeZone("UTC")
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private val files = (0 until count).map { i ->
        FileItem(
            id = i,
            path = "/storage/emulated/0/DCIM/Folder${i % 100}/IMG_$i.jpg",
            name = "IMG_$i.jpg",
            type = if (i % 20 == 0) "video" else "image",
            kind = "media",
            size = "",
            changed = "",
            owner = "Me",
            isPinned = i % 1000 == 0,
            sizeBytes = 1000L + i,
            // Newest first, spread over nine years.
            lastModified = now - i * (9 * 365 * day / count)
        )
    }

    @Test
    fun `grouping the feed stays quick`() {
        lateinit var feed: GalleryFeed
        val took = measureTimeMillis {
            feed = buildGalleryFeed(files, SectionLabels("Today", "Yesterday"), now, Locale.US, zone)
        }
        println("buildGalleryFeed($count): ${took}ms, ${feed.sections.size} sections")
        assertEquals(count, feed.files.size)
        assertTrue("took ${took}ms", took < 2_000)
    }

    @Test
    fun `mapping grid indexes stays quick`() {
        val feed = buildGalleryFeed(files, SectionLabels("Today", "Yesterday"), now, Locale.US, zone)
        val took = measureTimeMillis {
            for (grid in 0 until feed.gridItemCount) {
                val file = feed.fileIndexForGridIndex(grid)
                if (file >= 0) assertEquals(grid, feed.gridIndexForFileIndex(file))
            }
        }
        println("grid index round trips for $count: ${took}ms")
        assertTrue("took ${took}ms", took < 2_000)
    }

    @Test
    fun `counting albums stays quick`() {
        val facts = files.associate { it.id to MediaFacts(4000, 3000, now - it.id * 1000L, "image/jpeg") }
        val folders: List<FolderAlbum>
        val smart: List<SmartAlbum>
        val foldersTook = measureTimeMillis {
            folders = buildFolderAlbums(files, emptySet(), emptySet(), AlbumSort.DATE)
        }
        val smartTook = measureTimeMillis {
            smart = buildSmartAlbums(files, facts, emptyMap(), now, zone)
        }
        println("buildFolderAlbums($count): ${foldersTook}ms, buildSmartAlbums($count): ${smartTook}ms")
        assertEquals(100, folders.size)
        assertTrue(smart.isNotEmpty())
        assertTrue("took ${foldersTook}ms", foldersTook < 2_000)
        assertTrue("took ${smartTook}ms", smartTook < 5_000)
    }

    @Test
    fun `finding lookalikes never compares every pair`() {
        val random = Random(9)
        val prints = (0 until count).map { PhotoPrint(random.nextLong(), 4000, 3000, 100, 100, 100) }
        val took = measureTimeMillis { groupPhotos(prints, List(count) { null }) }
        println("groupPhotos($count): ${took}ms")
        // Every pair would be five billion comparisons, which wouldn't finish in anything like this.
        assertTrue("took ${took}ms", took < 15_000)
    }
}
