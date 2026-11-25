package com.kobblo.gallery.file.explorer.gallery.places

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.gallery.PhotoDetails
import com.kobblo.gallery.file.explorer.gallery.favoritesAfterMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/** The real table that ships with the app, read from the source tree. */
class PlacesTest {
    private val table: PlaceTable = File("src/main/assets/places.bin").inputStream().use { PlaceTable.read(it) }

    private fun at(latitude: Double, longitude: Double): String? =
        table.nearest(latitude, longitude).takeIf { it >= 0 }?.let { "${table.name(it)}, ${table.countryCode(it)}" }

    @Test
    fun `the table holds what it should`() {
        assertTrue(table.size > 25_000)
    }

    @Test
    fun `the middle of a big city is that city, not a suburb`() {
        assertEquals("Paris, FR", at(48.8566, 2.3522))
        assertEquals("Tokyo, JP", at(35.6762, 139.6503))
        assertEquals("New York City, US", at(40.7128, -74.0060))
        assertEquals("London, GB", at(51.5074, -0.1278))
    }

    @Test
    fun `a town near a big city keeps its own name`() {
        assertEquals("Versailles, FR", at(48.8014, 2.1301))
    }

    @Test
    fun `names keep their accents`() {
        assertEquals("Zürich, CH", at(47.3769, 8.5417))
        assertEquals("São Paulo, BR", at(-23.5505, -46.6333))
    }

    @Test
    fun `the southern and western hemispheres`() {
        assertEquals("Sydney, AU", at(-33.8688, 151.2093))
        assertEquals("Buenos Aires, AR", at(-34.6037, -58.3816))
    }

    @Test
    fun `the far side of the date line is near`() {
        // Labasa, Fiji sits at 179.4 E. A point at 179.9 W is near it across the line, not a world away.
        assertEquals("Labasa, FJ", at(-16.43, -179.9))
    }

    @Test
    fun `open sea isn't anywhere`() {
        assertNull(at(0.0, -140.0))
        assertNull(at(-60.0, -120.0))
        assertNull(at(30.0, -40.0))
    }

    @Test
    fun `the poles and the edges don't throw`() {
        assertNull(at(90.0, 0.0))
        at(-90.0, 180.0)
        at(0.0, 180.0)
        at(0.0, -180.0)
    }

    @Test
    fun `quick enough for a whole library`() {
        val elapsed = measureTimeMillis {
            for (i in 0 until 100_000) table.nearest(48.0 + i % 100 / 100.0, 2.0 + i % 77 / 100.0)
        }
        assertTrue("took $elapsed ms", elapsed < 10_000)
    }

    private fun file(id: Int, path: String, locked: Boolean = false) = FileItem(
        id = id,
        path = path,
        name = path.substringAfterLast('/'),
        type = "image",
        kind = "media",
        size = "",
        changed = "",
        owner = "Me",
        isLocked = locked
    )

    @Test
    fun `places group by country, largest first, and locked files don't count`() {
        val files = listOf(
            file(1, "/a/1.jpg"), file(2, "/a/2.jpg"), file(3, "/a/3.jpg"), file(4, "/a/4.jpg"),
            file(5, "/a/5.jpg", locked = true), file(6, "/a/6.jpg")
        )
        val details = mapOf(
            1 to PhotoDetails(null, 10, emptyList()),
            2 to PhotoDetails(null, 10, emptyList()),
            3 to PhotoDetails(null, 11, emptyList()),
            4 to PhotoDetails(null, 20, emptyList()),
            5 to PhotoDetails(null, 30, emptyList()),
            6 to PhotoDetails("Only a camera", -1, emptyList())
        )
        val groups = buildPlaceGroups(
            files,
            details,
            placeName = { "Town $it" },
            countryCode = { if (it < 20) "AA" else if (it < 30) "BB" else "CC" },
            countryLabel = { it }
        )
        assertEquals(listOf("AA", "BB"), groups.map { it.code })
        assertEquals(listOf(3, 1), groups.map { it.count })
        assertEquals(listOf("Town 10", "Town 11"), groups[0].places.map { it.name })
        assertEquals(listOf(1, 2), groups[0].places[0].covers.map { it.id })
    }

    @Test
    fun `tags count photos, and a photo can have several`() {
        val files = listOf(file(1, "/a/1.jpg"), file(2, "/a/2.jpg"), file(3, "/a/3.jpg", locked = true))
        val details = mapOf(
            1 to PhotoDetails(null, -1, listOf("Beach", "Family")),
            2 to PhotoDetails(null, -1, listOf("Beach")),
            3 to PhotoDetails(null, -1, listOf("Secret"))
        )
        val groups = buildTagGroups(files, details)
        assertEquals(listOf("Beach", "Family"), groups.map { it.name })
        assertEquals(listOf(2, 1), groups.map { it.count })
    }

    @Test
    fun `a favorite follows its file and whatever folder it's in`() {
        val favorites = setOf("/a/b/one.jpg", "/a/two.jpg", "/c/three.jpg")
        assertEquals(setOf("/a/b/uno.jpg", "/a/two.jpg", "/c/three.jpg"), favoritesAfterMove(favorites, "/a/b/one.jpg", "/a/b/uno.jpg"))
        assertEquals(setOf("/x/b/one.jpg", "/x/two.jpg", "/c/three.jpg"), favoritesAfterMove(favorites, "/a", "/x"))
        assertNull(favoritesAfterMove(favorites, "/c/other.jpg", "/c/new.jpg"))
        // A folder whose name only starts the same isn't the same folder.
        assertNull(favoritesAfterMove(favorites, "/a/two", "/a/2"))
    }
}
