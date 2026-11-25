package com.kobblo.gallery.file.explorer.tools.documents

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** What a preview can hold, whatever a file claims to contain. A file past these is cut short and says so. */
internal object DocLimits {
    /** The most read out of one part of a container, so a zip bomb stops where any real file would. */
    const val MAX_PART_BYTES = 32L * 1024 * 1024

    const val MAX_TEXT_CHARS = 2_000_000
    const val MAX_BLOCKS = 40_000
    const val MAX_SECTIONS = 2_000

    /** A long paragraph is laid out in pieces, so a page with no line breaks still scrolls. */
    const val MAX_PARAGRAPH_CHARS = 4_000

    const val MAX_TABLE_ROWS = 200
    const val MAX_TABLE_COLUMNS = 32
    const val MAX_CELL_CHARS = 1_000

    /** Plain text formats, read whole before they are parsed. */
    const val MAX_TEXT_FILE_BYTES = 16L * 1024 * 1024
}

/** Styling inside a run of text, by character range. Plain data, so the parsers need no UI. */
internal data class InlineSpan(val start: Int, val end: Int, val style: Style, val url: String? = null) {
    enum class Style { BOLD, ITALIC, CODE, LINK }
}

internal sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock
    data class Paragraph(val text: String, val spans: List<InlineSpan> = emptyList()) : DocBlock
    data class Bullet(val text: String, val depth: Int = 0, val marker: String = "•", val spans: List<InlineSpan> = emptyList()) : DocBlock
    data class Quote(val text: String, val spans: List<InlineSpan> = emptyList()) : DocBlock
    data class Code(val text: String) : DocBlock

    /**
     * Rows of plain text. [more] is above zero if rows were left out for being past the limit, though
     * not how many, since a reader stops collecting them there. [hasHeader] is false for a list of labels
     * and values, where the first row is no more special than the next.
     */
    data class Table(val rows: List<List<String>>, val more: Int = 0, val hasHeader: Boolean = true) : DocBlock
    data object Rule : DocBlock
}

/** A part of a document with its own number: a slide, for one. A plain document has a single, unnumbered one. */
internal data class DocSection(val slide: Int?, val blocks: List<DocBlock>)

/** The words a reader adds of its own, passed in so the parsers stay free of resources. */
internal class DocLabels(
    val size: String = "Size",
    val files: String = "Files",
    val comment: String = "Comment",
    val createdBy: String = "Created by",
    val created: String = "Created",
    val trackers: String = "Trackers",
    val from: String = "From",
    val to: String = "To",
    val copied: String = "Cc",
    val date: String = "Date",
    val attachments: String = "Attachments",
    val unnamed: String = "Unnamed",
    val value: String = "Value",
    /** Stands in for a value that is raw bytes. */
    val binaryData: String = "Data"
)

internal data class ReadableDocument(
    val sections: List<DocSection>,
    /** Parts of the file were left out for being past the limits. */
    val truncated: Boolean = false,
    /** Slides and the like, which read better as separate cards than as one flowing page. */
    val paged: Boolean = false
) {
    val isEmpty: Boolean get() = sections.all { it.blocks.isEmpty() }
}

/** The file is locked: reading it would need a password this app doesn't ask for. */
internal class ProtectedDocumentException : Exception()

/** The file isn't what it claims, or is too damaged to read anything from. */
internal class UnreadableDocumentException(message: String) : Exception(message)

/**
 * Collects blocks while holding to [DocLimits]. A reader keeps going until [isFull] says there's no
 * point, so a file's size never decides how much memory it costs.
 */
internal class DocBuilder {
    private val sections = ArrayList<DocSection>()
    private var current = ArrayList<DocBlock>()
    private var currentSlide: Int? = null
    private var chars = 0
    private var blocks = 0

    var truncated = false

    val isFull: Boolean get() = chars >= DocLimits.MAX_TEXT_CHARS || blocks >= DocLimits.MAX_BLOCKS || sections.size >= DocLimits.MAX_SECTIONS

