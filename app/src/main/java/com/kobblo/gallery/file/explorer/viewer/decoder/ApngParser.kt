package com.kobblo.gallery.file.explorer.viewer.decoder

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Takes an animated PNG apart into one standalone PNG per frame, each of which the platform's
 * ordinary decoder can read. Android draws an APNG's first frame and stops, and there is nothing to
 * animate it short of this.
 */
object ApngParser {
    private val Signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    const val DisposeNone = 0
    const val DisposeBackground = 1
    const val DisposePrevious = 2
    const val BlendSource = 0
    const val BlendOver = 1

    /** One frame: the part of the canvas it covers and how long it stays. */
    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val delayMillis: Int,
        val dispose: Int,
        val blend: Int,
        /** A complete PNG holding only this frame. */
        val png: ByteArray
    )

    class Animation(val width: Int, val height: Int, val plays: Int, val frames: List<Frame>)

    /** True when the PNG declares animation, which is all a tile or a thumbnail needs to know. */
    fun isAnimated(data: ByteArray): Boolean {
        if (!hasSignature(data)) return false
        var at = Signature.size
        while (at + 8 <= data.size) {
            val length = int(data, at)
            val type = chunkType(data, at + 4)
            if (type == "acTL") return true
            if (type == "IDAT" || length < 0) return false
            at += 12L.plus(length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return false
    }

    /** Returns the frames, or null when this is an ordinary still PNG or too broken to animate. */
    fun parse(data: ByteArray): Animation? {
        if (!hasSignature(data)) return null
        var at = Signature.size
        var ihdr: ByteArray? = null
        var plays = 0
        var animated = false
        // Palette and color chunks apply to every frame, so each standalone PNG needs a copy.
        val shared = ByteArrayOutputStream()
        val frames = ArrayList<Frame>()
        var control: IntArray? = null
        var body = ByteArrayOutputStream()

        fun finishFrame() {
            val c = control ?: return
            val header = ihdr ?: return
            if (body.size() > 0) {
                frames.add(Frame(c[0], c[1], c[2], c[3], c[4], c[5], c[6], assemble(header, c[2], c[3], shared.toByteArray(), body.toByteArray())))
            }
            control = null
            body = ByteArrayOutputStream()
        }

        while (at + 12 <= data.size) {
            val length = int(data, at)
            if (length < 0 || at + 12L + length > data.size) return null
            val type = chunkType(data, at + 4)
            val payload = at + 8
            when (type) {
                "IHDR" -> {
                    if (length != 13) return null
                    ihdr = data.copyOfRange(payload, payload + length)
                }
                "acTL" -> {
                    if (length < 8) return null
                    animated = true
                    plays = int(data, payload + 4)
                }
                "fcTL" -> {
                    if (length < 26) return null
                    finishFrame()
                    control = intArrayOf(
                        int(data, payload + 12),
                        int(data, payload + 16),
                        int(data, payload + 4),
                        int(data, payload + 8),
                        delayMillis(u16(data, payload + 20), u16(data, payload + 22)),
                        data[payload + 24].toInt() and 0xFF,
                        data[payload + 25].toInt() and 0xFF
                    )
                }
                "IDAT" -> {
                    // A default image with no control before it is only for viewers that can't animate.
                    if (control != null) body.write(chunk("IDAT", data, payload, length))
                }
                "fdAT" -> {
                    if (length < 4) return null
                    if (control != null) body.write(chunk("IDAT", data, payload + 4, length - 4))
                }
                "IEND" -> {
                    finishFrame()
                    at = data.size
                    continue
                }
                "PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT" -> shared.write(data, at, 12 + length)
            }
            at += 12 + length
        }
        finishFrame()

        val header = ihdr ?: return null
        if (!animated || frames.isEmpty()) return null
        val width = int(header, 0)
        val height = int(header, 4)
        // Frames that reach past the canvas are the classic way to break an APNG decoder.
        if (width <= 0 || height <= 0) return null
        if (frames.any { it.x < 0 || it.y < 0 || it.width <= 0 || it.height <= 0 || it.x.toLong() + it.width > width || it.y.toLong() + it.height > height }) return null
        return Animation(width, height, plays, frames)
    }

    private fun assemble(header: ByteArray, width: Int, height: Int, shared: ByteArray, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + shared.size + 64)
        out.write(Signature)
        val ihdr = header.copyOf()
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        out.write(chunk("IHDR", ihdr, 0, ihdr.size))
        out.write(shared)
        out.write(body)
        out.write(chunk("IEND", ByteArray(0), 0, 0))
        return out.toByteArray()
    }

    /** A frame's delay is a fraction of a second, where a denominator of zero means hundredths. */
    private fun delayMillis(numerator: Int, denominator: Int): Int {
        val over = if (denominator == 0) 100 else denominator
        return (numerator * 1000 / over).let { if (it <= 0) DefaultDelay else it }
    }

    private const val DefaultDelay = 100

    private fun chunk(type: String, data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArray(12 + length)
        putInt(out, 0, length)
        val name = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(name, 0, out, 4, 4)
        System.arraycopy(data, offset, out, 8, length)
        val crc = CRC32()
        crc.update(out, 4, 4 + length)
        putInt(out, 8 + length, crc.value.toInt())
        return out
    }

    private fun hasSignature(data: ByteArray): Boolean =
        data.size >= Signature.size && Signature.indices.all { data[it] == Signature[it] }

    private fun chunkType(data: ByteArray, at: Int): String = String(data, at, 4, Charsets.US_ASCII)

    private fun int(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun putInt(data: ByteArray, at: Int, value: Int) {
        data[at] = (value ushr 24).toByte()
        data[at + 1] = (value ushr 16).toByte()
        data[at + 2] = (value ushr 8).toByte()
        data[at + 3] = value.toByte()
    }
}
