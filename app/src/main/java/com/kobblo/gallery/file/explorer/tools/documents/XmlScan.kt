package com.kobblo.gallery.file.explorer.tools.documents

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

/**
 * A pull scanner for the XML inside documents, and for HTML, which it treats as tag soup.
 *
 * The platform's parsers would do for well-made files, but every file here is treated as hostile, and
 * what makes XML dangerous is what this leaves out. It has no DTD processing, so a `<!DOCTYPE>` is
 * skipped unread: an external entity can't reach out of the file, and entities can't multiply. It keeps
 * no stack, so nesting costs nothing however deep. And a run of text longer than [MAX_TEXT_RUN] comes
 * out in pieces, so the caller's own limit always gets a chance to stop it.
 */
internal class XmlScan(private val reader: Reader, private val html: Boolean = false) {
    enum class Event { START, END, TEXT, EOF }

    /** The tag's name without its namespace prefix, such as `p` for `w:p`. */
    var name: String = ""
        private set

    /** The tag's name as written, including its prefix. */
    var fullName: String = ""
        private set

    /** The text of a [Event.TEXT], with entities decoded. */
    var text: String = ""
        private set

    private val attributes = ArrayList<Pair<String, String>>()
    private val buffer = CharArray(8192)
    private var position = 0
    private var filled = 0
    private var pendingEnd = false
    private var finished = false
    private var inCdata = false
    private var started = false

    /** The current start tag closed itself, as in `<br/>`, so nothing raw can follow it. */
    val isSelfClosed: Boolean get() = pendingEnd

    /** The value of an attribute of the current start tag, matched by its local name or full name. */
    fun attribute(attribute: String): String? {
        for ((key, value) in attributes) {
            if (key == attribute || key.substringAfterLast(':') == attribute) return value
        }
        return null
    }

    fun next(): Event {
        if (pendingEnd) {
            pendingEnd = false
            return Event.END
        }
        if (finished) return Event.EOF
        // A byte order mark decodes as a character, and it isn't part of the text.
        if (!started) {
            started = true
            if (peek() == 0xFEFF) position++
        }
        val run = StringBuilder()
        if (inCdata) return readCdata(run)
        while (true) {
            val c = peek()
            if (c < 0) {
                finished = true
                return if (run.isNotEmpty()) emitText(run) else Event.EOF
            }
            if (c != '<'.code) {
                // Everything up to the next tag goes in one copy.
                var end = position
                val stop = minOf(filled, position + (MAX_TEXT_RUN - run.length))
                while (end < stop && buffer[end] != '<') end++
                run.append(buffer, position, end - position)
                position = end
                if (run.length >= MAX_TEXT_RUN) return emitText(run)
                continue
            }
            // Only a real tag ends the text. A stray "<" in a web page is just a character.
            val after = peekAhead()
            val opensTag = after == '/'.code || after == '!'.code || after == '?'.code || isNameStart(after)
            if (!opensTag) {
                run.append('<')
                position++
                continue
            }
            if (run.isNotEmpty()) return emitText(run)
            position++
            when (after) {
                '!'.code -> readDeclaration()?.let { return it }
                '?'.code -> skipUntil("?>")
                '/'.code -> {
                    position++
                    readName()
                    skipUntil(">")
                    return Event.END
                }
                else -> {
                    readName()
                    readAttributes()
                    return Event.START
                }
            }
        }
    }

    /**
     * Skips the raw body of an element whose content isn't markup, such as a `<script>`. Nothing in it
     * is looked at beyond finding where it ends.
     */
    fun skipRawUntilEndOf(tag: String) {
        val close = "</$tag"
        var matched = 0
        while (true) {
            val c = peek()
            if (c < 0) return
            position++
            val lower = Character.toLowerCase(c.toChar())
            matched = when {
                lower == close[matched] -> matched + 1
                lower == close[0] -> 1
                else -> 0
            }
            if (matched == close.length) {
                skipUntil(">")
                return
            }
        }
    }

    private fun emitText(run: StringBuilder): Event {
        text = decodeEntities(run, html)
        return Event.TEXT
    }

    /** CDATA becomes text. A comment or a DOCTYPE is passed over without reading what's in it. */
    private fun readDeclaration(): Event? {
        position++
        if (lookingAt("[CDATA[")) {
            position += "[CDATA[".length
            inCdata = true
            return readCdata(StringBuilder())
        }
        if (lookingAt("--")) {
            position += 2
            skipUntil("-->")
            return null
        }
        skipDeclarationBody()
        return null
    }

