package com.kobblo.gallery.file.explorer.tools.documents

import java.io.File

/** LibreOffice's text, spreadsheet and presentation files, which are all a ZIP with one `content.xml`. */
internal object OpenDocumentReader {
    private const val MAX_SHEETS = 64

    /** Past this, a repeat count isn't content: it's how a sheet says "and the rest of the columns are blank". */
    private const val MAX_REPEAT = 64

    fun readText(file: File): ReadableDocument = open(file) { zip ->
        val builder = DocBuilder()
        zip.scan("content.xml") { scan -> readFlowingText(scan, builder) } ?: throw UnreadableDocumentException("no content")
        if (zip.hitLimit) builder.truncated = true
        builder.build()
    }

    fun readPresentation(file: File): ReadableDocument = open(file) { zip ->
        val builder = DocBuilder()
        zip.scan("content.xml") { scan -> readSlides(scan, builder) } ?: throw UnreadableDocumentException("no content")
        if (zip.hitLimit) builder.truncated = true
        builder.build(paged = true)
    }

    fun readSpreadsheet(file: File): Workbook = open(file) { zip ->
        val budget = CellBudget()
        val sheets = zip.scan("content.xml") { scan -> readSheets(scan, budget) } ?: throw UnreadableDocumentException("no content")
        val truncatedPart = zip.hitLimit
        if (sheets.isEmpty()) throw UnreadableDocumentException("no sheets")
        Workbook(sheets.map { NamedSheet(it.first, it.second.also { b -> if (truncatedPart) b.truncated = true }.build()) })
    }

    /** What's inside the document's ZIP, once it's known not to be locked. */
    fun mimeType(zip: SafeZip): String? =
        zip.readBytes("mimetype", 256)?.let { String(it, Charsets.US_ASCII).trim() }

    private fun <T> open(file: File, block: (SafeZip) -> T): T = SafeZip(file).use { zip ->
        // An encrypted part would read as noise, and the manifest says so up front.
        val manifest = zip.readBytes("META-INF/manifest.xml", 2L * 1024 * 1024)
        if (manifest != null && String(manifest, Charsets.UTF_8).contains("encryption-data")) throw ProtectedDocumentException()
        block(zip)
    }

    /** Elements whose text isn't part of the reading order. A counter is enough, so nesting costs nothing. */
    private val SkippedInText = setOf(
        "annotation", "note", "tracked-changes", "forms", "script", "sequence-decls", "user-field-decls",
        "bookmark-ref", "font-face-decls", "automatic-styles", "master-styles", "event-listeners"
    )

    private fun readFlowingText(scan: XmlScan, builder: DocBuilder) {
        val line = StringBuilder()
        val spans = ArrayList<InlineSpan>()
        var skip = 0
        var listDepth = 0
        var heading: Int? = null
        var inParagraph = 0
        var tableDepth = 0
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()

        fun flush() {
            val text = line.toString()
            line.setLength(0)
            val level = heading
            heading = null
            val styled = spans.toList()
            spans.clear()
            when {
                tableDepth > 0 -> if (text.isNotBlank() && cell.length < DocLimits.MAX_CELL_CHARS) {
                    if (cell.isNotEmpty()) cell.append('\n')
                    cell.append(text.trim().take(DocLimits.MAX_CELL_CHARS))
                }
                level != null -> builder.heading(level, text)
                listDepth > 0 -> builder.bullet(text, listDepth - 1, spans = styled)
                else -> builder.paragraph(text, styled)
            }
        }

        while (!builder.isFull) {
            when (scan.next()) {
                XmlScan.Event.EOF -> break
                XmlScan.Event.START -> {
                    if (scan.name in SkippedInText) skip++
                    if (skip > 0) continue
                    when (scan.fullName) {
                        "text:h" -> {
                            inParagraph++
                            heading = scan.attribute("outline-level")?.toIntOrNull() ?: 1
                        }
                        "text:p" -> inParagraph++
                        "text:list" -> listDepth++
                        "text:s" -> repeat((scan.attribute("c")?.toIntOrNull() ?: 1).coerceIn(1, 64)) { line.append(' ') }
                        "text:tab" -> line.append('\t')
                        "text:line-break" -> line.append('\n')
                        "table:table" -> tableDepth++
                        "table:table-cell" -> if (tableDepth == 1) cell.setLength(0)
                    }
                }
                XmlScan.Event.TEXT -> if (skip == 0 && inParagraph > 0) {
                    line.append(scan.text)
                    if (builder.wontFit(line.length)) flush()
                }
                XmlScan.Event.END -> {
                    if (scan.name in SkippedInText) {
                        skip = (skip - 1).coerceAtLeast(0)
                        continue
                    }
                    if (skip > 0) continue
                    when (scan.fullName) {
                        "text:p", "text:h" -> {
                            inParagraph = (inParagraph - 1).coerceAtLeast(0)
                            flush()
                        }
                        "text:list" -> listDepth = (listDepth - 1).coerceAtLeast(0)
                        "table:table-cell" -> if (tableDepth == 1) {
                            row.add(cell.toString())
                            cell.setLength(0)
                        }
                        "table:table-row" -> if (tableDepth == 1) {
                            if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(row.toList()) else builder.truncated = true
                            row.clear()
                        }
                        "table:table" -> {
                            tableDepth = (tableDepth - 1).coerceAtLeast(0)
                            if (tableDepth == 0) {
                                builder.table(rows.toList())
                                rows.clear()
                            }
                        }
                    }
                }
            }
        }
        if (line.isNotEmpty()) flush()
    }

