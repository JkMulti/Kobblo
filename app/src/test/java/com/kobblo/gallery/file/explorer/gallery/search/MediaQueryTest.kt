package com.kobblo.gallery.file.explorer.gallery.search

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.MediaFacts
import com.kobblo.gallery.file.explorer.gallery.PhotoDetails
import com.kobblo.gallery.file.explorer.gallery.ProbeFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.system.measureTimeMillis

class MediaQueryTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val root = "/storage/emulated/0"
    private val now = at(2026, Calendar.NOVEMBER, 6)

    private fun at(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, 9, 0, 0)
        }.timeInMillis

    private fun file(
        id: Int,
        path: String,
        moment: Long = now,
        type: String = "image",
        pinned: Boolean = false,
        locked: Boolean = false,
        bytes: Long = 1_000L
    ) = FileItem(
        id = id,
        path = "$root/$path",
        name = path.substringAfterLast('/'),
        type = type,
        kind = "media",
        size = "",
        changed = "",
        owner = "Me",
        isPinned = pinned,
        isLocked = locked,
        sizeBytes = bytes,
        lastModified = moment
    )

    private val beach = file(1, "DCIM/Camera/IMG_0001.jpg", at(2023, Calendar.JULY, 14), bytes = 20_000_000)
    private val clip = file(2, "DCIM/Camera/VID_0002.mp4", at(2023, Calendar.AUGUST, 2), type = "video", pinned = true)
    private val shot = file(3, "Pictures/Screenshots/Screenshot_1.png", at(2026, Calendar.NOVEMBER, 5))
    private val gif = file(4, "Download/party.gif", at(2024, Calendar.MARCH, 1))
    private val hidden = file(5, "DCIM/Camera/IMG_0005.jpg", at(2023, Calendar.JULY, 15), locked = true)
    private val whatsapp = file(6, "WhatsApp/Media/IMG-20220101-WA0001.jpg", at(2022, Calendar.JANUARY, 1))
    private val all = listOf(shot, gif, clip, beach, hidden, whatsapp)

    private val paris = PhotoDetails("Canon EOS R5", place = 7, tags = listOf("Holiday"))
    private val zurich = PhotoDetails(null, place = 9, tags = emptyList())

    private val library = SearchLibrary(
        files = all,
        details = mapOf(1 to paris, 2 to zurich, 5 to paris),
        flags = mapOf(1 to ProbeFlags.MOTION_PHOTO),
        facts = mapOf(1 to MediaFacts(4000, 1000, 0L, "image/jpeg")),
        root = root,
        placeNames = { if (it == 7) listOf("Paris", "France") else listOf("Zürich", "Switzerland") },
        zone = zone
    )

    private fun search(raw: String): List<Int> =
        searchMedia(MediaQuery.parse(raw, now, Locale.ENGLISH, zone), library).map { it.id }

    @Test
    fun `a blank search finds nothing, rather than everything`() {
        assertEquals(emptyList<Int>(), search(""))
        assertEquals(emptyList<Int>(), search("   "))
    }

    @Test
    fun `names match in any case and any order`() {
        assertEquals(listOf(4), search("PARTY"))
        assertEquals(listOf(6), search("wa0001 whatsapp"))
        assertEquals(emptyList<Int>(), search("party wa0001"))
    }

    @Test
    fun `folders match but the storage root does not`() {
        assertEquals(listOf(3), search("screenshots"))
        assertEquals(emptyList<Int>(), search("emulated"))
        assertEquals(emptyList<Int>(), search("storage"))
    }

    @Test
    fun `a year fits the date or the name`() {
        assertEquals(listOf(2, 1), search("2023"))
        assertEquals(listOf(6), search("2022"))
        assertEquals(listOf(3), search("2026"))
    }

    @Test
    fun `a month fits any year, and with a year only that year`() {
        assertEquals(listOf(1), search("july"))
        assertEquals(listOf(4), search("march"))
        assertEquals(listOf(1), search("july 2023"))
        assertEquals(emptyList<Int>(), search("july 2024"))
        assertEquals(listOf(2), search("Aug"))
    }

    @Test
    fun `month names work in the person's own language`() {
        val found = searchMedia(MediaQuery.parse("juillet", now, Locale.FRENCH, zone), library).map { it.id }
        assertEquals(listOf(1), found)
    }

    @Test
    fun `seasons fit their months in any year`() {
        assertEquals(listOf(2, 1), search("summer"))
        assertEquals(listOf(6), search("winter"))
    }

    @Test
    fun `dates relative to today`() {
        assertEquals(listOf(3), search("yesterday"))
        assertEquals(listOf(3), search("this week"))
        assertEquals(listOf(3), search("this month"))
        assertEquals(listOf(3), search("this year"))
        assertEquals(emptyList<Int>(), search("today"))
        assertEquals(emptyList<Int>(), search("last year"))
    }

    @Test
    fun `last summer is the last one that finished`() {
        val photo = file(9, "DCIM/a.jpg", at(2026, Calendar.JULY, 1))
        val before = file(10, "DCIM/b.jpg", at(2025, Calendar.JULY, 1))
        val lib = SearchLibrary(listOf(photo, before), root = root, zone = zone)
        assertEquals(listOf(9), searchMedia(MediaQuery.parse("last summer", now, Locale.ENGLISH, zone), lib).map { it.id })
        val inSpring = at(2026, Calendar.APRIL, 1)
        assertEquals(listOf(10), searchMedia(MediaQuery.parse("last summer", inSpring, Locale.ENGLISH, zone), lib).map { it.id })
    }

    @Test
    fun `last winter runs across the new year`() {
        val december = file(9, "DCIM/a.jpg", at(2025, Calendar.DECEMBER, 25))
        val february = file(10, "DCIM/b.jpg", at(2026, Calendar.FEBRUARY, 10))
        val tooEarly = file(11, "DCIM/c.jpg", at(2024, Calendar.DECEMBER, 25))
        val lib = SearchLibrary(listOf(december, february, tooEarly), root = root, zone = zone)
        assertEquals(listOf(9, 10), searchMedia(MediaQuery.parse("last winter", now, Locale.ENGLISH, zone), lib).map { it.id })
    }

    @Test
    fun `words for kinds`() {
        assertEquals(listOf(2), search("videos"))
        assertEquals(listOf(4), search("gif"))
        assertEquals(listOf(3, 4, 1, 6), search("photos"))
        assertEquals(listOf(2), search("favorites"))
        assertEquals(listOf(2), search("favourite"))
        assertEquals(listOf(1), search("motion"))
        assertEquals(listOf(1), search("panorama"))
        assertEquals(listOf(2, 1), search("camera"))
    }

    @Test
    fun `sizes`() {
        assertEquals(listOf(1), search("large"))
        assertEquals(listOf(1), search(">10mb"))
        assertEquals(listOf(1), search("10mb+"))
        assertEquals(listOf(3, 4, 2, 6), search("<1mb"))
        assertEquals(emptyList<Int>(), search(">1gb"))
    }

    @Test
    fun `details read from inside the photo`() {
        assertEquals(listOf(1), search("canon"))
        assertEquals(listOf(1), search("paris"))
        assertEquals(listOf(1), search("france"))
        assertEquals(listOf(1), search("holiday"))
        assertEquals(listOf(1), search("canon paris 2023 july"))
    }

    @Test
    fun `accents don't matter for places`() {
        assertEquals(listOf(2), search("zurich"))
        assertEquals(listOf(2), search("zürich"))
    }

    @Test
    fun `locked files can't be found by anything`() {
        assertTrue(5 !in search("IMG_0005"))
        assertTrue(5 !in search("paris"))
        assertTrue(5 !in search("2023"))
        assertTrue(5 !in search("photos"))
    }

    @Test
    fun `every word has to fit`() {
        assertEquals(emptyList<Int>(), search("paris videos"))
        assertEquals(listOf(2), search("zurich videos"))
    }

    @Test
    fun `words in the app's language work beside english`() {
        val french = mapOf(
            R.string.media_search_words_video to "vidéo, vidéos",
            R.string.media_search_words_summer to "été",
            R.string.media_search_words_this_week to "cette semaine",
            R.string.media_search_words_last_summer to "été dernier,l'été  dernier"
        )
        val local = SearchWords.read { french[it].orEmpty() }
        fun searchFrench(raw: String) = searchMedia(MediaQuery.parse(raw, now, Locale.FRENCH, zone, local), library).map { it.id }

        assertEquals(listOf(2), searchFrench("Vidéos"))
        assertEquals(listOf(2), searchFrench("video"))
        assertEquals(listOf(2, 1), searchFrench("ete"))
        assertEquals(listOf(3), searchFrench("cette semaine"))
        assertEquals(listOf(2), searchFrench("videos 2023"))

        val photo = file(9, "DCIM/a.jpg", at(2026, Calendar.JULY, 1))
        val lib = SearchLibrary(listOf(photo), root = root, zone = zone)
        assertEquals(listOf(9), searchMedia(MediaQuery.parse("l'été dernier", now, Locale.FRENCH, zone, local), lib).map { it.id })
    }

    @Test
    fun `a name of several words in the app's language is read as one`() {
        val portuguese = mapOf(
            R.string.media_search_words_screenshot to "captura,capturas,capturas de tela",
            R.string.media_search_words_video to "vídeo,vídeos"
        )
        val local = SearchWords.read { portuguese[it].orEmpty() }
        val query = MediaQuery.parse("capturas de tela", now, Locale.forLanguageTag("pt"), zone, local)
        assertEquals(1, query.words.size)
        assertEquals(MediaWord.SCREENSHOT, query.words.single().kind)
        // With something after it, the phrase still counts as one word and the rest as its own.
        val both = MediaQuery.parse("capturas de tela 2023", now, Locale.forLanguageTag("pt"), zone, local)
        assertEquals(listOf(MediaWord.SCREENSHOT, null), both.words.map { it.kind })
        assertEquals(2023, both.words.last().year)
    }

    @Test
    fun `a checkpoint can stop it`() {
        var calls = 0
        try {
            searchMedia(MediaQuery.parse("x", now, Locale.ENGLISH, zone), library) {
                calls++
                throw IllegalStateException()
            }
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, calls)
    }

    @Test
    fun `hints come from what's in the library`() {
        val hints = buildSearchHints(
            library,
            mapOf(MediaWord.VIDEO to "Videos", MediaWord.GIF to "GIFs", MediaWord.RAW to "RAW", MediaWord.FAVORITE to "Favorites", MediaWord.SCREENSHOT to "Screenshots"),
            firstPlaceName = { if (it == 7) "Paris" else "Zürich" }
        )
        assertEquals(listOf("Favorites", "Videos", "Screenshots", "GIFs").toSet(), hints.kinds.toSet())
        assertEquals(listOf("2026", "2024", "2023", "2022"), hints.years)
        assertEquals(listOf("Canon EOS R5"), hints.cameras)
        assertEquals(listOf("Paris", "Zürich").toSet(), hints.places.toSet())
        assertEquals(listOf("Holiday"), hints.tags)
    }

    @Test
    fun `quick enough at 100,000 photos`() {
        val many = (0 until 100_000).map { i ->
            file(i, "DCIM/Folder${i % 100}/IMG_$i.jpg", now - i * 3_600_000L)
        }
        val details = HashMap<Int, PhotoDetails>()
        val shared = (0 until 200).map { PhotoDetails("Camera $it", it, listOf("tag$it")) }
        for (i in many.indices) details[i] = shared[i % 200]
        val lib = SearchLibrary(many, details = details, root = root, placeNames = { listOf("Place $it", "Country") }, zone = zone)

        for (raw in listOf("IMG_99999", "2023 july", "place 7", "camera 12 tag12", "video", "folder42 2024")) {
            val query = MediaQuery.parse(raw, now, Locale.ENGLISH, zone)
            val elapsed = measureTimeMillis { searchMedia(query, lib) }
            assertTrue("'$raw' took $elapsed ms", elapsed < 1_500)
        }
    }
}
