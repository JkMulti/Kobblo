package com.kobblo.gallery.file.explorer.tools.documents

import com.kobblo.gallery.file.explorer.tools.documents.CompoundFile.Companion.i32
import com.kobblo.gallery.file.explorer.tools.documents.CompoundFile.Companion.u16
import com.kobblo.gallery.file.explorer.tools.documents.CompoundFile.Companion.u32
import java.nio.charset.Charset

/** Words 97 to 2003. The text sits in pieces scattered through one stream, listed by a table in another. */
internal object WordBinaryReader {
    private const val MIN_WORD97_VERSION = 101
    private const val FIB_MAGIC = 0xA5EC
    private const val FLAG_TABLE_STREAM = 0x0200
    private const val FLAG_ENCRYPTED = 0x0100
    private const val OFFSET_TEXT_LENGTH = 0x4C
    private const val OFFSET_CLX = 0x01A2
    private const val PIECE_SIZE = 8
    private const val CP_COMPRESSED = 0x40000000

    fun read(compound: CompoundFile): ReadableDocument {
        val word = compound.read("WordDocument") ?: throw UnreadableDocumentException("no text stream")
        if (word.size < OFFSET_CLX + 8) throw UnreadableDocumentException("header too short")
        if (u16(word, 0) != FIB_MAGIC) throw UnreadableDocumentException("not a Word file")
        if (u16(word, 2) < MIN_WORD97_VERSION) throw UnreadableDocumentException("older than Word 97")
        val flags = u16(word, 0x0A)
        if (flags and FLAG_ENCRYPTED != 0) throw ProtectedDocumentException()
        val table = compound.read(if (flags and FLAG_TABLE_STREAM != 0) "1Table" else "0Table")
            ?: throw UnreadableDocumentException("no piece table")
        // Only the main story: headers, footnotes and text boxes come after it in the same pieces.
        val mainChars = i32(word, OFFSET_TEXT_LENGTH).coerceAtLeast(0).coerceAtMost(DocLimits.MAX_TEXT_CHARS * 2)
        val raw = extract(word, table, i32(word, OFFSET_CLX), i32(word, OFFSET_CLX + 4), mainChars)
        val builder = DocBuilder()
        if (raw.truncated) builder.truncated = true
        addText(raw.text, builder)
        return builder.build()
    }

    private class Extracted(val text: String, val truncated: Boolean)

