package com.kobblo.gallery.file.explorer.viewer.decoder

import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.Long
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.Short
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.TiffPage
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.TiffTag
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.expected
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.expectedGray
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.grayBytes
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.rgb
import com.kobblo.gallery.file.explorer.viewer.decoder.DecoderFixtures.rgbBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class RasterDecodersTest {
    private val W = 5
    private val H = 4

    private fun assertRgb(image: RasterImage, w: Int = W, h: Int = H) {
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("pixel $x,$y", expected(x, y), image.pixels[y * w + x])
        }
    }

    private fun assertGray(image: RasterImage, w: Int = W, h: Int = H) {
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("pixel $x,$y", expectedGray(x, y), image.pixels[y * w + x])
        }
    }

    // ── TIFF ──────────────────────────────────────────────────────────────────

    @Test
    fun `tiff uncompressed rgb in both byte orders`() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 1), listOf(rgbBytes(W, H)))), order)
            assertRgb(TiffDecoder.decode(file))
        }
    }

    @Test
    fun `tiff uncompressed gray`() {
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.grayTiffTags(W, H, 1), listOf(grayBytes(W, H)))))
        assertGray(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff white is zero inverts gray`() {
        val inverted = grayBytes(W, H).map { (255 - (it.toInt() and 0xFF)).toByte() }.toByteArray()
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.grayTiffTags(W, H, 1, photometric = 0), listOf(inverted))))
        assertGray(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff packbits rgb and gray`() {
        val rgbFile = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 32773), listOf(DecoderFixtures.packBits(rgbBytes(W, H))))))
        assertRgb(TiffDecoder.decode(rgbFile))
        val grayFile = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.grayTiffTags(W, H, 32773), listOf(DecoderFixtures.packBits(grayBytes(W, H))))))
        assertGray(TiffDecoder.decode(grayFile))
    }

    @Test
    fun `tiff lzw rgb and gray`() {
        val rgbFile = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 5), listOf(DecoderFixtures.lzw(rgbBytes(W, H))))))
        assertRgb(TiffDecoder.decode(rgbFile))
        val grayFile = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.grayTiffTags(W, H, 5), listOf(DecoderFixtures.lzw(grayBytes(W, H))))))
        assertGray(TiffDecoder.decode(grayFile))
    }

    @Test
    fun `tiff lzw crosses every code width on a large repetitive picture`() {
        // Enough varied data to force the width past 9, 10 and 11 bits and at least one clear code.
        val w = 400
        val h = 300
        val data = ByteArray(w * h * 3) { i -> ((i * 7) xor (i shr 3) xor (i * i shr 11)).toByte() }
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(w, h, 5), listOf(DecoderFixtures.lzw(data)))))
        val image = TiffDecoder.decode(file)
        var at = 0
        for (y in 0 until h) for (x in 0 until w) {
            val r = data[at++].toInt() and 0xFF
            val g = data[at++].toInt() and 0xFF
            val b = data[at++].toInt() and 0xFF
            assertEquals("pixel $x,$y", opaque(r, g, b), image.pixels[y * w + x])
        }
    }

    @Test
    fun `tiff lzw handles a run that references itself`() {
        // A long run of one byte makes the encoder emit the code it is just defining.
        val data = ByteArray(W * H * 3) { 7 }
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 5), listOf(DecoderFixtures.lzw(data)))))
        val image = TiffDecoder.decode(file)
        assertTrue(image.pixels.all { it == opaque(7, 7, 7) })
    }

    @Test
    fun `tiff lzw with predictor undoes the differencing`() {
        val raw = rgbBytes(W, H)
        val differenced = raw.copyOf()
        for (y in 0 until H) for (x in W - 1 downTo 1) for (s in 0 until 3) {
            val i = (y * W + x) * 3 + s
            differenced[i] = (raw[i] - raw[i - 3]).toByte()
        }
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 5, predictor = 2), listOf(DecoderFixtures.lzw(differenced)))))
        assertRgb(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff deflate`() {
        val deflater = Deflater()
        deflater.setInput(rgbBytes(W, H))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk))
        deflater.end()
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 8), listOf(out.toByteArray()))))
        assertRgb(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff several strips`() {
        val all = rgbBytes(W, H)
        val strips = listOf(all.copyOfRange(0, W * 3 * 3), all.copyOfRange(W * 3 * 3, all.size))
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 1, rowsPerStrip = 3), strips)))
        assertRgb(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff pages are counted and read separately`() {
        fun page(shift: Int): TiffPage {
            val bytes = rgbBytes(W, H).map { (it + shift).toByte() }.toByteArray()
            return TiffPage(DecoderFixtures.rgbTiffTags(W, H, 1), listOf(bytes))
        }
        val file = DecoderFixtures.tiff(listOf(page(0), page(1), page(2)))
        assertEquals(3, TiffDecoder.pageCount(file))
        assertRgb(TiffDecoder.decode(file, page = 0))
        val second = TiffDecoder.decode(file, page = 1)
        val c = rgb(0, 0)
        assertEquals(opaque(c[0] + 1, c[1] + 1, c[2] + 1), second.pixels[0])
        val third = TiffDecoder.decode(file, page = 2)
        assertEquals(opaque(c[0] + 2, c[1] + 2, c[2] + 2), third.pixels[0])
        assertThrows(ImageFormatException::class.java) { TiffDecoder.decode(file, page = 3) }
    }

    @Test
    fun `tiff previews don't count as pages`() {
        val preview = TiffPage(DecoderFixtures.grayTiffTags(W, H, 1) + TiffTag(254, Long, intArrayOf(1)), listOf(grayBytes(W, H)))
        val real = TiffPage(DecoderFixtures.rgbTiffTags(W, H, 1), listOf(rgbBytes(W, H)))
        val file = DecoderFixtures.tiff(listOf(preview, real))
        assertEquals(1, TiffDecoder.pageCount(file))
        assertRgb(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff tiles are placed where they belong and clipped at the edge`() {
        // Six wide and four tall in tiles of four, so the second tile runs past the picture's right edge.
        val w = 6
        val h = 4
        fun tile(startX: Int): ByteArray {
            val out = ByteArrayOutputStream()
            for (y in 0 until 4) for (x in startX until startX + 4) rgb(x, y).forEach { out.write(it) }
            return out.toByteArray()
        }
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(w)), TiffTag(257, Long, intArrayOf(h)), TiffTag(258, Short, intArrayOf(8, 8, 8)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(2)), TiffTag(277, Short, intArrayOf(3)),
            TiffTag(322, Long, intArrayOf(4)), TiffTag(323, Long, intArrayOf(4))
        )
        val image = TiffDecoder.decode(DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(tile(0), tile(4)), tiled = true))))
        assertRgb(image, w, h)
    }

    @Test
    fun `tiff with sixteen bits a sample keeps the top byte`() {
        val w = 2
        val h = 1
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(w)), TiffTag(257, Long, intArrayOf(h)), TiffTag(258, Short, intArrayOf(16)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(1)), TiffTag(277, Short, intArrayOf(1)),
            TiffTag(278, Long, intArrayOf(h))
        )
        val little = DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(byteArrayOf(0x34, 0x12, 0x78, 0x56)))))
        val image = TiffDecoder.decode(little)
        assertEquals(opaque(0x12, 0x12, 0x12), image.pixels[0])
        assertEquals(opaque(0x56, 0x56, 0x56), image.pixels[1])
    }

    @Test
    fun `tiff with one bit a sample reads each bit`() {
        val w = 3
        val h = 1
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(w)), TiffTag(257, Long, intArrayOf(h)), TiffTag(258, Short, intArrayOf(1)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(1)), TiffTag(277, Short, intArrayOf(1)),
            TiffTag(278, Long, intArrayOf(h))
        )
        val image = TiffDecoder.decode(DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(byteArrayOf(0b10100000.toByte()))))))
        assertEquals(opaque(255, 255, 255), image.pixels[0])
        assertEquals(opaque(0, 0, 0), image.pixels[1])
        assertEquals(opaque(255, 255, 255), image.pixels[2])
    }

    @Test
    fun `tiff cmyk stores ink directly`() {
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(1)), TiffTag(257, Long, intArrayOf(1)), TiffTag(258, Short, intArrayOf(8, 8, 8, 8)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(5)), TiffTag(277, Short, intArrayOf(4)),
            TiffTag(278, Long, intArrayOf(1))
        )
        val image = TiffDecoder.decode(DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(byteArrayOf(0, 0xFF.toByte(), 0, 0))))))
        assertEquals(opaque(255, 0, 255), image.pixels[0])
    }

    @Test
    fun `tiff with alpha keeps it`() {
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(1)), TiffTag(257, Long, intArrayOf(1)), TiffTag(258, Short, intArrayOf(8, 8, 8, 8)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(2)), TiffTag(277, Short, intArrayOf(4)),
            TiffTag(278, Long, intArrayOf(1)), TiffTag(338, Short, intArrayOf(2))
        )
        val image = TiffDecoder.decode(DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(byteArrayOf(1, 2, 3, 0x40))))))
        assertEquals(argb(0x40, 1, 2, 3), image.pixels[0])
    }

    @Test
    fun `tiff holding only a preview still shows it`() {
        val preview = TiffPage(DecoderFixtures.rgbTiffTags(W, H, 1) + TiffTag(254, Long, intArrayOf(1)), listOf(rgbBytes(W, H)))
        val file = DecoderFixtures.tiff(listOf(preview))
        assertEquals(1, TiffDecoder.pageCount(file))
        assertRgb(TiffDecoder.decode(file))
    }

    @Test
    fun `tiff palette`() {
        val w = 2
        val h = 2
        val map = IntArray(3 * 256)
        map[1] = 0xAA00
        map[256 + 1] = 0xBB00
        map[512 + 1] = 0xCC00
        val tags = listOf(
            TiffTag(256, Long, intArrayOf(w)), TiffTag(257, Long, intArrayOf(h)), TiffTag(258, Short, intArrayOf(8)),
            TiffTag(259, Short, intArrayOf(1)), TiffTag(262, Short, intArrayOf(3)), TiffTag(277, Short, intArrayOf(1)),
            TiffTag(278, Long, intArrayOf(h)), TiffTag(320, Short, map)
        )
        val file = DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(byteArrayOf(1, 0, 0, 1)))))
        val image = TiffDecoder.decode(file)
        assertEquals(opaque(0xAA, 0xBB, 0xCC), image.pixels[0])
        assertEquals(opaque(0, 0, 0), image.pixels[1])
    }

    @Test
    fun `tiff decodes at a reduced size`() {
        val w = 40
        val h = 20
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(w, h, 1), listOf(rgbBytes(w, h)))))
        val image = TiffDecoder.decode(file, target = DecodeTarget.fit(10))
        assertEquals(10, image.width)
        assertEquals(5, image.height)
        assertEquals(w, image.fullWidth)
        assertTrue(image.isSampled)
        assertEquals(expected(4, 8), image.pixels[2 * 10 + 1])
    }

    @Test
    fun `tiff orientation turns the picture upright`() {
        val tags = DecoderFixtures.rgbTiffTags(W, H, 1) + TiffTag(274, Short, intArrayOf(6))
        val file = DecoderFixtures.tiff(listOf(TiffPage(tags, listOf(rgbBytes(W, H)))))
        val image = TiffDecoder.decode(file)
        assertEquals(H, image.width)
        assertEquals(W, image.height)
        // Orientation 6 means the picture must be turned a quarter clockwise, so the file's
        // top-left pixel ends up at the top right.
        assertEquals(expected(0, 0), image.pixels[H - 1])
        assertEquals(expected(0, H - 1), image.pixels[0])
    }

    @Test
    fun `tiff that lies about itself fails cleanly`() {
        assertThrows(ImageFormatException::class.java) { TiffDecoder.decode(ByteBuffer.wrap(ByteArray(4))) }
        assertThrows(ImageFormatException::class.java) { TiffDecoder.decode(ByteBuffer.wrap("II*\u0000garbage".toByteArray())) }
        // A page link pointing back at itself must not loop forever.
        val loop = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        loop.put("II".toByteArray()); loop.putShort(42); loop.putInt(8)
        loop.putShort(0); loop.putInt(8)
        assertThrows(ImageFormatException::class.java) { TiffDecoder.pageCount(ByteBuffer.wrap(loop.array())) }
        // A huge declared size must be read in small steps, and never allocated whole.
        val hugeTags = DecoderFixtures.rgbTiffTags(100_000, 100_000, 1)
        val hugeFile = DecoderFixtures.tiff(listOf(TiffPage(hugeTags, listOf(ByteArray(3)))))
        val sampled = TiffDecoder.decode(hugeFile)
        assertTrue(sampled.width.toLong() * sampled.height <= MaxDecodedPixels)
        // A compressed strip that size can't be unpacked at all, and says so instead of running out of memory.
        val hugeCompressed = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(100_000, 100_000, 5), listOf(ByteArray(3)))))
        assertThrows(ImageFormatException::class.java) { TiffDecoder.decode(hugeCompressed) }
    }

    @Test
    fun `tiff with an unknown compression says so`() {
        val file = DecoderFixtures.tiff(listOf(TiffPage(DecoderFixtures.rgbTiffTags(W, H, 7), listOf(ByteArray(20)))))
        assertThrows(ImageFormatException::class.java) { TiffDecoder.decode(file) }
    }

    // ── PSD ───────────────────────────────────────────────────────────────────

    private fun planar(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in 0 until 3) for (y in 0 until height) for (x in 0 until width) out.write(rgb(x, y)[c])
        return out.toByteArray()
    }

    @Test
    fun `psd raw rgb composite`() {
        assertRgb(PsdDecoder.decode(DecoderFixtures.psd(W, H, 3, 3, 8, planar(W, H), 0)))
    }

    @Test
    fun `psd rle rgb composite`() {
        val table = ByteArrayOutputStream()
        val rows = ByteArrayOutputStream()
        val plain = planar(W, H)
        for (row in 0 until 3 * H) {
            val packed = DecoderFixtures.packBits(plain.copyOfRange(row * W, (row + 1) * W))
            table.write(packed.size shr 8)
            table.write(packed.size and 0xFF)
            rows.write(packed)
        }
        val data = table.toByteArray() + rows.toByteArray()
        assertRgb(PsdDecoder.decode(DecoderFixtures.psd(W, H, 3, 3, 8, data, 1)))
    }

    @Test
    fun `psd gray and sixteen bit`() {
        assertGray(PsdDecoder.decode(DecoderFixtures.psd(W, H, 1, 1, 8, grayBytes(W, H), 0)))
        val sixteen = ByteArrayOutputStream()
        for (y in 0 until H) for (x in 0 until W) {
            sixteen.write(DecoderFixtures.gray(x, y))
            sixteen.write(0x7F)
        }
        assertGray(PsdDecoder.decode(DecoderFixtures.psd(W, H, 1, 1, 16, sixteen.toByteArray(), 0)))
    }

    @Test
    fun `psd cmyk stores ink inverted`() {
        val data = byteArrayOf(0xFF.toByte(), 0, 0x80.toByte(), 0xFF.toByte())
        val image = PsdDecoder.decode(DecoderFixtures.psd(1, 1, 4, 4, 8, data, 0))
        assertEquals(opaque(255, 0, 128), image.pixels[0])
    }

    @Test
    fun `psd ignores extra channels and reads the palette`() {
        val withAlpha = planar(W, H) + ByteArray(W * H) { 9 }
        assertRgb(PsdDecoder.decode(DecoderFixtures.psd(W, H, 4, 3, 8, withAlpha, 0)))

        val palette = ByteArray(768).also { it[2] = 10; it[256 + 2] = 20; it[512 + 2] = 30 }
        val indexed = PsdDecoder.decode(DecoderFixtures.psd(1, 1, 1, 2, 8, byteArrayOf(2), 0, palette))
        assertEquals(opaque(10, 20, 30), indexed.pixels[0])
    }

    @Test
    fun `psd decodes at a reduced size`() {
        val image = PsdDecoder.decode(DecoderFixtures.psd(W * 2, H * 2, 3, 3, 8, planar(W * 2, H * 2), 0), target = DecodeTarget.fit(W))
        assertEquals(W, image.width)
        assertEquals(H, image.height)
        assertEquals(expected(2, 2), image.pixels[1 * W + 1])
    }

    @Test
    fun `broken psd fails cleanly`() {
        assertThrows(ImageFormatException::class.java) { PsdDecoder.decode(ByteBuffer.wrap("8BPS".toByteArray())) }
        assertThrows(ImageFormatException::class.java) { PsdDecoder.decode(DecoderFixtures.psd(W, H, 3, 3, 8, ByteArray(3), 0)) }
    }

    // ── TGA ───────────────────────────────────────────────────────────────────

    private fun bgr(width: Int, height: Int, bottomUp: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val rows = if (bottomUp) (height - 1 downTo 0) else (0 until height)
        for (y in rows) for (x in 0 until width) rgb(x, y).let { out.write(it[2]); out.write(it[1]); out.write(it[0]) }
        return out.toByteArray()
    }

    @Test
    fun `tga bottom up and top down`() {
        assertRgb(TgaDecoder.decode(DecoderFixtures.tga(W, H, 2, 24, 0x00, bgr(W, H, bottomUp = true))))
        assertRgb(TgaDecoder.decode(DecoderFixtures.tga(W, H, 2, 24, 0x20, bgr(W, H, bottomUp = false))))
    }

    @Test
    fun `tga skips the id field`() {
        assertRgb(TgaDecoder.decode(DecoderFixtures.tga(W, H, 2, 24, 0x20, bgr(W, H, bottomUp = false), id = ByteArray(7) { 1 })))
    }

    @Test
    fun `tga thirty two bit with alpha`() {
        val body = byteArrayOf(1, 2, 3, 0x40, 4, 5, 6, 0x80.toByte())
        val image = TgaDecoder.decode(DecoderFixtures.tga(2, 1, 2, 32, 0x28, body))
        assertEquals(argb(0x40, 3, 2, 1), image.pixels[0])
        assertEquals(argb(0x80, 6, 5, 4), image.pixels[1])
    }

    @Test
    fun `tga with an all zero alpha channel stays visible`() {
        val body = byteArrayOf(1, 2, 3, 0, 4, 5, 6, 0)
        val image = TgaDecoder.decode(DecoderFixtures.tga(2, 1, 2, 32, 0x28, body))
        assertEquals(opaque(3, 2, 1), image.pixels[0])
    }

    @Test
    fun `tga run length encoded`() {
        val out = ByteArrayOutputStream()
        // A run of three of the same pixel, then two literals.
        out.write(0x82); out.write(byteArrayOf(10, 20, 30))
        out.write(0x01); out.write(byteArrayOf(1, 2, 3)); out.write(byteArrayOf(4, 5, 6))
        val image = TgaDecoder.decode(DecoderFixtures.tga(5, 1, 10, 24, 0x20, out.toByteArray()))
        assertEquals(opaque(30, 20, 10), image.pixels[0])
        assertEquals(opaque(30, 20, 10), image.pixels[2])
        assertEquals(opaque(3, 2, 1), image.pixels[3])
        assertEquals(opaque(6, 5, 4), image.pixels[4])
    }

    @Test
    fun `tga gray and color mapped`() {
        assertGray(TgaDecoder.decode(DecoderFixtures.tga(W, H, 3, 8, 0x20, grayBytes(W, H))))
        val map = byteArrayOf(9, 9, 9, 30, 20, 10)
        val image = TgaDecoder.decode(DecoderFixtures.tga(1, 1, 1, 8, 0x20, byteArrayOf(1), map = map, mapLength = 2, mapDepth = 24))
        assertEquals(opaque(10, 20, 30), image.pixels[0])
    }

    @Test
    fun `tga sixteen bit`() {
        // One pixel: red 31, green 0, blue 0, in 5-5-5.
        val image = TgaDecoder.decode(DecoderFixtures.tga(1, 1, 2, 16, 0x20, byteArrayOf(0x00, 0x7C)))
        assertEquals(opaque(255, 0, 0), image.pixels[0])
    }

    @Test
    fun `tga sanity check rejects other files`() {
        assertFalse(TgaDecoder.looksLikeTga(ByteArray(4)))
        assertFalse(TgaDecoder.looksLikeTga("<html><body>not a picture</body>".toByteArray()))
        val real = DecoderFixtures.tga(W, H, 2, 24, 0, bgr(W, H, true))
        assertTrue(TgaDecoder.looksLikeTga(ByteArray(18) { real.get(it) }))
        assertThrows(ImageFormatException::class.java) { TgaDecoder.decode(DecoderFixtures.tga(W, H, 2, 24, 0, ByteArray(5))) }
    }

    // ── PNM ───────────────────────────────────────────────────────────────────

    private fun pnm(text: String, body: ByteArray = ByteArray(0)) = ByteBuffer.wrap(text.toByteArray() + body)

    @Test
    fun `ppm binary and text with comments`() {
        assertRgb(PnmDecoder.decode(pnm("P6\n# made by hand\n$W $H\n255\n", rgbBytes(W, H))))
        val text = StringBuilder("P3 $W $H 255\n")
        for (y in 0 until H) for (x in 0 until W) rgb(x, y).forEach { text.append(it).append(' ') }
        assertRgb(PnmDecoder.decode(pnm(text.toString())))
    }

    @Test
    fun `pgm binary and text`() {
        assertGray(PnmDecoder.decode(pnm("P5 $W $H 255\n", grayBytes(W, H))))
        val text = StringBuilder("P2\n$W $H\n255\n")
        for (y in 0 until H) for (x in 0 until W) text.append(DecoderFixtures.gray(x, y)).append('\n')
        assertGray(PnmDecoder.decode(pnm(text.toString())))
    }

    @Test
    fun `pgm scales other max values and sixteen bit`() {
        val low = PnmDecoder.decode(pnm("P2 1 1 15\n15\n"))
        assertEquals(opaque(255, 255, 255), low.pixels[0])
        val wide = PnmDecoder.decode(pnm("P5 1 1 65535\n", byteArrayOf(0x80.toByte(), 0x00)))
        val v = (0x8000L * 255 / 65535).toInt()
        assertEquals(opaque(v, v, v), wide.pixels[0])
    }

    @Test
    fun `pbm set bits are black`() {
        val binary = PnmDecoder.decode(pnm("P4 2 1\n", byteArrayOf(0b01000000)))
        assertEquals(opaque(255, 255, 255), binary.pixels[0])
        assertEquals(opaque(0, 0, 0), binary.pixels[1])
        val text = PnmDecoder.decode(pnm("P1 2 1\n1 0\n"))
        assertEquals(opaque(0, 0, 0), text.pixels[0])
        assertEquals(opaque(255, 255, 255), text.pixels[1])
    }

    @Test
    fun `broken pnm fails cleanly`() {
        assertThrows(ImageFormatException::class.java) { PnmDecoder.decode(pnm("P9 1 1 255\n")) }
        assertThrows(ImageFormatException::class.java) { PnmDecoder.decode(pnm("P6 2 2 255\n", ByteArray(3))) }
        assertThrows(ImageFormatException::class.java) { PnmDecoder.decode(pnm("P6 a b c\n")) }
        assertFalse(PnmDecoder.isPnm("Plain text".toByteArray()))
        assertTrue(PnmDecoder.isPnm("P6\n".toByteArray()))
    }

    // ── Reduced sizes ─────────────────────────────────────────────────────────

    @Test
    fun `a target never goes below what was asked for`() {
        // Fitted: the long side lands on or above the target.
        assertEquals(10, DecodeTarget.fit(300).stepFor(3000, 600))
        // Filled: a wide picture cropped to a square keeps enough height to cover it.
        assertEquals(1, DecodeTarget(1000, 1000, fill = true).stepFor(3000, 600))
        assertEquals(1, DecodeTarget.Original.stepFor(100, 100))
    }

    @Test
    fun `a target holds pixels below the memory cap`() {
        val step = DecodeTarget.Original.stepFor(30_000, 30_000)
        assertTrue(sampledSize(30_000, step).toLong() * sampledSize(30_000, step) <= MaxDecodedPixels)
    }

    @Test
    fun `packbits stops cleanly when input runs out`() {
        val out = ByteArray(10)
        val written = unpackBits(ByteBuffer.wrap(byteArrayOf(5, 1, 2)), 0, 3, out)
        assertEquals(2, written)
        assertEquals(1.toByte(), out[0])
    }
}
