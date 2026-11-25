package com.kobblo.gallery.file.explorer.viewer.metadata

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Random access to a file's bytes, which a plain array can stand in for under test. */
interface ByteSource {
    val size: Long

    /** Reads up to [length] bytes at [position], returning how many it got. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

class ArrayByteSource(private val data: ByteArray, private val used: Int = data.size) : ByteSource {
    override val size: Long get() = used.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= used) return 0
        val count = minOf(length.toLong(), used - position).toInt()
        System.arraycopy(data, position.toInt(), into, offset, count)
        return count
    }
}

class FileByteSource(file: File) : ByteSource, AutoCloseable {
    private val handle = RandomAccessFile(file, "r")
    override val size: Long = handle.length()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return 0
        handle.seek(position)
        return handle.read(into, offset, length).coerceAtLeast(0)
    }

    override fun close() = handle.close()
}

/** One marker segment before the image data: its marker byte and its payload's place in the file. */
class JpegSegment(val marker: Int, val payloadOffset: Long, val payloadLength: Int)

/** What a JPEG says about itself, in the segments before its image data. */
object JpegSegments {
    const val App1 = 0xE1
    const val App13 = 0xED
    const val Comment = 0xFE
    private const val Sos = 0xDA
    private const val Eoi = 0xD9

    private val ExifPrefix = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val XmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val PhotoshopPrefix = "Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1)

    fun isJpeg(source: ByteSource): Boolean {
        val head = ByteArray(3)
        return source.read(0, head, 0, 3) == 3 && isJpegHead(head)
    }

    private fun isJpegHead(head: ByteArray) =
        head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()

    /** Every segment up to the start of the image data. */
    fun read(source: ByteSource): List<JpegSegment> {
        if (!isJpeg(source)) return emptyList()
        val segments = ArrayList<JpegSegment>()
        val header = ByteArray(4)
        var at = 2L
        while (at + 4 <= source.size && segments.size < MaxSegments) {
            if (source.read(at, header, 0, 4) < 4) break
            if (header[0] != 0xFF.toByte()) break
            val marker = header[1].toInt() and 0xFF
            if (marker == 0xFF) {
                at++
                continue
            }
            if (marker == Sos || marker == Eoi) break
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (length < 2) break
            segments.add(JpegSegment(marker, at + 4, length - 2))
            at += 2L + length
        }
        return segments
    }

    /** The XMP packet, which belongs to no particular tool, or null. */
    fun xmp(source: ByteSource, segments: List<JpegSegment> = read(source)): String? {
        val segment = segments.firstOrNull { it.marker == App1 && startsWith(source, it, XmpPrefix) } ?: return null
        val bytes = payload(source, segment) ?: return null
        return String(bytes, XmpPrefix.size, bytes.size - XmpPrefix.size, Charsets.UTF_8)
    }

    fun iptc(source: ByteSource, segments: List<JpegSegment> = read(source)): ByteArray? {
        val segment = segments.firstOrNull { it.marker == App13 && startsWith(source, it, PhotoshopPrefix) } ?: return null
        val bytes = payload(source, segment) ?: return null
        return bytes.copyOfRange(PhotoshopPrefix.size, bytes.size)
    }

    fun isExif(payload: ByteArray): Boolean = payload.startsWith(ExifPrefix)
    fun isXmp(payload: ByteArray): Boolean = payload.startsWith(XmpPrefix)
    fun isPhotoshop(payload: ByteArray): Boolean = payload.startsWith(PhotoshopPrefix)

    internal val exifPrefixSize get() = ExifPrefix.size
    internal val xmpPrefix get() = XmpPrefix
    internal val photoshopPrefix get() = PhotoshopPrefix

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun startsWith(source: ByteSource, segment: JpegSegment, prefix: ByteArray): Boolean {
        if (segment.payloadLength < prefix.size) return false
        val head = ByteArray(prefix.size)
        return source.read(segment.payloadOffset, head, 0, head.size) == head.size && head.contentEquals(prefix)
    }

    private fun payload(source: ByteSource, segment: JpegSegment): ByteArray? {
        val bytes = ByteArray(segment.payloadLength)
        var read = 0
        while (read < bytes.size) {
            val got = source.read(segment.payloadOffset + read, bytes, read, bytes.size - read)
            if (got <= 0) return null
            read += got
        }
        return bytes
    }