    private fun extract(word: ByteArray, table: ByteArray, clxAt: Int, clxSize: Int, mainChars: Int): Extracted {
        if (clxAt < 0 || clxSize <= 0 || clxAt.toLong() + clxSize > table.size) throw UnreadableDocumentException("bad piece table")
        var at = clxAt
        val end = clxAt + clxSize
        // Formatting records come first, and the piece table is what follows them.
        while (at < end && table[at].toInt() == 1) {
            if (at + 3 > end) throw UnreadableDocumentException("bad piece table")
            at += 3 + u16(table, at + 1)
        }
        if (at + 5 > end || table[at].toInt() != 2) throw UnreadableDocumentException("no piece table")
        val plcSize = i32(table, at + 1)
        val plcAt = at + 5
        if (plcSize < 4 || plcAt.toLong() + plcSize > end) throw UnreadableDocumentException("bad piece table")
        val pieces = (plcSize - 4) / (4 + PIECE_SIZE)
        val descriptors = plcAt + (pieces + 1) * 4
        val out = StringBuilder()
        var truncated = false
        val windows1252 = Charset.forName("windows-1252")
        for (index in 0 until pieces) {
            if (out.length >= mainChars) break
            val cpStart = i32(table, plcAt + index * 4)
            val cpEnd = i32(table, plcAt + (index + 1) * 4)
            val count = (cpEnd.toLong() - cpStart).coerceIn(0L, (mainChars - out.length).toLong()).toInt()
            if (count == 0) continue
            val fcRaw = i32(table, descriptors + index * PIECE_SIZE + 2)
            val compressed = fcRaw and CP_COMPRESSED != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val from = fc / 2
                val available = (word.size - from).coerceAtLeast(0)
                val take = minOf(count, available)
                if (take < count) truncated = true
                if (take > 0) out.append(String(word, from, take, windows1252))
            } else {
                val available = ((word.size - fc).coerceAtLeast(0)) / 2
                val take = minOf(count, available)
                if (take < count) truncated = true
                if (take > 0) out.append(String(word, fc, take * 2, Charsets.UTF_16LE))
            }
            if (out.length >= DocLimits.MAX_TEXT_CHARS) {
                truncated = true
                break
            }
        }
        return Extracted(out.toString(), truncated)
    }

    /**
     * Word marks structure with control characters in the text: 13 ends a paragraph, 7 ends a table
     * cell, and 19 to 21 wrap a field, whose instructions are hidden and whose result is what shows.
     */
    fun addText(raw: String, builder: DocBuilder) {
        val line = StringBuilder()
        val rows = ArrayList<List<String>>()
        val cells = ArrayList<String>()
        var fieldDepth = 0
        var hiding = false
        var lastWasCell = false

        fun flushTable() {
            if (rows.isNotEmpty()) builder.table(rows.toList())
            rows.clear()
        }

        for (c in raw) {
            if (builder.isFull) {
                builder.truncated = true
                break
            }
            when (c.code) {
                0x13 -> {
                    fieldDepth++
                    hiding = true
                }
                0x14 -> hiding = false
                0x15 -> {
                    fieldDepth = (fieldDepth - 1).coerceAtLeast(0)
                    if (fieldDepth == 0) hiding = false
                }
                0x07 -> {
                    if (lastWasCell) {
                        // A second mark in a row ends the row. An empty cell looks the same, and
                        // telling them apart needs the formatting tables, so such a row splits in two.
                        if (cells.isNotEmpty()) {
                            if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(cells.toList()) else builder.truncated = true
                        }
                        cells.clear()
                        lastWasCell = false
                    } else {
                        cells.add(line.toString().trim().replace('\r', '\n'))
                        line.setLength(0)
                        lastWasCell = true
                    }
                }
                0x0D, 0x0C -> {
                    if (hiding) continue
                    lastWasCell = false
                    // A mark inside a row's later cells stays in the cell. In its first cell nothing
                    // cheap tells it from any other paragraph, so that one reads above the table.
                    if (cells.isNotEmpty()) {
                        line.append('\n')
                    } else {
                        flushTable()
                        builder.paragraph(line.toString())
                        line.setLength(0)
                    }
                }
                0x0B -> if (!hiding) line.append('\n')
                0x09 -> if (!hiding) line.append('\t')
                0x1E -> if (!hiding) line.append('-')
                0x1F, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x08 -> Unit
                else -> {
                    if (!hiding && c.code >= 0x20) {
                        line.append(c)
                        lastWasCell = false
                    }
                }
            }
        }
        if (rows.isNotEmpty()) flushTable()
        if (line.isNotBlank()) builder.paragraph(line.toString())
    }
}

/**
 * PowerPoint 97 to 2003. A slide's words are in one of two places: placeholders live in a list on the
 * document, and loose text boxes on the slide. An edit can leave old copies of slides behind in the
 * stream, so the file's own directory says which ones are current.
 */
internal object PowerPointBinaryReader {
    private const val T_DOCUMENT = 0x03E8
    private const val T_SLIDE = 0x03EE
    private const val T_SLIDE_PERSIST = 0x03F3
    private const val T_TEXT_HEADER = 0x0F9F
    private const val T_TEXT_CHARS = 0x0FA0
    private const val T_TEXT_BYTES = 0x0FA8
    private const val T_LIST_WITH_TEXT = 0x0FF0
    private const val T_USER_EDIT = 0x0FF5
    private const val T_PERSIST_DIRECTORY = 0x1772
    private const val INSTANCE_SLIDES = 0
    private const val HEADER = 8
    private const val MAX_RECORDS = 2_000_000
    private const val MAX_DEPTH = 64
    private const val MAX_EDITS = 4096

    private class SlideText(var persistId: Int? = null) {
        val lines = ArrayList<Pair<Int?, String>>()
    }

