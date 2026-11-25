package com.kobblo.gallery.file.explorer.viewer.decoder

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Baseline TIFF, which Android has no decoder for: uncompressed, PackBits, LZW and Deflate, strips or
 * tiles, gray, RGB, palette and CMYK, at 1, 4, 8 or 16 bits a sample, across any number of pages.
 * JPEG inside TIFF is the common case it doesn't take, and reports as unsupported.
 */
object TiffDecoder {
    private const val MaxPages = 4096

    /** A compressed strip has to be unpacked whole, and this is as much as a phone can spare for that. */
    private const val MaxCompressedSegment = 256L * 1024 * 1024

    fun isTiff(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val intel = head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte() && head[3] == 0.toByte()
        val motorola = head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[2] == 0.toByte() && head[3] == 42.toByte()
        return intel || motorola
    }

    /** How many real pages there are. Reduced-size previews stored alongside a page don't count. */
    fun pageCount(data: ByteBuffer): Int = checkedRead { readPages(data).size }

    /** Decodes one page, at no more than the size [target] asks for. */
    fun decode(data: ByteBuffer, page: Int = 0, target: DecodeTarget = DecodeTarget.Original): RasterImage = checkedRead {
        val pages = readPages(data)
        val ifd = pages.getOrNull(page) ?: unsupported("No page $page of ${pages.size}")
        val raster = PageDecoder(ifd, target).decode()
        applyOrientation(raster, ifd.int(TagOrientation) ?: 1)
    }

    // ── Reading the directories ───────────────────────────────────────────────

    private class Entry(val type: Int, val count: Int, val valueOffset: Int)

    private class Ifd(val data: ByteBuffer, private val entries: Map<Int, Entry>) {
        fun has(tag: Int) = tag in entries

        fun int(tag: Int): Int? = entries[tag]?.takeIf { it.count > 0 }?.let { read(it, 0) }

        fun ints(tag: Int): IntArray? = entries[tag]?.let { entry ->
            // A count comes straight from the file, so bound it by what could be stored there.
            if (entry.count < 0 || entry.count > data.capacity()) unsupported("Bad count for tag $tag")
            IntArray(entry.count) { read(entry, it) }
        }

        private fun read(entry: Entry, index: Int): Int = when (entry.type) {
            TypeByte, TypeUndefined, TypeAscii -> data.u8(entry.valueOffset + index)
            TypeShort -> data.getShort(entry.valueOffset + index * 2).toInt() and 0xFFFF
            TypeLong -> data.getInt(entry.valueOffset + index * 4)
            else -> unsupported("Tag type ${entry.type}")
        }
    }

    private fun readPages(source: ByteBuffer): List<Ifd> {
        val data = source.duplicate()
        if (data.capacity() < 8) unsupported("Too short for TIFF")
        val order = when (data.get(0).toInt().toChar()) {
            'I' -> ByteOrder.LITTLE_ENDIAN
            'M' -> ByteOrder.BIG_ENDIAN
            else -> unsupported("Not TIFF")
        }
        data.order(order)
        if ((data.getShort(2).toInt() and 0xFFFF) != 42) unsupported("Not classic TIFF")

        val pages = ArrayList<Ifd>()
        val previews = ArrayList<Ifd>()
        val seen = HashSet<Int>()
        var offset = data.getInt(4)
        // Next-page links can loop, whether by accident or on purpose.
        while (offset != 0 && seen.add(offset) && seen.size <= MaxPages) {
            if (offset < 0 || offset + 2 > data.capacity()) break
            val count = data.getShort(offset).toInt() and 0xFFFF
            val entries = HashMap<Int, Entry>(count)
            for (i in 0 until count) {
                val at = offset + 2 + i * 12
                val tag = data.getShort(at).toInt() and 0xFFFF
                val type = data.getShort(at + 2).toInt() and 0xFFFF
                val values = data.getInt(at + 4)
                val width = when (type) {
                    TypeByte, TypeAscii, TypeUndefined -> 1
                    TypeShort -> 2
                    TypeLong -> 4
                    else -> 8
                }
                // A value that fits in its four bytes is stored in the entry itself.
                val fitsInline = values.toLong() * width <= 4L
                entries[tag] = Entry(type, values, if (fitsInline) at + 8 else data.getInt(at + 8))
            }
            val ifd = Ifd(data, entries)
            val isPreview = ((ifd.int(TagNewSubfileType) ?: 0) and 1) == 1
            if (ifd.has(TagWidth) && ifd.has(TagHeight)) (if (isPreview) previews else pages).add(ifd)
            offset = data.getInt(offset + 2 + count * 12)
        }
        // A DNG can keep its real image out of this chain, so that a preview is all a reader finds.
        if (pages.isEmpty()) pages.addAll(previews)
        if (pages.isEmpty()) unsupported("TIFF without a page")
        return pages
    }

