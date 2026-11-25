package com.kobblo.gallery.file.explorer.viewer.decoder

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes tiny files of each format by hand, so a decoder is tested against the spec rather than itself. */
internal object DecoderFixtures {
    /** A small picture whose every pixel is different, so a flipped or shifted decode can't pass by luck. */
    fun rgb(x: Int, y: Int): IntArray = intArrayOf((x * 40 + 10) and 0xFF, (y * 40 + 20) and 0xFF, ((x + y) * 20 + 30) and 0xFF)

    fun expected(x: Int, y: Int): Int = rgb(x, y).let { opaque(it[0], it[1], it[2]) }

    fun gray(x: Int, y: Int): Int = (x * 30 + y * 10 + 5) and 0xFF

    fun expectedGray(x: Int, y: Int): Int = gray(x, y).let { opaque(it, it, it) }

    fun rgbBytes(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (y in 0 until height) for (x in 0 until width) rgb(x, y).forEach { out.write(it) }
        return out.toByteArray()
    }

    fun grayBytes(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (y in 0 until height) for (x in 0 until width) out.write(gray(x, y))
        return out.toByteArray()
    }

    class TiffTag(val tag: Int, val type: Int, val values: IntArray)

    const val Short = 3
    const val Long = 4

    /** One page of a classic TIFF, laid out as IFD then values then data. */
    class TiffPage(val tags: List<TiffTag>, val strips: List<ByteArray>, val tiled: Boolean = false)