    /** Visits records in order, stepping into containers. Nesting is an explicit, capped list of ends. */
    private class Walker(private val data: ByteArray, private val onRecord: (type: Int, instance: Int, at: Int, size: Int) -> Unit) {
        fun walk(from: Int, to: Int) {
            val ends = ArrayList<Int>()
            var at = from
            var seen = 0
            while (at + HEADER <= to && seen < MAX_RECORDS) {
                while (ends.isNotEmpty() && at >= ends.last()) ends.removeAt(ends.lastIndex)
                seen++
                val versionInstance = u16(data, at)
                val type = u16(data, at + 2)
                val size = u32(data, at + 4)
                val body = at + HEADER
                val fits = body + size <= to
                if (versionInstance and 0xF == 0xF && fits && ends.size < MAX_DEPTH) {
                    onRecord(type, versionInstance shr 4, body, size.toInt())
                    ends.add(body + size.toInt())
                    at = body
                    continue
                }
                if (!fits) return
                onRecord(type, versionInstance shr 4, body, size.toInt())
                at = body + size.toInt()
            }
        }
    }

    fun read(compound: CompoundFile): ReadableDocument {
        // An encrypted deck replaces its current-edit pointer with a token and hides the rest.
        if (compound.has("EncryptedSummary")) throw ProtectedDocumentException()
        val data = compound.read("PowerPoint Document") ?: throw UnreadableDocumentException("no deck stream")
        val currentUser = compound.read("Current User")
        val directory = currentUser?.let { persistDirectory(data, it) }
        val builder = DocBuilder()
        val slides = readOutline(data, directory)
        val boxes = readSlideBoxes(data, directory, slides.mapNotNull { it.persistId })
        if (slides.isEmpty() && boxes.isEmpty()) throw UnreadableDocumentException("no slide text")

        val count = maxOf(slides.size, boxes.size)
        for (index in 0 until count) {
            if (builder.isFull) {
                builder.truncated = true
                break
            }
            builder.slide(index + 1)
            val outline = slides.getOrNull(index)?.lines.orEmpty()
            outline.forEach { (kind, text) -> addText(kind, text, builder) }
            // A newer file can keep the same words in both places.
            val alreadyShown = outline.map { it.second }.toSet()
            boxes.getOrNull(index)?.forEach { (kind, text) -> if (text !in alreadyShown) addText(kind, text, builder) }
        }
        return builder.build(paged = true)
    }

    private fun addText(kind: Int?, text: String, builder: DocBuilder) {
        // Paragraphs in a text atom are split by carriage returns, and 0x0B is a soft line break.
        val isTitle = kind == 0 || kind == 6
        text.split('\r').forEach { paragraph ->
            val clean = paragraph.replace('\u000B', '\n').filter { it == '\n' || it == '\t' || it.code >= 0x20 }
            if (isTitle) builder.heading(2, clean) else builder.paragraph(clean)
        }
    }

    /** The slides' placeholders, in the order the deck shows them. */
    private fun readOutline(data: ByteArray, directory: Map<Int, Int>?): List<SlideText> {
        val slides = ArrayList<SlideText>()
        var pendingKind: Int? = null
        var inSlideList = false
        var listEnd = 0
        // With a directory, only the current Document counts. Without one, every list in the stream does.
        val (from, to) = directory?.get(DOCUMENT_KEY)?.let { at -> documentRange(data, at) } ?: (0 to data.size)
        var kept = 0
        fun keep(kind: Int?, text: String) {
            if (kept >= DocLimits.MAX_TEXT_CHARS) return
            kept += text.length
            slides.lastOrNull()?.lines?.add(kind to text)
        }
        Walker(data) { type, instance, at, size ->
            when (type) {
                T_LIST_WITH_TEXT -> {
                    inSlideList = instance == INSTANCE_SLIDES
                    listEnd = at + size
                }
                T_SLIDE_PERSIST -> if (inSlideList && at < listEnd && size >= 4 && slides.size < DocLimits.MAX_SECTIONS) {
                    slides.add(SlideText(i32(data, at)))
                    pendingKind = null
                }
                T_TEXT_HEADER -> if (inSlideList && at < listEnd && size >= 4) pendingKind = i32(data, at)
                T_TEXT_CHARS -> if (inSlideList && at < listEnd) keep(pendingKind, String(data, at, size and 1.inv(), Charsets.UTF_16LE))
                T_TEXT_BYTES -> if (inSlideList && at < listEnd) keep(pendingKind, String(data, at, size, Windows1252))
            }
        }.walk(from, to)
        return slides
    }

