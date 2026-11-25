package com.kobblo.gallery.file.explorer.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/*
 * The parts of showing a document as pages that are only about text: which reader a file needs, turning
 * bytes into characters, digging the words out of a Word file's markup, and deciding where each page
 * ends. Nothing here touches Android, so all of it can be tested on a desktop.
 */

/** How a document keeps its words, which decides who reads them. */
internal enum class DocumentFormat {
    /** A Word file, old or new. Its words have to be dug out of it. */
    Word,

    /** Everything else is shown as the text it is, Markdown and source code included. */
    Text;

    companion object {
        /** Going by the extension alone, as the rest of the app does when it picks a viewer. */
        fun of(fileName: String): DocumentFormat = when (fileName.substringAfterLast('.', "").lowercase()) {
            "docx", "doc" -> Word
            else -> Text
        }
    }
}

/** Whether a file is a PDF, which like every other choice of viewer goes by the end of its name. */
internal fun isPdfName(fileName: String): Boolean = fileName.endsWith(".pdf", ignoreCase = true)

private val MimeTypes = mapOf(
    "pdf" to "application/pdf",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "json" to "application/json",
    "md" to "text/plain",
    "txt" to "text/plain"
)

/** What other apps are told a document is. One with no type to name is offered to any app that will take a file. */
internal fun documentMimeType(fileName: String): String =
    MimeTypes[fileName.substringAfterLast('.', "").lowercase()] ?: "*/*"

/** The start of a stream, and whether the stream went on past what was kept. */
internal class CappedBytes(val bytes: ByteArray, val cutShort: Boolean)

/** Reads no more than [limit] bytes, then looks whether that was everything. */
internal fun InputStream.readCapped(limit: Int): CappedBytes {
    val kept = ByteArrayOutputStream()
    val block = ByteArray(64 * 1024)
    while (kept.size() < limit) {
        val count = read(block, 0, minOf(block.size, limit - kept.size()))
        if (count < 0) return CappedBytes(kept.toByteArray(), cutShort = false)
        kept.write(block, 0, count)
    }
    return CappedBytes(kept.toByteArray(), cutShort = read() >= 0)
}

/** The marks a file can open with to say how it's encoded. */
private enum class ByteOrderMark(val charset: Charset, vararg mark: Int) {
    Utf8(Charsets.UTF_8, 0xEF, 0xBB, 0xBF),
    Utf16LittleEndian(Charsets.UTF_16LE, 0xFF, 0xFE),
    Utf16BigEndian(Charsets.UTF_16BE, 0xFE, 0xFF);

    private val mark = ByteArray(mark.size) { mark[it].toByte() }

    val length: Int get() = mark.size

    fun opens(bytes: ByteArray): Boolean = bytes.size >= mark.size && mark.indices.all { bytes[it] == mark[it] }
}

/**
 * The characters in a text file's bytes. A byte order mark settles the encoding. Without one the bytes
 * are UTF-8 if every one of them is valid UTF-8, and Latin-1 otherwise: there any byte is a character,
 * so a file in some older encoding still shows its plain letters rather than nothing.
 */
internal fun decodeDocumentText(bytes: ByteArray): String {
    for (mark in ByteOrderMark.entries) {
        if (mark.opens(bytes)) return String(bytes, mark.length, bytes.size - mark.length, mark.charset)
    }
    return strictUtf8(bytes) ?: String(bytes, Charsets.ISO_8859_1)
}

/** Null unless the bytes are UTF-8 through and through. */
private fun strictUtf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/** What sets one table cell apart from the next once a table is flattened to a line of text. */
private const val CellBar = "  |  "

/**
 * Gathers a Word document's words as its markup goes past, tag by tag, under their names without the
 * `w:` in front.
 *
 * A paragraph ends in a blank line. A table comes out one row to a line with bars between its cells,
 * and inside a cell paragraphs run together, since a cell reads as one thing. Tabs and line breaks
 * written as tags become the characters they stand for.
 */