    private const val MaxSegments = 512
}

/**
 * Rewrites a JPEG's metadata without touching its image data, which is the point: re-encoding would
 * lose quality for the sake of a location tag. Whatever follows the image, such as a motion photo's
 * clip, comes along untouched.
 */
object JpegMetadataRewriter {
    private const val Sos = 0xDA
    private const val Soi = 0xD8
    private const val Eoi = 0xD9
    private const val App1 = 0xE1
    private const val App13 = 0xED
    private const val Comment = 0xFE

    /** Copies [input] to [output] with every trace of where it was taken removed. */
    fun removeLocation(input: InputStream, output: OutputStream) {
        rewrite(input, output) { marker, payload ->
            when {
                marker == App1 && JpegSegments.isExif(payload) -> ExifEditor.clearGps(payload)
                marker == App1 && JpegSegments.isXmp(payload) -> stripXmpLocation(payload)
                marker == App13 && JpegSegments.isPhotoshop(payload) -> stripIptcLocation(payload)
                else -> payload
            }
        }
    }

    /**
     * Copies [input] to [output] with EXIF, XMP, IPTC and comments gone. Which way up the photo goes
     * survives as a minimal EXIF block, or sharing it would turn it on its side. A color profile
     * stays too, since it's part of how the picture looks rather than a fact about whoever took it.
     */
    fun removeAll(input: InputStream, output: OutputStream) {
        rewrite(input, output) { marker, payload ->
            when {
                marker == App1 && JpegSegments.isExif(payload) -> ExifEditor.orientationOf(payload).takeIf { it > 1 }?.let(ExifEditor::minimal)
                marker == App1 && JpegSegments.isXmp(payload) -> null
                marker == App1 -> null
                marker == App13 -> null
                marker == Comment -> null
                else -> payload
            }
        }
    }

    private fun stripXmpLocation(payload: ByteArray): ByteArray {
        val prefix = JpegSegments.xmpPrefix.size
        val text = String(payload, prefix, payload.size - prefix, Charsets.UTF_8)
        if (!XmpLocation.mentionsLocation(text)) return payload
        val cleaned = XmpLocation.strip(text).toByteArray(Charsets.UTF_8)
        return JpegSegments.xmpPrefix + cleaned
    }

    private fun stripIptcLocation(payload: ByteArray): ByteArray {
        val prefix = JpegSegments.photoshopPrefix
        return prefix + Iptc.withoutLocation(payload.copyOfRange(prefix.size, payload.size))
    }

    private fun rewrite(input: InputStream, output: OutputStream, edit: (marker: Int, payload: ByteArray) -> ByteArray?) {
        if (input.readByte() != 0xFF || input.readByte() != Soi) throw IOException("Not a JPEG")
        output.write(0xFF)
        output.write(Soi)
        while (true) {
            var byte = input.readByte()
            if (byte != 0xFF) throw IOException("Lost sync in JPEG headers")
            // Padding of repeated FF bytes is allowed before a marker.
            do byte = input.readByte() while (byte == 0xFF)
            val marker = byte
            if (marker == Eoi) {
                output.write(0xFF)
                output.write(Eoi)
                input.copyTo(output)
                return
            }
            val high = input.readByte()
            val low = input.readByte()
            val length = (high shl 8) or low
            if (length < 2) throw IOException("Bad JPEG segment length")
            val payload = input.readExactly(length - 2)
            if (marker == Sos) {
                writeSegment(output, marker, payload)
                input.copyTo(output)
                return
            }
            val kept = edit(marker, payload) ?: continue
            writeSegment(output, marker, kept)
        }
    }

    private fun writeSegment(output: OutputStream, marker: Int, payload: ByteArray) {
        val length = payload.size + 2
        if (length > 0xFFFF) throw IOException("Segment too large")
        output.write(0xFF)
        output.write(marker)
        output.write(length shr 8)
        output.write(length and 0xFF)
        output.write(payload)
    }

    private fun InputStream.readByte(): Int {
        val byte = read()
        if (byte < 0) throw EOFException()
        return byte
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var at = 0
        while (at < count) {
            val got = read(bytes, at, count - at)
            if (got < 0) throw EOFException()
            at += got
        }
        return bytes
    }
}

/** Small edits to an EXIF block, in place, so nothing else in it has to move. */
internal object ExifEditor {
    private const val TagOrientation = 0x0112
    private const val TagGpsPointer = 0x8825

