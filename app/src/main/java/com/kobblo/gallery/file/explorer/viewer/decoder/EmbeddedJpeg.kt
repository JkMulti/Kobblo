package com.kobblo.gallery.file.explorer.viewer.decoder

import java.nio.ByteBuffer

/**
 * Finds the largest JPEG inside a file. Camera RAW formats differ wildly, but nearly all of them carry
 * a full-size JPEG preview, which is what a gallery shows anyway.
 */
object EmbeddedJpeg {
    class Range(val offset: Int, val length: Int)

    /** The biggest complete JPEG in [data], or null. Small ones are thumbnails, and are skipped by size. */
    fun findLargest(data: ByteBuffer, minLength: Int = 1024): Range? {
        var best: Range? = null
        var at = 0
        val end = data.capacity()
        while (at + 3 < end) {
            if (data.u8(at) == 0xFF && data.u8(at + 1) == 0xD8 && data.u8(at + 2) == 0xFF) {
                val length = jpegLength(data, at)
                if (length > 0) {
                    if (length >= minLength && (best == null || length > best.length)) best = Range(at, length)
                    at += length
                    continue
                }
            }
            at++
        }
        return best
    }

    /** Walks the markers to the scan and then to its end, or returns 0 if it isn't really a JPEG. */
    private fun jpegLength(data: ByteBuffer, start: Int): Int {
        val end = data.capacity()
        var at = start + 2
        while (at + 2 <= end) {
            if (data.u8(at) != 0xFF) return 0
            val marker = data.u8(at + 1)
            when {
                marker == 0xFF -> at++
                marker == 0xD9 -> return at + 2 - start
                marker in 0xD0..0xD7 || marker == 0x01 -> at += 2
                marker == 0xDA -> {
                    at += 2 + segmentLength(data, at + 2)
                    // Entropy-coded data: a stuffed FF 00 or a restart marker is part of the scan.
                    while (at + 2 <= end) {
                        if (data.u8(at) == 0xFF) {
                            val next = data.u8(at + 1)
                            if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) break
                        }
                        at++
                    }
                    if (at + 2 > end) return 0
                }
                else -> {
                    val length = segmentLength(data, at + 2)
                    if (length < 2) return 0
                    at += 2 + length
                }
            }
        }
        return 0
    }

    private fun segmentLength(data: ByteBuffer, at: Int): Int =
        if (at + 2 > data.capacity()) 0 else (data.u8(at) shl 8) or data.u8(at + 1)
}