    /** Text that lives directly on a slide rather than in its outline. */
    private fun readSlideBoxes(data: ByteArray, directory: Map<Int, Int>?, order: List<Int>): List<List<Pair<Int?, String>>> {
        if (directory != null && order.isNotEmpty()) {
            return order.map { persistId ->
                val at = directory[persistId] ?: return@map emptyList()
                textInRecord(data, at)
            }
        }
        val out = ArrayList<ArrayList<Pair<Int?, String>>>()
        var slideEnd = -1
        var kept = 0
        var pendingKind: Int? = null
        fun keep(text: String) {
            if (kept >= DocLimits.MAX_TEXT_CHARS) return
            kept += text.length
            out.last().add(pendingKind to text)
        }
        Walker(data) { type, _, at, size ->
            when (type) {
                T_SLIDE -> if (out.size < DocLimits.MAX_SECTIONS) {
                    out.add(ArrayList())
                    slideEnd = at + size
                    pendingKind = null
                }
                T_TEXT_HEADER -> if (at < slideEnd && size >= 4) pendingKind = i32(data, at)
                T_TEXT_CHARS -> if (at < slideEnd) keep(String(data, at, size and 1.inv(), Charsets.UTF_16LE))
                T_TEXT_BYTES -> if (at < slideEnd) keep(String(data, at, size, Windows1252))
            }
        }.walk(0, data.size)
        return out
    }

    private fun textInRecord(data: ByteArray, at: Int): List<Pair<Int?, String>> {
        val range = documentRange(data, at) ?: return emptyList()
        val out = ArrayList<Pair<Int?, String>>()
        var pendingKind: Int? = null
        Walker(data) { type, _, body, size ->
            when (type) {
                T_TEXT_HEADER -> if (size >= 4) pendingKind = i32(data, body)
                T_TEXT_CHARS -> out.add(pendingKind to String(data, body, size and 1.inv(), Charsets.UTF_16LE))
                T_TEXT_BYTES -> out.add(pendingKind to String(data, body, size, Windows1252))
            }
        }.walk(range.first, range.second)
        return out
    }

    private fun documentRange(data: ByteArray, at: Int): Pair<Int, Int>? {
        if (at < 0 || at.toLong() + HEADER > data.size) return null
        val size = u32(data, at + 4)
        val end = at + HEADER + size
        if (end > data.size) return null
        return (at + HEADER) to end.toInt()
    }

    private const val DOCUMENT_KEY = -1
    private val Windows1252: Charset = Charset.forName("windows-1252")

    /**
     * Follows the chain of edits from newest to oldest, keeping the first place each object appears.
     * A record the chain can't reach is an old copy. Anything off here returns null, and the deck is
     * then read in stream order, which is right for every file not fast-saved.
     */
    private fun persistDirectory(data: ByteArray, currentUser: ByteArray): Map<Int, Int>? {
        if (currentUser.size < HEADER + 12) return null
        val token = i32(currentUser, HEADER + 4)
        // An encrypted deck carries a different token here, and its text isn't readable.
        if (token == ENCRYPTED_TOKEN) throw ProtectedDocumentException()
        if (token != CURRENT_USER_TOKEN) return null
        var offset = u32(currentUser, HEADER + 8)
        val out = HashMap<Int, Int>()
        var documentPersistId: Int? = null
        var edits = 0
        while (offset in 1 until data.size.toLong() && edits < MAX_EDITS) {
            edits++
            val at = offset.toInt()
            if (at + HEADER + 20 > data.size || u16(data, at + 2) != T_USER_EDIT) return null
            val body = at + HEADER
            val previous = u32(data, body + 8)
            val directoryAt = u32(data, body + 12)
            if (documentPersistId == null) documentPersistId = i32(data, body + 16)
            if (directoryAt + HEADER > data.size) return null
            val dirAt = directoryAt.toInt()
            if (u16(data, dirAt + 2) != T_PERSIST_DIRECTORY) return null
            val dirEnd = dirAt + HEADER + u32(data, dirAt + 4)
            if (dirEnd > data.size) return null
            var cursor = dirAt + HEADER
            while (cursor + 4 <= dirEnd) {
                val entry = i32(data, cursor)
                cursor += 4
                val first = entry and 0xFFFFF
                val count = entry ushr 20
                for (i in 0 until count) {
                    if (cursor + 4 > dirEnd) return null
                    out.putIfAbsent(first + i, i32(data, cursor))
                    cursor += 4
                }
            }
            offset = previous
        }
        val document = documentPersistId?.let(out::get) ?: return null
        if (documentRange(data, document) == null || u16(data, document + 2) != T_DOCUMENT) return null
        out[DOCUMENT_KEY] = document
        return out
    }

