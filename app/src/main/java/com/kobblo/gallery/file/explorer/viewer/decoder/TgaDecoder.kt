package com.kobblo.gallery.file.explorer.viewer.decoder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Targa: color-mapped, true-color and gray, raw or run-length encoded, at 8, 15, 16, 24 or 32 bits. */
object TgaDecoder {
    private const val HeaderSize = 18
    private val KnownTypes = setOf(1, 2, 3, 9, 10, 11)
    private val KnownDepths = setOf(8, 15, 16, 24, 32)

    /** TGA has no signature, so this is only a sanity check of the header's fields. */
    fun looksLikeTga(head: ByteArray): Boolean {
        if (head.size < HeaderSize) return false
        val colorMapType = head[1].toInt() and 0xFF
        val type = head[2].toInt() and 0xFF
        val width = (head[12].toInt() and 0xFF) or ((head[13].toInt() and 0xFF) shl 8)
        val height = (head[14].toInt() and 0xFF) or ((head[15].toInt() and 0xFF) shl 8)
        val depth = head[16].toInt() and 0xFF
        return colorMapType <= 1 && type in KnownTypes && depth in KnownDepths && width > 0 && height > 0
    }

    fun decode(source: ByteBuffer, target: DecodeTarget = DecodeTarget.Original): RasterImage = checkedRead {
        val data = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (data.capacity() < HeaderSize) unsupported("Too short for TGA")
        val idLength = data.u8(0)
        val colorMapType = data.u8(1)
        val type = data.u8(2)
        val mapFirst = data.getShort(3).toInt() and 0xFFFF
        val mapLength = data.getShort(5).toInt() and 0xFFFF
        val mapDepth = data.u8(7)
        val width = data.getShort(12).toInt() and 0xFFFF
        val height = data.getShort(14).toInt() and 0xFFFF
        val depth = data.u8(16)
        val descriptor = data.u8(17)
        if (type !in KnownTypes || depth !in KnownDepths) unsupported("TGA type $type at $depth bits")

        val isRle = type >= 9
        val isGray = type == 3 || type == 11
        val isMapped = type == 1 || type == 9
        if (isMapped && colorMapType != 1) unsupported("Color-mapped TGA without a map")
        val mapBytes = (mapDepth + 7) / 8
        val mapAt = HeaderSize + idLength
        var at = mapAt + (if (colorMapType == 1) mapLength * mapBytes else 0)

        // Alpha bits of zero, or a 32-bit file that never sets alpha, both mean opaque.
        val alphaBits = descriptor and 0x0F
        val hasAlpha = depth == 32 && alphaBits > 0 || depth == 16 && alphaBits > 0
        val fromRight = descriptor and 0x10 != 0
        val fromTop = descriptor and 0x20 != 0

        val pixelBytes = (depth + 7) / 8
        val step = target.stepFor(width, height)
        val outWidth = sampledSize(width, step)
        val outHeight = sampledSize(height, step)
        val out = IntArray(outWidth * outHeight)

        fun readColor(position: Int): Int {
            if (isMapped) {
                val index = (if (pixelBytes == 1) data.u8(position) else data.getShort(position).toInt() and 0xFFFF) - mapFirst
                if (index !in 0 until mapLength) return opaque(0, 0, 0)
                return readTrueColor(data, mapAt + index * mapBytes, mapDepth, mapDepth == 32)
            }
            if (isGray) {
                val v = data.u8(position)
                return if (depth == 16) argb(data.u8(position + 1), v, v, v) else opaque(v, v, v)
            }
            return readTrueColor(data, position, depth, hasAlpha)
        }

        var sawAlpha = !hasAlpha
        fun store(index: Int, color: Int) {
            if (color ushr 24 != 0) sawAlpha = true
            val fileColumn = index % width
            val fileRow = index / width
            val x = if (fromRight) width - 1 - fileColumn else fileColumn
            val y = if (fromTop) fileRow else height - 1 - fileRow
            if (x % step == 0 && y % step == 0) out[(y / step) * outWidth + x / step] = color
        }

        val total = width.toLong() * height
        var index = 0L
        if (isRle) {
            while (index < total) {
                val header = data.u8(at++)
                val count = (header and 0x7F) + 1
                if (header and 0x80 != 0) {
                    val color = readColor(at)
                    at += pixelBytes
                    for (i in 0 until count) {
                        if (index >= total) break
                        store(index.toInt(), color)
                        index++
                    }
                } else {
                    for (i in 0 until count) {
                        if (index >= total) break
                        store(index.toInt(), readColor(at))
                        at += pixelBytes
                        index++
                    }
                }
            }
        } else {
            while (index < total) {
                store(index.toInt(), readColor(at))
                at += pixelBytes
                index++
            }
        }

        // Some writers mark alpha and then leave it all zero, which would draw nothing.
        if (!sawAlpha) for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        RasterImage(outWidth, outHeight, out, width, height)
    }

    private fun readTrueColor(data: ByteBuffer, at: Int, depth: Int, useAlpha: Boolean): Int = when (depth) {
        15, 16 -> {
            val v = data.getShort(at).toInt() and 0xFFFF
            val r = (v shr 10) and 0x1F
            val g = (v shr 5) and 0x1F
            val b = v and 0x1F
            val a = if (useAlpha && v and 0x8000 == 0) 0 else 255
            argb(a, r * 255 / 31, g * 255 / 31, b * 255 / 31)
        }
        24 -> opaque(data.u8(at + 2), data.u8(at + 1), data.u8(at))
        32 -> argb(if (useAlpha) data.u8(at + 3) else 255, data.u8(at + 2), data.u8(at + 1), data.u8(at))
        else -> unsupported("Map depth $depth")
    }
}
