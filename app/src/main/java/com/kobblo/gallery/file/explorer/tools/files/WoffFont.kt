package com.kobblo.gallery.file.explorer.tools.files

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * WOFF is an ordinary font whose tables are compressed one by one, so undoing that gives a file the
 * platform can load. WOFF2 is a different matter: it needs Brotli, which isn't worth carrying for this.
 * Sizes all come from the file, so what comes out of each table is counted rather than believed.
 */
internal object WoffFont {
    private const val MAGIC = 0x774F4646
    private const val HEADER = 44
    private const val ENTRY = 20
    private const val MAX_TABLES = 512
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    /** True if [file] was a WOFF and [into] now holds it unpacked. A damaged one counts as not a WOFF. */
    fun unpackIfWoff(file: File, into: File): Boolean = try {
        RandomAccessFile(file, "r").use { source ->
            if (source.length() < HEADER || source.readInt() != MAGIC) return@use false
            val packed = ByteArray(source.length().coerceAtMost(MAX_TOTAL_BYTES).toInt())
            source.seek(0)
            source.readFully(packed)
            val font = unpack(packed) ?: return@use false
            into.writeBytes(font)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** The font inside a WOFF's bytes, or null if they don't hold up. */
    fun unpack(data: ByteArray): ByteArray? {
        if (data.size < HEADER || int(data, 0) != MAGIC) return null
        val flavor = int(data, 4)
        val tableCount = short(data, 12)
        if (tableCount !in 1..MAX_TABLES || HEADER.toLong() + tableCount * ENTRY > data.size) return null

        class Table(val tag: Int, val checksum: Int, val bytes: ByteArray)
        val tables = ArrayList<Table>(tableCount)
        var total = 0L
        for (index in 0 until tableCount) {
            val at = HEADER + index * ENTRY
            val tag = int(data, at)
            val offset = int(data, at + 4).toLong() and 0xFFFFFFFFL
            val packedSize = int(data, at + 8).toLong() and 0xFFFFFFFFL
            val size = int(data, at + 12).toLong() and 0xFFFFFFFFL
            val checksum = int(data, at + 16)
            total += size
            if (total > MAX_TOTAL_BYTES || offset + packedSize > data.size || packedSize > size) return null
            val bytes = if (packedSize == size) {
                data.copyOfRange(offset.toInt(), (offset + size).toInt())
            } else {
                inflate(data, offset.toInt(), packedSize.toInt(), size.toInt()) ?: return null
            }
            tables.add(Table(tag, checksum, bytes))
        }

        val out = ByteArrayOutputStream(total.toInt() + 12 + tableCount * 16)
        fun writeInt(value: Int) {
            out.write(value ushr 24)
            out.write(value ushr 16)
            out.write(value ushr 8)
            out.write(value)
        }
        fun writeShort(value: Int) {
            out.write(value ushr 8)
            out.write(value)
        }
        var power = 1
        var log = 0
        while (power * 2 <= tableCount) {
            power *= 2
            log++
        }
        writeInt(flavor)
        writeShort(tableCount)
        writeShort(power * 16)
        writeShort(log)
        writeShort(tableCount * 16 - power * 16)
        // The directory must be sorted by tag, and each table starts on a four-byte boundary.
        val sorted = tables.sortedBy { it.tag.toLong() and 0xFFFFFFFFL }
        var position = 12 + tableCount * 16
        for (table in sorted) {
            writeInt(table.tag)
            writeInt(table.checksum)
            writeInt(position)
            writeInt(table.bytes.size)
            position += (table.bytes.size + 3) and 3.inv()
        }
        for (table in sorted) {
            out.write(table.bytes)
            repeat(((table.bytes.size + 3) and 3.inv()) - table.bytes.size) { out.write(0) }
        }
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, offset: Int, size: Int, expected: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data, offset, size)
            val out = ByteArray(expected)
            var filled = 0
            while (filled < expected && !inflater.finished()) {
                val read = inflater.inflate(out, filled, expected - filled)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                filled += read
            }
            if (filled == expected) out else null
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun int(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

    private fun short(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
}