    // ── Reading one page ──────────────────────────────────────────────────────

    /** One block of compressed data, a strip or a tile, and the part of the picture it covers. */
    private class Segment(val x: Int, val y: Int, val width: Int, val height: Int, val offset: Int, val length: Int)

    private class PageDecoder(private val ifd: Ifd, target: DecodeTarget) {
        private val data = ifd.data
        private val width = ifd.int(TagWidth) ?: unsupported("No width")
        private val height = ifd.int(TagHeight) ?: unsupported("No height")
        private val step = target.stepFor(width, height)
        private val outWidth = sampledSize(width, step)
        private val outHeight = sampledSize(height, step)
        private val samples = ifd.int(TagSamplesPerPixel) ?: 1
        private val bits = ifd.ints(TagBitsPerSample)?.firstOrNull() ?: 1
        private val compression = ifd.int(TagCompression) ?: CompressionNone
        private val photometric = ifd.int(TagPhotometric) ?: PhotometricBlackIsZero
        private val predictor = ifd.int(TagPredictor) ?: 1
        private val colorMap = ifd.ints(TagColorMap)
        private val extra = ifd.ints(TagExtraSamples)?.firstOrNull() ?: 0
        private val big = data.order() == ByteOrder.BIG_ENDIAN
        private val out = IntArray(outWidth * outHeight)

        init {
            if (samples !in 1..8) unsupported("$samples samples a pixel")
            if (bits !in setOf(1, 2, 4, 8, 16)) unsupported("$bits bits a sample")
            if ((ifd.int(TagPlanar) ?: 1) != 1) unsupported("Planar TIFF")
            if ((ifd.int(TagSampleFormat) ?: 1) == 3) unsupported("Floating point TIFF")
            if (predictor !in 1..2) unsupported("Predictor $predictor")
            val known = when (photometric) {
                PhotometricWhiteIsZero, PhotometricBlackIsZero -> samples >= 1
                PhotometricRgb, PhotometricLinearRaw -> samples >= 3
                PhotometricPalette -> samples == 1 && colorMap != null
                PhotometricCmyk -> samples >= 4
                else -> false
            }
            if (!known) unsupported("Photometric $photometric")
        }

        fun decode(): RasterImage {
            for (segment in segments()) {
                val rowBytes = ((segment.width.toLong() * samples * bits + 7) / 8).toInt()
                // Uncompressed data is already in memory, so only the rows actually kept get touched.
                if (compression == CompressionNone && predictor == 1) drawInPlace(segment, rowBytes)
                else {
                    val raw = inflate(segment, rowBytes)
                    if (predictor == 2) undoPredictor(raw, rowBytes, segment.width)
                    drawDecompressed(raw, rowBytes, segment)
                }
            }
            return RasterImage(outWidth, outHeight, out, width, height)
        }