    /**
     * True once a paragraph still being read can't fit anyway. Without this a single run of text, which
     * is exactly what a bomb is, would be read all the way to the end of the file before anything stopped it.
     */
    fun wontFit(length: Int): Boolean = length >= DocLimits.MAX_TEXT_CHARS - chars

    /** Starts a new slide. Whatever came before stays in the one it was added to. */
    fun slide(number: Int) {
        finishSection()
        currentSlide = number
    }

    fun heading(level: Int, text: String) {
        val clean = squeeze(text)
        if (clean.isNotEmpty()) add(DocBlock.Heading(level.coerceIn(1, 6), clean), clean.length)
    }

    fun paragraph(text: String, spans: List<InlineSpan> = emptyList()) {
        val clean = text.trim('\n', ' ', '\t', '\r')
        if (clean.isEmpty()) return
        val lead = text.indexOf(clean)
        pieces(clean).forEach { (start, end) ->
            add(DocBlock.Paragraph(clean.substring(start, end), slice(spans, lead + start, lead + end)), end - start)
        }
    }

    fun bullet(text: String, depth: Int = 0, marker: String = "•", spans: List<InlineSpan> = emptyList()) {
        val clean = text.trim('\n', ' ', '\t', '\r')
        if (clean.isEmpty()) return
        val lead = text.indexOf(clean)
        val depthShown = depth.coerceIn(0, 6)
        pieces(clean).forEachIndexed { index, (start, end) ->
            add(DocBlock.Bullet(clean.substring(start, end), depthShown, if (index == 0) marker else "", slice(spans, lead + start, lead + end)), end - start)
        }
    }

    fun quote(text: String, spans: List<InlineSpan> = emptyList()) {
        val clean = text.trim('\n', ' ', '\t', '\r')
        if (clean.isEmpty()) return
        val lead = text.indexOf(clean)
        pieces(clean).forEach { (start, end) ->
            add(DocBlock.Quote(clean.substring(start, end), slice(spans, lead + start, lead + end)), end - start)
        }
    }

    fun code(text: String) {
        val clean = text.trimEnd('\n', '\r')
        if (clean.isEmpty()) return
        pieces(clean).forEach { (start, end) -> add(DocBlock.Code(clean.substring(start, end)), end - start) }
    }

    fun rule() = add(DocBlock.Rule, 1)

    fun table(rows: List<List<String>>, hasHeader: Boolean = true) {
        val kept = rows.filter { row -> row.any { it.isNotBlank() } }
        if (kept.isEmpty()) return
        val shown = kept.take(DocLimits.MAX_TABLE_ROWS).map { row ->
            row.take(DocLimits.MAX_TABLE_COLUMNS).map { it.trim().take(DocLimits.MAX_CELL_CHARS) }
        }
        if (kept.size > shown.size) truncated = true
        add(DocBlock.Table(shown, more = kept.size - shown.size, hasHeader = hasHeader), shown.sumOf { row -> row.sumOf { it.length } })
    }

    fun build(paged: Boolean = false): ReadableDocument {
        finishSection()
        return ReadableDocument(sections, truncated || isFull, paged)
    }

    private fun add(block: DocBlock, length: Int) {
        if (isFull) {
            truncated = true
            return
        }
        current.add(block)
        chars += length
        blocks++
    }

    private fun finishSection() {
        if (current.isNotEmpty() || currentSlide != null) {
            if (sections.size >= DocLimits.MAX_SECTIONS) truncated = true else sections.add(DocSection(currentSlide, current))
        }
        current = ArrayList()
        currentSlide = null
    }

    private fun squeeze(text: String) = text.replace(Whitespace, " ").trim()

    /**
     * Ranges of [text] no longer than a block should be, cut at a space where there is one. It stops at
     * what's left of the limit, and looks for that space only within the piece: searching a long run
     * of text with no spaces from each piece's end back to its start took minutes on a phone.
     */
    private fun pieces(text: String): List<Pair<Int, Int>> {
        if (text.length <= DocLimits.MAX_PARAGRAPH_CHARS) return listOf(0 to text.length)
        val out = ArrayList<Pair<Int, Int>>()
        val last = minOf(text.length.toLong(), (DocLimits.MAX_TEXT_CHARS - chars).coerceAtLeast(0).toLong()).toInt()
        if (last < text.length) truncated = true
        var start = 0
        while (start < last) {
            var end = (start + DocLimits.MAX_PARAGRAPH_CHARS).coerceAtMost(last)
            if (end < text.length) {
                var space = end
                val lowest = start + DocLimits.MAX_PARAGRAPH_CHARS / 2
                while (space > lowest && text[space] != ' ') space--
                if (space > lowest) end = space + 1
            }
            out.add(start to end)
            start = end
        }
        return out
    }

