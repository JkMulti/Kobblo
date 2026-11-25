package com.kobblo.gallery.file.explorer.viewer.metadata

import java.io.RandomAccessFile

/**
 * Removes metadata from an MP4 or MOV by renaming boxes to `free`, which every reader skips. Nothing
 * moves, so the offsets into the video data stay right, and it can't damage the samples because it
 * never reads them. It works in place, on a copy made by the caller.
 */
object Mp4MetadataStripper {
    private val Containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    /** Boxes inside `udta` that say where it was shot. Others, like a title, are left alone. */
    private val LocationBoxes = setOf("©xyz", "loci", "©lat", "©lon", "©loc")
    private val MetadataBoxes = setOf("udta", "meta")
    private val TimeBoxes = setOf("mvhd", "tkhd", "mdhd")

    /** True when this looks like an MP4 family file at all. */
    fun isMp4(file: RandomAccessFile): Boolean {
        if (file.length() < 12) return false
        file.seek(4)
        val type = ByteArray(4)
        file.readFully(type)
        return String(type, Charsets.ISO_8859_1) == "ftyp"
    }

    /** Blanks location boxes. Returns how many it found. */
    fun removeLocation(file: RandomAccessFile): Int {
        var removed = 0
        walk(file, 0, file.length(), intoUdta = true) { type, start, size, headerSize ->
            when {
                type in LocationBoxes -> {
                    blank(file, start, size, headerSize)
                    removed++
                    true
                }
                // A location may also be a keyed tag inside a `meta` box, as iPhones and others write it.
                type == "meta" -> {
                    if (blankKeyedLocation(file, start + headerSize, size - headerSize)) removed++
                    true
                }
                else -> false
            }
        }
        return removed
    }

    /** Blanks every metadata box and zeroes the capture dates. Returns how many boxes changed. */
    fun removeAll(file: RandomAccessFile): Int {
        var changed = 0
        walk(file, 0, file.length(), intoUdta = false) { type, start, size, headerSize ->
            when {
                type in MetadataBoxes || type == "uuid" -> {
                    blank(file, start, size, headerSize)
                    changed++
                    true
                }
                type in TimeBoxes -> {
                    zeroTimes(file, start + headerSize)
                    changed++
                    true
                }
                else -> false
            }
        }
        return changed
    }

    /** An ISO 6709 string from a keyed tag inside a `meta` box, if there is one. */
    fun findKeyedLocation(file: RandomAccessFile): String? {
        var found: String? = null
        walk(file, 0, file.length(), intoUdta = true) { type, start, size, headerSize ->
            if (type != "meta" || found != null) return@walk false
            if (size - headerSize <= MaxMetaScan) {
                val bytes = ByteArray((size - headerSize).toInt())
                file.seek(start + headerSize)
                file.readFully(bytes)
                val text = String(bytes, Charsets.ISO_8859_1)
                if (text.contains(LocationKey)) found = Iso6709Pattern.find(text)?.value
            }
            true
        }
        return found
    }

    /**
     * Walks the box tree. [visit] returns true when it has dealt with a box, so the walk needn't look
     * inside. A size only says what the file claims, so anything impossible ends the walk there.
     */
    private fun walk(
        file: RandomAccessFile,
        from: Long,
        to: Long,
        intoUdta: Boolean,
        depth: Int = 0,
        visit: (type: String, start: Long, size: Long, headerSize: Int) -> Boolean
    ) {
        if (depth > MaxDepth) return
        var at = from
        val header = ByteArray(8)
        while (at + 8 <= to) {
            file.seek(at)
            file.readFully(header)
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            when (size) {
                1L -> {
                    if (at + 16 > to) return
                    val wide = ByteArray(8)
                    file.readFully(wide)
                    size = (u32(wide, 0) shl 32) or u32(wide, 4)
                    headerSize = 16
                }
                0L -> size = to - at
            }
            if (size < headerSize || at + size > to) return
            if (!visit(type, at, size, headerSize)) {
                if (type in Containers || (intoUdta && type == "udta")) {
                    walk(file, at + headerSize, at + size, intoUdta, depth + 1, visit)
                }
            }
            at += size
        }
    }

    private fun blank(file: RandomAccessFile, start: Long, size: Long, headerSize: Int) {
        // Zeroed first, so the information is really gone and not just skipped by polite readers.
        file.seek(start + headerSize)
        val zeros = ByteArray(minOf(size - headerSize, 64 * 1024L).toInt())
        var left = size - headerSize
        while (left > 0) {
            val step = minOf(left, zeros.size.toLong()).toInt()
            file.write(zeros, 0, step)
            left -= step
        }
        file.seek(start + 4)
        file.write("free".toByteArray(Charsets.ISO_8859_1))
    }

    private fun zeroTimes(file: RandomAccessFile, payload: Long) {
        file.seek(payload)
        val version = file.read()
        file.seek(payload + 4)
        // Creation and modification times come first in all three: 32 bits each in version 0, 64 in 1.
        file.write(ByteArray(if (version == 1) 16 else 8))
    }

    /**
     * Blanks the place inside a `meta` box and leaves its other tags. Keyed tags sit in two parallel
     * lists, so removing one would mean rewriting both. Spacing out the value and renaming the key
     * moves nothing, and leaves no location for anything to read.
     */
    private fun blankKeyedLocation(file: RandomAccessFile, start: Long, length: Long): Boolean {
        if (length > MaxMetaScan) return false
        val bytes = ByteArray(length.toInt())
        file.seek(start)
        file.readFully(bytes)
        val text = String(bytes, Charsets.ISO_8859_1)
        if (!text.contains(LocationKey)) return false
        var changed = false
        for (match in Iso6709Pattern.findAll(text)) {
            match.range.forEach { bytes[it] = ' '.code.toByte() }
            changed = true
        }
        for (match in Regex(LocationKey).findAll(text)) {
            match.range.forEach { bytes[it] = 'x'.code.toByte() }
            changed = true
        }
        if (changed) {
            file.seek(start)
            file.write(bytes)
        }
        return changed
    }

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private const val LocationKey = "location"
    private val Iso6709Pattern = Regex("""[+-]\d{1,3}(?:\.\d+)?[+-]\d{1,3}(?:\.\d+)?(?:[+-]\d+(?:\.\d+)?)?/""")
    private const val MaxDepth = 8
    private const val MaxMetaScan = 1024L * 1024
}