    private fun readSlides(scan: XmlScan, builder: DocBuilder) {
        val line = StringBuilder()
        var slide = 0
        var skip = 0
        var inParagraph = false
        var titleFrame = false
        while (!builder.isFull) {
            when (scan.next()) {
                XmlScan.Event.EOF -> return
                XmlScan.Event.START -> {
                    if (scan.name == "notes" || scan.name == "annotation") skip++
                    if (skip > 0) continue
                    when (scan.fullName) {
                        "draw:page" -> {
                            slide++
                            builder.slide(slide)
                        }
                        "draw:frame" -> titleFrame = scan.attribute("class") == "title"
                        "text:p", "text:h" -> {
                            inParagraph = true
                            line.setLength(0)
                        }
                        "text:s" -> if (inParagraph) line.append(' ')
                        "text:tab" -> if (inParagraph) line.append('\t')
                        "text:line-break" -> if (inParagraph) line.append('\n')
                    }
                }
                XmlScan.Event.TEXT -> if (skip == 0 && inParagraph) {
                    line.append(scan.text)
                    if (builder.wontFit(line.length)) {
                        builder.paragraph(line.toString())
                        line.setLength(0)
                    }
                }
                XmlScan.Event.END -> {
                    if (scan.name == "notes" || scan.name == "annotation") {
                        skip = (skip - 1).coerceAtLeast(0)
                        continue
                    }
                    if (skip > 0) continue
                    when (scan.fullName) {
                        "text:p", "text:h" -> {
                            inParagraph = false
                            if (slide > 0) {
                                if (titleFrame) builder.heading(2, line.toString()) else builder.paragraph(line.toString())
                            }
                        }
                        "draw:frame" -> titleFrame = false
                    }
                }
            }
        }
    }

    private fun readSheets(scan: XmlScan, budget: CellBudget): List<Pair<String, SheetBuilder>> {
        val sheets = ArrayList<Pair<String, SheetBuilder>>()
        var sheet: SheetBuilder? = null
        var rowIndex = 0
        var column = 0
        var rowRepeat = 1
        var cellRepeat = 1
        var cellValue: String? = null
        val cellText = StringBuilder()
        var inCell = false
        var inParagraph = false
        var paragraphs = 0
        val rowCells = ArrayList<Triple<Int, Int, String>>()
        var scanned = 0L
        while (scanned < MAX_SCAN_EVENTS) {
            scanned++
            when (scan.next()) {
                XmlScan.Event.EOF -> break
                XmlScan.Event.START -> when (scan.fullName) {
                    "table:table" -> {
                        if (sheets.size >= MAX_SHEETS) return sheets
                        sheet = SheetBuilder(budget)
                        sheets.add(scan.attribute("name").orEmpty() to sheet!!)
                        rowIndex = 0
                    }
                    "table:table-row" -> {
                        column = 0
                        rowCells.clear()
                        rowRepeat = scan.attribute("number-rows-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    }
                    "table:table-cell", "table:covered-table-cell" -> {
                        inCell = true
                        cellRepeat = scan.attribute("number-columns-repeated")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        // A date's text can be formatted any way, but its value is always ISO.
                        cellValue = scan.attribute("date-value")?.take(10) ?: scan.attribute("value")?.let(CellFormat::number)
                        cellText.setLength(0)
                        paragraphs = 0
                    }
                    "text:p" -> if (inCell) {
                        inParagraph = true
                        if (paragraphs++ > 0 && cellText.length < 8192) cellText.append('\n')
                    }
                    "text:s" -> if (inParagraph && cellText.length < 8192) cellText.append(' ')
                }
                XmlScan.Event.TEXT -> if (inParagraph && cellText.length < 8192) cellText.append(scan.text)
                XmlScan.Event.END -> when (scan.fullName) {
                    "text:p" -> inParagraph = false
                    "table:table-cell", "table:covered-table-cell" -> {
                        inCell = false
                        val text = cellText.toString().ifEmpty { cellValue.orEmpty() }
                        if (text.isNotEmpty()) {
                            repeat(cellRepeat.coerceAtMost(MAX_REPEAT)) { offset ->
                                rowCells.add(Triple(rowIndex, column + offset, text))
                            }
                        }
                        // Blank repeats move along without adding anything, however many there are.
                        column = (column.toLong() + cellRepeat).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
                    }
                    "table:table-row" -> {
                        val target = sheet
                        if (target != null && rowCells.isNotEmpty()) {
                            repeat(rowRepeat.coerceAtMost(MAX_REPEAT)) { offset ->
                                rowCells.forEach { (row, col, value) -> target.put(row + offset, col, value) }
                            }
                            if (rowRepeat > MAX_REPEAT) target.truncated = true
                        }
                        rowIndex = (rowIndex.toLong() + rowRepeat).coerceAtMost(Int.MAX_VALUE.toLong() / 2).toInt()
                    }
                }
            }
        }
        return sheets
    }

    private const val MAX_SCAN_EVENTS = 40_000_000L
}
