package com.kobblo.gallery.file.explorer.viewer.decoder

import java.nio.ByteBuffer

/** The Netpbm family: PBM, PGM and PPM, in both their text and binary forms. */
object PnmDecoder {
    fun isPnm(head: ByteArray): Boolean =
        head.size >= 3 && head[0] == 'P'.code.toByte() && head[1] in '1'.code.toByte()..'6'.code.toByte() &&
            head[2].toInt().toChar().isWhitespace()

    fun decode(source: ByteBuffer, target: DecodeTarget = DecodeTarget.Original): RasterImage = checkedRead {
        val data = source.duplicate()
        if (data.capacity() < 3 || data.get(0) != 'P'.code.toByte()) unsupported("Not PNM")
        val kind = data.get(1).toInt().toChar()
        if (kind !in '1'..'6') unsupported("PNM kind $kind")
        val reader = Tokens(data, 2)
        val width = reader.int()
        val height = reader.int()
        val isBits = kind == '1' || kind == '4'
        val maxValue = if (isBits) 1 else reader.int()
        if (maxValue !in 1..65535) unsupported("Max value $maxValue")
        val channels = if (kind == '3' || kind == '6') 3 else 1
        val isBinary = kind >= '4'

        val step = target.stepFor(width, height)
        val outWidth = sampledSize(width, step)
        val outHeight = sampledSize(height, step)
        val out = IntArray(outWidth * outHeight)

        fun scale(value: Int): Int = if (maxValue == 255) value and 0xFF else (value.coerceAtMost(maxValue) * 255L / maxValue).toInt()
        fun store(x: Int, y: Int, r: Int, g: Int, b: Int) {
            if (x % step == 0 && y % step == 0) out[(y / step) * outWidth + x / step] = opaque(r, g, b)
        }

        if (kind == '4') {
            // One bit a pixel, every row padded to a whole byte, and a set bit means black.
            val rowBytes = (width + 7) / 8
            val start = reader.position + 1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val bit = (data.get(start + y * rowBytes + x / 8).toInt() shr (7 - x % 8)) and 1
                    val v = (1 - bit) * 255
                    store(x, y, v, v, v)
                }
            }
        } else if (isBinary) {
            val sampleBytes = if (maxValue > 255) 2 else 1
            var at = reader.position + 1
            fun sample(): Int {
                val value = if (sampleBytes == 2) {
                    (data.u8(at) shl 8) or data.u8(at + 1)
                } else {
                    data.u8(at)
                }
                at += sampleBytes
                return scale(value)
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (channels == 3) store(x, y, sample(), sample(), sample())
                    else sample().let { store(x, y, it, it, it) }
                }
            }
        } else {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    when {
                        kind == '1' -> {
                            val v = (1 - reader.bit()) * 255
                            store(x, y, v, v, v)
                        }
                        channels == 3 -> store(x, y, scale(reader.int()), scale(reader.int()), scale(reader.int()))
                        else -> scale(reader.int()).let { store(x, y, it, it, it) }
                    }
                }
            }
        }
        RasterImage(outWidth, outHeight, out, width, height)
    }

    /** Whitespace-separated numbers, with # comments running to the end of a line. */
    private class Tokens(private val data: ByteBuffer, var position: Int) {
        fun int(): Int {
            skip()
            val start = position
            var value = 0L
            while (position < data.capacity()) {
                val c = data.get(position).toInt()
                if (c !in '0'.code..'9'.code) break
                value = value * 10 + (c - '0'.code)
                if (value > Int.MAX_VALUE) unsupported("Number too large")
                position++
            }
            if (position == start) unsupported("Expected a number")
            return value.toInt()
        }

        /** PBM's text form may run its digits together. */
        fun bit(): Int {
            skip()
            if (position >= data.capacity()) unsupported("Too little data")
            val c = data.get(position++).toInt()
            return if (c == '1'.code) 1 else 0
        }

        private fun skip() {
            while (position < data.capacity()) {
                val c = data.get(position).toInt()
                when {
                    c == '#'.code -> while (position < data.capacity() && data.get(position).toInt() != '\n'.code) position++
                    c.toChar().isWhitespace() -> position++
                    else -> return
                }
            }
        }
    }
}