    private const val CURRENT_USER_TOKEN = 0xE391C05F.toInt()
    private const val ENCRYPTED_TOKEN = 0xF3D1C4DF.toInt()
}

/** Excel 97 to 2003: a stream of small records, with each sheet's cells starting where the workbook says. */
internal object ExcelBinaryReader {
    private const val R_BOF = 0x0809
    private const val R_EOF = 0x000A
    private const val R_FILEPASS = 0x002F
    private const val R_DATE1904 = 0x0022
    private const val R_BOUNDSHEET = 0x0085
    private const val R_SST = 0x00FC
    private const val R_CONTINUE = 0x003C
    private const val R_FORMAT = 0x041E
    private const val R_XF = 0x00E0
    private const val R_LABELSST = 0x00FD
    private const val R_LABEL = 0x0204
    private const val R_NUMBER = 0x0203
    private const val R_RK = 0x027E
    private const val R_MULRK = 0x00BD
    private const val R_BOOLERR = 0x0205
    private const val R_FORMULA = 0x0006
    private const val R_STRING = 0x0207
    private const val BIFF8 = 0x0600
    private const val MAX_SHARED_STRINGS = 300_000
    private const val MAX_SHEETS = 64
    private const val MAX_STRING_CHARS = 32_767

    private val ErrorNames = mapOf(
        0x00 to "#NULL!", 0x07 to "#DIV/0!", 0x0F to "#VALUE!", 0x17 to "#REF!", 0x1D to "#NAME?", 0x24 to "#NUM!", 0x2A to "#N/A"
    )

    private class SheetRef(val name: String, val offset: Long)

    fun read(compound: CompoundFile): Workbook {
        val data = compound.read("Workbook") ?: compound.read("Book") ?: throw UnreadableDocumentException("no workbook stream")
        val sheets = ArrayList<SheetRef>()
        val customFormats = HashMap<Int, String>()
        val dateStyles = ArrayList<Boolean>()
        var strings: List<String> = emptyList()
        var date1904 = false

        // The globals come first and end at the first EOF. Its strings can run across CONTINUE records.
        var at = 0
        var sawBof = false
        while (at + 4 <= data.size) {
            val type = u16(data, at)
            val size = u16(data, at + 2)
            val body = at + 4
            if (body + size > data.size) break
            when (type) {
                R_BOF -> {
                    if (size < 4) throw UnreadableDocumentException("short BOF")
                    if (!sawBof && u16(data, body) != BIFF8) throw UnreadableDocumentException("older than Excel 97")
                    sawBof = true
                }
                R_FILEPASS -> throw ProtectedDocumentException()
                R_DATE1904 -> if (size >= 2) date1904 = u16(data, body) != 0
                R_FORMAT -> if (size >= 5) {
                    val id = u16(data, body)
                    customFormats[id] = readWideString(data, body + 2, body + size).first
                }
                R_XF -> if (size >= 4) {
                    val id = u16(data, body + 2)
                    dateStyles.add(CellFormat.isDateFormat(id, customFormats[id]))
                }
                R_BOUNDSHEET -> if (size >= 8 && sheets.size < MAX_SHEETS) {
                    val offset = u32(data, body)
                    val kind = data[body + 5].toInt() and 0xFF
                    val nameChars = data[body + 6].toInt() and 0xFF
                    val wide = data[body + 7].toInt() and 1 != 0
                    val name = readChars(data, body + 8, body + size, nameChars, wide)
                    // Kind 0 is a worksheet. The others are charts and macros, with no cells to show.
                    if (kind == 0) sheets.add(SheetRef(name, offset))
                }
                R_SST -> strings = readSharedStrings(data, at)
                R_EOF -> break
            }
            at = body + size
        }
        if (!sawBof) throw UnreadableDocumentException("no BOF")
        if (sheets.isEmpty()) throw UnreadableDocumentException("no sheets")

        val budget = CellBudget()
        val built = sheets.map { sheet ->
            val builder = SheetBuilder(budget)
            if (sheet.offset in 0 until data.size.toLong()) readSheet(data, sheet.offset.toInt(), strings, dateStyles, date1904, builder)
            NamedSheet(sheet.name, builder.build())
        }
        return Workbook(built)
    }

