package com.kobblo.gallery.file.explorer.tools.documents

import java.nio.charset.Charset

/**
 * Rich Text Format as plain paragraphs. It keeps groups on an explicit, capped stack, because a file
 * can open a million braces and never close them.
 */
internal object RtfReader {
    private const val MAX_DEPTH = 256

    /** Groups that hold settings or objects rather than words. */
    private val SkippedDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "themedata", "datastore", "latentstyles",
        "listtable", "listoverridetable", "rsidtbl", "generator", "header", "headerl", "headerr", "headerf",
        "footer", "footerl", "footerr", "footerf", "footnote", "annotation", "fldinst", "xmlnstbl", "mmathPr",
        "expandedcolortbl", "listtext", "pntext", "wgrffmtfilter", "filetbl", "revtbl", "shpinst", "sp", "sn"
    )

    private class Group(val skip: Boolean, val unicodeSkip: Int)

    fun read(bytes: ByteArray): ReadableDocument {
        if (!looksLikeRtf(bytes)) throw UnreadableDocumentException("not RTF")
        val builder = DocBuilder()
        val line = StringBuilder()
        val pendingBytes = java.io.ByteArrayOutputStream()
        val rows = ArrayList<List<String>>()
        val cells = ArrayList<String>()
        var charset: Charset = Charset.forName("windows-1252")
        val stack = ArrayList<Group>()
        var skip = false
        var unicodeSkip = 1
        var skipChars = 0
        var depthOverflow = 0
        var at = 0

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                line.append(String(pendingBytes.toByteArray(), charset))
                pendingBytes.reset()
            }
        }

        fun flushTable() {
            if (rows.isNotEmpty()) builder.table(rows.toList())
            rows.clear()
        }

        fun endParagraph() {
            flushBytes()
            flushTable()
            builder.paragraph(line.toString())
            line.setLength(0)
        }

        while (at < bytes.size && !builder.isFull) {
            val c = bytes[at].toInt() and 0xFF
            when (c) {
                '{'.code -> {
                    at++
                    flushBytes()
                    if (stack.size >= MAX_DEPTH) {
                        depthOverflow++
                    } else {
                        stack.add(Group(skip, unicodeSkip))
                    }
                }
                '}'.code -> {
                    at++
                    flushBytes()
                    if (depthOverflow > 0) {
                        depthOverflow--
                    } else if (stack.isNotEmpty()) {
                        val group = stack.removeAt(stack.lastIndex)
                        skip = group.skip
                        unicodeSkip = group.unicodeSkip
                    }
                }
                '\\'.code -> {
                    at++
                    if (at >= bytes.size) break
                    val next = bytes[at].toInt() and 0xFF
                    if (next == '\''.code) {
                        // A byte in the document's code page. Consecutive ones may be one character.
                        if (at + 2 >= bytes.size) break
                        val high = Character.digit(bytes[at + 1].toInt(), 16)
                        val low = Character.digit(bytes[at + 2].toInt(), 16)
                        at += 3
                        if (skipChars > 0) {
                            skipChars--
                        } else if (!skip && high >= 0 && low >= 0) {
                            pendingBytes.write(high * 16 + low)
                        }
                        continue
                    }
                    if (!isLetter(next)) {
                        at++
                        if (skip) continue
                        when (next) {
                            '\\'.code, '{'.code, '}'.code -> {
                                flushBytes()
                                if (skipChars > 0) skipChars-- else line.append(next.toChar())
                            }
                            '~'.code -> line.append(' ')
                            '_'.code -> line.append('-')
                            '-'.code -> Unit
                            '*'.code -> skip = true
                            '\n'.code, '\r'.code -> endParagraph()
                        }
                        continue
                    }
                    // A control word: letters, an optional number, and one space that belongs to it.
                    val wordStart = at
                    while (at < bytes.size && isLetter(bytes[at].toInt() and 0xFF)) at++
                    val word = String(bytes, wordStart, (at - wordStart).coerceAtMost(40), Charsets.US_ASCII)
                    val numberStart = at
                    if (at < bytes.size && bytes[at].toInt() == '-'.code) at++
                    while (at < bytes.size && (bytes[at].toInt() and 0xFF) in '0'.code..'9'.code && at - numberStart < 12) at++
                    val number = String(bytes, numberStart, at - numberStart, Charsets.US_ASCII).toIntOrNull()
                    if (at < bytes.size && bytes[at].toInt() == ' '.code) at++
                    if (word == "bin") {
                        // Raw binary follows, and it could hold anything, braces included.
                        val length = (number ?: 0).coerceAtLeast(0)
                        at = (at.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
                        continue
                    }
                    if (word in SkippedDestinations) {
                        skip = true
                        continue
                    }
                    if (word == "ansicpg" && number != null) {
                        charset = charsetFor(number) ?: charset
                        continue
                    }
                    if (word == "uc" && number != null) {
                        unicodeSkip = number.coerceIn(0, 8)
                        continue
                    }
                    if (skip) continue
                    when (word) {
                        "u" -> if (number != null) {
                            flushBytes()
                            val code = if (number < 0) number + 65536 else number
                            if (code in 1..0xFFFF) line.append(code.toChar())
                            skipChars = unicodeSkip
                        }
                        "par", "sect", "page" -> endParagraph()
                        "line" -> {
                            flushBytes()
                            line.append('\n')
                        }
                        "tab" -> {
                            flushBytes()
                            line.append('\t')
                        }
                        "cell" -> {
                            flushBytes()
                            cells.add(line.toString().trim())
                            line.setLength(0)
                        }
                        "row" -> {
                            flushBytes()
                            if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(cells.toList()) else builder.truncated = true
                            cells.clear()
                            line.setLength(0)
                        }
                        "emdash" -> line.append('—')
                        "endash" -> line.append('–')
                        "bullet" -> line.append('•')
                        "lquote" -> line.append('‘')
                        "rquote" -> line.append('’')
                        "ldblquote" -> line.append('“')
                        "rdblquote" -> line.append('”')
                    }
                }
                '\n'.code, '\r'.code -> at++
                else -> {
                    at++
                    if (skip) continue
                    if (skipChars > 0) {
                        // The stand-in for a character already given in Unicode.
                        skipChars--
                    } else {
                        flushBytes()
                        pendingBytes.write(c)
                    }
                }
            }
        }
        flushBytes()
        flushTable()
        builder.paragraph(line.toString())
        if (at < bytes.size) builder.truncated = true
        return builder.build()
    }

    fun looksLikeRtf(bytes: ByteArray): Boolean {
        val mark = "{\\rtf"
        if (bytes.size < mark.length) return false
        return mark.indices.all { bytes[it].toInt() == mark[it].code }
    }

    private fun isLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    /** Code pages by the names every platform knows them by: the East Asian ones vary as `windows-`. */
    private fun charsetFor(page: Int): Charset? = try {
        when (page) {
            65001 -> Charsets.UTF_8
            932 -> Charset.forName("Shift_JIS")
            936 -> Charset.forName("GBK")
            949 -> Charset.forName("EUC-KR")
            950 -> Charset.forName("Big5")
            else -> Charset.forName("windows-$page")
        }
    } catch (_: Exception) {
        null
    }
}
