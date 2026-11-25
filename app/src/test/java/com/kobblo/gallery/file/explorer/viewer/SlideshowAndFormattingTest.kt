package com.kobblo.gallery.file.explorer.viewer

import com.kobblo.gallery.file.explorer.viewer.decoder.ImageHeaders
import com.kobblo.gallery.file.explorer.viewer.metadata.MediaFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowAndFormattingTest {
    @Test
    fun `a slideshow steps forward and wraps around`() {
        assertEquals(1, Slideshow.next(0, 3, skipVideos = false) { false })
        assertEquals(0, Slideshow.next(2, 3, skipVideos = false) { false })
    }

    @Test
    fun `skipping videos passes over them`() {
        val videos = setOf(1, 2)
        assertEquals(3, Slideshow.next(0, 4, skipVideos = true) { it in videos })
        assertEquals(0, Slideshow.next(3, 4, skipVideos = true) { it in videos })
        // Playing through keeps them.
        assertEquals(1, Slideshow.next(0, 4, skipVideos = false) { it in videos })
    }

    @Test
    fun `there's nothing to do with one page or only videos`() {
        assertNull(Slideshow.next(0, 1, skipVideos = false) { false })
        assertNull(Slideshow.next(0, 0, skipVideos = false) { false })
        assertNull(Slideshow.next(0, 3, skipVideos = true) { true })
    }

    @Test
    fun `exposure reads like a camera`() {
        assertEquals("1/250 s", MediaFormatting.exposure(0.004))
        assertEquals("2 s", MediaFormatting.exposure(2.0))
        assertEquals("1.5 s", MediaFormatting.exposure(1.5))
        assertNull(MediaFormatting.exposure(0.0))
        assertEquals("f/1.8", MediaFormatting.aperture(1.8))
        assertEquals("f/2", MediaFormatting.aperture(2.0))
        assertEquals("50 mm", MediaFormatting.focalLength(50.0))
        assertEquals("ISO 400", MediaFormatting.iso(400))
        assertEquals("+1 EV", MediaFormatting.exposureBias(1.0))
        assertEquals("-0.7 EV", MediaFormatting.exposureBias(-0.7))
        assertNull(MediaFormatting.exposureBias(0.0))
    }

    @Test
    fun `coordinates say which side of the world`() {
        assertEquals("24.5000° N, 67.2500° E", MediaFormatting.coordinate(24.5, 67.25))
        assertEquals("33.8688° S, 151.2093° W", MediaFormatting.coordinate(-33.8688, -151.2093))
    }

    @Test
    fun `video locations parse from iso 6709`() {
        assertEquals(24.5 to 67.25, MediaFormatting.parseIso6709("+24.5000+067.2500/"))
        assertEquals(-33.8688 to 151.2093, MediaFormatting.parseIso6709("-33.8688+151.2093+010.000/"))
        assertNull(MediaFormatting.parseIso6709(null))
        assertNull(MediaFormatting.parseIso6709("nowhere"))
        assertNull(MediaFormatting.parseIso6709("+99.0000+067.0000/"))
    }

    @Test
    fun `track facts read as people say them`() {
        assertEquals("24 fps", MediaFormatting.frameRate(24f))
        assertEquals("29.97 fps", MediaFormatting.frameRate(29.97f))
        assertEquals("1.5 Mbps", MediaFormatting.bitRate(1_500_000))
        assertEquals("128 kbps", MediaFormatting.bitRate(128_000))
        assertNull(MediaFormatting.surroundLayout(2))
        assertEquals("5.1", MediaFormatting.surroundLayout(6))
        assertEquals("7.1", MediaFormatting.surroundLayout(8))
        assertEquals("44.1 kHz", MediaFormatting.sampleRate(44100))
        assertEquals("1:05", MediaFormatting.duration(65_000))
        assertNull(MediaFormatting.duration(0))
    }

    @Test
    fun `sizes come from headers the platform can't read`() {
        // Height then width, big endian, after the signature, version and reserved bytes.
        val psd = "8BPS".toByteArray() + byteArrayOf(0, 1) + ByteArray(8) + byteArrayOf(0, 0, 2, 0, 0, 0, 3, 0)
        assertEquals(768 to 512, ImageHeaders.dimensions(psd + ByteArray(10), "psd"))
        val tga = ByteArray(18).also { it[2] = 2; it[12] = 0x20; it[13] = 0x03; it[14] = 0x58; it[15] = 0x02; it[16] = 24 }
        assertEquals(800 to 600, ImageHeaders.dimensions(tga, "tga"))
        assertEquals(40 to 30, ImageHeaders.dimensions("P6\n# hello 99 99\n40 30\n255\n".toByteArray(), "ppm"))
        assertNull(ImageHeaders.dimensions("hello".toByteArray(), "txt"))
    }
}