internal class WordTextCollector {
    private val body = StringBuilder()
    private val cell = StringBuilder()
    private var inCell = false

    private val current: StringBuilder get() = if (inCell) cell else body

    /** The words of one run of text. */
    fun text(run: String) {
        current.append(run)
    }

    fun open(tag: String) {
        when (tag) {
            "tab" -> current.append('\t')
            "br", "cr" -> current.append('\n')
            "tc" -> {
                inCell = true
                cell.setLength(0)
            }
        }
    }

    fun close(tag: String) {
        when (tag) {
            "p" -> if (!inCell) body.append("\n\n")
            "tc" -> {
                inCell = false
                val words = cell.trim()
                if (words.isNotEmpty()) body.append(words).append(CellBar)
            }
            "tr" -> body.append('\n')
        }
    }

    fun result(): String = body.trim().toString()
}

private val ParagraphTag = Regex("<w:p[^>]*>")
private val CellTag = Regex("<w:tc[^>]*>")
private val RowTag = Regex("<w:tr[^>]*>")
private val AnyTag = Regex("<[^>]+>")

/** The entities XML defines, in the order they're decoded. */
private val Entities = listOf("&lt;" to "<", "&gt;" to ">", "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'")

/**
 * What's left of Word markup when its tags are cut out rather than understood, for markup too broken
 * to parse. Rougher than [WordTextCollector], but the words are all there. `&amp;` is decoded after
 * `&lt;` and `&gt;`, so text that spells one of those out keeps it spelled out.
 */
internal fun stripWordMarkup(markup: String): String {
    val untagged = markup
        .replace(ParagraphTag, "\n\n")
        .replace(CellTag, CellBar)
        .replace(RowTag, "\n")
        .replace(AnyTag, "")
    return Entities.fold(untagged) { text, (entity, character) -> text.replace(entity, character) }.trim()
}

/**
 * The sheet every page is laid out on. Text is always set on a sheet this size, and a page drawn at
 * any other width is this one scaled, so a thumbnail breaks its lines exactly where the full page does.
 */
internal object PagePaper {
    const val Width = 1200
    const val Margin = 84

    /** A4's proportions. */
    private const val HeightPerWidth = 1.414f

    val Height: Int = heightFor(Width)
    val TextWidth: Int = Width - Margin * 2
    val TextHeight: Int = Height - Margin * 2

    /** How tall a page is when it's drawn [width] pixels wide. */
    fun heightFor(width: Int): Int = (width * HeightPerWidth).toInt().coerceAtLeast(1)

    /** How much the sheet shrinks or grows to be [width] pixels wide. */
    fun scaleFor(width: Int): Float = width.toFloat() / Width
}

/** A document's text split into pages. [cutShort] says the text went on after the last of them. */
internal class Pagination(val pages: List<CharSequence>, val cutShort: Boolean)

/** No document shows more pages than this. A log can run to millions of lines, and each page is a bitmap to draw. */
internal const val MaxDocumentPages = 250

/** How much text is measured to fill one page. A page holds far less, and measuring a whole book for each page would crawl. */
private const val PageLookAhead = 8000

/**
 * Splits [text] into pages. [fill] is handed the text that starts a page and answers how many of its
 * characters that page takes; a page always takes at least one, so an answer of nothing can't stall it.
 */
internal fun paginate(text: CharSequence, pageLimit: Int = MaxDocumentPages, fill: (CharSequence) -> Int): Pagination {
    val pages = ArrayList<CharSequence>()
    var start = 0
    while (start < text.length) {
        if (pages.size >= pageLimit) return Pagination(pages, cutShort = true)
        val ahead = text.subSequence(start, minOf(text.length, start + PageLookAhead))
        val end = minOf(text.length, start + fill(ahead).coerceAtLeast(1))
        pages += text.subSequence(start, end)
        start = end
    }
    return Pagination(pages, cutShort = false)
}
