package com.kobblo.gallery.file.explorer.tools.documents

import java.io.File

/** Word's `.docx`, read for its words. Layout stays with Word: a preview needs headings, lists and tables. */
internal object DocxReader {
    private const val MAX_SPANS = 2_000

    fun read(file: File): ReadableDocument = SafeZip(file).use { zip ->
        val styles = readStyles(zip)
        val numbering = readNumbering(zip)
        val builder = DocBuilder()
        zip.scan("word/document.xml") { scan -> readBody(scan, styles, numbering, builder) }
            ?: throw UnreadableDocumentException("no document part")
        if (zip.hitLimit) builder.truncated = true
        builder.build()
    }

    /** What a paragraph style means. A style can be a heading, or carry a list along with it. */
    private class StyleInfo(var heading: Int? = null, var listId: String? = null, var listDepth: Int = 0)

    /** Word localizes style ids, so `Überschrift1` is told apart by its name. */
    private fun readStyles(zip: SafeZip): Map<String, StyleInfo> {
        val out = HashMap<String, StyleInfo>()
        zip.scan("word/styles.xml", maxBytes = 8L * 1024 * 1024) { scan ->
            var current: StyleInfo? = null
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.name) {
                        "style" -> current = scan.attribute("styleId")?.takeIf { out.size < 65_536 }?.let { id ->
                            StyleInfo().also { out[id] = it }
                        }
                        "name" -> {
                            val styleName = scan.attribute("val")?.lowercase().orEmpty()
                            when {
                                styleName == "title" -> current?.heading = 1
                                styleName.startsWith("heading ") -> current?.heading = styleName.removePrefix("heading ").toIntOrNull()
                            }
                        }
                        "numId" -> current?.listId = scan.attribute("val")
                        "ilvl" -> scan.attribute("val")?.toIntOrNull()?.let { current?.listDepth = it }
                    }
                    XmlScan.Event.END -> if (scan.name == "style") current = null
                    else -> Unit
                }
            }
        }
        return out
    }

    /** Which lists count and which show bullets, level by level, so step 3 still reads as step 3. */
    private fun readNumbering(zip: SafeZip): Numbering {
        val abstractLevels = HashMap<String, HashMap<Int, String>>()
        val lists = HashMap<String, String>()
        zip.scan("word/numbering.xml", maxBytes = 8L * 1024 * 1024) { scan ->
            var abstractId: String? = null
            var listId: String? = null
            var level: Int? = null
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> when (scan.name) {
                        "abstractNum" -> abstractId = scan.attribute("abstractNumId")
                        "lvl" -> level = scan.attribute("ilvl")?.toIntOrNull()
                        "numFmt" -> {
                            val key = abstractId
                            val at = level
                            val format = scan.attribute("val")
                            if (key != null && at != null && format != null && abstractLevels.size < 4096) {
                                abstractLevels.getOrPut(key) { HashMap() }[at] = format
                            }
                        }
                        "num" -> listId = scan.attribute("numId")
                        "abstractNumId" -> {
                            val key = listId
                            val target = scan.attribute("val")
                            if (key != null && target != null && lists.size < 65_536) lists[key] = target
                        }
                    }
                    else -> Unit
                }
            }
        }
        return Numbering(lists.mapNotNull { (list, target) -> abstractLevels[target]?.let { list to it } }.toMap())
    }

    private fun readBody(scan: XmlScan, styles: Map<String, StyleInfo>, numbering: Numbering, builder: DocBuilder) {
        val paragraph = StringBuilder()
        val spans = ArrayList<InlineSpan>()
        val row = ArrayList<String>()
        val rows = ArrayList<List<String>>()
        val cell = StringBuilder()
        var inText = false
        var inParagraphProperties = false
        var bold = false
        var italic = false
        var styleLevel: Int? = null
        var listDepth: Int? = null
        var listId: String? = null
        var fallbackDepth = 0
        var tableDepth = 0

        fun flushParagraph() {
            val text = paragraph.toString()
            when {
                tableDepth > 0 -> {
                    if (text.isNotBlank() && cell.length < DocLimits.MAX_CELL_CHARS) {
                        if (cell.isNotEmpty()) cell.append('\n')
                        cell.append(text.trim().take(DocLimits.MAX_CELL_CHARS))
                    }
                }
                styleLevel != null -> builder.heading(styleLevel!!, text)
                listDepth != null -> if (text.isNotBlank()) {
                    builder.bullet(text, listDepth!!, numbering.marker(listId, listDepth!!), spans.toList())
                }
                else -> builder.paragraph(text, spans.toList())
            }
            paragraph.setLength(0)
            spans.clear()
            styleLevel = null
            listDepth = null
            listId = null
        }

        while (!builder.isFull) {
            when (scan.next()) {
                XmlScan.Event.EOF -> break
                XmlScan.Event.START -> {
                    // Word writes a drawing twice, the second time for old readers.
                    if (scan.name == "Fallback") fallbackDepth++
                    if (fallbackDepth > 0) continue
                    when (scan.name) {
                        "p" -> if (paragraph.isNotEmpty()) flushParagraph()
                        "pPr" -> inParagraphProperties = true
                        "pStyle" -> scan.attribute("val")?.let { id ->
                            val style = styles[id]
                            styleLevel = style?.heading ?: builtInHeading(id)
                            // The paragraph's own settings come after this, so they still win.
                            style?.listId?.let {
                                listId = it
                                listDepth = style.listDepth
                            }
                        }
                        "ilvl" -> listDepth = scan.attribute("val")?.toIntOrNull() ?: 0
                        "numPr" -> if (listDepth == null) listDepth = 0
                        "numId" -> listId = scan.attribute("val")
                        "r" -> {
                            bold = false
                            italic = false
                        }
                        "b" -> bold = isOn(scan.attribute("val"))
                        "i" -> italic = isOn(scan.attribute("val"))
                        "t" -> inText = true
                        "tab" -> if (!inParagraphProperties) paragraph.append('\t')
                        "br", "cr" -> paragraph.append('\n')
                        "noBreakHyphen" -> paragraph.append('-')
                        "tbl" -> tableDepth++
                        "tc" -> if (tableDepth == 1) cell.setLength(0)
                    }
                }
                XmlScan.Event.TEXT -> if (inText && fallbackDepth == 0) {
                    val start = paragraph.length
                    paragraph.append(scan.text)
                    if (spans.size < MAX_SPANS) {
                        if (bold) spans.add(InlineSpan(start, paragraph.length, InlineSpan.Style.BOLD))
                        if (italic) spans.add(InlineSpan(start, paragraph.length, InlineSpan.Style.ITALIC))
                    }
                    if (builder.wontFit(paragraph.length)) flushParagraph()
                }
                XmlScan.Event.END -> {
                    if (scan.name == "Fallback") {
                        fallbackDepth = (fallbackDepth - 1).coerceAtLeast(0)
                        continue
                    }
                    if (fallbackDepth > 0) continue
                    when (scan.name) {
                        "t" -> inText = false
                        "pPr" -> inParagraphProperties = false
                        "p" -> flushParagraph()
                        "tc" -> if (tableDepth == 1) {
                            if (paragraph.isNotEmpty()) flushParagraph()
                            row.add(cell.toString())
                            cell.setLength(0)
                        }
                        "tr" -> if (tableDepth == 1) {
                            if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(row.toList()) else builder.truncated = true
                            row.clear()
                        }
                        "tbl" -> {
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
        if (paragraph.isNotEmpty()) flushParagraph()
        if (rows.isNotEmpty()) builder.table(rows.toList())
    }

    private fun builtInHeading(id: String): Int? {
        val lower = id.lowercase()
        return when {
            lower == "title" -> 1
            lower.startsWith("heading") -> lower.removePrefix("heading").toIntOrNull() ?: 1
            else -> null
        }
    }

    private fun isOn(value: String?) = value == null || (value != "0" && value != "false" && value != "none")

    /** Counts a document's lists as it's read. Anything it doesn't recognize shows as a bullet. */
    internal class Numbering(private val formats: Map<String, Map<Int, String>>) {
        private val counters = HashMap<String, IntArray>()

        fun marker(list: String?, level: Int): String {
            val key = list ?: return BULLET
            val format = formats[key]?.get(level) ?: return BULLET
            if (format !in Counted) return BULLET
            val counts = counters.getOrPut(key) { IntArray(LEVELS) }
            val at = level.coerceIn(0, LEVELS - 1)
            counts[at]++
            for (deeper in at + 1 until LEVELS) counts[deeper] = 0
            val count = counts[at]
            return when (format) {
                "lowerLetter" -> letter(count).lowercase()
                "upperLetter" -> letter(count)
                "lowerRoman" -> roman(count).lowercase()
                "upperRoman" -> roman(count)
                else -> count.toString()
            } + "."
        }

        private fun letter(count: Int): String {
            val index = (count - 1) % 26
            return ('A' + index).toString().repeat((count - 1) / 26 + 1)
        }

        private fun roman(count: Int): String {
            if (count !in 1..3999) return count.toString()
            val out = StringBuilder()
            var left = count
            for ((value, mark) in Numerals) {
                while (left >= value) {
                    out.append(mark)
                    left -= value
                }
            }
            return out.toString()
        }

        private companion object {
            const val BULLET = "•"
            const val LEVELS = 10
            val Counted = setOf("decimal", "decimalZero", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman")
            val Numerals = listOf(
                1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
                50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I"
            )
        }
    }
}

/** PowerPoint's `.pptx`, as the words on each slide, in the order the deck shows them. */
internal object PptxReader {
    fun read(file: File): ReadableDocument = SafeZip(file).use { zip ->
        val slides = slidesInOrder(zip)
        if (slides.isEmpty()) throw UnreadableDocumentException("no slides")
        val builder = DocBuilder()
        for ((index, part) in slides.withIndex()) {
            if (builder.isFull) {
                builder.truncated = true
                break
            }
            builder.slide(index + 1)
            zip.scan(part) { scan -> readSlide(scan, builder) }
        }
        if (zip.hitLimit) builder.truncated = true
        builder.build(paged = true)
    }

    /** The deck's own order. Part names say nothing about it: slide 12 can come first. */
    private fun slidesInOrder(zip: SafeZip): List<String> {
        val targets = OoxmlRelations.read(zip, "ppt/_rels/presentation.xml.rels", "ppt")
        val ordered = ArrayList<String>()
        zip.scan("ppt/presentation.xml") { scan ->
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> if (scan.name == "sldId") {
                        scan.attribute("r:id")?.let(targets::get)?.let(ordered::add)
                    }
                    else -> Unit
                }
            }
        }
        if (ordered.isNotEmpty()) return ordered.filter(zip::has)
        return OoxmlRelations.numbered(zip.names(), "ppt/slides/slide")
    }

    private fun readSlide(scan: XmlScan, builder: DocBuilder) {
        val paragraph = StringBuilder()
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var inText = false
        var isTitle = false
        var level = 0
        var inCell = false
        while (!builder.isFull) {
            when (scan.next()) {
                XmlScan.Event.EOF -> return
                XmlScan.Event.START -> when (scan.name) {
                    "sp" -> isTitle = false
                    "ph" -> {
                        val type = scan.attribute("type")
                        isTitle = type == "title" || type == "ctrTitle"
                    }
                    "p" -> {
                        paragraph.setLength(0)
                        level = 0
                    }
                    "pPr" -> level = scan.attribute("lvl")?.toIntOrNull() ?: 0
                    "t" -> inText = true
                    "br" -> paragraph.append('\n')
                    "tc" -> {
                        inCell = true
                        cell.setLength(0)
                    }
                }
                XmlScan.Event.TEXT -> if (inText) {
                    paragraph.append(scan.text)
                    if (builder.wontFit(paragraph.length)) {
                        builder.paragraph(paragraph.toString())
                        paragraph.setLength(0)
                    }
                }
                XmlScan.Event.END -> when (scan.name) {
                    "t" -> inText = false
                    "p" -> {
                        val text = paragraph.toString()
                        when {
                            inCell -> {
                                if (text.isNotBlank() && cell.length < DocLimits.MAX_CELL_CHARS) {
                                    if (cell.isNotEmpty()) cell.append('\n')
                                    cell.append(text.trim().take(DocLimits.MAX_CELL_CHARS))
                                }
                            }
                            isTitle -> builder.heading(2, text)
                            level > 0 -> builder.bullet(text, level - 1)
                            else -> builder.paragraph(text)
                        }
                    }
                    "tc" -> {
                        inCell = false
                        row.add(cell.toString())
                    }
                    "tr" -> {
                        if (rows.size <= DocLimits.MAX_TABLE_ROWS) rows.add(row.toList())
                        row.clear()
                    }
                    "tbl" -> {
                        builder.table(rows.toList())
                        rows.clear()
                    }
                }
            }
        }
    }
}

/** How the parts of an Office file point at each other. */
internal object OoxmlRelations {
    /** A relationship id and the part it leads to, worked out from [baseDir]. */
    fun read(zip: SafeZip, relsPart: String, baseDir: String): Map<String, String> {
        val out = HashMap<String, String>()
        zip.scan(relsPart, maxBytes = 8L * 1024 * 1024) { scan ->
            while (true) {
                when (scan.next()) {
                    XmlScan.Event.EOF -> break
                    XmlScan.Event.START -> if (scan.name == "Relationship") {
                        val id = scan.attribute("Id")
                        val target = scan.attribute("Target")
                        if (id != null && target != null) out[id] = SafeZip.resolve(baseDir, target)
                    }
                    else -> Unit
                }
            }
        }
        return out
    }

    /** Parts such as `slide3.xml`, by number rather than by name, so slide 10 follows slide 9. */
    fun numbered(names: List<String>, prefix: String): List<String> =
        names.filter { it.startsWith(prefix) && it.endsWith(".xml") }
            .sortedBy { it.removePrefix(prefix).removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
}