        private fun segments(): List<Segment> {
            val offsets = ifd.ints(TagTileOffsets)
            if (offsets != null) {
                val counts = ifd.ints(TagTileByteCounts) ?: unsupported("No tile sizes")
                val tileWidth = ifd.int(TagTileWidth) ?: unsupported("No tile width")
                val tileHeight = ifd.int(TagTileHeight) ?: unsupported("No tile height")
                if (tileWidth <= 0 || tileHeight <= 0) unsupported("Bad tile size")
                val across = (width + tileWidth - 1) / tileWidth
                return offsets.indices.map { i ->
                    val x = (i % across) * tileWidth
                    val y = (i / across) * tileHeight
                    Segment(x, y, tileWidth, tileHeight, offsets[i], counts.getOrElse(i) { 0 })
                }
            }
            val stripOffsets = ifd.ints(TagStripOffsets) ?: unsupported("No strips")
            val counts = ifd.ints(TagStripByteCounts)
            val rowsPerStrip = (ifd.int(TagRowsPerStrip) ?: height).let { if (it <= 0 || it > height) height else it }
            return stripOffsets.indices.mapNotNull { i ->
                val y = i.toLong() * rowsPerStrip
                if (y >= height) return@mapNotNull null
                val rows = minOf(rowsPerStrip.toLong(), height - y).toInt()
                // A writer may leave the sizes out of an uncompressed file.
                val fallback = ((width.toLong() * samples * bits + 7) / 8 * rows).toInt()
                Segment(0, y.toInt(), width, rows, stripOffsets[i], counts?.getOrNull(i)?.takeIf { it > 0 } ?: fallback)
            }
        }

        private fun inflate(segment: Segment, rowBytes: Int): ByteArray {
            val total = rowBytes.toLong() * segment.height
            if (total > MaxCompressedSegment) unsupported("Compressed strip too large")
            val size = total.toInt()
            val raw = ByteArray(size)
            if (segment.offset < 0 || segment.offset >= data.capacity()) return raw
            val length = minOf(segment.length.toLong(), (data.capacity() - segment.offset).toLong()).toInt()
            if (length <= 0) return raw
            when (compression) {
                CompressionNone -> {
                    val view = data.duplicate()
                    view.position(segment.offset)
                    view.get(raw, 0, minOf(length, size))
                }
                CompressionPackBits -> unpackBits(data, segment.offset, length, raw)
                CompressionLzw -> Lzw.decode(data, segment.offset, length, raw)
                CompressionDeflate, CompressionAdobeDeflate -> {
                    val inflater = Inflater()
                    try {
                        val input = ByteArray(length)
                        val view = data.duplicate()
                        view.position(segment.offset)
                        view.get(input)
                        inflater.setInput(input)
                        var written = 0
                        while (written < size && !inflater.finished() && !inflater.needsInput()) {
                            val n = inflater.inflate(raw, written, size - written)
                            if (n == 0 && inflater.needsDictionary()) break
                            written += n
                        }
                    } catch (e: DataFormatException) {
                        unsupported("Bad Deflate data")
                    } finally {
                        inflater.end()
                    }
                }
                else -> unsupported("Compression $compression")
            }
            return raw
        }

        /** Horizontal differencing: each sample was stored as the change from its left neighbour. */
        private fun undoPredictor(raw: ByteArray, rowBytes: Int, columns: Int) {
            when (bits) {
                8 -> for (row in 0 until raw.size / rowBytes.coerceAtLeast(1)) {
                    val start = row * rowBytes
                    for (i in samples until columns * samples) {
                        raw[start + i] = (raw[start + i] + raw[start + i - samples]).toByte()
                    }
                }
                16 -> for (row in 0 until raw.size / rowBytes.coerceAtLeast(1)) {
                    val start = row * rowBytes
                    for (i in samples until columns * samples) {
                        val at = start + i * 2
                        val back = at - samples * 2
                        val sum = sample16(raw, at) + sample16(raw, back)
                        if (big) {
                            raw[at] = (sum shr 8).toByte()
                            raw[at + 1] = sum.toByte()
                        } else {
                            raw[at] = sum.toByte()
                            raw[at + 1] = (sum shr 8).toByte()
                        }
                    }
                }
                else -> unsupported("Predictor with $bits bits")
            }
        }

        private fun sample16(raw: ByteArray, at: Int): Int {
            val first = raw[at].toInt() and 0xFF
            val second = raw[at + 1].toInt() and 0xFF
            return if (big) (first shl 8) or second else (second shl 8) or first
        }

