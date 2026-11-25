package com.kobblo.gallery.file.explorer.tools.files

/** Laying out bytes as a hex dump: an offset, the bytes in hex, and the same bytes as text. */
object HexFormat {
    private const val DIGITS = "0123456789ABCDEF"

    /** Offsets stay eight digits wide until a file is too big for that, so columns line up. */
    fun offsetDigits(fileLength: Long): Int = if (fileLength <= 0xFFFFFFFFL) 8 else 12

    fun offset(value: Long, digits: Int): String = value.toString(16).uppercase().padStart(digits, '0')

    /** The bytes in hex with a wider gap at the halfway point, padded so a short last row aligns. */
    fun hexColumn(bytes: ByteArray, from: Int, count: Int, bytesPerRow: Int): String {
        val out = StringBuilder(bytesPerRow * 3 + 1)
        for (i in 0 until bytesPerRow) {
            if (i > 0) out.append(if (i == bytesPerRow / 2) "  " else " ")
            if (i < count) {
                val value = bytes[from + i].toInt() and 0xFF
                out.append(DIGITS[value shr 4]).append(DIGITS[value and 0x0F])
            } else {
                out.append("  ")
            }
        }
        return out.toString()
    }

    /** Printable ASCII as itself and everything else as a dot. */
    fun textColumn(bytes: ByteArray, from: Int, count: Int): String {
        val out = StringBuilder(count)
        for (i in 0 until count) {
            val value = bytes[from + i].toInt() and 0xFF
            out.append(if (value in 0x20..0x7E) value.toChar() else '.')
        }
        return out.toString()
    }

    /** The number of rows needed for a file. */
    fun rowCount(fileLength: Long, bytesPerRow: Int): Long =
        if (fileLength <= 0) 0 else (fileLength + bytesPerRow - 1) / bytesPerRow

    /**
     * Reads a typed offset, in hex with or without `0x`, or as decimal with a leading `#`.
     * Plain digits are taken as hex because that's what the screen shows.
     */
    fun parseOffset(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return try {
            when {
                text.startsWith("#") -> text.drop(1).toLong()
                text.startsWith("0x", ignoreCase = true) -> text.drop(2).toLong(16)
                else -> text.toLong(16)
            }.takeIf { it >= 0 }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
