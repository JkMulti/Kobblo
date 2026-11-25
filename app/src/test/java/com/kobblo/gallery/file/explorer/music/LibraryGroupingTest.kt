package com.kobblo.gallery.file.explorer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {
    private fun track(
        path: String,
        title: String = path.substringAfterLast('/').substringBeforeLast('.'),
        artist: String = "",
        album: String = "",
        albumArtist: String = "",
        number: Int = 0,
        disc: Int = 0,
        added: Long = 0,
        year: Int = 0,
        durationMs: Long = 0
    ) = MusicTrack(
        path = path, title = title, artist = artist, album = album, albumArtist = albumArtist,
        trackNumber = number, discNumber = disc, dateAddedSec = added, year = year, durationMs = durationMs
    )

    @Test
    fun sortKeyIgnoresCaseAccentsArticlesAndPunctuation() {
        assertEquals("beatles", sortKey("The Beatles"))
        assertEquals("cafe", sortKey("Café"))
        assertEquals("apple", sortKey("An Apple"))
        assertEquals("hello)", sortKey("  (Hello)"))
        assertEquals("the", sortKey("The"))
        assertEquals("...", sortKey("..."))
    }

    @Test
    fun namesWithNumbersSortTheWayPeopleCount() {
        val names = listOf("Track 10", "track 2", "Track 1", "Track 02b", "01 a", "1 b")
        assertEquals(
            listOf("01 a", "1 b", "Track 1", "track 2", "Track 02b", "Track 10"),
            names.sortedWith { a, b -> naturalCompare(a, b) }
        )
    }

    @Test
    fun sortsSongsEachWay() {
        val a = track("/m/a.mp3", title = "Zebra", artist = "Beta", album = "One", number = 2, added = 5)
        val b = track("/m/b.mp3", title = "The Apple", artist = "Alpha", album = "Two", number = 1, added = 9)
        val c = track("/m/c.mp3", title = "Mango", artist = "", album = "One", number = 1, added = 1)

        assertEquals(listOf(b, c, a), sortSongs(listOf(a, b, c), SongSort.Title))
        // Untagged artists go last rather than first.
        assertEquals(listOf(b, a, c), sortSongs(listOf(a, b, c), SongSort.Artist))
        assertEquals(listOf(c, a, b), sortSongs(listOf(a, b, c), SongSort.Album))
        assertEquals(listOf(b, a, c), sortSongs(listOf(a, b, c), SongSort.RecentlyAdded))
    }

    @Test
    fun albumPlaysByDiscThenTrackThenName() {
        val tracks = listOf(
            track("/m/z.mp3", album = "X", number = 1, disc = 2),
            track("/m/b.mp3", album = "X", number = 2, disc = 1),
            track("/m/a.mp3", album = "X", number = 1, disc = 1),
            track("/m/c 10.mp3", album = "X"),
            track("/m/c 9.mp3", album = "X")
        )
        val album = groupAlbums(tracks).single()
        assertEquals(listOf("/m/c 9.mp3", "/m/c 10.mp3", "/m/a.mp3", "/m/b.mp3", "/m/z.mp3"), album.tracks.map { it.path })
    }

    @Test
    fun sameAlbumNameInDifferentFoldersIsADifferentAlbum() {
        val albums = groupAlbums(
            listOf(
                track("/m/x/1.mp3", artist = "A", album = "Greatest Hits"),
                track("/m/y/2.mp3", artist = "B", album = "Greatest Hits")
            )
        )
        assertEquals(2, albums.size)
    }

    @Test
    fun anAlbumArtistHoldsASplitAlbumTogether() {
        val albums = groupAlbums(
            listOf(
                track("/m/cd1/1.mp3", artist = "A", album = "Box", albumArtist = "Band"),
                track("/m/cd2/2.mp3", artist = "B", album = "Box", albumArtist = "Band")
            )
        )
        val album = albums.single()
        assertEquals("Band", album.artist)
        assertFalse(album.isVarious)
    }

    @Test
    fun manyArtistsWithNoAlbumArtistIsVarious() {
        val album = groupAlbums(
            listOf(
                track("/m/mix/1.mp3", artist = "A", album = "Mix"),
                track("/m/mix/2.mp3", artist = "B", album = "Mix")
            )
        ).single()
        assertTrue(album.isVarious)
        assertEquals("", album.artist)
    }

    @Test
    fun anUntaggedFileIsFiledUnderItsFolder() {
        val album = groupAlbums(listOf(track("/m/Downloads/x.mp3"))).single()
        assertEquals("Downloads", album.title)
        assertEquals("", album.artist)
        assertFalse(album.isVarious)
    }

    @Test
    fun albumsAreInNameOrderAndAddUp() {
        val albums = groupAlbums(
            listOf(
                track("/m/a/1.mp3", album = "Zed", durationMs = 1000),
                track("/m/a/2.mp3", album = "Zed", durationMs = 2000, year = 1999),
                track("/m/b/3.mp3", album = "The Alpha", year = 2001)
            )
        )
        assertEquals(listOf("The Alpha", "Zed"), albums.map { it.title })
        assertEquals(3000L, albums[1].durationMs)
        assertEquals(1999, albums[1].year)
    }

    @Test
    fun artistsGroupRegardlessOfCaseWithUntaggedLast() {
        val artists = groupArtists(
            listOf(
                track("/m/a/1.mp3", artist = "The Lowlands", album = "One"),
                track("/m/a/2.mp3", artist = "the lowlands", album = "One"),
                track("/m/b/3.mp3", artist = "Aria", album = "Two"),
                track("/m/c/4.mp3")
            )
        )
        assertEquals(listOf("Aria", "The Lowlands", ""), artists.map { it.name })
        assertEquals(2, artists[1].tracks.size)
        assertEquals(1, artists[1].albums.size)
        assertEquals(1, artists[2].tracks.size)
    }

    @Test
    fun anArtistsAlbumsAreNewestFirst() {
        val artist = groupArtists(
            listOf(
                track("/m/a/1.mp3", artist = "A", album = "Old", year = 1990),
                track("/m/b/2.mp3", artist = "A", album = "New", year = 2020)
            )
        ).single()
        assertEquals(listOf("New", "Old"), artist.albums.map { it.title })
    }

    @Test
    fun foldersGroupByPathAndPlayByName() {
        val folders = groupFolders(
            listOf(
                track("/m/Rock/b 10.mp3"),
                track("/m/Rock/b 9.mp3"),
                track("/m/Ambient/a.mp3")
            )
        )
        assertEquals(listOf("Ambient", "Rock"), folders.map { it.name })
        assertEquals(listOf("/m/Rock/b 9.mp3", "/m/Rock/b 10.mp3"), folders[1].tracks.map { it.path })
    }

    @Test
    fun searchMatchesEveryWordAcrossEveryField() {
        val song = track("/m/Quiet/paper.mp3", title = "Paper Walls", artist = "The Lowlands", album = "Quiet Rooms")
        assertTrue(song.matchesQuery(queryWords("paper")))
        assertTrue(song.matchesQuery(queryWords("LOWLANDS paper")))
        assertTrue(song.matchesQuery(queryWords("quiet")))
        assertTrue(song.matchesQuery(queryWords("paper.mp3")))
        assertTrue(song.matchesQuery(queryWords("")))
        assertFalse(song.matchesQuery(queryWords("paper rock")))
    }

    @Test
    fun searchIgnoresAccents() {
        val song = track("/m/x.mp3", title = "Café del Mar")
        assertTrue(song.matchesQuery(queryWords("cafe")))
        assertTrue(track("/m/y.mp3", title = "Cafe").matchesQuery(queryWords("café")))
    }

    @Test
    fun theLetterIndexPointsAtTheFirstSongOfEachLetter() {
        val tracks = sortSongs(
            listOf("Zebra", "apple", "The Ant", "1999", "Banana", "Елка", "Éclair").map { track("/m/$it.mp3", title = it) },
            SongSort.Title
        )
        val index = letterIndex(tracks)
        assertEquals(listOf('#', 'A', 'B', 'E', 'Z'), index.map { it.first })
        assertEquals("1999", tracks[index[0].second].title)
        assertEquals("The Ant", tracks[index[1].second].title)
        assertEquals("Banana", tracks[index[2].second].title)
        assertEquals("Éclair", tracks[index[3].second].title)
        assertEquals("Zebra", tracks[index[4].second].title)
    }

    @Test
    fun anEmptyListHasNoLetters() {
        assertTrue(letterIndex(emptyList()).isEmpty())
    }

    @Test
    fun untaggedTitlesComeFromTheFileName() {
        assertEquals("raw stream", titleFromFileName("raw_stream.aac"))
        assertEquals("Untagged tone", titleFromFileName("Untagged tone.wav"))
        assertEquals("noext", titleFromFileName("noext"))
        assertEquals(".hidden", titleFromFileName(".hidden"))
    }

    @Test
    fun everyNameForSharedStorageComesToTheSamePath() {
        val root = "/storage/emulated/0"
        assertEquals("$root/Music/a.mp3", canonicalStoragePath("/sdcard/Music/a.mp3", root))
        assertEquals("$root/Music/a.mp3", canonicalStoragePath("/mnt/sdcard/Music/a.mp3", root))
        assertEquals("$root/Music/a.mp3", canonicalStoragePath("/storage/self/primary/Music/a.mp3", root))
        assertEquals("$root/Music/a.mp3", canonicalStoragePath("$root/Music/a.mp3", root))
        assertEquals("/storage/1234-5678/a.mp3", canonicalStoragePath("/storage/1234-5678/a.mp3", root))
        assertEquals("/sdcardish/a.mp3", canonicalStoragePath("/sdcardish/a.mp3", root))
    }

    @Test
    fun knowsWhatCountsAsAudio() {
        assertTrue(isAudioFileName("a.OPUS"))
        assertTrue(isAudioFileName("a.b.aac"))
        assertFalse(isAudioFileName("a.txt"))
        assertFalse(isAudioFileName("mp3"))
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:05", formatTrackTime(5_400))
        assertEquals("3:07", formatTrackTime(187_000))
        assertEquals("1:02:03", formatTrackTime(3_723_000))
        assertEquals("0:00", formatTrackTime(-5))
        assertEquals(RunningTime.Minutes(12), runningTimeOf(12 * 60_000L))
        assertEquals(RunningTime.Hours(2, 5), runningTimeOf(125 * 60_000L))
        assertEquals(RunningTime.Seconds(36), runningTimeOf(36_000L))
    }
}
