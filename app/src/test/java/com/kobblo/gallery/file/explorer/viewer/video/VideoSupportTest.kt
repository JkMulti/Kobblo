package com.kobblo.gallery.file.explorer.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSupportTest {
    @Test
    fun `a position is kept only away from the start and end`() {
        assertFalse(ResumePolicy.isWorthKeeping(500, 60_000))
        assertTrue(ResumePolicy.isWorthKeeping(5_000, 60_000))
        assertFalse(ResumePolicy.isWorthKeeping(59_500, 60_000))
        // An unknown duration can't rule the end out, so it keeps the place.
        assertTrue(ResumePolicy.isWorthKeeping(5_000, -1))
    }

    @Test
    fun `saved positions survive being stored`() {
        val value = ResumePolicy.encode(1234, 99)
        assertEquals(1234, ResumePolicy.decodePosition(value))
        assertEquals(99, ResumePolicy.decodeSavedAt(value))
        assertEquals(0, ResumePolicy.decodePosition(null))
        assertEquals(0, ResumePolicy.decodePosition("junk"))
        assertEquals(0, ResumePolicy.decodeSavedAt("junk"))
    }

    @Test
    fun `the store forgets the oldest past its capacity`() {
        val entries = mapOf("a" to 1L, "b" to 3L, "c" to 2L, "d" to 4L)
        assertEquals(listOf("a", "c"), ResumePolicy.keysToForget(entries, 2))
        assertTrue(ResumePolicy.keysToForget(entries, 4).isEmpty())
    }

    @Test
    fun `sidecars match by base name with an optional language`() {
        val siblings = listOf(
            "movie.mp4", "movie.srt", "movie.en.srt", "MOVIE.fr.vtt", "movie.final.cut.srt",
            "movie2.srt", "movie.txt", "other.srt", "movie.ass"
        )
        val found = SidecarSubtitles.match("movie.mp4", siblings)
        assertEquals(listOf("movie.ass", "movie.srt", "MOVIE.fr.vtt", "movie.en.srt").sorted(), found.map { it.fileName }.sorted())
        assertEquals(null, found.first { it.fileName == "movie.srt" }.language)
        assertEquals("en", found.first { it.fileName == "movie.en.srt" }.language)
        assertEquals("fr", found.first { it.fileName == "MOVIE.fr.vtt" }.language)
        assertEquals("application/x-subrip", found.first { it.fileName == "movie.srt" }.mimeType)
        assertEquals("text/vtt", found.first { it.fileName == "MOVIE.fr.vtt" }.mimeType)
        // The plain one goes first, so it's the one switched on.
        assertEquals(null, found.first().language)
    }

    @Test
    fun `names with dots in them still match`() {
        val found = SidecarSubtitles.match("my.holiday.2024.mkv", listOf("my.holiday.2024.srt", "my.holiday.srt"))
        assertEquals(listOf("my.holiday.2024.srt"), found.map { it.fileName })
    }

    @Test
    fun `bdav positions step over each packet's timestamp`() {
        assertEquals(4, BdavPackets.storedPosition(0))
        assertEquals(4 + 187, BdavPackets.storedPosition(187))
        // The first byte of the second packet, past its own timestamp.
        assertEquals(192 + 4, BdavPackets.storedPosition(188))
        assertEquals(192 * 10 + 4 + 5, BdavPackets.storedPosition(188 * 10 + 5))
    }

    @Test
    fun `bdav length counts only whole packets of plain bytes`() {
        // Three whole packets stored, read from the start: three plain packets.
        assertEquals(188L * 3, BdavPackets.plainBytesAvailable(0, 192L * 3 - 4))
        // From partway through the second packet.
        assertEquals(188L * 3 - 200, BdavPackets.plainBytesAvailable(200, 192L * 3 - BdavPackets.storedPosition(200)))
    }

    @Test
    fun `bdav is told apart from a plain stream by where its sync bytes fall`() {
        val bdav = ByteArray(192 * 4) { if (it % 192 == 4) 0x47 else 0 }
        assertTrue(BdavPackets.looksLikeBdav(bdav, bdav.size))
        val plain = ByteArray(192 * 4) { if (it % 188 == 0) 0x47 else 0 }
        assertFalse(BdavPackets.looksLikeBdav(plain, plain.size))
        assertFalse(BdavPackets.looksLikeBdav(bdav, 100))
    }

    @Test
    fun `times read like a player's`() {
        assertEquals("0:00", formatVideoTime(0))
        assertEquals("0:05", formatVideoTime(4_600))
        assertEquals("1:05", formatVideoTime(65_000))
        assertEquals("1:01:05", formatVideoTime(3_665_000))
        assertEquals("0:00", formatVideoTime(-500))
    }
}
