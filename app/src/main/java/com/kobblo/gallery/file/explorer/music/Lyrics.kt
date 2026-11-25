package com.kobblo.gallery.file.explorer.music

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics for one song. Unsynced lyrics have every [LyricLine.timeMs] at zero and never scroll themselves. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

private val Timestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?::(\d{1,3}))?(?:\.(\d{1,3}))?]""")
private val MetadataTag = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")
private val WordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

/**
 * Parses LRC: `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss]`, `[mm:ss:xx]` and `[hh:mm:ss.xx]`, several times
 * on one line, `<mm:ss.xx>` word timings, which are dropped, and the `[offset:]` tag. Text with no
 * times at all comes back as plain lyrics, so a text file can be read the same way.
 */
object LrcParser {
    fun parse(text: String): Lyrics {
        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()

        text.removePrefix("﻿").lineSequence().forEach { raw ->
            val line = raw.trim()
            val times = mutableListOf<Long>()
            var rest = line
            while (true) {
                val match = Timestamp.matchAt(rest, 0) ?: break
                times += match.toMillis()
                rest = rest.substring(match.range.last + 1).trimStart()
            }
            when {
                times.isNotEmpty() -> {
                    val words = rest.replace(WordTag, "").replace(Regex("\\s{2,}"), " ").trim()
                    times.forEach { timed += LyricLine(it, words) }
                }
                else -> {
                    val tag = MetadataTag.find(line)
                    if (tag != null) {
                        if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                            offsetMs = tag.groupValues[2].trim().toLongOrNull() ?: offsetMs
                        }
                    } else {
                        plain += line
                    }
                }
            }
        }

        if (timed.isEmpty()) {
            return Lyrics(plain.trimBlankEnds().map { LyricLine(0L, it) }, synced = false)
        }
        // A positive offset means the words should come sooner.
        val lines = timed
            .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
            .sortedBy { it.timeMs }
        return Lyrics(lines, synced = true)
    }

    private fun MatchResult.toMillis(): Long {
        val (first, second, third, fraction) = destructured
        // Three colon parts with no dot is the old [mm:ss:xx]. Hours only count with a dot after them.
        return if (third.isNotEmpty() && fraction.isNotEmpty()) {
            first.toLong() * 3_600_000L + second.toLong() * 60_000L + third.toLong() * 1000L + fractionToMillis(fraction)
        } else {
            first.toLong() * 60_000L + second.toLong() * 1000L + fractionToMillis(fraction.ifEmpty { third })
        }
    }

    private fun fractionToMillis(fraction: String): Long = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100L
        2 -> fraction.toLong() * 10L
        else -> fraction.toLong()
    }

    private fun List<String>.trimBlankEnds(): List<String> =
        dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

    /** The line playing at [positionMs]: the last one that has started, or -1 before the first. */
    fun lineIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }
}

/**
 * Reads a lyrics file whatever it was saved as: a byte order mark says so outright, otherwise it's
 * UTF-8 if it decodes cleanly as UTF-8, and otherwise the single-byte Western encoding most old LRC
 * files were written in.
 */
fun decodeLyricBytes(bytes: ByteArray): String {
    val mark = ByteOrderMarks.firstOrNull { it.opens(bytes) }
    if (mark != null) return String(bytes, mark.size, bytes.size - mark.size, mark.charset)
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}

/** The bytes a text file can open with to say outright which encoding the rest of it is in. */
private class ByteOrderMark(val charset: Charset, vararg values: Int) {
    private val mark = ByteArray(values.size) { values[it].toByte() }

    val size: Int get() = mark.size

    fun opens(bytes: ByteArray): Boolean = bytes.size >= size && mark.indices.all { bytes[it] == mark[it] }
}

private val ByteOrderMarks = listOf(
    ByteOrderMark(Charsets.UTF_8, 0xEF, 0xBB, 0xBF),
    ByteOrderMark(Charsets.UTF_16LE, 0xFF, 0xFE),
    ByteOrderMark(Charsets.UTF_16BE, 0xFE, 0xFF)
)

/** Decodes the ID3v2 lyric frames the player hands over as raw bytes. */
object Id3Lyrics {
    private fun charset(encoding: Int): Charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }

    private fun terminatorSize(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    /** Finds the end of a null-terminated string that starts at [from], or -1. */
    private fun terminatorAt(data: ByteArray, from: Int, encoding: Int): Int {
        val step = terminatorSize(encoding)
        var i = from
        while (i + step - 1 < data.size) {
            if (data[i] == 0.toByte() && (step == 1 || data[i + 1] == 0.toByte())) return i
            i += step
        }
        return -1
    }

    /** USLT: encoding, three-letter language, a description, then the lyrics. */
    fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        val descriptionEnd = terminatorAt(data, 4, encoding)
        if (descriptionEnd < 0) return null
        val start = descriptionEnd + terminatorSize(encoding)
        if (start >= data.size) return null
        return String(data, start, data.size - start, charset(encoding)).trim().ifEmpty { null }
    }

    /** SYLT: encoding, language, time format, content type, a description, then text and time pairs. */
    fun decodeSylt(data: ByteArray): Lyrics? {
        if (data.size < 7) return null
        val encoding = data[0].toInt() and 0xFF
        val timeFormat = data[4].toInt() and 0xFF
        if (timeFormat != 2) return null // Only milliseconds; MPEG frame counts would need the bitrate.
        var at = terminatorAt(data, 6, encoding)
        if (at < 0) return null
        at += terminatorSize(encoding)
        val lines = mutableListOf<LyricLine>()
        while (at < data.size) {
            val end = terminatorAt(data, at, encoding)
            if (end < 0) break
            val text = String(data, at, end - at, charset(encoding)).trim()
            at = end + terminatorSize(encoding)
            if (at + 4 > data.size) break
            val time = ((data[at].toLong() and 0xFF) shl 24) or
                ((data[at + 1].toLong() and 0xFF) shl 16) or
                ((data[at + 2].toLong() and 0xFF) shl 8) or
                (data[at + 3].toLong() and 0xFF)
            at += 4
            lines += LyricLine(time, text)
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines.sortedBy { it.timeMs }, synced = true)
    }
}