    /** CDATA is literal, so it comes out undecoded, and a long stretch of it in pieces like any text. */
    private fun readCdata(run: StringBuilder): Event {
        while (run.length < MAX_TEXT_RUN) {
            val c = peek()
            if (c < 0) {
                finished = true
                inCdata = false
                break
            }
            if (c == ']'.code && lookingAt("]]>")) {
                position += 3
                inCdata = false
                break
            }
            run.append(c.toChar())
            position++
        }
        text = run.toString()
        return Event.TEXT
    }

    /** A DOCTYPE may hold `<!ENTITY ...>` lines in brackets, and its end is the `>` outside of them. */
    private fun skipDeclarationBody() {
        var depth = 0
        var quote = 0
        while (true) {
            val c = peek()
            if (c < 0) return
            position++
            if (quote != 0) {
                if (c == quote) quote = 0
                continue
            }
            when (c) {
                '"'.code, '\''.code -> quote = c
                '['.code, '<'.code -> depth++
                ']'.code -> depth = (depth - 1).coerceAtLeast(0)
                '>'.code -> if (depth == 0) return else depth--
            }
        }
    }

    private fun readName() {
        val out = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || isSpace(c) || c == '>'.code || c == '/'.code) break
            if (out.length < MAX_NAME) out.append(c.toChar())
            position++
        }
        val written = out.toString()
        fullName = if (html) written.lowercase() else written
        name = fullName.substringAfterLast(':')
    }

    private fun readAttributes() {
        attributes.clear()
        val key = StringBuilder()
        val value = StringBuilder()
        var total = 0
        while (true) {
            skipSpace()
            val c = peek()
            if (c < 0) {
                finished = true
                return
            }
            if (c == '>'.code) {
                position++
                return
            }
            if (c == '/'.code) {
                position++
                if (peek() == '>'.code) {
                    position++
                    pendingEnd = true
                    return
                }
                continue
            }
            key.setLength(0)
            while (true) {
                val k = peek()
                if (k < 0 || isSpace(k) || k == '='.code || k == '>'.code || k == '/'.code) break
                if (key.length < MAX_NAME) key.append(k.toChar())
                position++
            }
            if (key.isEmpty()) {
                // Something like a stray quote. Stepping over it keeps this moving.
                position++
                continue
            }
            skipSpace()
            value.setLength(0)
            if (peek() == '='.code) {
                position++
                skipSpace()
                val open = peek()
                if (open == '"'.code || open == '\''.code) {
                    position++
                    while (true) {
                        val v = peek()
                        if (v < 0 || v == open) break
                        if (value.length < MAX_ATTRIBUTE) value.append(v.toChar())
                        position++
                    }
                    if (peek() == open) position++
                } else {
                    while (true) {
                        val v = peek()
                        if (v < 0 || isSpace(v) || v == '>'.code) break
                        if (value.length < MAX_ATTRIBUTE) value.append(v.toChar())
                        position++
                    }
                }
            }
            total++
            if (total <= MAX_ATTRIBUTES) {
                val attributeName = key.toString().let { if (html) it.lowercase() else it }
                attributes.add(attributeName to decodeEntities(value, html))
            }
        }
    }

    private fun skipUntil(end: String) {
        val first = end[0]
        while (true) {
            val c = peek()
            if (c < 0) {
                finished = true
                return
            }
            if (c == first.code && lookingAt(end)) {
                position += end.length
                return
            }
            position++
            while (position < filled && buffer[position] != first) position++
        }
    }

    private fun skipSpace() {
        while (true) {
            val c = peek()
            if (c < 0 || !isSpace(c)) return
            position++
        }
    }

    private fun peek(): Int {
        if (position >= filled && !refill(0)) return -1
        return buffer[position].code
    }

    private fun peekAhead(): Int {
        if (position + 1 >= filled && !refill(1)) return -1
        return buffer[position + 1].code
    }

    private fun lookingAt(text: String): Boolean {
        if (position + text.length > filled && !refill(text.length - 1)) return false
        if (position + text.length > filled) return false
        for (i in text.indices) {
            if (buffer[position + i] != text[i]) return false
        }
        return true
    }

    /** Keeps the unread [keep] characters and reads more behind them. False means nothing more came. */
    private fun refill(keep: Int): Boolean {
        val unread = (filled - position).coerceAtLeast(0)
        if (unread > 0) System.arraycopy(buffer, position, buffer, 0, unread)
        position = 0
        filled = unread
        // A reader may hand back a single character at a time, so one read isn't always enough.
        while (filled <= keep) {
            val read = reader.read(buffer, filled, buffer.size - filled)
            if (read <= 0) return false
            filled += read
        }
        return true
    }

    private fun isSpace(c: Int) = c == ' '.code || c == '\n'.code || c == '\t'.code || c == '\r'.code || c == 0x0C

    private fun isNameStart(c: Int) = c >= 0 && (Character.isLetter(c) || c == '_'.code || c == ':'.code)

    companion object {
        const val MAX_TEXT_RUN = 16 * 1024
        private const val MAX_NAME = 256
        private const val MAX_ATTRIBUTE = 4096
        private const val MAX_ATTRIBUTES = 64

        /** Decodes the five XML entities and numeric ones. A web page also gets the common named ones. */
        fun decodeEntities(raw: CharSequence, html: Boolean = false): String {
            if (raw.indexOf('&', 0, raw.length) < 0) return raw.toString()
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '&') {
                    out.append(c)
                    i++
                    continue
                }
                val end = raw.indexOf(';', i + 1, 12)
                val replaced = if (end > 0) entity(raw.substring(i + 1, end), html) else null
                if (replaced == null) {
                    out.append('&')
                    i++
                } else {
                    out.append(replaced)
                    i = end + 1
                }
            }
            return out.toString()
        }

        private fun CharSequence.indexOf(char: Char, from: Int, within: Int): Int {
            val last = minOf(length, from + within)
            for (i in from until last) if (this[i] == char) return i
            return -1
        }

        private fun entity(name: String, html: Boolean): String? {
            when (name) {
                "amp" -> return "&"
                "lt" -> return "<"
                "gt" -> return ">"
                "quot" -> return "\""
                "apos" -> return "'"
            }
            if (name.startsWith("#")) {
                val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                    name.substring(2).toIntOrNull(16)
                } else {
                    name.substring(1).toIntOrNull()
                } ?: return null
                // A NUL or a lone surrogate would only come out as a square box.
                if (code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return null
                return String(Character.toChars(code))
            }
            return if (html) HtmlEntities[name] else null
        }

        private val HtmlEntities = mapOf(
            "nbsp" to " ", "shy" to "", "ensp" to " ", "emsp" to " ", "thinsp" to " ", "zwnj" to "", "zwj" to "",
            "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
            "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
            "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
            "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
            "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
            "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã", "auml" to "ä", "aring" to "å", "aelig" to "æ",
            "ccedil" to "ç", "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
            "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ",
            "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "oslash" to "ø",
            "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "szlig" to "ß", "yacute" to "ý", "yuml" to "ÿ",
            "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã", "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ",
            "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
            "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï", "Ntilde" to "Ñ",
            "Ograve" to "Ò", "Oacute" to "Ó", "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Oslash" to "Ø",
            "Ugrave" to "Ù", "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "Yacute" to "Ý"
        )

        /**
         * A reader for an XML part, honoring its byte order mark and declared encoding. It reads only
         * what it has to, so the caller's limits on the stream keep working.
         */
        fun readerFor(input: InputStream): Reader {
            val stream = java.io.BufferedInputStream(input, 512)
            stream.mark(256)
            val head = ByteArray(200)
            var got = 0
            while (got < head.size) {
                val n = stream.read(head, got, head.size - got)
                if (n <= 0) break
                got += n
            }
            stream.reset()
            return InputStreamReader(stream, charsetOf(head, got))
        }

        private fun charsetOf(head: ByteArray, size: Int): Charset {
            if (size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
            if (size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return Charsets.UTF_8
            if (size >= 4 && head[0] == '<'.code.toByte() && head[1] == 0.toByte()) return Charsets.UTF_16LE
            if (size >= 4 && head[0] == 0.toByte() && head[1] == '<'.code.toByte()) return Charsets.UTF_16BE
            val prolog = String(head, 0, size, Charsets.ISO_8859_1)
            val declared = Regex("""encoding\s*=\s*["']([A-Za-z0-9_.:-]+)["']""").find(prolog.substringBefore("?>"))?.groupValues?.get(1)
            return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        }
    }
}
