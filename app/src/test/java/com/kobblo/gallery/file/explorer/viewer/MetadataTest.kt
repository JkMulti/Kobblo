package com.kobblo.gallery.file.explorer.viewer

import com.kobblo.gallery.file.explorer.viewer.metadata.ArrayByteSource
import com.kobblo.gallery.file.explorer.viewer.metadata.CodecNames
import com.kobblo.gallery.file.explorer.viewer.metadata.Iptc
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegMetadataRewriter
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegSegments
import com.kobblo.gallery.file.explorer.viewer.metadata.Mp4MetadataStripper
import com.kobblo.gallery.file.explorer.viewer.metadata.Xmp
import com.kobblo.gallery.file.explorer.viewer.motion.MotionClip
import com.kobblo.gallery.file.explorer.viewer.motion.MotionPhoto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MetadataTest {
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val imageData = byteArrayOf(
        0xFF.toByte(), 0xC0.toByte(), 0x00, 0x03, 0x11,
        0xFF.toByte(), 0xDA.toByte(), 0x00, 0x03, 0x22,
        1, 2, 3, 0xFF.toByte(), 0x00, 4, 5,
        0xFF.toByte(), 0xD9.toByte()
    )

    private fun exif(order: ByteOrder = ByteOrder.BIG_ENDIAN, orientation: Int = 6): ByteArray {
        // IFD0 with orientation, a make, and a pointer to a GPS block holding two rational values.
        val tiff = ByteBuffer.allocate(200).order(order)
        tiff.put(if (order == ByteOrder.BIG_ENDIAN) "MM".toByteArray() else "II".toByteArray())
        tiff.putShort(42)
        tiff.putInt(8)
        tiff.putShort(3)
        tiff.putShort(0x0112); tiff.putShort(3); tiff.putInt(1); tiff.putShort(orientation.toShort()); tiff.putShort(0)
        tiff.putShort(0x010F); tiff.putShort(2); tiff.putInt(4); tiff.put("Cam\u0000".toByteArray())
        tiff.putShort(0x8825.toShort()); tiff.putShort(4); tiff.putInt(1); tiff.putInt(64)
        tiff.putInt(0)
        tiff.position(64)
        tiff.putShort(2)
        tiff.putShort(1); tiff.putShort(2); tiff.putInt(2); tiff.put("N\u0000\u0000\u0000".toByteArray())
        tiff.putShort(2); tiff.putShort(5); tiff.putInt(3); tiff.putInt(120)
        tiff.putInt(0)
        tiff.position(120)
        tiff.putInt(24); tiff.putInt(1); tiff.putInt(30); tiff.putInt(1); tiff.putInt(0); tiff.putInt(1)
        return "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
    }

    private val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun xmpPacket(body: String): ByteArray = xmpPrefix + """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">$body</rdf:RDF></x:xmpmeta>""".toByteArray()

    private fun jpeg(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        segments.forEach(out::write)
        out.write(imageData)
        return out.toByteArray()
    }

    private fun mp4Clip(): ByteArray {
        val out = ByteArrayOutputStream()
        val ftyp = ByteBuffer.allocate(24).apply { putInt(24); put("ftypisom".toByteArray()); putInt(0); put("isomavc1".toByteArray()) }.array()
        out.write(ftyp)
        out.write(ByteBuffer.allocate(40).apply { putInt(40); put("mdat".toByteArray()) }.array())
        return out.toByteArray()
    }

    // ── JPEG segments ─────────────────────────────────────────────────────────

    @Test
    fun `segments stop at the image data`() {
        val file = jpeg(segment(0xE0, ByteArray(4)), segment(0xE1, exif()))
        val segments = JpegSegments.read(ArrayByteSource(file))
        assertEquals(listOf(0xE0, 0xE1, 0xC0), segments.map { it.marker })
    }

    @Test
    fun `xmp and iptc are found by their prefixes`() {
        val packet = xmpPacket("""<rdf:Description xmlns:dc="d" dc:title="Sunset"/>""")
        val iptc = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + photoshopBlock(mapOf(120 to "A caption"))
        val source = ArrayByteSource(jpeg(segment(0xE1, exif()), segment(0xE1, packet), segment(0xED, iptc)))
        assertTrue(JpegSegments.xmp(source)!!.contains("Sunset"))
        assertEquals(listOf("Caption" to "A caption"), Iptc.parse(JpegSegments.iptc(source)!!))
    }

    @Test
    fun `a file that isn't a jpeg has no segments`() {
        assertTrue(JpegSegments.read(ArrayByteSource("hello world".toByteArray())).isEmpty())
        assertNull(JpegSegments.xmp(ArrayByteSource(ByteArray(0))))
    }

    @Test
    fun `a segment claiming too much doesn't crash`() {
        val file = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00)
        val source = ArrayByteSource(file)
        assertNull(JpegSegments.xmp(source))
    }

    // ── XMP ───────────────────────────────────────────────────────────────────

    @Test
    fun `xmp properties come from attributes elements and lists`() {
        val xmp = """
            <rdf:Description xmlns:GCamera="g" GCamera:MicroVideo="1" xmp:CreatorTool="Tool &amp; Co">
              <dc:creator><rdf:Seq><rdf:li>Ann</rdf:li><rdf:li>Bo</rdf:li></rdf:Seq></dc:creator>
              <photoshop:City>Karachi</photoshop:City>
            </rdf:Description>
        """.trimIndent()
        val found = Xmp.properties(xmp).toMap()
        assertEquals("1", found["GCamera:MicroVideo"])
        assertEquals("Tool & Co", found["xmp:CreatorTool"])
        assertEquals("Ann, Bo", found["dc:creator"])
        assertEquals("Karachi", found["photoshop:City"])
        assertFalse(found.keys.any { it.startsWith("xmlns") || it.startsWith("rdf:") })
    }

    @Test
    fun `xmp escapes are undone once and no further`() {
        val xmp = """<rdf:Description dc:title="a &lt;b&gt; &quot;c&quot; &apos;d&apos; e&#10;f &amp; g" dc:note="&amp;lt;"/>"""
        val found = Xmp.properties(xmp).toMap()
        assertEquals("a <b> \"c\" 'd' e f & g", found["dc:title"])
        // Text that spells out an entity stays spelled out, rather than becoming the character.
        assertEquals("&lt;", found["dc:note"])
        assertEquals("R&D", Xmp.value("""<a xx:Label="R&amp;D"/>""", "Label"))
    }

    @Test
    fun `xmp value finds a name under any prefix`() {
        assertEquals("123", Xmp.value("""<a xx:MicroVideoOffset="123"/>""", "MicroVideoOffset"))
        assertEquals("456", Xmp.value("""<yy:MicroVideoOffset>456</yy:MicroVideoOffset>""", "MicroVideoOffset"))
        assertNull(Xmp.value("""<a b="1"/>""", "MicroVideoOffset"))
    }

    // ── IPTC ──────────────────────────────────────────────────────────────────

    private fun photoshopBlock(values: Map<Int, String>, repeats: List<Pair<Int, String>> = emptyList()): ByteArray {
        val iim = ByteArrayOutputStream()
        (values.toList() + repeats).forEach { (dataSet, text) ->
            val bytes = text.toByteArray()
            iim.write(byteArrayOf(0x1C, 2, dataSet.toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte()))
            iim.write(bytes)
        }
        val data = iim.toByteArray()
        val out = ByteArrayOutputStream()
        // An unrelated resource first, with an odd size, to exercise the padding.
        out.write("8BIM".toByteArray()); out.write(byteArrayOf(0x03, 0xED.toByte(), 0, 0, 0, 0, 0, 3, 1, 2, 3, 0))
        out.write("8BIM".toByteArray()); out.write(byteArrayOf(0x04, 0x04, 0, 0))
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun `iptc joins repeated keywords and finds its resource among others`() {
        val block = photoshopBlock(mapOf(5 to "Title here", 90 to "Lahore"), listOf(25 to "one", 25 to "two"))
        val found = Iptc.parse(block).toMap()
        assertEquals("Title here", found["Title"])
        assertEquals("Lahore", found["City"])
        assertEquals("one, two", found["Keywords"])
    }

    @Test
    fun `iptc with nothing in it finds nothing`() {
        assertTrue(Iptc.parse(ByteArray(0)).isEmpty())
        assertTrue(Iptc.parse("8BIM".toByteArray()).isEmpty())
        assertTrue(Iptc.parse(ByteArray(50) { 1 }).isEmpty())
    }

    // ── Removing metadata from a JPEG ─────────────────────────────────────────

    private fun rewrite(file: ByteArray, block: (ByteArrayInputStream, ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        block(ByteArrayInputStream(file), out)
        return out.toByteArray()
    }

    private fun containsAscii(data: ByteArray, text: String) = String(data, Charsets.ISO_8859_1).contains(text)

    @Test
    fun `remove location clears gps in both byte orders and keeps the rest`() {
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val original = jpeg(segment(0xE1, exif(order)))
            val cleaned = rewrite(original, JpegMetadataRewriter::removeLocation)
            assertEquals(original.size, cleaned.size)
            val exifBytes = exif(order)
            // The camera make and orientation survive, and the coordinate rationals don't.
            assertTrue(containsAscii(cleaned, "Cam"))
            assertFalse(containsAscii(cleaned, "N\u0000\u0000\u0000"))
            assertTrue(cleaned.takeLast(imageData.size).toByteArray().contentEquals(imageData))
            assertFalse(cleaned.contentEquals(original))
            assertTrue(containsAscii(exifBytes, "N\u0000\u0000\u0000"))
        }
    }

    @Test
    fun `remove location strips location from xmp and leaves the motion photo tags`() {
        val xmp = xmpPacket(
            """<rdf:Description GCamera:MicroVideoOffset="99" exif:GPSLatitude="24,30N" exif:GPSLongitude="67,15E">""" +
                """<photoshop:City>Karachi</photoshop:City><dc:title>Keep me</dc:title></rdf:Description>"""
        )
        val cleaned = rewrite(jpeg(segment(0xE1, xmp)), JpegMetadataRewriter::removeLocation)
        val text = String(cleaned, Charsets.ISO_8859_1)
        assertFalse(text.contains("GPSLatitude"))
        assertFalse(text.contains("Karachi"))
        assertTrue(text.contains("MicroVideoOffset=\"99\""))
        assertTrue(text.contains("Keep me"))
    }

    @Test
    fun `remove location takes place names out of iptc and keeps the rest`() {
        val block = photoshopBlock(mapOf(5 to "Title stays", 90 to "Lahore", 95 to "Punjab", 101 to "Pakistan", 120 to "Caption stays"))
        val iptc = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + block
        val cleaned = rewrite(jpeg(segment(0xED, iptc)), JpegMetadataRewriter::removeLocation)
        val found = Iptc.parse(JpegSegments.iptc(ArrayByteSource(cleaned))!!).toMap()
        assertEquals("Title stays", found["Title"])
        assertEquals("Caption stays", found["Caption"])
        assertFalse(found.containsKey("City"))
        assertFalse(found.containsKey("State"))
        assertFalse(found.containsKey("Country"))
        assertFalse(containsAscii(cleaned, "Lahore"))
        assertTrue(cleaned.takeLast(imageData.size).toByteArray().contentEquals(imageData))
    }

    @Test
    fun `iptc with no place in it is left byte for byte`() {
        val block = photoshopBlock(mapOf(5 to "Only a title"))
        assertArrayEquals(block, Iptc.withoutLocation(block))
        assertArrayEquals(ByteArray(3), Iptc.withoutLocation(ByteArray(3)))
    }

    @Test
    fun `whatever follows the image comes along untouched`() {
        val clip = mp4Clip()
        val original = jpeg(segment(0xE1, exif())) + clip
        val cleaned = rewrite(original, JpegMetadataRewriter::removeLocation)
        assertArrayEquals(clip, cleaned.takeLast(clip.size).toByteArray())
    }

    @Test
    fun `share without metadata keeps only orientation and color`() {
        val icc = "ICC_PROFILE\u0000abc".toByteArray(Charsets.ISO_8859_1)
        val iptc = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1) + photoshopBlock(mapOf(120 to "Secret caption"))
        val original = jpeg(
            segment(0xE0, "JFIF\u0000".toByteArray()),
            segment(0xE1, exif(orientation = 6)),
            segment(0xE1, xmpPacket("""<a xmp:Author="Me"/>""")),
            segment(0xE2, icc),
            segment(0xED, iptc),
            segment(0xFE, "A comment".toByteArray())
        )
        val cleaned = rewrite(original, JpegMetadataRewriter::removeAll)
        assertFalse(containsAscii(cleaned, "Cam"))
        assertFalse(containsAscii(cleaned, "Author"))
        assertFalse(containsAscii(cleaned, "Secret"))
        assertFalse(containsAscii(cleaned, "A comment"))
        assertTrue(containsAscii(cleaned, "ICC_PROFILE"))
        assertTrue(containsAscii(cleaned, "JFIF"))
        assertTrue(cleaned.takeLast(imageData.size).toByteArray().contentEquals(imageData))

        // Still a readable JPEG, still telling which way up it goes.
        val exifSegment = JpegSegments.read(ArrayByteSource(cleaned)).first { it.marker == 0xE1 }
        val payload = ByteArray(exifSegment.payloadLength)
        ArrayByteSource(cleaned).read(exifSegment.payloadOffset, payload, 0, payload.size)
        assertTrue(JpegSegments.isExif(payload))
        assertEquals(6, payload[6 + 8 + 2 + 8 + 1].toInt())
    }

    @Test
    fun `an upright photo needs no exif at all`() {
        val cleaned = rewrite(jpeg(segment(0xE1, exif(orientation = 1))), JpegMetadataRewriter::removeAll)
        assertTrue(JpegSegments.read(ArrayByteSource(cleaned)).none { it.marker == 0xE1 })
    }

    @Test
    fun `not a jpeg is refused`() {
        assertThrows(IOException::class.java) { rewrite("hello".toByteArray(), JpegMetadataRewriter::removeAll) }
        assertThrows(IOException::class.java) { rewrite(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), JpegMetadataRewriter::removeAll) }
    }

    // ── Motion photos ─────────────────────────────────────────────────────────

    @Test
    fun `motion photo from the older xmp offset`() {
        val clip = mp4Clip()
        val xmp = xmpPacket("""<rdf:Description GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="${clip.size}"/>""")
        val file = jpeg(segment(0xE1, xmp)) + clip
        assertEquals(MotionClip((file.size - clip.size).toLong(), clip.size.toLong()), MotionPhoto.locate(ArrayByteSource(file)))
    }

    @Test
    fun `motion photo from the newer container directory`() {
        val clip = mp4Clip()
        val xmp = xmpPacket(
            """<rdf:Description GCamera:MotionPhoto="1"><Container:Directory><rdf:Seq>""" +
                """<rdf:li><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/></rdf:li>""" +
                """<rdf:li><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${clip.size}"/></rdf:li>""" +
                """</rdf:Seq></Container:Directory></rdf:Description>"""
        )
        val file = jpeg(segment(0xE1, xmp)) + clip
        assertEquals(clip.size.toLong(), MotionPhoto.locate(ArrayByteSource(file))?.length)
    }

    @Test
    fun `an mp4 past the end of a jpeg is found without any tags`() {
        val clip = mp4Clip()
        val base = jpeg(segment(0xE0, ByteArray(10)))
        val file = base + clip
        assertEquals(MotionClip(base.size.toLong(), clip.size.toLong()), MotionPhoto.locate(ArrayByteSource(file)))
    }

    @Test
    fun `samsung tags its clip and ends it with a trailer`() {
        val clip = mp4Clip()
        val tag = "MotionPhoto_Data".toByteArray()
        val trailer = "SEFH".toByteArray() + ByteArray(20) + "SEFT".toByteArray()
        val base = jpeg(segment(0xE0, ByteArray(4)))
        val file = base + tag + clip + trailer
        assertEquals(MotionClip((base.size + tag.size).toLong(), clip.size.toLong()), MotionPhoto.locate(ArrayByteSource(file)))
    }

    @Test
    fun `an offset that doesn't land on an mp4 is ignored`() {
        val xmp = xmpPacket("""<rdf:Description GCamera:MicroVideoOffset="20"/>""")
        assertNull(MotionPhoto.locate(ArrayByteSource(jpeg(segment(0xE1, xmp)))))
        val huge = xmpPacket("""<rdf:Description GCamera:MicroVideoOffset="99999999999"/>""")
        assertNull(MotionPhoto.locate(ArrayByteSource(jpeg(segment(0xE1, huge)))))
    }

    @Test
    fun `a plain jpeg and other files aren't motion photos`() {
        assertNull(MotionPhoto.locate(ArrayByteSource(jpeg(segment(0xE0, ByteArray(4))))))
        assertNull(MotionPhoto.locate(ArrayByteSource(mp4Clip())))
        assertNull(MotionPhoto.locate(ArrayByteSource(ByteArray(0))))
    }

    @Test
    fun `panorama tags are read from xmp`() {
        assertTrue(MotionPhoto.isPanoramaXmp("""<a GPano:ProjectionType="equirectangular"/>"""))
        assertTrue(MotionPhoto.isPanoramaXmp("""<a GPano:UsePanoramaViewer="True"/>"""))
        assertFalse(MotionPhoto.isPanoramaXmp("""<a GPano:UsePanoramaViewer="False"/>"""))
        assertFalse(MotionPhoto.isPanoramaXmp("""<a b="c"/>"""))
    }

    // ── MP4 ───────────────────────────────────────────────────────────────────

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, it -> acc + it }
        return ByteBuffer.allocate(8 + body.size).apply { putInt(8 + body.size); put(type.toByteArray(Charsets.ISO_8859_1)); put(body) }.array()
    }

    private fun mp4WithMetadata(): ByteArray {
        val xyz = box("©xyz", "+24.5000+067.2500/".toByteArray())
        val title = box("©nam", "A title".toByteArray())
        val mvhd = box("mvhd", byteArrayOf(0, 0, 0, 0), ByteBuffer.allocate(8).putInt(111).putInt(222).array(), ByteArray(88))
        val tkhd = box("tkhd", byteArrayOf(0, 0, 0, 0), ByteBuffer.allocate(8).putInt(333).putInt(444).array(), ByteArray(60))
        val trak = box("trak", tkhd)
        val moov = box("moov", mvhd, trak, box("udta", xyz, title))
        return box("ftyp", "isom".toByteArray(), ByteArray(4)) + moov + box("mdat", ByteArray(32) { 7 })
    }

    private fun withFile(data: ByteArray, block: (RandomAccessFile, File) -> Unit) {
        val file = Files.createTempFile("meta", ".mp4").toFile()
        try {
            file.writeBytes(data)
            RandomAccessFile(file, "rw").use { block(it, file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `mp4 remove location blanks only the location box`() {
        val original = mp4WithMetadata()
        withFile(original) { handle, file ->
            assertTrue(Mp4MetadataStripper.isMp4(handle))
            assertEquals(1, Mp4MetadataStripper.removeLocation(handle))
            val after = file.readBytes()
            assertEquals(original.size, after.size)
            val text = String(after, Charsets.ISO_8859_1)
            assertFalse(text.contains("+24.5000"))
            assertTrue(text.contains("A title"))
            assertTrue(text.contains("free"))
            // Nothing moved, so the sample data sits exactly where the index says it does.
            assertArrayEquals(original.takeLast(40).toByteArray(), after.takeLast(40).toByteArray())
        }
    }

    @Test
    fun `mp4 keyed location is found and removed`() {
        val meta = box("meta", ByteArray(4), box("keys", "com.apple.quicktime.location.ISO6709 com.apple.quicktime.title".toByteArray()), box("ilst", "+24.5000+067.2500+010.000/ Keep this title".toByteArray()))
        val moov = box("moov", box("mvhd", ByteArray(100)), meta)
        val file = box("ftyp", "isom".toByteArray(), ByteArray(4)) + moov + box("mdat", ByteArray(16) { 7 })
        withFile(file) { handle, stored ->
            assertEquals("+24.5000+067.2500+010.000/", Mp4MetadataStripper.findKeyedLocation(handle))
            assertEquals(1, Mp4MetadataStripper.removeLocation(handle))
            val after = String(stored.readBytes(), Charsets.ISO_8859_1)
            assertFalse(after.contains("+24.5000"))
            assertFalse(after.contains("location"))
            // Only the place goes, and nothing moved.
            assertTrue(after.contains("Keep this title"))
            assertEquals(file.size, after.length)
            assertNull(Mp4MetadataStripper.findKeyedLocation(handle))
        }
    }

    @Test
    fun `mp4 remove all blanks metadata and zeroes the dates`() {
        val original = mp4WithMetadata()
        withFile(original) { handle, file ->
            assertTrue(Mp4MetadataStripper.removeAll(handle) >= 3)
            val after = file.readBytes()
            assertEquals(original.size, after.size)
            val text = String(after, Charsets.ISO_8859_1)
            assertFalse(text.contains("A title"))
            assertFalse(text.contains("+24.5000"))
            val buffer = ByteBuffer.wrap(after)
            val mvhdAt = text.indexOf("mvhd") + 4 + 4
            assertEquals(0, buffer.getInt(mvhdAt))
            assertEquals(0, buffer.getInt(mvhdAt + 4))
            assertArrayEquals(original.takeLast(40).toByteArray(), after.takeLast(40).toByteArray())
        }
    }

    @Test
    fun `mp4 with a lying box size stops without damage`() {
        val liar = ByteBuffer.allocate(32).apply { putInt(16); put("ftyp".toByteArray()); position(16); putInt(9999); put("moov".toByteArray()) }.array()
        withFile(liar) { handle, file ->
            assertEquals(0, Mp4MetadataStripper.removeAll(handle))
            assertArrayEquals(liar, file.readBytes())
        }
    }

    @Test
    fun `not an mp4 is recognized`() {
        withFile("not a video at all".toByteArray()) { handle, _ ->
            assertFalse(Mp4MetadataStripper.isMp4(handle))
        }
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    @Test
    fun `codecs read as people call them`() {
        assertEquals("H.264", CodecNames.label("video/avc"))
        assertEquals("AAC", CodecNames.label("audio/mp4a-latm"))
        assertEquals("X-UNKNOWN", CodecNames.label("video/x-unknown"))
        assertNull(CodecNames.label(null))
        assertNotNull(CodecNames.label("audio/opus"))
    }
}