    private fun readSheet(data: ByteArray, from: Int, strings: List<String>, dateStyles: List<Boolean>, date1904: Boolean, sheet: SheetBuilder) {
        var at = from
        var pendingFormula: Pair<Int, Int>? = null
        fun shown(xf: Int, value: Double): String =
            if (dateStyles.getOrNull(xf) == true) CellFormat.date(value, date1904) else CellFormat.number(value)

        while (at + 4 <= data.size) {
            val type = u16(data, at)
            val size = u16(data, at + 2)
            val body = at + 4
            if (body + size > data.size) return
            when (type) {
                R_EOF -> return
                R_LABELSST -> if (size >= 10) {
                    val text = strings.getOrNull(i32(data, body + 6)).orEmpty()
                    sheet.put(u16(data, body), u16(data, body + 2), text)
                }
                R_LABEL -> if (size >= 8) {
                    sheet.put(u16(data, body), u16(data, body + 2), readWideString(data, body + 6, body + size).first)
                }
                R_NUMBER -> if (size >= 14) {
                    val value = java.lang.Double.longBitsToDouble(i64(data, body + 6))
                    sheet.put(u16(data, body), u16(data, body + 2), shown(u16(data, body + 4), value))
                }
                R_RK -> if (size >= 10) {
                    sheet.put(u16(data, body), u16(data, body + 2), shown(u16(data, body + 4), rk(i32(data, body + 6))))
                }
                R_MULRK -> if (size >= 12) {
                    val row = u16(data, body)
                    var column = u16(data, body + 2)
                    var cursor = body + 4
                    while (cursor + 6 <= body + size - 2) {
                        sheet.put(row, column, shown(u16(data, cursor), rk(i32(data, cursor + 2))))
                        cursor += 6
                        column++
                    }
                }
                R_BOOLERR -> if (size >= 8) {
                    val value = data[body + 6].toInt() and 0xFF
                    val isError = data[body + 7].toInt() != 0
                    val text = if (isError) ErrorNames[value] ?: "#ERROR" else if (value != 0) "TRUE" else "FALSE"
                    sheet.put(u16(data, body), u16(data, body + 2), text)
                }
                R_FORMULA -> if (size >= 14) {
                    val row = u16(data, body)
                    val column = u16(data, body + 2)
                    pendingFormula = null
                    val isSpecial = (data[body + 12].toInt() and 0xFF) == 0xFF && (data[body + 13].toInt() and 0xFF) == 0xFF
                    if (!isSpecial) {
                        sheet.put(row, column, shown(u16(data, body + 4), java.lang.Double.longBitsToDouble(i64(data, body + 6))))
                    } else {
                        when (data[body + 6].toInt() and 0xFF) {
                            0 -> pendingFormula = row to column
                            1 -> sheet.put(row, column, if (data[body + 8].toInt() != 0) "TRUE" else "FALSE")
                            2 -> sheet.put(row, column, ErrorNames[data[body + 8].toInt() and 0xFF] ?: "#ERROR")
                        }
                    }
                }
                R_STRING -> pendingFormula?.let { (row, column) ->
                    if (size >= 3) sheet.put(row, column, readWideString(data, body, body + size).first)
                    pendingFormula = null
                }
            }
            at = body + size
        }
    }

