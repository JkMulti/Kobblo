package com.kobblo.gallery.file.explorer.tools.documents

import com.kobblo.gallery.file.explorer.tools.files.TextTable
import java.io.File
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/** An empty [name] means the file gave the sheet none, and the screen numbers it instead. */
internal data class NamedSheet(val name: String, val table: TextTable)

internal data class Workbook(val sheets: List<NamedSheet>)

/** What every sheet of a workbook shares, so a hostile file can't get around it by having many sheets. */
internal class CellBudget(var cells: Int = MAX_CELLS, var chars: Long = MAX_CHARS) {
    val exhausted: Boolean get() = cells <= 0 || chars <= 0

    companion object {
        const val MAX_CELLS = 300_000
        const val MAX_CHARS = 16_000_000L
    }
}

/**
 * One sheet's cells, kept sparse until the end, because a file can name a cell a million rows down
 * with one line of XML. Only what's really there costs memory, and long gaps close up.
 */
internal class SheetBuilder(private val budget: CellBudget) {
    private val rows = TreeMap<Int, TreeMap<Int, String>>()
    var truncated = false

    fun put(row: Int, column: Int, value: String) {
        if (value.isEmpty()) return
        if (row < 0 || column < 0) return
        if (column >= MAX_COLUMNS || budget.exhausted || (rows.size >= MAX_ROWS && row !in rows)) {
            truncated = true
            return
        }
        val text = if (value.length > MAX_CELL_CHARS) value.take(MAX_CELL_CHARS) else value
        rows.getOrPut(row) { TreeMap() }[column] = text
        budget.cells--
        budget.chars -= text.length
    }

    fun build(): TextTable {
        val width = rows.values.maxOfOrNull { it.lastKey() + 1 } ?: 0
        val out = ArrayList<List<String>>(rows.size)
        var last = -1
        for ((index, cells) in rows) {
            // A gap of a few blank rows is part of how a sheet reads. A million of them isn't.
            val gap = (index - last - 1).coerceIn(0, MAX_BLANK_ROWS)
            repeat(gap) { out.add(emptyList()) }
            last = index
            val row = ArrayList<String>(width)
            for (column in 0..cells.lastKey()) row.add(cells[column].orEmpty())
            out.add(row)
        }
        return TextTable(out, truncated)
    }

    companion object {
        const val MAX_ROWS = 20_000
        const val MAX_COLUMNS = 128
        const val MAX_BLANK_ROWS = 20
        private const val MAX_CELL_CHARS = 2_000
    }
}

/** Numbers and dates the way a person expects to read them in a cell. */
internal object CellFormat {
    private val DateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val TimeOnly = DateTimeFormatter.ofPattern("HH:mm")

    /** Formats Excel has built in by number, which files never spell out. */
    private val BuiltInDateFormats = (14..22).toSet() + (27..36) + (45..47) + (50..58)

    fun number(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return value.toString()
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
        val size = Math.abs(value)
        if (size >= 1e15 || size < 1e-6) return value.toString()
        // Binary fractions show as 0.30000000000000004, and nobody typed that.
        return BigDecimal(value).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    fun number(text: String): String = text.toDoubleOrNull()?.let(::number) ?: text

    fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in BuiltInDateFormats) return true
        if (code == null) return false
        // What's in quotes or brackets is literal text, such as a currency or a color.
        val bare = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").replace(Regex("\\\\."), "").lowercase()
        if ("general" in bare || '0' in bare || '#' in bare) return false
        return bare.any { it == 'y' || it == 'm' || it == 'd' || it == 'h' || it == 's' }
    }

    /** A date counts days from an epoch. Excel's 1900 system thinks 1900 had a leap day, so it starts a day early. */
    fun date(serial: Double, date1904: Boolean = false): String {
        if (serial.isNaN() || serial.isInfinite() || serial < 0 || serial > MAX_SERIAL) return number(serial)
        val days = Math.floor(serial).toLong()
        val minutes = Math.round((serial - days) * 24 * 60)
        val epoch = if (date1904) LocalDate.of(1904, 1, 1) else LocalDate.of(1899, 12, 30)
        val moment = LocalDateTime.of(epoch.plusDays(days), java.time.LocalTime.MIDNIGHT).plusMinutes(minutes)
        return when {
            days == 0L && minutes != 0L -> moment.format(TimeOnly)
            minutes == 0L -> moment.format(DateOnly)
            else -> moment.format(DateTime)
        }
    }

    private const val MAX_SERIAL = 3_000_000.0
}

/** Excel's `.xlsx`, every sheet, read straight from its XML. Values and text are all a preview needs. */
internal object XlsxWorkbookReader {
    private const val MAX_SHARED_STRINGS = 300_000
    private const val MAX_SHEETS = 64

