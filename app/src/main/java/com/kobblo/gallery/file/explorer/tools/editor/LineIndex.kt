package com.kobblo.gallery.file.explorer.tools.editor

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Where each line of a file begins, found by one pass over its bytes, so any line of a huge file can
 * be read on demand. A very long line is cut into pieces, always on a character boundary, because a
 * screen can't lay out megabytes of one line.
 */
class LineIndex private constructor(
    private val file: File,
    val charset: Charset,
    private val offsets: LongArray,
    val lineCount: Int,
    private val bodyStart: Long,
    private val fileLength: Long
) {
    /** The text of [line], without its line break. */
    fun read(line: Int, source: RandomAccessFile): String {
        if (line < 0 || line >= lineCount) return ""
        val start = offsets[line]
        val end = if (line + 1 < lineCount) offsets[line + 1] else fileLength
        val length = (end - start).coerceAtMost(MAX_LINE_BYTES.toLong() * 2).toInt()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        source.seek(start)
        source.readFully(bytes)
        return String(bytes, charset).trimEnd('\n', '\r')
    }

    /** Opens the file for [read]. */
    fun open(): RandomAccessFile = RandomAccessFile(file, "r")

    /** The first line at or after [from] containing [query], or -1. Stops when [isActive] turns false. */
    fun findNext(query: String, from: Int, isActive: () -> Boolean = { true }): Int {
        if (query.isEmpty()) return -1
        open().use { source ->
            var line = from.coerceAtLeast(0)
            while (line < lineCount && isActive()) {
                if (read(line, source).contains(query, ignoreCase = true)) return line
                line++
            }
        }
        return -1
    }

    companion object {
        const val MAX_LINE_BYTES = 4096
        private const val BUFFER = 256 * 1024

        /**
         * @param onProgress receives the fraction of the file read so far.
         * @param isActive is checked between blocks, and stops the scan when it turns false.
         */
        fun build(
            file: File,
            charset: Charset,
            onProgress: (Float) -> Unit = {},
            isActive: () -> Boolean = { true }
        ): LineIndex {
            val length = file.length()
            val wide = charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
            val unit = if (wide) 2 else 1
            val newlineLowByte = if (charset == Charsets.UTF_16BE) 1 else 0
            val builder = LongList()
            RandomAccessFile(file, "r").use { source ->
                var bodyStart = 0L
                val head = ByteArray(3)
                val headRead = source.read(head)
                bodyStart = bomLength(head, headRead, charset)
                builder.add(bodyStart)
                source.seek(bodyStart)

                val buffer = ByteArray(BUFFER)
                var position = bodyStart
                var lineStart = bodyStart
                var carry = 0
                var pending: Byte = 0
                while (isActive()) {
                    val read = source.read(buffer, carry, buffer.size - carry)
                    if (carry > 0) buffer[0] = pending
                    if (read <= 0) break
                    val available = read + carry
                    val usable = available - available % unit
                    var i = 0
                    while (i < usable) {
                        val isNewline = if (wide) {
                            buffer[i + newlineLowByte].toInt() == 0x0A && buffer[i + 1 - newlineLowByte].toInt() == 0
                        } else {
                            buffer[i].toInt() == 0x0A
                        }
                        val at = position + i
                        if (isNewline) {
                            lineStart = at + unit
                            if (lineStart < length) builder.add(lineStart)
                        } else if (at - lineStart >= MAX_LINE_BYTES) {
                            // A cut in the middle of a line, moved back to the start of a character.
                            val cut = if (wide) at else at - continuationBytesBefore(buffer, i, at - position)
                            if (cut > lineStart) {
                                lineStart = cut
                                builder.add(lineStart)
                            }
                        }
                        i += unit
                    }
                    position += usable
                    carry = available - usable
                    if (carry > 0) pending = buffer[usable]
                    onProgress(if (length == 0L) 1f else (position.toFloat() / length).coerceIn(0f, 1f))
                }
                return LineIndex(file, charset, builder.toArray(), builder.size, bodyStart, length)
            }
        }

        private fun bomLength(head: ByteArray, read: Int, charset: Charset): Long = when {
            charset == Charsets.UTF_8 && read >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte() -> 3
            charset == Charsets.UTF_16LE && read >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> 2
            charset == Charsets.UTF_16BE && read >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> 2
            else -> 0
        }

        /** How far back a UTF-8 character's start is from position [i], looking at most 3 bytes. */
        private fun continuationBytesBefore(buffer: ByteArray, i: Int, limit: Long): Int {
            var back = 0
            while (back < 3 && back < limit && i - back >= 0 && (buffer[i - back].toInt() and 0xC0) == 0x80) back++
            return back
        }

        private class LongList {
            private var items = LongArray(1024)
            var size = 0
                private set

            fun add(value: Long) {
                if (size == items.size) items = items.copyOf(items.size * 2)
                items[size++] = value
            }

            fun toArray(): LongArray = items.copyOf(size)
        }
    }
}
