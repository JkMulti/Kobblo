package com.kobblo.gallery.file.explorer.viewer.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

class ApngParserTest {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(12 + payload.size)
        out.putInt(payload.size)
        out.put(type.toByteArray())
        out.put(payload)
        val crc = CRC32()
        crc.update(type.toByteArray())
        crc.update(payload)
        out.putInt(crc.value.toInt())
        return out.array()
    }

    private fun ihdr(w: Int, h: Int): ByteArray = ByteBuffer.allocate(13).apply {
        putInt(w); putInt(h); put(8); put(2); put(0); put(0); put(0)
    }.array()

    private fun actl(frames: Int, plays: Int): ByteArray = ByteBuffer.allocate(8).apply { putInt(frames); putInt(plays) }.array()

    private fun fctl(seq: Int, w: Int, h: Int, x: Int, y: Int, delayNum: Int, delayDen: Int, dispose: Int, blend: Int): ByteArray =
        ByteBuffer.allocate(26).apply {
            putInt(seq); putInt(w); putInt(h); putInt(x); putInt(y)
            putShort(delayNum.toShort()); putShort(delayDen.toShort()); put(dispose.toByte()); put(blend.toByte())
        }.array()

    private fun scanlines(w: Int, h: Int, r: Int, g: Int, b: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) { raw.write(r); raw.write(g); raw.write(b) }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun seq(sequence: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + data.size).apply { putInt(sequence); put(data) }.array()

    private fun apng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ihdr(4, 4)))
        out.write(chunk("acTL", actl(2, 0)))
        out.write(chunk("fcTL", fctl(0, 4, 4, 0, 0, 1, 2, ApngParser.DisposeNone, ApngParser.BlendSource)))
        out.write(chunk("IDAT", scanlines(4, 4, 255, 0, 0)))
        out.write(chunk("fcTL", fctl(1, 2, 2, 1, 1, 0, 0, ApngParser.DisposeBackground, ApngParser.BlendOver)))
        out.write(chunk("fdAT", seq(2, scanlines(2, 2, 0, 255, 0))))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun stillPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ihdr(1, 1)))
        out.write(chunk("IDAT", scanlines(1, 1, 1, 2, 3)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `an animated png is recognized and a still one isn't`() {
        assertTrue(ApngParser.isAnimated(apng()))
        assertFalse(ApngParser.isAnimated(stillPng()))
        assertNull(ApngParser.parse(stillPng()))
        assertFalse(ApngParser.isAnimated("not a png".toByteArray()))
    }

    @Test
    fun `frames carry their placement timing and disposal`() {
        val animation = ApngParser.parse(apng())
        assertNotNull(animation)
        assertEquals(4, animation!!.width)
        assertEquals(0, animation.plays)
        assertEquals(2, animation.frames.size)
        val first = animation.frames[0]
        assertEquals(500, first.delayMillis)
        val second = animation.frames[1]
        assertEquals(1, second.x)
        assertEquals(1, second.y)
        assertEquals(2, second.width)
        assertEquals(ApngParser.DisposeBackground, second.dispose)
        assertEquals(ApngParser.BlendOver, second.blend)
        // A delay of nothing would spin, so it falls back to something watchable.
        assertEquals(100, second.delayMillis)
    }

    @Test
    fun `each frame is a standalone png another decoder can read`() {
        val animation = ApngParser.parse(apng())!!
        val first = ImageIO.read(ByteArrayInputStream(animation.frames[0].png))
        assertEquals(4, first.width)
        assertEquals(0xFFFF0000.toInt(), first.getRGB(0, 0))
        val second = ImageIO.read(ByteArrayInputStream(animation.frames[1].png))
        assertEquals(2, second.width)
        assertEquals(0xFF00FF00.toInt(), second.getRGB(1, 1))
    }

    @Test
    fun `a default image outside the animation isn't a frame`() {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ihdr(2, 2)))
        out.write(chunk("acTL", actl(1, 1)))
        out.write(chunk("IDAT", scanlines(2, 2, 9, 9, 9)))
        out.write(chunk("fcTL", fctl(0, 2, 2, 0, 0, 1, 10, 0, 0)))
        out.write(chunk("fdAT", seq(1, scanlines(2, 2, 200, 0, 0))))
        out.write(chunk("IEND", ByteArray(0)))
        val animation = ApngParser.parse(out.toByteArray())!!
        assertEquals(1, animation.frames.size)
        assertEquals(1, animation.plays)
        val only = ImageIO.read(ByteArrayInputStream(animation.frames[0].png))
        assertEquals(0xFFC80000.toInt(), only.getRGB(0, 0))
    }

    @Test
    fun `a frame reaching past the canvas is refused`() {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ihdr(2, 2)))
        out.write(chunk("acTL", actl(1, 0)))
        out.write(chunk("fcTL", fctl(0, 4, 4, 1, 1, 1, 10, 0, 0)))
        out.write(chunk("IDAT", scanlines(4, 4, 1, 1, 1)))
        out.write(chunk("IEND", ByteArray(0)))
        assertNull(ApngParser.parse(out.toByteArray()))
    }

    @Test
    fun `a truncated file is refused without throwing`() {
        val whole = apng()
        for (cut in listOf(10, 40, whole.size / 2, whole.size - 5)) {
            ApngParser.parse(whole.copyOf(cut))
        }
    }
}