    fun read(file: File): Workbook = SafeZip(file).use { zip ->
        val shared = readSharedStrings(zip)
        val styles = readStyles(zip)
        val date1904 = usesDate1904(zip)
        val budget = CellBudget()
        val targets = OoxmlRelations.read(zip, "xl/_rels/workbook.xml.rels", "xl")
        val declared = ArrayList<Pair<String, String>>()
        zip.scan("xl/workbook.xml", maxBytes = 8L * 1024 * 1024) { scan ->
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> if (scan.name == "sheet" && declared.size < MAX_SHEETS) {
                        val part = scan.attribute("r:id")?.let(targets::get)
                        if (part != null) declared.add(scan.attribute("name").orEmpty() to part)
                    }
                    else -> Unit
                }
            }
        }
        // A workbook written by something minimal may list nothing, but its sheets are still there.
        val parts = declared.filter { zip.has(it.second) }.ifEmpty {
            OoxmlRelations.numbered(zip.names(), "xl/worksheets/sheet").map { part -> "" to part }
        }
        if (parts.isEmpty()) throw UnreadableDocumentException("no sheets")
        val sheets = parts.take(MAX_SHEETS).map { (name, part) ->
            val builder = SheetBuilder(budget)
            zip.scan(part) { scan -> readSheet(scan, shared, styles, date1904, builder) }
            if (zip.hitLimit) builder.truncated = true
            NamedSheet(name, builder.build())
        }
        Workbook(sheets)
    }

    private fun readSharedStrings(zip: SafeZip): List<String> {
        val strings = ArrayList<String>()
        zip.scan("xl/sharedStrings.xml") { scan ->
            val current = StringBuilder()
            var inText = false
            var inPhonetic = false
            while (strings.size < MAX_SHARED_STRINGS) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.name) {
                        "si" -> current.setLength(0)
                        "rPh" -> inPhonetic = true
                        "t" -> inText = !inPhonetic
                    }
                    XmlScan.Event.TEXT -> if (inText && current.length < 8192) current.append(scan.text)
                    XmlScan.Event.END -> when (scan.name) {
                        "t" -> inText = false
                        "rPh" -> inPhonetic = false
                        "si" -> strings.add(current.toString())
                    }
                }
            }
        }
        return strings
    }

    /** Which cell styles are dates: a date is only a number with a format, so a sheet can't say otherwise. */
    private fun readStyles(zip: SafeZip): List<Boolean> {
        val customFormats = HashMap<Int, String>()
        val dateStyles = ArrayList<Boolean>()
        zip.scan("xl/styles.xml", maxBytes = 8L * 1024 * 1024) { scan ->
            var inCellXfs = false
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.name) {
                        "numFmt" -> {
                            val id = scan.attribute("numFmtId")?.toIntOrNull()
                            val code = scan.attribute("formatCode")
                            if (id != null && code != null) customFormats[id] = code
                        }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs && dateStyles.size < 65_536) {
                            val id = scan.attribute("numFmtId")?.toIntOrNull() ?: 0
                            dateStyles.add(CellFormat.isDateFormat(id, customFormats[id]))
                        }
                    }
                    XmlScan.Event.END -> if (scan.name == "cellXfs") inCellXfs = false
                    else -> Unit
                }
            }
        }
        return dateStyles
    }

    private fun usesDate1904(zip: SafeZip): Boolean {
        return zip.scan("xl/workbook.xml", maxBytes = 8L * 1024 * 1024) { scan ->
            var found = false
            while (true) {
                val event = scan.next()
                if (event == XmlScan.Event.EOF) break
                if (event == XmlScan.Event.START && scan.name == "workbookPr") {
                    val value = scan.attribute("date1904")
                    found = value == "1" || value == "true"
                    break
                }
            }
            found
        } ?: false
    }

    private fun readSheet(scan: XmlScan, shared: List<String>, dateStyles: List<Boolean>, date1904: Boolean, sheet: SheetBuilder) {
        val value = StringBuilder()
        var rowNumber = -1
        var column = 0
        var cellType: String? = null
        var isDate = false
        var inValue = false
        var cellCount = 0
        while (true) {
            when (scan.next()) {
                XmlScan.Event.EOF -> return
                XmlScan.Event.START -> when (scan.name) {
                    "row" -> {
                        rowNumber = scan.attribute("r")?.toIntOrNull()?.minus(1) ?: (rowNumber + 1)
                        column = 0
                    }
                    "c" -> {
                        cellType = scan.attribute("t")
                        column = scan.attribute("r")?.let(::columnIndex) ?: column
                        isDate = scan.attribute("s")?.toIntOrNull()?.let(dateStyles::getOrNull) == true
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlScan.Event.TEXT -> if (inValue && value.length < 8192) value.append(scan.text)
                XmlScan.Event.END -> when (scan.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val text = value.toString()
                        val shown = when (cellType) {
                            "s" -> text.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                            "b" -> if (text == "1") "TRUE" else "FALSE"
                            "str", "inlineStr", "e" -> text
                            else -> if (isDate) text.toDoubleOrNull()?.let { CellFormat.date(it, date1904) } ?: text else CellFormat.number(text)
                        }
                        sheet.put(rowNumber, column, shown)
                        column++
                        cellCount++
                        // Nothing more can fit, so reading on would only cost time.
                        if (cellCount > CellBudget.MAX_CELLS * 4) return
                    }
                }
            }
        }
    }

    /** "C7" is column 2, counted from zero. Anything past what a sheet can hold just reads as far out. */
    fun columnIndex(reference: String): Int {
        var index = 0
        for (c in reference) {
            if (!c.isLetter()) break
            index = index * 26 + (c.uppercaseChar() - 'A' + 1)
            if (index > 1_000_000) return Int.MAX_VALUE
        }
        return (index - 1).coerceAtLeast(0)
    }
}