    /** Blanks the GPS block and the pointer to it. */
    fun clearGps(payload: ByteArray): ByteArray {
        val out = payload.copyOf()
        val reader = reader(out) ?: return out
        val root = reader.u32(4)
        val count = reader.u16(root)
        for (i in 0 until count) {
            val entry = root + 2 + i * 12
            if (reader.u16(entry) != TagGpsPointer) continue
            val gps = reader.u32(entry + 8)
            blankIfd(reader, gps)
            // Tag 0xFFFF is unknown to every reader, which is how to retire an entry without moving
            // the ones after it.
            reader.put16(entry, 0xFFFF)
            reader.put32(entry + 8, 0)
        }
        return out
    }

    private fun blankIfd(reader: TiffReader, ifd: Int) {
        val count = reader.u16(ifd)
        if (count > MaxEntries) return
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            val type = reader.u16(entry + 2)
            val values = reader.u32(entry + 4)
            val width = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9, 11 -> 4
                5, 10, 12 -> 8
                else -> 1
            }
            val size = values.toLong() * width
            if (size > 4) {
                val at = reader.u32(entry + 8)
                reader.blank(at, size)
            }
            reader.blank(entry, 12)
        }
        reader.put16(ifd, 0)
    }

    fun orientationOf(payload: ByteArray): Int {
        val reader = reader(payload) ?: return 1
        val root = reader.u32(4)
        val count = reader.u16(root)
        for (i in 0 until count) {
            val entry = root + 2 + i * 12
            if (reader.u16(entry) == TagOrientation) return reader.u16(entry + 8)
        }
        return 1
    }

    /** An EXIF block holding nothing but the orientation. */
    fun minimal(orientation: Int): ByteArray {
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01,
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        return exif + tiff
    }

    private fun reader(payload: ByteArray): TiffReader? {
        val prefix = JpegSegments.exifPrefixSize
        if (!JpegSegments.isExif(payload) || payload.size < prefix + 8) return null
        val little = payload[prefix] == 'I'.code.toByte()
        val big = payload[prefix] == 'M'.code.toByte()
        if (!little && !big) return null
        return TiffReader(payload, prefix, little)
    }

    /** Bounds are checked on every read, since a tag's offset is only a claim made by the file. */
    private class TiffReader(private val data: ByteArray, private val base: Int, private val little: Boolean) {
        fun u16(at: Int): Int {
            val i = base + at
            if (at < 0 || i + 2 > data.size) return 0
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(at: Int): Int {
            val i = base + at
            if (at < 0 || i + 4 > data.size) return 0
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            val c = data[i + 2].toInt() and 0xFF
            val d = data[i + 3].toInt() and 0xFF
            return if (little) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun put16(at: Int, value: Int) {
            val i = base + at
            if (at < 0 || i + 2 > data.size) return
            if (little) {
                data[i] = value.toByte()
                data[i + 1] = (value shr 8).toByte()
            } else {
                data[i] = (value shr 8).toByte()
                data[i + 1] = value.toByte()
            }
        }

        fun put32(at: Int, value: Int) {
            val i = base + at
            if (at < 0 || i + 4 > data.size) return
            for (k in 0 until 4) {
                val shift = if (little) k * 8 else (3 - k) * 8
                data[i + k] = (value shr shift).toByte()
            }
        }

        fun blank(at: Int, length: Long) {
            val i = base + at
            if (at < 0 || i >= data.size) return
            val end = minOf(i + length, data.size.toLong()).toInt()
            data.fill(0, i, end)
        }
    }

    private const val MaxEntries = 512
}

/** Location inside an XMP packet, where exif:GPS tags and a few others can repeat what EXIF held. */
internal object XmpLocation {
    private val Attribute = Regex("""\s+(?:exif|exifEX|photoshop|Iptc4xmpCore):(?:GPS[A-Za-z]*|City|State|Country|Location)\s*=\s*"[^"]*"""")
    private val Element = Regex("""<(exif|exifEX|photoshop|Iptc4xmpCore):(GPS[A-Za-z]*|City|State|Country|Location)\b[^>]*?(?:/>|>.*?</\1:\2>)""", RegexOption.DOT_MATCHES_ALL)

    fun mentionsLocation(xmp: String): Boolean = Attribute.containsMatchIn(xmp) || Element.containsMatchIn(xmp)

    fun strip(xmp: String): String = Element.replace(Attribute.replace(xmp, ""), "")
}