    /** A compact number: 30 bits of value, a flag for hundredths, and a flag for integer or float. */
    fun rk(value: Int): Double {
        val isInteger = value and 2 != 0
        val number = if (isInteger) {
            (value shr 2).toDouble()
        } else {
            java.lang.Double.longBitsToDouble((value.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (value and 1 != 0) number / 100.0 else number
    }

    private fun i64(bytes: ByteArray, at: Int): Long =
        (i32(bytes, at).toLong() and 0xFFFFFFFFL) or (i32(bytes, at + 4).toLong() shl 32)

    /** A length, a flags byte, then the characters: one byte each or two, as the flags say. */
    private fun readWideString(data: ByteArray, at: Int, end: Int): Pair<String, Int> {
        if (at + 3 > end) return "" to end
        val chars = u16(data, at)
        val flags = data[at + 2].toInt() and 0xFF
        var cursor = at + 3
        val wide = flags and 1 != 0
        if (flags and 8 != 0) cursor += 2
        if (flags and 4 != 0) cursor += 4
        val text = readChars(data, cursor, end, chars, wide)
        return text to cursor + chars * (if (wide) 2 else 1)
    }

    private fun readChars(data: ByteArray, at: Int, end: Int, chars: Int, wide: Boolean): String {
        if (at >= end) return ""
        val perChar = if (wide) 2 else 1
        val available = (end - at) / perChar
        val count = minOf(chars, available).coerceAtMost(MAX_STRING_CHARS)
        return if (wide) String(data, at, count * 2, Charsets.UTF_16LE) else String(data, at, count, Charsets.ISO_8859_1)
    }

    /** Strings may break across CONTINUE records, and each piece says again whether it's wide. */
    private fun readSharedStrings(data: ByteArray, recordAt: Int): List<String> {
        val pieces = ArrayList<IntArray>()
        var at = recordAt
        val first = u16(data, at + 2)
        pieces.add(intArrayOf(at + 4, at + 4 + first))
        at += 4 + first
        while (at + 4 <= data.size && u16(data, at) == R_CONTINUE) {
            val size = u16(data, at + 2)
            if (at + 4 + size > data.size) break
            pieces.add(intArrayOf(at + 4, at + 4 + size))
            at += 4 + size
        }
        var piece = 0
        var cursor = pieces[0][0] + 8
        val total = i32(data, pieces[0][0] + 4)
        val out = ArrayList<String>()
        val text = StringBuilder()

        fun nextPiece(): Boolean {
            piece++
            if (piece >= pieces.size) return false
            cursor = pieces[piece][0]
            return true
        }

        fun skip(bytes: Long) {
            var left = bytes
            while (left > 0) {
                val here = pieces[piece][1] - cursor
                if (left <= here) {
                    cursor += left.toInt()
                    return
                }
                left -= here
                if (!nextPiece()) return
            }
        }

        val wanted = total.coerceIn(0, MAX_SHARED_STRINGS)
        while (out.size < wanted) {
            if (piece >= pieces.size) break
            if (cursor + 3 > pieces[piece][1]) {
                if (!nextPiece()) break
                continue
            }
            val chars = u16(data, cursor)
            var flags = data[cursor + 2].toInt() and 0xFF
            cursor += 3
            var richRuns = 0
            var extra = 0L
            if (flags and 8 != 0) {
                if (cursor + 2 > pieces[piece][1]) break
                richRuns = u16(data, cursor)
                cursor += 2
            }
            if (flags and 4 != 0) {
                if (cursor + 4 > pieces[piece][1]) break
                extra = u32(data, cursor)
                cursor += 4
            }
            text.setLength(0)
            var left = chars
            var wide = flags and 1 != 0
            var ok = true
            while (left > 0) {
                val perChar = if (wide) 2 else 1
                val here = (pieces[piece][1] - cursor) / perChar
                if (here <= 0) {
                    // Past a boundary, a fresh flags byte restates the width for the rest of the string.
                    if (!nextPiece() || cursor >= pieces[piece][1]) {
                        ok = false
                        break
                    }
                    flags = data[cursor].toInt() and 0xFF
                    wide = flags and 1 != 0
                    cursor++
                    continue
                }
                val take = minOf(left, here)
                if (text.length < MAX_STRING_CHARS) {
                    text.append(if (wide) String(data, cursor, take * 2, Charsets.UTF_16LE) else String(data, cursor, take, Charsets.ISO_8859_1))
                }
                cursor += take * perChar
                left -= take
            }
            if (!ok) break
            out.add(text.toString())
            skip(richRuns * 4L + extra)
        }
        return out
    }
}
