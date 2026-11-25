package com.kobblo.gallery.file.explorer.data

import com.kobblo.gallery.file.explorer.data.share.IncomingFileRules
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingFileRulesTest {
    private val noExtensions: (String) -> String? = { null }
    private val knownExtensions: (String) -> String? = { mime ->
        mapOf("image/jpeg" to "jpg", "application/pdf" to "pdf", "weird/blank" to " ")[mime]
    }

    @Test
    fun `the declared type decides when the name says nothing`() {
        assertEquals("image", IncomingFileRules.typeOf("1000012345", "image/jpeg"))
        assertEquals("video", IncomingFileRules.typeOf("clip", "video/mp4"))
        assertEquals("audio", IncomingFileRules.typeOf("voice note", "audio/ogg"))
        assertEquals("doc", IncomingFileRules.typeOf("scan", "application/pdf"))
        assertEquals("doc", IncomingFileRules.typeOf("notes", "text/plain"))
        assertEquals("apk", IncomingFileRules.typeOf("base", "application/vnd.android.package-archive"))
    }

    @Test
    fun `the name decides when the type is missing or says nothing`() {
        assertEquals("image", IncomingFileRules.typeOf("IMG_0001.HEIC", null))
        assertEquals("video", IncomingFileRules.typeOf("clip.mkv", "application/octet-stream"))
        assertEquals("audio", IncomingFileRules.typeOf("song.mp3", null))
        assertEquals("doc", IncomingFileRules.typeOf("manual.pdf", "application/octet-stream"))
        assertEquals("doc", IncomingFileRules.typeOf("Main.kt", null))
        assertEquals("doc", IncomingFileRules.typeOf("data.CSV", null))
        assertEquals("apk", IncomingFileRules.typeOf("app.apk", "application/octet-stream"))
        assertEquals("archive", IncomingFileRules.typeOf("backup.7z", null))
        assertEquals("archive", IncomingFileRules.typeOf("site.tar.gz", "application/gzip"))
    }

    @Test
    fun `what neither knows is just a file`() {
        assertEquals("file", IncomingFileRules.typeOf("shared_file", null))
        assertEquals("file", IncomingFileRules.typeOf("report.docx", "application/octet-stream"))
        assertEquals("file", IncomingFileRules.typeOf("archive.zip.part", null))
        // A ZIP is only known by its name: its declared type alone doesn't make it an archive.
        assertEquals("file", IncomingFileRules.typeOf("download", "application/zip"))
    }

    @Test
    fun `pictures win over video, and video over sound, when the name and the type disagree`() {
        assertEquals("image", IncomingFileRules.typeOf("clip.mp4", "image/gif"))
        assertEquals("image", IncomingFileRules.typeOf("photo.jpg", "video/mp4"))
        assertEquals("video", IncomingFileRules.typeOf("clip.mp4", "audio/mpeg"))
        assertEquals("audio", IncomingFileRules.typeOf("notes.txt", "audio/mpeg"))
        assertEquals("doc", IncomingFileRules.typeOf("app.apk", "text/plain"))
    }

    @Test
    fun `a size reads in binary steps with one decimal`() {
        mapOf(
            1L to "1 B",
            1_023L to "1023 B",
            1_024L to "1.0 KB",
            1_536L to "1.5 KB",
            1_048_575L to "1024.0 KB",
            1_048_576L to "1.0 MB",
            5_767_168L to "5.5 MB",
            1_073_741_824L to "1.0 GB",
            // Nothing above a gigabyte: a terabyte is a great many of them.
            1_099_511_627_776L to "1024.0 GB"
        ).forEach { (bytes, text) -> assertEquals("for $bytes", text, IncomingFileRules.sizeLabel(bytes)) }
    }

    @Test
    fun `a size the sender never gave is two dashes`() {
        assertEquals("--", IncomingFileRules.sizeLabel(0L))
        assertEquals("--", IncomingFileRules.sizeLabel(-1L))
    }

    @Test
    fun `a name is taken from the end of the address`() {
        assertEquals("photo.jpg", IncomingFileRules.nameFromAddress("photo.jpg", "image/jpeg", knownExtensions))
        assertEquals("photo.jpg", IncomingFileRules.nameFromAddress("primary:DCIM/Camera/photo.jpg", null, noExtensions))
    }

    @Test
    fun `a name with no extension borrows one from the declared type`() {
        assertEquals("1000012345.jpg", IncomingFileRules.nameFromAddress("1000012345", "image/jpeg", knownExtensions))
        assertEquals("msf:77.pdf", IncomingFileRules.nameFromAddress("msf:77", "application/pdf", knownExtensions))
    }

    @Test
    fun `a name stays bare when no extension is known for its type`() {
        assertEquals("1000012345", IncomingFileRules.nameFromAddress("1000012345", null, knownExtensions))
        assertEquals("1000012345", IncomingFileRules.nameFromAddress("1000012345", "application/x-unheard-of", knownExtensions))
        assertEquals("1000012345", IncomingFileRules.nameFromAddress("1000012345", "weird/blank", knownExtensions))
    }

    @Test
    fun `an address with nothing at its end still gets a name`() {
        assertEquals("shared_file", IncomingFileRules.nameFromAddress(null, null, noExtensions))
        assertEquals("shared_file.jpg", IncomingFileRules.nameFromAddress(null, "image/jpeg", knownExtensions))
    }
}
