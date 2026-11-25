package com.kobblo.gallery.file.explorer.tools.editor

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How a text file's line breaks are written. */
enum class LineEnding(val text: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR")
}

/** Everything about a text file's bytes that isn't its characters, so saving can put it all back. */
data class TextFormat(
    val charset: Charset,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
    /** The file mixed several kinds of line break. Saving an edit writes them all as [lineEnding]. */
    val mixedLineEndings: Boolean = false
)

/**
 * @param text the characters, with every line break as `\n`, which is what an editor works with.
 * @param roundTrips whether saving this text unchanged would give back the exact same bytes. A file
 * whose bytes aren't valid in any encoding we can name stays viewable, but must not be edited.
 */
data class DecodedText(
    val text: String,
    val format: TextFormat,
    val roundTrips: Boolean
)

object TextCodec {
    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Encodings someone can choose to reopen a file in, most useful first. */
    val choices: List<Charset> by lazy {
        listOf("UTF-8", "UTF-16LE", "UTF-16BE", "windows-1252", "ISO-8859-1", "windows-1251", "Shift_JIS", "GBK", "Big5", "EUC-KR")
            .mapNotNull { name -> try { Charset.forName(name) } catch (_: Exception) { null } }
    }

    /** Works out the encoding and decodes. */
    fun decode(bytes: ByteArray): DecodedText = decode(bytes, detectCharset(bytes))

    fun decode(bytes: ByteArray, charset: Charset): DecodedText {
        val hasBom = bomFor(charset)?.let { bytes.startsWith(it) } == true
        val body = if (hasBom) bytes.copyOfRange(bomFor(charset)!!.size, bytes.size) else bytes
        val raw = String(body, charset)
        val (normalized, ending, mixed) = normalizeLineEndings(raw)
        val format = TextFormat(charset, hasBom, ending, mixed)
        // Mixed line breaks are reported separately, so they don't count against the round trip.
        val roundTrips = if (mixed) raw.toByteArray(charset).contentEquals(body) else encode(normalized, format).contentEquals(bytes)
        return DecodedText(normalized, format, roundTrips)
    }

    /** The bytes to write for [text], which uses `\n` for line breaks. */
    fun encode(text: String, format: TextFormat): ByteArray {
        val withEndings = if (format.lineEnding == LineEnding.LF) text else text.replace("\n", format.lineEnding.text)
        val body = withEndings.toByteArray(format.charset)
        val bom = if (format.hasBom) bomFor(format.charset) else null
        return if (bom != null) bom + body else body
    }

    /** False when [text] holds characters the encoding has no bytes for. */
    fun canEncode(text: String, charset: Charset): Boolean = try {
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.startsWith(BOM_UTF8)) return Charsets.UTF_8
        if (bytes.startsWith(BOM_UTF16LE)) return Charsets.UTF_16LE
        if (bytes.startsWith(BOM_UTF16BE)) return Charsets.UTF_16BE
        utf16WithoutBom(bytes)?.let { return it }
        if (isValid(bytes, Charsets.UTF_8)) return Charsets.UTF_8
        val windows1252 = try { Charset.forName("windows-1252") } catch (_: Exception) { null }
        if (windows1252 != null && isValid(bytes, windows1252)) return windows1252
        // Every byte has a meaning here, so this always decodes and always round-trips.
        return Charsets.ISO_8859_1
    }

    /**
     * Whether these bytes look like text at all, judged on a sample from the start of a file. A
     * NUL byte is the giveaway for binary, except in UTF-16, where half the bytes of plain English
     * are NUL.
     */
    fun looksLikeText(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return true
        if (sample.startsWith(BOM_UTF8) || sample.startsWith(BOM_UTF16LE) || sample.startsWith(BOM_UTF16BE)) return true
        if (utf16WithoutBom(sample) != null) return true
        var control = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            // Tab, line feed, form feed, carriage return, escape and backspace turn up in real text.
            if (v < 0x20 && v != 9 && v != 10 && v != 12 && v != 13 && v != 27 && v != 8) control++
        }
        return control * 100 < sample.size * 2
    }

    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        val pairs = minOf(bytes.size / 2, 2048)
        if (pairs < 4) return null
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until pairs) {
            if (bytes[i * 2].toInt() == 0) zeroEven++
            if (bytes[i * 2 + 1].toInt() == 0) zeroOdd++
        }
        val threshold = pairs * 7 / 10
        return when {
            zeroOdd >= threshold && zeroEven * 10 < pairs -> Charsets.UTF_16LE
            zeroEven >= threshold && zeroOdd * 10 < pairs -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun isValid(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun bomFor(charset: Charset): ByteArray? = when (charset) {
        Charsets.UTF_8 -> BOM_UTF8
        Charsets.UTF_16LE -> BOM_UTF16LE
        Charsets.UTF_16BE -> BOM_UTF16BE
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun normalizeLineEndings(raw: String): Triple<String, LineEnding, Boolean> {
        var lf = 0
        var crlf = 0
        var cr = 0
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\r' -> if (i + 1 < raw.length && raw[i + 1] == '\n') {
                    crlf++
                    i++
                } else {
                    cr++
                }
                '\n' -> lf++
            }
            i++
        }
        val kinds = listOf(lf, crlf, cr).count { it > 0 }
        val dominant = when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf && cr > crlf -> LineEnding.CR
            else -> LineEnding.LF
        }
        val normalized = if (crlf == 0 && cr == 0) raw else raw.replace("\r\n", "\n").replace('\r', '\n')
        return Triple(normalized, dominant, kinds > 1)
    }
}
