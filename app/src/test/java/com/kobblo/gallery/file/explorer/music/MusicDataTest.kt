package com.kobblo.gallery.file.explorer.music

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDataTest {
    @Test
    fun favoritesAreNewestFirstWithNoRepeats() {
        val data = MusicData().withFavorite("/a", true).withFavorite("/b", true).withFavorite("/a", true)
        assertEquals(listOf("/a", "/b"), data.favorites)
        assertTrue(data.isFavorite("/b"))
        assertFalse(data.withFavorite("/b", false).isFavorite("/b"))
    }

    private fun withList() = MusicData().withNewPlaylist("p", "  Road Trip ", listOf("/a", "/b", "/c"), now = 1)

    @Test
    fun createsAndRenamesPlaylists() {
        val data = withList()
        assertEquals("Road Trip", data.playlist("p")?.name)
        assertEquals("Long Drive", data.withRenamedPlaylist("p", " Long Drive", now = 2).playlist("p")?.name)
        assertEquals(2L, data.withRenamedPlaylist("p", "x", now = 2).playlist("p")?.updatedAt)
    }

    @Test
    fun deletesPlaylists() {
        assertEquals(null, withList().withoutPlaylist("p").playlist("p"))
    }

    @Test
    fun addingToAPlaylistSkipsWhatItHas() {
        val data = withList().withAddedToPlaylist("p", listOf("/b", "/d", "/d"), now = 2)
        assertEquals(listOf("/a", "/b", "/c", "/d"), data.playlist("p")?.paths)
    }

    @Test
    fun reordersAndRemovesInPlaylists() {
        val moved = withList().withMovedInPlaylist("p", 2, 0, now = 2)
        assertEquals(listOf("/c", "/a", "/b"), moved.playlist("p")?.paths)
        val removed = moved.withRemovedFromPlaylist("p", 1, now = 3)
        assertEquals(listOf("/c", "/b"), removed.playlist("p")?.paths)
    }

    @Test
    fun anInvalidPlaylistChangeDoesNothing() {
        val data = withList()
        assertEquals(data.playlist("p")?.paths, data.withMovedInPlaylist("p", 9, 0, now = 2).playlist("p")?.paths)
        assertEquals(data.playlist("p")?.paths, data.withRemovedFromPlaylist("p", 9, now = 2).playlist("p")?.paths)
        assertEquals(data.playlists, data.withRenamedPlaylist("nope", "x", now = 2).playlists)
    }

    @Test
    fun recentlyPlayedIsNewestFirstEachSongOnce() {
        val data = MusicData().withPlay("/a", 1).withPlay("/b", 2).withPlay("/a", 3)
        assertEquals(listOf("/a", "/b"), data.recent)
    }

    @Test
    fun recentlyPlayedIsCapped() {
        var data = MusicData()
        repeat(MusicData.RecentLimit + 10) { data = data.withPlay("/$it", it.toLong()) }
        assertEquals(MusicData.RecentLimit, data.recent.size)
        assertEquals("/${MusicData.RecentLimit + 9}", data.recent.first())
    }

    @Test
    fun mostPlayedRanksByCountThenRecency() {
        val data = MusicData()
            .withPlay("/a", 1)
            .withPlay("/b", 2).withPlay("/b", 3)
            .withPlay("/c", 4)
            .withListened("/never-counted", 5000)
        assertEquals(listOf("/b", "/c", "/a"), data.mostPlayed())
        assertEquals(listOf("/b"), data.mostPlayed(limit = 1))
    }

    @Test
    fun listeningTimeAddsUp() {
        val data = MusicData().withListened("/a", 1000).withListened("/a", 500).withListened("/b", 250)
        assertEquals(1500L, data.stats["/a"]?.listenedMs)
        assertEquals(1750L, data.totalListenedMs)
    }

    @Test
    fun lyricOffsetsAreRememberedAndZeroForgetsThem() {
        val data = MusicData().withLyricOffset("/a", -500)
        assertEquals(-500L, data.lyricOffsets["/a"])
        assertTrue(data.withLyricOffset("/a", 0).lyricOffsets.isEmpty())
    }

    @Test
    fun pitchIsAFactorOfSemitones() {
        assertEquals(1f, SoundSettings().pitchFactor, 0.0001f)
        assertEquals(2f, SoundSettings(pitchSemitones = 12).pitchFactor, 0.0001f)
        assertEquals(0.5f, SoundSettings(pitchSemitones = -12).pitchFactor, 0.0001f)
    }

    @Test
    fun survivesATripThroughJson() {
        val data = withList()
            .withFavorite("/a", true)
            .withPlay("/a", 5)
            .withListened("/a", 1234)
            .withLyricOffset("/a", 250)
            .copy(sound = SoundSettings(speed = 1.25f, pitchSemitones = -2, eqEnabled = true, eqBands = listOf(100, -200)), songSort = SongSort.Album)
        val gson = Gson()
        assertEquals(data, gson.fromJson(gson.toJson(data), MusicData::class.java))
    }

    @Test
    fun aFileFromAnOlderVersionFillsInDefaults() {
        val data = Gson().fromJson("""{"favorites":["/a"]}""", MusicData::class.java)
        assertEquals(listOf("/a"), data.favorites)
        assertEquals(SoundSettings(), data.sound)
        assertEquals(emptyList<Playlist>(), data.playlists)
        assertEquals(SongSort.Title, data.songSort)
    }

    @Test
    fun aSongCountsAsPlayedAfterHalfOrThirtySeconds() {
        val counted = mutableListOf<String>()
        val tracker = ListenTracker({ _, _ -> }, { counted += it })
        tracker.begin("/short", 12_000)
        tracker.listened(5_999)
        assertTrue(counted.isEmpty())
        tracker.listened(1)
        assertEquals(listOf("/short"), counted)

        counted.clear()
        tracker.begin("/long", 600_000)
        tracker.listened(29_999)
        assertTrue(counted.isEmpty())
        tracker.listened(1)
        assertEquals(listOf("/long"), counted)
    }

    @Test
    fun aSongOnlyCountsOncePerTimeThrough() {
        val counted = mutableListOf<String>()
        val tracker = ListenTracker({ _, _ -> }, { counted += it })
        tracker.begin("/a", 10_000)
        tracker.listened(9_000)
        tracker.listened(9_000)
        assertEquals(1, counted.size)
        tracker.begin("/a", 10_000)
        tracker.listened(9_000)
        assertEquals(2, counted.size)
    }

    @Test
    fun anUnknownLengthCountsAfterThirtySecondsUntilItIsKnown() {
        val counted = mutableListOf<String>()
        val tracker = ListenTracker({ _, _ -> }, { counted += it })
        tracker.begin("/a", 0)
        tracker.listened(20_000)
        assertTrue(counted.isEmpty())
        tracker.updateDuration(30_000)
        tracker.listened(1)
        assertEquals(1, counted.size)
    }

    @Test
    fun listeningTimeIsReportedAndTheEmptyTrackerIgnoresIt() {
        var total = 0L
        val tracker = ListenTracker({ _, delta -> total += delta }, { })
        tracker.listened(500)
        assertEquals(0L, total)
        tracker.begin("/a", 10_000)
        tracker.listened(500)
        tracker.listened(-5)
        assertEquals(500L, total)
    }
}
