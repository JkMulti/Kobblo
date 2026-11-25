package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DateFromNameTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val now = at(2026, 11, 6, 10, 0, 0)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance(zone, Locale.ROOT).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis

    private fun parse(name: String) = DateFromName.parse(name, now, zone)

    @Test
    fun `what cameras and phones write`() {
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("IMG_20230512_142301.jpg"))
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("PXL_20230512_142301123.jpg"))
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("VID-20230512-142301.mp4"))
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("20230512142301.jpg"))
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("Screenshot_2023-05-12-14-23-01.png"))
        assertEquals(at(2023, 5, 12, 14, 23, 1), parse("Photo 2023-05-12 at 14.23.01.jpeg"))
    }

    @Test
    fun `a name with only the day means noon, so no time zone moves it to another day`() {
        assertEquals(at(2023, 5, 12, 12, 0, 0), parse("IMG-20230512-WA0012.jpg"))
        assertEquals(at(2023, 5, 12, 12, 0, 0), parse("trip 2023-05-12.jpg"))
    }

    @Test
    fun `milliseconds since 1970`() {
        val moment = at(2023, 5, 12, 14, 23, 1)
        assertEquals(moment, parse("$moment.jpg"))
    }

    @Test
    fun `numbers that only look like dates`() {
        assertNull(parse("IMG_0001.jpg"))
        assertNull(parse("IMG_20231399.jpg"))
        assertNull(parse("IMG_20230231.jpg"))
        assertNull(parse("IMG_202305.jpg"))
        assertNull(parse("123456789012345678.jpg"))
        assertNull(parse("holiday.jpg"))
        assertNull(parse("IMG_20230512_256199.jpg".replace("20230512", "20231399")))
    }

    @Test
    fun `a date in the future isn't believed`() {
        assertNull(parse("IMG_20991231_235959.jpg"))
        assertNull(parse("IMG-20991231-WA0001.jpg"))
    }

    private fun file(name: String, moment: Long, locked: Boolean = false) =
        FileItem(1, "/a/$name", name, "image", "media", "", "", "", isLocked = locked, lastModified = moment)

    @Test
    fun `only a date more than a day off is worth changing`() {
        val named = at(2023, 5, 12, 12, 0, 0)
        assertEquals(named, DateFromName.correction(file("IMG-20230512-WA0012.jpg", now), 0L, now, zone))
        assertNull(DateFromName.correction(file("IMG-20230512-WA0012.jpg", named + 3_600_000), 0L, now, zone))
    }

    @Test
    fun `a date inside the file beats any name`() {
        assertNull(DateFromName.correction(file("IMG-20230512-WA0012.jpg", now), now, now, zone))
    }

    @Test
    fun `a locked file isn't touched`() {
        assertNull(DateFromName.correction(file("IMG-20230512-WA0012.jpg", now, locked = true), 0L, now, zone))
    }
}