        private fun drawInPlace(segment: Segment, rowBytes: Int) {
            val row = ByteArray(rowBytes)
            val view = data.duplicate()
            for (rowIndex in 0 until segment.height) {
                val y = segment.y + rowIndex
                if (y >= height) break
                if (y % step != 0) continue
                val at = segment.offset.toLong() + rowIndex.toLong() * rowBytes
                // A file cut short simply leaves the rest of the picture empty.
                if (segment.offset < 0 || at + rowBytes > data.capacity()) break
                view.position(at.toInt())
                view.get(row)
                drawRow(row, 0, segment, y)
            }
        }

        private fun drawDecompressed(raw: ByteArray, rowBytes: Int, segment: Segment) {
            for (rowIndex in 0 until segment.height) {
                val y = segment.y + rowIndex
                if (y >= height) break
                if (y % step == 0) drawRow(raw, rowIndex * rowBytes, segment, y)
            }
        }

        private val rowSamples = IntArray(samples)

        private fun drawRow(raw: ByteArray, rowStart: Int, segment: Segment, y: Int) {
            val outRow = (y / step) * outWidth
            for (column in 0 until segment.width) {
                val x = segment.x + column
                if (x >= width) break
                if (x % step != 0) continue
                for (s in 0 until samples) rowSamples[s] = readSample(raw, rowStart, column * samples + s)
                out[outRow + x / step] = toArgb(rowSamples)
            }
        }

        /** Reads a sample as it is stored. Eight bits and above are scaled to 0..255; palettes need the raw index. */
        private fun readSample(raw: ByteArray, rowStart: Int, sampleIndex: Int): Int = when (bits) {
            8 -> raw[rowStart + sampleIndex].toInt() and 0xFF
            16 -> sample16(raw, rowStart + sampleIndex * 2) shr 8
            else -> {
                val bitIndex = sampleIndex * bits
                val byte = raw[rowStart + bitIndex / 8].toInt() and 0xFF
                (byte shr (8 - bits - bitIndex % 8)) and ((1 shl bits) - 1)
            }
        }

        private fun scaleLowBits(value: Int): Int = if (bits >= 8) value else value * 255 / ((1 shl bits) - 1)

        private fun toArgb(values: IntArray): Int = when (photometric) {
            PhotometricWhiteIsZero -> {
                val v = 255 - scaleLowBits(values[0])
                grayWithAlpha(v, values)
            }
            PhotometricBlackIsZero -> grayWithAlpha(scaleLowBits(values[0]), values)
            PhotometricRgb, PhotometricLinearRaw -> {
                val r = scaleLowBits(values[0])
                val g = scaleLowBits(values[1])
                val b = scaleLowBits(values[2])
                if (samples > 3 && extra != 0) argb(scaleLowBits(values[3]), r, g, b) else opaque(r, g, b)
            }
            PhotometricPalette -> {
                val map = colorMap!!
                val third = map.size / 3
                val index = values[0].coerceAtMost(third - 1)
                opaque(map[index] shr 8, map[third + index] shr 8, map[third * 2 + index] shr 8)
            }
            else -> {
                val k = 255 - scaleLowBits(values[3])
                opaque(
                    (255 - scaleLowBits(values[0])) * k / 255,
                    (255 - scaleLowBits(values[1])) * k / 255,
                    (255 - scaleLowBits(values[2])) * k / 255
                )
            }
        }

        private fun grayWithAlpha(v: Int, values: IntArray): Int =
            if (samples > 1 && extra != 0) argb(scaleLowBits(values[1]), v, v, v) else opaque(v, v, v)
    }

