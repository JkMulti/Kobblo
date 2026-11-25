package com.kobblo.gallery.file.explorer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileKindsTest {

    @Test
    fun `every image format in the brief is an image`() {
        val images = "tif tiff psd tga ppm pgm ico heic heif avif svg apng wbmp dng cr2 cr3 nef arw orf rw2 raf srw jpg png gif webp bmp"
        images.split(" ").forEach { assertEquals(it, "image", FileKinds.typeOf("photo.$it")) }
    }

    @Test
    fun `every video format in the brief is a video`() {
        val videos = "mp4 mkv webm avi mov 3gp 3g2 ts m2ts mts flv f4v m4v mpg mpeg vob ogv wmv asf rmvb"
        videos.split(" ").forEach { assertEquals(it, "video", FileKinds.typeOf("clip.$it")) }
    }

    @Test
    fun `audio archives documents and apps keep their types`() {
        assertEquals("audio", FileKinds.typeOf("a.mp3"))
        assertEquals("audio", FileKinds.typeOf("a.opus"))
        assertEquals("archive", FileKinds.typeOf("a.tar"))
        assertEquals("doc", FileKinds.typeOf("a.pdf"))
        assertEquals("doc", FileKinds.typeOf("a.md"))
        assertEquals("apk", FileKinds.typeOf("a.apk"))
        assertEquals("file", FileKinds.typeOf("a.bin"))
        assertEquals("file", FileKinds.typeOf("noextension"))
    }

    @Test
    fun `case and extra dots don't matter`() {
        assertEquals("image", FileKinds.typeOf("HOLIDAY.JPG"))
        assertEquals("video", FileKinds.typeOf("my.holiday.final.MKV"))
        assertEquals("file", FileKinds.typeOf("trailing."))
        assertEquals("file", FileKinds.typeOf(".hidden"))
    }

    @Test
    fun `a real transport stream stays a video and TypeScript does not`() {
        val dir = Files.createTempDirectory("kinds").toFile()
        try {
            val stream = File(dir, "movie.ts")
            stream.writeBytes(ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 0 })
            assertEquals("video", FileKinds.typeOf(stream))

            val code = File(dir, "index.ts")
            code.writeText("export const answer = 42;\n".repeat(50))
            assertEquals("doc", FileKinds.typeOf(code))

            // Other names are decided by name alone, without touching the file.
            assertEquals("video", FileKinds.typeOf(File(dir, "missing.mp4")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `transport stream sniffing needs two aligned sync bytes`() {
        assertFalse(FileKinds.looksLikeTransportStream(ByteArray(0)))
        assertFalse(FileKinds.looksLikeTransportStream(ByteArray(100) { 0x47 }))
        val aligned = ByteArray(376) { if (it % 188 == 0) 0x47 else 1 }
        assertTrue(FileKinds.looksLikeTransportStream(aligned))
        aligned[188] = 0
        assertFalse(FileKinds.looksLikeTransportStream(aligned))
    }

    @Test
    fun `mime types are specific where other apps need them`() {
        assertEquals("video/x-matroska", FileKinds.mimeTypeOf("a.mkv"))
        assertEquals("image/tiff", FileKinds.mimeTypeOf("a.tif"))
        assertEquals("image/x-tga", FileKinds.mimeTypeOf("a.TGA"))
        assertEquals("video/mp2t", FileKinds.mimeTypeOf("a.ts"))
        assertEquals("audio/*", FileKinds.mimeTypeOf("a.opus"))
        assertEquals("*/*", FileKinds.mimeTypeOf("a.bin"))
    }

    @Test
    fun `raw is a subset of images`() {
        assertTrue(FileKinds.Images.containsAll(FileKinds.Raw))
        assertTrue(FileKinds.isRaw("a.CR3"))
        assertFalse(FileKinds.isRaw("a.jpg"))
    }
}