    private fun slice(spans: List<InlineSpan>, from: Int, to: Int): List<InlineSpan> {
        if (spans.isEmpty()) return spans
        return spans.mapNotNull { span ->
            val start = maxOf(span.start, from)
            val end = minOf(span.end, to)
            if (start < end) span.copy(start = start - from, end = end - from) else null
        }
    }

    private companion object {
        val Whitespace = Regex("\\s+")
    }
}

/** A stream that ends at [limit] whatever it holds, and remembers that it had to. */
internal class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
    var count = 0L
        private set
    var hitLimit = false
        private set

    override fun read(): Int {
        checkInterrupted()
        if (count >= limit) {
            hitLimit = true
            return -1
        }
        val value = input.read()
        if (value >= 0) count++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        checkInterrupted()
        if (count >= limit) {
            hitLimit = true
            return -1
        }
        val read = input.read(b, off, minOf(len.toLong(), limit - count).toInt())
        if (read > 0) count += read
        return read
    }

    override fun close() = input.close()

    /** Everything a preview reads comes through here, so this is what stops it once nobody's waiting. */
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
    }
}

/**
 * A ZIP read as a hostile file. Nothing is trusted from its directory: a part's declared size is
 * ignored, and every part is read through a limit on what actually comes out.
 */
internal class SafeZip(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val byLowerName: Map<String, ZipEntry> by lazy {
        val map = HashMap<String, ZipEntry>()
        val entries = zip.entries()
        var seen = 0
        while (entries.hasMoreElements() && seen < MAX_ENTRIES) {
            val entry = entries.nextElement()
            map.putIfAbsent(entry.name.lowercase(), entry)
            seen++
        }
        map
    }

    /** Set once any part ran past its limit, so the reader can say the result was cut short. */
    var hitLimit = false
        private set

    fun has(name: String): Boolean = entry(name) != null

    /** Every part's name, capped at a number no real document reaches. */
    fun names(): List<String> = byLowerName.values.map { it.name }

    fun open(name: String, maxBytes: Long = DocLimits.MAX_PART_BYTES): LimitedInputStream? {
        val entry = entry(name) ?: return null
        if (entry.isDirectory) return null
        return LimitedInputStream(zip.getInputStream(entry), maxBytes)
    }

    /** Runs [block] over a part's XML. Null means the part isn't there. */
    fun <T> scan(name: String, html: Boolean = false, maxBytes: Long = DocLimits.MAX_PART_BYTES, block: (XmlScan) -> T): T? {
        val stream = open(name, maxBytes) ?: return null
        return stream.use { limited ->
            val result = block(XmlScan(XmlScan.readerFor(limited), html))
            if (limited.hitLimit) hitLimit = true
            result
        }
    }

    fun readBytes(name: String, maxBytes: Long): ByteArray? {
        val stream = open(name, maxBytes) ?: return null
        return stream.use { limited ->
            val bytes = limited.readBytes()
            if (limited.hitLimit) hitLimit = true
            bytes
        }
    }

    override fun close() = zip.close()

    private fun entry(name: String): ZipEntry? = zip.getEntry(name) ?: byLowerName[name.lowercase()]

    companion object {
        private const val MAX_ENTRIES = 100_000

        /** Where a path inside a container leads from [baseDir], with `.` and `..` worked out. */
        fun resolve(baseDir: String, target: String): String {
            val cleaned = target.substringBefore('#').substringBefore('?').replace('\\', '/')
            val joined = if (cleaned.startsWith("/")) cleaned else "$baseDir/$cleaned"
            val parts = ArrayDeque<String>()
            joined.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/")
        }
    }
}
