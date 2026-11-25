package com.kobblo.gallery.file.explorer.viewer.decoder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Photoshop's flat composite, the picture a PSD keeps after all its layers for readers that don't
 * want to merge them. Bitmap, gray, indexed, RGB and CMYK, at 1, 8 or 16 bits a channel, raw or
 * RLE. A PSB differs only in the width of a few lengths, so it comes along.
 */
object PsdDecoder {
    private const val ModeBitmap = 0
    private const val ModeIndexed = 2
    private const val ModeRgb = 3
    private const val ModeCmyk = 4

    fun isPsd(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == '8'.code.toByte() && head[1] == 'B'.code.toByte() &&
            head[2] == 'P'.code.toByte() && head[3] == 'S'.code.toByte()

    fun decode(source: ByteBuffer, target: DecodeTarget = DecodeTarget.Original): RasterImage = checkedRead {
        val data = source.duplicate().order(ByteOrder.BIG_ENDIAN)
        if (data.capacity() < 26 || !isPsd(ByteArray(4) { data.get(it) })) unsupported("Not PSD")
        val version = data.getShort(4).toInt() and 0xFFFF
        if (version !in 1..2) unsupported("PSD version $version")
        val isBig = version == 2
        val channels = data.getShort(12).toInt() and 0xFFFF
        val height = data.getInt(14)
        val width = data.getInt(18)
        val depth = data.getShort(22).toInt() and 0xFFFF
        val mode = data.getShort(24).toInt() and 0xFFFF
        if (depth !in setOf(1, 8, 16)) unsupported("$depth bits a channel")
        if (channels !in 1..56) unsupported("$channels channels")

        var at = 26
        val paletteLength = data.getInt(at)
        val paletteAt = at + 4
        at = paletteAt + nonNegative(paletteLength)
        at += 4 + nonNegative(data.getInt(at))
        // The layers come next. They aren't read: the composite sits right after them.
        at = if (isBig) {
            val length = data.getLong(at)
            if (length < 0 || length > Int.MAX_VALUE) unsupported("Bad layer section")
            at + 8 + length.toInt()
        } else {
            at + 4 + nonNegative(data.getInt(at))
        }

        val compression = data.getShort(at).toInt() and 0xFFFF
        at += 2
        val step = target.stepFor(width, height)
        val outWidth = sampledSize(width, step)
        val outHeight = sampledSize(height, step)
        val rowBytes = when (depth) {
            1 -> (width + 7) / 8
            8 -> width
            else -> width * 2
        }
        // How many channels make up a pixel in this mode. An extra one is usually alpha or a mask.
        val colorChannels = when (mode) {
            ModeRgb -> 3
            ModeCmyk -> 4
            else -> 1
        }
        if (channels < colorChannels) unsupported("$channels channels for mode $mode")

        val rows: RowSource = when (compression) {
            0 -> RawRows(data, at, rowBytes, height)
            1 -> RleRows(data, at, channels, height, rowBytes, isBig)
            else -> unsupported("Compression $compression")
        }

        val out = IntArray(outWidth * outHeight)
        val palette = if (mode == ModeIndexed && paletteLength >= 768) paletteAt else -1
        val channelRows = Array(colorChannels) { ByteArray(rowBytes) }
        for (y in 0 until height step step) {
            for (c in 0 until colorChannels) rows.read(c, y, channelRows[c])
            val outRow = (y / step) * outWidth
            for (x in 0 until width step step) {
                out[outRow + x / step] = pixel(channelRows, x, depth, mode, data, palette)
            }
        }
        RasterImage(outWidth, outHeight, out, width, height)
    }

    private fun nonNegative(value: Int): Int = if (value < 0) unsupported("Bad section length") else value

    private fun pixel(rows: Array<ByteArray>, x: Int, depth: Int, mode: Int, data: ByteBuffer, palette: Int): Int {
        fun sample(channel: Int): Int = when (depth) {
            8 -> rows[channel][x].toInt() and 0xFF
            // Stored big endian, so the first byte is the top eight bits.
            16 -> rows[channel][x * 2].toInt() and 0xFF
            else -> {
                val bit = (rows[channel][x / 8].toInt() shr (7 - x % 8)) and 1
                // In bitmap mode a set bit means black.
                if (mode == ModeBitmap) (1 - bit) * 255 else bit * 255
            }
        }
        return when (mode) {
            ModeRgb -> opaque(sample(0), sample(1), sample(2))
            ModeCmyk -> {
                // Photoshop stores ink inverted, so 255 means none.
                val k = sample(3)
                opaque(sample(0) * k / 255, sample(1) * k / 255, sample(2) * k / 255)
            }
            ModeIndexed -> if (palette < 0) {
                val v = sample(0)
                opaque(v, v, v)
            } else {
                val i = sample(0)
                opaque(data.u8(palette + i), data.u8(palette + 256 + i), data.u8(palette + 512 + i))
            }
            else -> {
                val v = sample(0)
                opaque(v, v, v)
            }
        }
    }

    private interface RowSource {
        fun read(channel: Int, row: Int, into: ByteArray)
    }

    private class RawRows(val data: ByteBuffer, val start: Int, val rowBytes: Int, val height: Int) : RowSource {
        override fun read(channel: Int, row: Int, into: ByteArray) {
            val view = data.duplicate()
            view.position(start + channel * height * rowBytes + row * rowBytes)
            view.get(into)
        }
    }

    /** RLE keeps a table of every row's compressed size up front, so any row can be reached directly. */
    private class RleRows(
        val data: ByteBuffer,
        start: Int,
        channels: Int,
        val height: Int,
        val rowBytes: Int,
        isBig: Boolean
    ) : RowSource {
        private val entrySize = if (isBig) 4 else 2
        private val table = start
        private val rowCount = channels.toLong() * height
        private val offsets = LongArray(rowCount.toInt().also { if (it * entrySize > data.capacity()) unsupported("Bad RLE table") } + 1)

        init {
            var at = start + rowCount * entrySize
            for (i in 0 until rowCount.toInt()) {
                offsets[i] = at
                at += sizeOf(i)
            }
            offsets[rowCount.toInt()] = at
        }

        private fun sizeOf(index: Int): Int =
            if (entrySize == 4) data.getInt(table + index * 4)
            else data.getShort(table + index * 2).toInt() and 0xFFFF

        override fun read(channel: Int, row: Int, into: ByteArray) {
            val index = channel * height + row
            into.fill(0)
            unpackBits(data, offsets[index].toInt(), (offsets[index + 1] - offsets[index]).toInt(), into)
        }
    }
}
