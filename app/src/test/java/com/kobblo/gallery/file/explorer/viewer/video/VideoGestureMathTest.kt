package com.kobblo.gallery.file.explorer.viewer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VideoGestureMathTest {
    private val width = 1080f
    private val height = 2400f
    private val side = 90f
    private val top = 160f
    private val bottom = 160f

    @Test
    fun `touches at the edges are left for the system`() {
        fun safe(x: Float, y: Float) = VideoGestureMath.isInSafeZone(x, y, width, height, side, top, bottom)
        assertTrue("back gesture, left", safe(10f, 1200f))
        assertTrue("back gesture, right", safe(1070f, 1200f))
        assertTrue("status bar", safe(540f, 20f))
        assertTrue("navigation", safe(540f, 2390f))
        assertFalse(safe(300f, 1200f))
        assertFalse(safe(800f, 1200f))
    }

    @Test
    fun `sideways is seeking, wherever it starts`() {
        assertEquals(VideoDrag.Seek, VideoGestureMath.classify(100f, width, 50f, 10f))
        assertEquals(VideoDrag.Seek, VideoGestureMath.classify(540f, width, -50f, 10f))
        assertEquals(VideoDrag.Seek, VideoGestureMath.classify(1000f, width, 50f, -10f))
    }

    @Test
    fun `up and down depends on the side, and the middle does nothing`() {
        assertEquals(VideoDrag.Brightness, VideoGestureMath.classify(100f, width, 5f, 50f))
        assertEquals(VideoDrag.Volume, VideoGestureMath.classify(1000f, width, 5f, 50f))
        assertEquals(VideoDrag.None, VideoGestureMath.classify(540f, width, 5f, 50f))
    }

    @Test
    fun `a seek scales with the screen, up to two minutes across it`() {
        assertEquals(60_000L, VideoGestureMath.seekDelta(540f, width, 3_600_000L))
        assertEquals(-60_000L, VideoGestureMath.seekDelta(-540f, width, 3_600_000L))
        // A short clip uses its own length, so that the drag isn't twitchy.
        assertEquals(15_000L, VideoGestureMath.seekDelta(540f, width, 30_000L))
        // An unknown length falls back on the same two minutes.
        assertEquals(60_000L, VideoGestureMath.seekDelta(540f, width, 0L))
        assertEquals(0L, VideoGestureMath.seekDelta(540f, 0f, 30_000L))
    }

    @Test
    fun `a seek stays inside the clip`() {
        assertEquals(0L, VideoGestureMath.seekTarget(5_000L, -60_000L, 30_000L))
        assertEquals(30_000L, VideoGestureMath.seekTarget(25_000L, 60_000L, 30_000L))
        assertEquals(90_000L, VideoGestureMath.seekTarget(30_000L, 60_000L, 0L))
    }

    @Test
    fun `levels follow the finger, up being more, and stop at the ends`() {
        val range = height * 0.7f
        assertEquals(0.75f, VideoGestureMath.levelAfterDrag(0.5f, range / 4f, height), 0.001f)
        assertEquals(0.25f, VideoGestureMath.levelAfterDrag(0.5f, -range / 4f, height), 0.001f)
        assertEquals(1f, VideoGestureMath.levelAfterDrag(0.5f, height * 10f, height), 0.001f)
        assertEquals(0f, VideoGestureMath.levelAfterDrag(0.5f, -height * 10f, height), 0.001f)
    }

    @Test
    fun `a level becomes the nearest volume step`() {
        assertEquals(0, VideoGestureMath.volumeIndex(0f, 15))
        assertEquals(15, VideoGestureMath.volumeIndex(1f, 15))
        assertEquals(8, VideoGestureMath.volumeIndex(0.5f, 15))
        assertEquals(15, VideoGestureMath.volumeIndex(5f, 15))
    }

    @Test
    fun `a window is never dimmed all the way to black`() {
        assertTrue(VideoGestureMath.brightnessForWindow(0f) > 0f)
        assertEquals(1f, VideoGestureMath.brightnessForWindow(2f), 0.001f)
    }

    @Test
    fun `pinching out fills and in fits, ignoring fingers settling`() {
        assertEquals(VideoScale.Fill, VideoGestureMath.scaleForPinch(VideoScale.Fit, 1.3f))
        assertEquals(VideoScale.Fit, VideoGestureMath.scaleForPinch(VideoScale.Fill, 0.7f))
        assertEquals(VideoScale.Fit, VideoGestureMath.scaleForPinch(VideoScale.Fit, 1.05f))
        assertEquals(VideoScale.Stretch, VideoGestureMath.scaleForPinch(VideoScale.Stretch, 1f))
    }

    @Test
    fun `seek amounts read with a sign`() {
        assertEquals("+0:32", VideoGestureMath.signedTime(32_000L))
        assertEquals("-1:05", VideoGestureMath.signedTime(-65_000L))
    }

    @Test
    fun `scale modes go round in order`() {
        assertEquals(VideoScale.Fill, VideoScale.Fit.next())
        assertEquals(VideoScale.Stretch, VideoScale.Fill.next())
        assertEquals(VideoScale.Fit, VideoScale.Stretch.next())
    }

    @Test
    fun `fit shows all of the video and fill leaves no bars`() {
        val video = 16f / 9f
        val box = 1080f / 2400f
        val (fitW, fitH) = VideoScale.Fit.frameFraction(video, box)
        assertEquals(1f, fitW, 0.001f)
        assertEquals(box / video, fitH, 0.001f)
        val (fillW, fillH) = VideoScale.Fill.frameFraction(video, box)
        assertEquals(1f, fillH, 0.001f)
        assertEquals(video / box, fillW, 0.001f)
        assertEquals(1f to 1f, VideoScale.Stretch.frameFraction(video, box))
    }

    @Test
    fun `a taller video than the box fits by height`() {
        val video = 9f / 16f
        val box = 2400f / 1080f
        val (fitW, fitH) = VideoScale.Fit.frameFraction(video, box)
        assertEquals(1f, fitH, 0.001f)
        assertEquals(video / box, fitW, 0.001f)
    }

    @Test
    fun `an unknown shape fills the box until it's known`() {
        assertEquals(1f to 1f, VideoScale.Fit.frameFraction(0f, 0.5f))
    }

    @Test
    fun `the next video is the nearest one in the same folder, passing photos`() {
        val paths = listOf("/a/1.mp4", "/a/2.jpg", "/a/3.mp4", "/b/4.mp4", "/a/5.mp4")
        val isVideo = { i: Int -> paths[i].endsWith(".mp4") }
        assertEquals(2, VideoNeighbors.find(paths, isVideo, 0, 1))
        assertEquals(0, VideoNeighbors.find(paths, isVideo, 2, -1))
        assertEquals(4, VideoNeighbors.find(paths, isVideo, 2, 1))
        assertEquals(-1, VideoNeighbors.find(paths, isVideo, 4, 1))
        assertEquals(-1, VideoNeighbors.find(paths, isVideo, 0, -1))
        assertEquals(-1, VideoNeighbors.find(paths, isVideo, 3, 1))
        assertEquals(-1, VideoNeighbors.find(paths, isVideo, 99, 1))
    }

    @Test
    fun `resume isn't offered for short clips or near the end`() {
        assertFalse("short clip", ResumePolicy.isWorthKeeping(10_000, 20_000))
        assertTrue("just long enough", ResumePolicy.isWorthKeeping(10_000, 30_000))
        assertFalse("barely started", ResumePolicy.isWorthKeeping(3_000, 600_000))
        assertFalse("credits", ResumePolicy.isWorthKeeping(590_000, 600_000))
        assertTrue("well before the end", ResumePolicy.isWorthKeeping(500_000, 600_000))
        // A two-hour film's closing two percent is over two minutes.
        assertFalse(ResumePolicy.isWorthKeeping(7_100_000, 7_200_000))
    }

    @Test
    fun `a resume is tied to the file's size as well as its name`() {
        assertTrue(ResumePolicy.keyFor("/a/b.mp4", 1000L) != ResumePolicy.keyFor("/a/b.mp4", 2000L))
    }

    @Test
    fun `sidecar languages can have regions and qualifiers`() {
        val siblings = listOf("m.pt-BR.srt", "m.en.forced.srt", "m.en.sdh.vtt", "m.zh_TW.srt", "m.English.srt", "m.final.cut.srt", "m.Klingon.srt")
        val found = SidecarSubtitles.match("m.mp4", siblings)
        assertEquals("pt-BR", found.first { it.fileName == "m.pt-BR.srt" }.language)
        assertEquals("en", found.first { it.fileName == "m.en.forced.srt" }.language)
        assertEquals("en", found.first { it.fileName == "m.en.sdh.vtt" }.language)
        assertEquals("zh-TW", found.first { it.fileName == "m.zh_TW.srt" }.language)
        assertEquals("en", found.first { it.fileName == "m.English.srt" }.language)
        assertFalse(found.any { it.fileName == "m.final.cut.srt" })
        assertFalse(found.any { it.fileName == "m.Klingon.srt" })
    }

    @Test
    fun `sidecars are labelled by language name, or the extension without one`() {
        val english = Sidecar("m.en.srt", "srt", "en", "application/x-subrip")
        assertEquals("English", SidecarSubtitles.labelFor(english, Locale.ENGLISH))
        val plain = Sidecar("m.srt", "srt", null, "application/x-subrip")
        assertEquals("SRT", SidecarSubtitles.labelFor(plain, Locale.ENGLISH))
    }

    @Test
    fun `utf-8 and marked unicode are left alone`() {
        assertEquals(null, SubtitleEncoding.needsConverting("Café".toByteArray(Charsets.UTF_8), null, "en"))
        assertEquals(null, SubtitleEncoding.needsConverting(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x43, 0), null, "en"))
        assertEquals(null, SubtitleEncoding.needsConverting("plain".toByteArray(Charsets.US_ASCII), "ru", "en"))
    }

    @Test
    fun `older encodings are read by what language the file says it is`() {
        val russian = "Привет".toByteArray(charset("windows-1251"))
        val charset = SubtitleEncoding.needsConverting(russian, "ru", "en")
        assertEquals("windows-1251", charset?.name())
        assertEquals("Привет", String(russian, charset!!))
    }

    @Test
    fun `with no language in the name, the phone's language is the best guess`() {
        val arabic = "مرحبا".toByteArray(charset("windows-1256"))
        assertEquals("windows-1256", SubtitleEncoding.needsConverting(arabic, null, "ar-SA")?.name())
    }

    @Test
    fun `western text falls back on western encoding`() {
        val french = "Café déjà vu".toByteArray(charset("windows-1252"))
        val charset = SubtitleEncoding.needsConverting(french, null, "en-US")
        assertEquals("windows-1252", charset?.name())
        assertEquals("Café déjà vu", String(french, charset!!))
    }

    @Test
    fun `chinese depends on the region`() {
        assertEquals("GBK", SubtitleEncoding.charsetFor("zh").name())
        assertEquals("Big5", SubtitleEncoding.charsetFor("zh-TW").name())
    }
}
