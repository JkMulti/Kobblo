package com.kobblo.gallery.file.explorer.tools.documents

import java.io.ByteArrayInputStream
import java.io.Reader
import java.io.StringReader
import java.nio.charset.Charset

/**
 * A web page turned into plain blocks, drawn by the app rather than by a browser. That's the whole
 * safety argument: nothing here can run a script or fetch anything, because nothing here knows how.
 * A picture is its description, and a link is text that opens only when tapped.
 */
internal object HtmlReader {
    private val BlockEnds = setOf(
        "p", "div", "section", "article", "header", "footer", "nav", "aside", "main", "figure", "figcaption",
        "address", "form", "fieldset", "center", "details", "summary", "dl", "dt", "dd", "body", "html"
    )
    private val SkippedElements = setOf(
        "head", "script", "style", "noscript", "template", "iframe", "object", "embed", "applet", "svg", "math",
        "canvas", "audio", "video", "select", "button", "input", "textarea", "map", "frameset", "frame", "link", "meta", "base"
    )

    /** Their content isn't markup, so a stray `<` inside can't be allowed to open a tag. */
    private val RawElements = setOf("script", "style", "textarea", "xmp", "noembed", "noframes", "plaintext")

    /** These never have an end tag, so they mustn't start skipping. */
    private val VoidElements = setOf("link", "meta", "base", "input", "embed", "frame", "br", "hr", "img", "wbr", "area", "col", "param", "source", "track")

    /** A link goes out only when someone taps it, and only to the kinds of place a link should lead. */
    fun isOpenableLink(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https" || scheme == "mailto"
    }

    fun readBytes(bytes: ByteArray, knownCharset: Charset? = null): ReadableDocument {
        val truncated = bytes.size.toLong() >= DocLimits.MAX_TEXT_FILE_BYTES
        val charset = knownCharset ?: detectCharset(bytes)
        val builder = DocBuilder()
        read(java.io.InputStreamReader(ByteArrayInputStream(bytes), charset), builder)
        if (truncated) builder.truncated = true
        return builder.build()
    }

    fun readText(text: String, builder: DocBuilder = DocBuilder()): ReadableDocument {
        read(StringReader(text), builder)
        return builder.build()
    }

