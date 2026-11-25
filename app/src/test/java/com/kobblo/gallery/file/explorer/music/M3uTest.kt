package com.kobblo.gallery.file.explorer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uTest {
    @Test
    fun readsExtendedPlaylists() {
        val entries = M3u.parse(
            "#EXTM3U\n#EXTINF:123,Aria Vale - First Light\n/sdcard/Music/a.mp3\n#EXTINF:-1,Just a title\n/sdcard/Music/b.mp3\n"
        )
        assertEquals(2, entries.size)
        assertEquals(M3uEntry("/sdcard/Music/a.mp3", "First Light", "Aria Vale", 123), entries[0])
        assertEquals(M3uEntry("/sdcard/Music/b.mp3", "Just a title", null, null), entries[1])
    }

    @Test
    fun readsPlainPlaylistsAndSkipsComments() {
        val entries = M3u.parse("# a comment\n\n/m/a.mp3\r\n/m/b.mp3\r\n")
        assertEquals(listOf("/m/a.mp3", "/m/b.mp3"), entries.map { it.path })
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheFirstLine() {
        assertEquals("/m/a.mp3", M3u.parse("\uFEFF/m/a.mp3").single().path)
    }

    @Test
    fun resolvesRelativePathsAgainstThePlaylist() {
        val base = "/storage/emulated/0/Music/Lists"
        assertEquals("/storage/emulated/0/Music/Lists/a.mp3", M3u.parse("a.mp3", base).single().path)
        assertEquals("/storage/emulated/0/Music/Rock/a.mp3", M3u.parse("../Rock/a.mp3", base).single().path)
        assertEquals("/storage/emulated/0/Music/Lists/a.mp3", M3u.parse("./a.mp3", base).single().path)
    }

    @Test
    fun takesWindowsSeparators() {
        val base = "/m/Lists"
        assertEquals("/m/Rock/a b.mp3", M3u.parse("..\\Rock\\a b.mp3", base).single().path)
    }

    @Test
    fun keepsAWindowsDrivePathSoItsNameCanStillBeMatched() {
        val entry = M3u.parse("C:\\Music\\Rock\\song.mp3", "/m").single()
        assertEquals("song.mp3", entry.path.substringAfterLast('/'))
    }

    @Test
    fun readsFileUrls() {
        assertEquals("/m/a b.mp3", M3u.parse("file:///m/a%20b.mp3").single().path)
        assertEquals("/m/a+b.mp3", M3u.parse("file:///m/a+b.mp3").single().path)
    }

    @Test
    fun leavesOutAnythingOnANetwork() {
        assertTrue(M3u.parse("https://example.com/stream.mp3\nhttp://x/y.mp3\nrtsp://x/y").isEmpty())
    }

    @Test
    fun infoOnlyAppliesToTheNextEntry() {
        val entries = M3u.parse("#EXTINF:10,A - B\n/m/a.mp3\n/m/b.mp3")
        assertEquals("B", entries[0].title)
        assertNull(entries[1].title)
    }

    @Test
    fun writesWhatItReads() {
        val entries = listOf(
            M3uEntry("/m/Rock/a.mp3", "First", "Artist", 100),
            M3uEntry("/m/b.mp3", "Second", null, 20)
        )
        assertEquals(entries, M3u.parse(M3u.write(entries)))
    }

    @Test
    fun writesPathsRelativeToItsFolder() {
        val text = M3u.write(listOf(M3uEntry("/m/Lists/a.mp3"), M3uEntry("/other/b.mp3")), "/m/Lists")
        assertEquals("#EXTM3U\na.mp3\n/other/b.mp3\n", text)
        assertEquals(listOf("/m/Lists/a.mp3", "/other/b.mp3"), M3u.parse(text, "/m/Lists").map { it.path })
    }

    @Test
    fun makesASafeFileName() {
        assertEquals("Road_Trip_ 2.m3u", M3u.fileNameFor("Road/Trip: 2"))
        assertEquals("Playlist.m3u", M3u.fileNameFor("..."))
    }

    @Test
    fun normalizesPaths() {
        assertEquals("/a/c", M3u.normalize("/a/b/../c"))
        assertEquals("/a/b", M3u.normalize("/a/./b/"))
        assertEquals("/", M3u.normalize("/a/.."))
    }
}