    /** Turns the picture upright. Values 5 to 8 swap its sides. */
    internal fun applyOrientation(image: RasterImage, orientation: Int): RasterImage {
        if (orientation !in 2..8) return image
        val w = image.width
        val h = image.height
        val swap = orientation >= 5
        val outWidth = if (swap) h else w
        val outHeight = if (swap) w else h
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (nx, ny) = when (orientation) {
                    2 -> (w - 1 - x) to y
                    3 -> (w - 1 - x) to (h - 1 - y)
                    4 -> x to (h - 1 - y)
                    5 -> y to x
                    6 -> (h - 1 - y) to x
                    7 -> (h - 1 - y) to (w - 1 - x)
                    else -> y to (w - 1 - x)
                }
                out[ny * outWidth + nx] = image.pixels[y * w + x]
            }
        }
        val fullWidth = if (swap) image.fullHeight else image.fullWidth
        val fullHeight = if (swap) image.fullWidth else image.fullHeight
        return RasterImage(outWidth, outHeight, out, fullWidth, fullHeight)
    }

    private const val TypeByte = 1
    private const val TypeAscii = 2
    private const val TypeShort = 3
    private const val TypeLong = 4
    private const val TypeUndefined = 7

    private const val TagNewSubfileType = 254
    private const val TagWidth = 256
    private const val TagHeight = 257
    private const val TagBitsPerSample = 258
    private const val TagCompression = 259
    private const val TagPhotometric = 262
    private const val TagStripOffsets = 273
    private const val TagOrientation = 274
    private const val TagSamplesPerPixel = 277
    private const val TagRowsPerStrip = 278
    private const val TagStripByteCounts = 279
    private const val TagPlanar = 284
    private const val TagPredictor = 317
    private const val TagColorMap = 320
    private const val TagTileWidth = 322
    private const val TagTileHeight = 323
    private const val TagTileOffsets = 324
    private const val TagTileByteCounts = 325
    private const val TagExtraSamples = 338
    private const val TagSampleFormat = 339

    private const val CompressionNone = 1
    private const val CompressionLzw = 5
    private const val CompressionAdobeDeflate = 8
    private const val CompressionPackBits = 32773
    private const val CompressionDeflate = 32946

    private const val PhotometricWhiteIsZero = 0
    private const val PhotometricBlackIsZero = 1
    private const val PhotometricRgb = 2
    private const val PhotometricPalette = 3
    private const val PhotometricCmyk = 5
    private const val PhotometricLinearRaw = 34892
}

/** TIFF's LZW: most significant bit first, with the code width rising one code early. */
internal object Lzw {
    private const val Clear = 256
    private const val End = 257
    private const val MaxCodes = 4096

    fun decode(src: ByteBuffer, start: Int, length: Int, dst: ByteArray) {
        // Every code stands for a run of bytes already in the output, so nothing needs storing twice.
        val runStart = IntArray(MaxCodes)
        val runLength = IntArray(MaxCodes)
        var nextCode = 258
        var width = 9
        var bitBuffer = 0L
        var bitCount = 0
        var read = start
        val readEnd = start + length
        var write = 0
        var previousStart = -1
        var previousLength = 0

        while (true) {
            while (bitCount < width && read < readEnd) {
                bitBuffer = (bitBuffer shl 8) or (src.get(read++).toLong() and 0xFF)
                bitCount += 8
            }
            if (bitCount < width) return
            val code = ((bitBuffer shr (bitCount - width)) and ((1L shl width) - 1)).toInt()
            bitCount -= width
            if (code == End) return
            if (code == Clear) {
                nextCode = 258
                width = 9
                previousStart = -1
                continue
            }

            val currentStart = write
            when {
                code < 256 -> {
                    if (write >= dst.size) return
                    dst[write++] = code.toByte()
                }
                code < nextCode -> {
                    val from = runStart[code]
                    for (i in 0 until runLength[code]) {
                        if (write >= dst.size) return
                        dst[write++] = dst[from + i]
                    }
                }
                // The code this read itself is about to define: the previous run plus its own first byte.
                code == nextCode && previousStart >= 0 -> {
                    for (i in 0 until previousLength) {
                        if (write >= dst.size) return
                        dst[write++] = dst[previousStart + i]
                    }
                    if (write >= dst.size) return
                    dst[write++] = dst[previousStart]
                }
                else -> throw ImageFormatException("Bad LZW code $code")
            }

            if (previousStart >= 0 && nextCode < MaxCodes) {
                // A new code is the previous run plus the next byte, and the next byte is what was
                // just written straight after that run.
                runStart[nextCode] = previousStart
                runLength[nextCode] = previousLength + 1
                nextCode++
                width = when {
                    nextCode >= 2047 -> 12
                    nextCode >= 1023 -> 11
                    nextCode >= 511 -> 10
                    else -> 9
                }
            }
            previousStart = currentStart
            previousLength = write - currentStart
        }
    }
}