    /** A byte order mark or valid UTF-8 settles it, and only then does what the page declares get a say. */
    fun detectCharset(bytes: ByteArray): Charset {
        val detected = com.kobblo.gallery.file.explorer.tools.editor.TextCodec.detectCharset(bytes)
        if (detected == Charsets.UTF_8 || detected == Charsets.UTF_16LE || detected == Charsets.UTF_16BE) return detected
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = Regex("""charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: detected
    }

    fun read(reader: Reader, builder: DocBuilder) {
        val scan = XmlScan(reader, html = true)
        val text = StringBuilder()
        val spans = ArrayList<InlineSpan>()
        val openSpans = ArrayList<Triple<InlineSpan.Style, Int, String?>>()
        var skip: String? = null
        var skipDepth = 0
        var pre = 0
        var quote = 0
        val listStack = ArrayList<Int>()
        var heading: Int? = null
        var inItem = false

        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var tableDepth = 0

        fun appendInline(value: String) {
            if (tableDepth > 0) {
                if (cell.length < DocLimits.MAX_CELL_CHARS) cell.append(value)
                return
            }
            text.append(value)
        }

        fun flush() {
            val raw = text.toString()
            text.setLength(0)
            val styled = spans.toList()
            spans.clear()
            openSpans.clear()
            val level = heading
            if (raw.isNotBlank()) heading = null
            when {
                raw.isBlank() -> Unit
                pre > 0 -> builder.code(raw)
                level != null -> builder.heading(level, raw)
                inItem -> {
                    val marker = listStack.lastOrNull()?.let { number ->
                        if (number > 0) {
                            listStack[listStack.lastIndex] = number + 1
                            "$number."
                        } else {
                            "•"
                        }
                    } ?: "•"
                    builder.bullet(raw, (listStack.size - 1).coerceAtLeast(0), marker, styled)
                }
                quote > 0 -> builder.quote(raw, styled)
                else -> builder.paragraph(raw, styled)
            }
        }

        while (!builder.isFull) {
            when (scan.next()) {
                XmlScan.Event.EOF -> break
                XmlScan.Event.START -> {
                    val name = scan.name
                    if (name in RawElements) {
                        // A script is passed over whole, whatever's inside it, so it can't fake an end tag.
                        if (!scan.isSelfClosed) scan.skipRawUntilEndOf(name)
                        continue
                    }
                    if (skip != null) {
                        // A page that never closes its head still starts its body.
                        if (name == "body" && skip == "head") {
                            skip = null
                        } else {
                            if (name == skip) skipDepth++
                            continue
                        }
                    }
                    if (name in SkippedElements || scan.attribute("hidden") != null) {
                        if (name !in VoidElements) {
                            skip = name
                            skipDepth = 1
                        }
                        continue
                    }
                    when (name) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            flush()
                            heading = name[1].digitToInt()
                        }
                        in BlockEnds -> {
                            if (tableDepth == 0) flush()
                        }
                        "br" -> if (tableDepth > 0) cell.append(' ') else text.append('\n')
                        "hr" -> if (tableDepth == 0) {
                            flush()
                            builder.rule()
                        }
                        "ul", "menu", "dir" -> {
                            flush()
                            listStack.add(0)
                        }
                        "ol" -> {
                            flush()
                            listStack.add(scan.attribute("start")?.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1)
                        }
                        "li" -> {
                            flush()
                            inItem = true
                        }
                        "blockquote" -> {
                            flush()
                            quote++
                        }
                        "pre", "listing" -> {
                            flush()
                            pre++
                        }
                        "table" -> {
                            if (tableDepth == 0) flush()
                            tableDepth++
                        }
                        "td", "th" -> if (tableDepth == 1) cell.setLength(0)
                        "img" -> {
                            // Never fetched, so its description is all there is to show.
                            val description = scan.attribute("alt")?.trim().orEmpty()
                            if (description.isNotEmpty()) appendInline("[$description]")
                        }
                        "b", "strong" -> openSpans.add(Triple(InlineSpan.Style.BOLD, text.length, null))
                        "i", "em", "cite" -> openSpans.add(Triple(InlineSpan.Style.ITALIC, text.length, null))
                        "code", "tt", "kbd", "samp" -> openSpans.add(Triple(InlineSpan.Style.CODE, text.length, null))
                        "a" -> openSpans.add(Triple(InlineSpan.Style.LINK, text.length, scan.attribute("href")))
                    }
                }
                XmlScan.Event.TEXT -> {
                    if (skip != null) continue
                    val value = scan.text
                    if (pre > 0) {
                        text.append(value)
                    } else {
                        // HTML collapses white space, and a line break in the source means nothing.
                        val collapsed = value.replace(Whitespace, " ")
                        val needsNoLeadingSpace = (if (tableDepth > 0) cell.isEmpty() || cell.last() == ' ' else text.isEmpty() || text.last().isWhitespace())
                        appendInline(if (needsNoLeadingSpace) collapsed.trimStart() else collapsed)
                    }
                    if (builder.wontFit(text.length)) flush()
                }
                XmlScan.Event.END -> {
                    val name = scan.name
                    if (skip != null) {
                        if (name == skip && --skipDepth == 0) skip = null
                        continue
                    }
                    when (name) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            flush()
                            heading = null
                        }
                        in BlockEnds -> if (tableDepth == 0) flush()
                        "ul", "menu", "dir", "ol" -> {
                            flush()
                            listStack.removeLastOrNull()
                            inItem = listStack.isNotEmpty() && inItem
                        }
                        "li" -> {
                            flush()
                            inItem = false
                        }
                        "blockquote" -> {
                            flush()
                            quote = (quote - 1).coerceAtLeast(0)
                        }
                        "pre", "listing" -> {
                            flush()
                            pre = (pre - 1).coerceAtLeast(0)
                        }
                        "td", "th" -> if (tableDepth == 1) {
                            row.add(cell.toString().trim())
                            cell.setLength(0)
                        }
                        "tr" -> if (tableDepth == 1) {
                            if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(row.toList()) else builder.truncated = true
                            row.clear()
                        }
                        "table" -> {
                            tableDepth = (tableDepth - 1).coerceAtLeast(0)
                            if (tableDepth == 0) {
                                if (row.isNotEmpty()) rows.add(row.toList())
                                row.clear()
                                builder.table(rows.toList())
                                rows.clear()
                            }
                        }
                        "b", "strong" -> closeSpan(InlineSpan.Style.BOLD, openSpans, spans, text.length)
                        "i", "em", "cite" -> closeSpan(InlineSpan.Style.ITALIC, openSpans, spans, text.length)
                        "code", "tt", "kbd", "samp" -> closeSpan(InlineSpan.Style.CODE, openSpans, spans, text.length)
                        "a" -> closeSpan(InlineSpan.Style.LINK, openSpans, spans, text.length)
                    }
                }
            }
        }
        flush()
        if (rows.isNotEmpty()) builder.table(rows.toList())
    }

    private fun closeSpan(
        style: InlineSpan.Style,
        open: MutableList<Triple<InlineSpan.Style, Int, String?>>,
        spans: MutableList<InlineSpan>,
        end: Int
    ) {
        val index = open.indexOfLast { it.first == style }
        if (index < 0) return
        val (_, start, url) = open.removeAt(index)
        if (end > start && spans.size < MAX_SPANS) spans.add(InlineSpan(start, end, style, url))
    }

    private const val MAX_SPANS = 2_000
    private val Whitespace = Regex("\\s+")
}

/**
 * Markdown as blocks: the parts people really write. Images are shown as their description, since
 * fetching one would be exactly the remote content a preview must not load.
 */
internal object MarkdownReader {
    private val Heading = Regex("^ {0,3}(#{1,6})\\s+(.*?)(\\s+#+\\s*)?$")
    private val Fence = Regex("^ {0,3}(```+|~~~+)")
    private val Thematic = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val Bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val Ordered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val QuoteLine = Regex("^ {0,3}>\\s?(.*)$")
    private val TableRule = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    /** A pattern can take a very long time on a very long line, and no real heading or bullet is one. */
    private const val MAX_PATTERN_LINE = 4_096

    /** How far a marker looks for its partner, so a line of nothing but `[` stays cheap. */
    private const val CLOSER_WINDOW = 2_000
    private const val MAX_INLINE = 64_000

    fun read(text: String): ReadableDocument {
        val builder = DocBuilder()
        val lines = text.lineSequence().iterator()
        val paragraph = StringBuilder()
        val quote = StringBuilder()
        var lookahead: String? = null

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                val (plain, spans) = inline(paragraph.toString())
                builder.paragraph(plain, spans)
                paragraph.setLength(0)
            }
        }

        fun flushQuote() {
            if (quote.isNotEmpty()) {
                val (plain, spans) = inline(quote.toString().trim())
                builder.quote(plain, spans)
                quote.setLength(0)
            }
        }

        fun nextLine(): String? {
            val held = lookahead
            if (held != null) {
                lookahead = null
                return held
            }
            return if (lines.hasNext()) lines.next() else null
        }

        while (!builder.isFull) {
            val line = nextLine() ?: break
            if (line.length > MAX_PATTERN_LINE) {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                if (paragraph.length < DocLimits.MAX_TEXT_CHARS) paragraph.append(line.trim())
                continue
            }
            val fence = Fence.find(line)
            if (fence != null) {
                flushParagraph()
                flushQuote()
                val marker = fence.groupValues[1]
                val body = StringBuilder()
                while (true) {
                    val code = nextLine() ?: break
                    if (code.trimStart().startsWith(marker)) break
                    if (body.length < DocLimits.MAX_TEXT_CHARS) body.append(code).append('\n')
                }
                builder.code(body.toString())
                continue
            }
            if (line.isBlank()) {
                flushParagraph()
                flushQuote()
                continue
            }
            val quoted = QuoteLine.find(line)
            if (quoted != null) {
                flushParagraph()
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(quoted.groupValues[1])
                continue
            }
            flushQuote()
            val heading = Heading.find(line)
            if (heading != null) {
                flushParagraph()
                val (plain, _) = inline(heading.groupValues[2])
                builder.heading(heading.groupValues[1].length, plain)
                continue
            }
            if (Thematic.matches(line) && paragraph.isEmpty()) {
                builder.rule()
                continue
            }
            // A line of = or - under text is an old way to write a heading.
            if (paragraph.isNotEmpty() && line.isNotEmpty() && (line.all { it == '=' } || line.all { it == '-' })) {
                val (plain, _) = inline(paragraph.toString())
                paragraph.setLength(0)
                builder.heading(if (line[0] == '=') 1 else 2, plain)
                continue
            }
            val bullet = Bullet.find(line)
            if (bullet != null) {
                flushParagraph()
                val (plain, spans) = inline(bullet.groupValues[2])
                builder.bullet(plain, bullet.groupValues[1].length / 2, spans = spans)
                continue
            }
            val ordered = Ordered.find(line)
            if (ordered != null) {
                flushParagraph()
                val (plain, spans) = inline(ordered.groupValues[3])
                builder.bullet(plain, ordered.groupValues[1].length / 3, "${ordered.groupValues[2]}.", spans)
                continue
            }
            if ('|' in line) {
                val following = nextLine()
                if (following != null && following.length <= MAX_PATTERN_LINE && TableRule.matches(following) &&
                    tableCells(following).size == tableCells(line).size
                ) {
                    flushParagraph()
                    val rows = ArrayList<List<String>>()
                    rows.add(tableCells(line))
                    while (rows.size <= DocLimits.MAX_TABLE_ROWS) {
                        val row = nextLine() ?: break
                        if (row.isBlank() || '|' !in row) {
                            lookahead = row
                            break
                        }
                        rows.add(tableCells(row))
                    }
                    builder.table(rows)
                    continue
                }
                lookahead = following
            }
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            if (paragraph.length < DocLimits.MAX_TEXT_CHARS) paragraph.append(line.trim())
        }
        flushParagraph()
        flushQuote()
        if (lines.hasNext()) builder.truncated = true
        return builder.build()
    }

    private fun tableCells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()).first }

    /** Bold, italics, code, links and images. Anything left unmatched stays as the characters it is. */
    fun inline(source: String): Pair<String, List<InlineSpan>> {
        if (source.length > MAX_INLINE) return source to emptyList()
        val out = StringBuilder(source.length)
        val spans = ArrayList<InlineSpan>()
        var at = 0
        while (at < source.length) {
            val c = source[at]
            when {
                c == '\\' && at + 1 < source.length && !source[at + 1].isLetterOrDigit() -> {
                    out.append(source[at + 1])
                    at += 2
                }
                c == '`' -> {
                    val end = near(source, "`", at + 1)
                    if (end < 0) {
                        out.append(c)
                        at++
                    } else {
                        val start = out.length
                        out.append(source, at + 1, end)
                        spans.add(InlineSpan(start, out.length, InlineSpan.Style.CODE))
                        at = end + 1
                    }
                }
                c == '!' && at + 1 < source.length && source[at + 1] == '[' -> {
                    val close = near(source, "]", at + 2)
                    val urlEnd = if (close > 0 && close + 1 < source.length && source[close + 1] == '(') near(source, ")", close + 2) else -1
                    if (urlEnd > 0) {
                        val description = source.substring(at + 2, close)
                        if (description.isNotBlank()) out.append('[').append(description).append(']')
                        at = urlEnd + 1
                    } else {
                        out.append(c)
                        at++
                    }
                }
                c == '[' -> {
                    val close = near(source, "]", at + 1)
                    val urlEnd = if (close > 0 && close + 1 < source.length && source[close + 1] == '(') near(source, ")", close + 2) else -1
                    if (urlEnd > 0) {
                        val (label, inner) = inline(source.substring(at + 1, close))
                        val start = out.length
                        out.append(label)
                        inner.forEach { spans.add(it.copy(start = it.start + start, end = it.end + start)) }
                        val target = source.substring(close + 2, urlEnd).trim().substringBefore(' ')
                        spans.add(InlineSpan(start, out.length, InlineSpan.Style.LINK, target))
                        at = urlEnd + 1
                    } else {
                        out.append(c)
                        at++
                    }
                }
                (c == '*' || c == '_') -> {
                    val double = at + 1 < source.length && source[at + 1] == c
                    val marker = if (double) "$c$c" else c.toString()
                    val opensWord = at + marker.length < source.length && !source[at + marker.length].isWhitespace()
                    // Inside a word, an underscore is just a character: snake_case stays as it is.
                    val midWord = c == '_' && at > 0 && source[at - 1].isLetterOrDigit()
                    val end = if (opensWord && !midWord) near(source, marker, at + marker.length) else -1
                    if (end > at + marker.length - 1 && end > 0) {
                        val (inner, nested) = inline(source.substring(at + marker.length, end))
                        val start = out.length
                        out.append(inner)
                        nested.forEach { spans.add(it.copy(start = it.start + start, end = it.end + start)) }
                        spans.add(InlineSpan(start, out.length, if (double) InlineSpan.Style.BOLD else InlineSpan.Style.ITALIC))
                        at = end + marker.length
                    } else {
                        out.append(c)
                        at++
                    }
                }
                else -> {
                    out.append(c)
                    at++
                }
            }
        }
        return out.toString() to spans
    }

    private fun near(source: String, needle: String, from: Int): Int {
        val last = minOf(source.length - needle.length, from + CLOSER_WINDOW)
        var at = from
        while (at <= last) {
            if (source.startsWith(needle, at)) return at
            at++
        }
        return -1
    }
}
