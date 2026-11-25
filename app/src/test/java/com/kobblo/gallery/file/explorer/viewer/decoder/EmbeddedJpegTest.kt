package com.kobblo.gallery.file.explorer.viewer.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class EmbeddedJpegTest {
    private fun ff(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun jpeg(scanBytes: Int): ByteArray {
        val header = ff(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00, 0xFF, 0xDA, 0x00, 0x02)
        val scan = ByteArray(scanBytes) { (it % 200).toByte() }
        // Inside a scan, FF is always stuffed or a restart, and neither ends it.
        if (scanBytes > 8) {
            scan[3] = 0xFF.toByte(); scan[4] = 0x00
            scan[6] = 0xFF.toByte(); scan[7] = 0xD3.toByte()
        }
        return header + scan + ff(0xFF, 0xD9)
    }

    @Test
    fun `finds the largest jpeg among several`() {
        val small = jpeg(2000)
        val big = jpeg(9000)
        val file = ByteArray(100) + small + ByteArray(50) + big + ByteArray(30)
        val found = EmbeddedJpeg.findLargest(ByteBuffer.wrap(file))
        assertNotNull(found)
        assertEquals(100 + small.size + 50, found!!.offset)
        assertEquals(big.size, found.length)
    }

    @Test
    fun `tiny thumbnails don't count`() {
        assertNull(EmbeddedJpeg.findLargest(ByteBuffer.wrap(ByteArray(10) + jpeg(50))))
    }

    @Test
    fun `no jpeg or a cut off one finds nothing`() {
        assertNull(EmbeddedJpeg.findLargest(ByteBuffer.wrap(ByteArray(5000))))
        val cut = jpeg(4000).copyOf(3000)
        assertNull(EmbeddedJpeg.findLargest(ByteBuffer.wrap(cut)))
    }

    @Test
    fun `a stray start marker in other data doesn't hide the real one`() {
        val stray = ff(0xFF, 0xD8, 0xFF, 0x00, 0x00)
        val real = jpeg(3000)
        val found = EmbeddedJpeg.findLargest(ByteBuffer.wrap(stray + real))
        assertEquals(stray.size, found!!.offset)
    }
}
