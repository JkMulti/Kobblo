package com.kobblo.gallery.file.explorer.data

import com.kobblo.gallery.file.explorer.data.core.DownloadsOrganizer
import com.kobblo.gallery.file.explorer.data.core.DownloadsOrganizer.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsOrganizerTest {

    @Test
    fun `each kind has its folder`() {
        mapOf(
            "report.pdf" to Group.Docs,
            "notes.TXT" to Group.Docs,
            "table.csv" to Group.Docs,
            "photo.JPG" to Group.Images,
            "scan.heic" to Group.Images,
            "app.apk" to Group.Apps,
            "bundle.xapk" to Group.Apps,
            "song.mp3" to Group.Music,
            "voice.opus" to Group.Music,
            "clip.mp4" to Group.Videos,
            "film.mkv" to Group.Videos
        ).forEach { (name, group) -> assertEquals("for $name", group, DownloadsOrganizer.groupOf(name)) }
    }

    @Test
    fun `what has no folder is left where it is, so it isn't counted either`() {
        listOf("backup.zip", "archive.tar.gz", "data.rar", ".nomedia", "no_extension", "disk.iso").forEach {
            assertNull("for $it", DownloadsOrganizer.groupOf(it))
        }
    }
}