    fun tiff(pages: List<TiffPage>, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteBuffer {
        // A generous fixed layout is easier to read than computing offsets twice.
        val buffer = ByteBuffer.allocate(pages.sumOf { p -> 4096 + p.strips.sumOf { it.size } } + 16).order(order)
        buffer.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        buffer.put(if (order == ByteOrder.LITTLE_ENDIAN) 'I'.code.toByte() else 'M'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(8)
        var at = 8
        pages.forEachIndexed { index, page ->
            val offsetsPlaceholder = page.strips.indices
            val dataStart = at + 4000
            var dataAt = dataStart
            val stripOffsets = offsetsPlaceholder.map { i ->
                val offset = dataAt
                dataAt += page.strips[i].size
                offset
            }
            val offsetTag = if (page.tiled) 324 else 273
            val sizeTag = if (page.tiled) 325 else 279
            val tags = (page.tags + TiffTag(offsetTag, Long, stripOffsets.toIntArray()) +
                TiffTag(sizeTag, Long, page.strips.map { it.size }.toIntArray())).sortedBy { it.tag }
            buffer.position(at)
            buffer.putShort(tags.size.toShort())
            var extraAt = at + 2 + tags.size * 12 + 4
            tags.forEach { tag ->
                buffer.putShort(tag.tag.toShort())
                buffer.putShort(tag.type.toShort())
                buffer.putInt(tag.values.size)
                val width = if (tag.type == Short) 2 else 4
                if (tag.values.size * width <= 4) {
                    val start = buffer.position()
                    tag.values.forEach { if (tag.type == Short) buffer.putShort(it.toShort()) else buffer.putInt(it) }
                    buffer.position(start + 4)
                } else {
                    buffer.putInt(extraAt)
                    val resume = buffer.position()
                    buffer.position(extraAt)
                    tag.values.forEach { if (tag.type == Short) buffer.putShort(it.toShort()) else buffer.putInt(it) }
                    extraAt = buffer.position()
                    buffer.position(resume)
                }
            }
            val nextOffset = if (index == pages.lastIndex) 0 else dataAt
            buffer.putInt(nextOffset)
            buffer.position(dataStart)
            page.strips.forEach { buffer.put(it) }
            at = dataAt
        }
        return ByteBuffer.wrap(buffer.array(), 0, buffer.capacity()).order(ByteOrder.BIG_ENDIAN)
    }

    fun rgbTiffTags(width: Int, height: Int, compression: Int, rowsPerStrip: Int = height, predictor: Int = 1) = buildList {
        add(TiffTag(256, Long, intArrayOf(width)))
        add(TiffTag(257, Long, intArrayOf(height)))
        add(TiffTag(258, Short, intArrayOf(8, 8, 8)))
        add(TiffTag(259, Short, intArrayOf(compression)))
        add(TiffTag(262, Short, intArrayOf(2)))
        add(TiffTag(277, Short, intArrayOf(3)))
        add(TiffTag(278, Long, intArrayOf(rowsPerStrip)))
        if (predictor != 1) add(TiffTag(317, Short, intArrayOf(predictor)))
    }

    fun grayTiffTags(width: Int, height: Int, compression: Int, photometric: Int = 1) = listOf(
        TiffTag(256, Long, intArrayOf(width)),
        TiffTag(257, Long, intArrayOf(height)),
        TiffTag(258, Short, intArrayOf(8)),
        TiffTag(259, Short, intArrayOf(compression)),
        TiffTag(262, Short, intArrayOf(photometric)),
        TiffTag(277, Short, intArrayOf(1)),
        TiffTag(278, Long, intArrayOf(height))
    )

    fun packBits(data: ByteArray): ByteArray {
        // Literal runs only would pass too easily, so runs are encoded whenever they exist.
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var run = 1
            while (i + run < data.size && run < 128 && data[i + run] == data[i]) run++
            if (run >= 2) {
                out.write(1 - run)
                out.write(data[i].toInt())
                i += run
            } else {
                var literal = 1
                while (i + literal < data.size && literal < 128 &&
                    (i + literal + 1 >= data.size || data[i + literal] != data[i + literal + 1])) literal++
                out.write(literal - 1)
                out.write(data, i, literal)
                i += literal
            }
        }
        return out.toByteArray()
    }

    /** TIFF LZW, written the way the spec's own example encoder does it. */
    fun lzw(data: ByteArray): ByteArray {
        val out = BitWriter()
        var table = HashMap<String, Int>()
        fun reset() {
            table = HashMap()
            for (i in 0 until 256) table[i.toChar().toString()] = i
        }
        reset()
        var next = 258
        var width = 9
        out.write(256, width)
        var current = ""
        for (byte in data) {
            val c = (byte.toInt() and 0xFF).toChar()
            val candidate = current + c
            if (candidate in table) {
                current = candidate
            } else {
                out.write(table.getValue(current), width)
                table[candidate] = next++
                when (next) {
                    512 -> width = 10
                    1024 -> width = 11
                    2048 -> width = 12
                }
                if (next == 4094) {
                    out.write(256, width)
                    reset()
                    next = 258
                    width = 9
                }
                current = c.toString()
            }
        }
        if (current.isNotEmpty()) {
            out.write(table.getValue(current), width)
            next++
            when (next) {
                512 -> width = 10
                1024 -> width = 11
                2048 -> width = 12
            }
        }
        out.write(257, width)
        return out.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var buffer = 0
        private var count = 0
        fun write(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                buffer = (buffer shl 1) or ((value shr i) and 1)
                count++
                if (count == 8) {
                    out.write(buffer)
                    buffer = 0
                    count = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (count > 0) {
                out.write(buffer shl (8 - count))
                buffer = 0
                count = 0
            }
            return out.toByteArray()
        }
    }

    fun tga(width: Int, height: Int, type: Int, depth: Int, descriptor: Int, body: ByteArray, id: ByteArray = ByteArray(0), map: ByteArray = ByteArray(0), mapLength: Int = 0, mapDepth: Int = 0): ByteBuffer {
        val buffer = ByteBuffer.allocate(18 + id.size + map.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(id.size.toByte())
        buffer.put(if (mapLength > 0) 1 else 0)
        buffer.put(type.toByte())
        buffer.putShort(0)
        buffer.putShort(mapLength.toShort())
        buffer.put(mapDepth.toByte())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(width.toShort())
        buffer.putShort(height.toShort())
        buffer.put(depth.toByte())
        buffer.put(descriptor.toByte())
        buffer.put(id)
        buffer.put(map)
        buffer.put(body)
        buffer.rewind()
        return buffer
    }

    fun psd(width: Int, height: Int, channels: Int, mode: Int, depth: Int, imageData: ByteArray, compression: Int, palette: ByteArray = ByteArray(0)): ByteBuffer {
        val buffer = ByteBuffer.allocate(26 + 4 + palette.size + 4 + 4 + 2 + imageData.size + 8).order(ByteOrder.BIG_ENDIAN)
        buffer.put("8BPS".toByteArray())
        buffer.putShort(1)
        buffer.put(ByteArray(6))
        buffer.putShort(channels.toShort())
        buffer.putInt(height)
        buffer.putInt(width)
        buffer.putShort(depth.toShort())
        buffer.putShort(mode.toShort())
        buffer.putInt(palette.size)
        buffer.put(palette)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putShort(compression.toShort())
        buffer.put(imageData)
        return ByteBuffer.wrap(buffer.array(), 0, buffer.position()).order(ByteOrder.BIG_ENDIAN)
    }
}
