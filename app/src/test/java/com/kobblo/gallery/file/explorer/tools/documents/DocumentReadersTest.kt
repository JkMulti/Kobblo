package com.kobblo.gallery.file.explorer.tools.documents

import com.kobblo.gallery.file.explorer.tools.files.FileSniffer
import com.kobblo.gallery.file.explorer.tools.files.FontInfo
import com.kobblo.gallery.file.explorer.tools.files.WoffFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Files written by real tools, so these tests can't simply agree with how the parsers were written. */
private fun fixture(name: String, folder: TemporaryFolder): File {
    val out = folder.newFile(name)
    val stream = requireNotNull(DocumentFixtures::class.java.getResourceAsStream("/documents/$name")) { "missing fixture $name" }
    stream.use { input -> out.outputStream().use { input.copyTo(it) } }
    return out
}

private object DocumentFixtures

private fun allText(doc: ReadableDocument): String = doc.sections.flatMap { it.blocks }.joinToString("\n") { block ->
    when (block) {
        is DocBlock.Heading -> block.text
        is DocBlock.Paragraph -> block.text
        is DocBlock.Bullet -> block.text
        is DocBlock.Quote -> block.text
        is DocBlock.Code -> block.text
        is DocBlock.Table -> block.rows.joinToString("\n") { it.joinToString(" | ") }
        DocBlock.Rule -> "---"
    }
}

private fun TemporaryFolder.zip(name: String, parts: List<Pair<String, ByteArray>>): File {
    val file = newFile(name)
    ZipOutputStream(file.outputStream()).use { zip ->
        parts.forEach { (entry, bytes) ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return file
}

private fun TemporaryFolder.textZip(name: String, vararg parts: Pair<String, String>): File =
    zip(name, parts.map { it.first to it.second.toByteArray() })

class XmlScanTest {
    private fun events(xml: String, html: Boolean = false): List<String> {
        val scan = XmlScan(StringReader(xml), html)
        val out = ArrayList<String>()
        while (true) {
            when (scan.next()) {
                XmlScan.Event.EOF -> return out
                XmlScan.Event.START -> out.add("<${scan.name}")
                XmlScan.Event.END -> out.add(">${scan.name}")
                XmlScan.Event.TEXT -> out.add(scan.text)
            }
        }
    }

    @Test fun `reads tags attributes and text`() {
        val scan = XmlScan(StringReader("""<w:p w:val='1' x="a&amp;b">hi</w:p>"""))
        assertEquals(XmlScan.Event.START, scan.next())
        assertEquals("p", scan.name)
        assertEquals("w:p", scan.fullName)
        assertEquals("1", scan.attribute("val"))
        assertEquals("a&b", scan.attribute("x"))
        assertEquals(XmlScan.Event.TEXT, scan.next())
        assertEquals("hi", scan.text)
        assertEquals(XmlScan.Event.END, scan.next())
        assertEquals(XmlScan.Event.EOF, scan.next())
    }

    @Test fun `a self closed tag still ends`() {
        assertEquals(listOf("<a", "<b", ">b", ">a"), events("<a><b/></a>"))
    }

    @Test fun `comments and declarations leave nothing behind`() {
        assertEquals(listOf("<a", "x", ">a"), events("""<?xml version="1.0"?><!-- <fake> --><a>x</a>"""))
    }

    @Test fun `cdata is literal`() {
        assertEquals(listOf("<a", "<b>&amp;", ">a"), events("<a><![CDATA[<b>&amp;]]></a>"))
    }

    @Test fun `entities defined by the file are never expanded`() {
        val laughs = """<!DOCTYPE lolz [<!ENTITY a "lol"><!ENTITY b "&a;&a;&a;"><!ENTITY x SYSTEM "file:///etc/hosts">]><r>&b; &x;</r>"""
        assertEquals(listOf("<r", "&b; &x;", ">r"), events(laughs))
    }

    @Test fun `numeric entities decode and bad ones stay as text`() {
        assertEquals("A日", XmlScan.decodeEntities("&#65;&#x65E5;"))
        assertEquals("&#0;&#xD800;&#x110000;", XmlScan.decodeEntities("&#0;&#xD800;&#x110000;"))
        assertEquals("& AT&T &nope;", XmlScan.decodeEntities("& AT&T &nope;"))
    }

    @Test fun `html entities only count in html`() {
        assertEquals("&eacute;", XmlScan.decodeEntities("&eacute;"))
        assertEquals("é", XmlScan.decodeEntities("&eacute;", html = true))
    }

    @Test fun `depth costs nothing`() {
        val deep = "<a>".repeat(500_000) + "x" + "</a>".repeat(500_000)
        val scan = XmlScan(StringReader(deep))
        var count = 0
        while (scan.next() != XmlScan.Event.EOF) count++
        assertEquals(1_000_001, count)
    }

    @Test fun `a huge run of text comes out in pieces`() {
        val scan = XmlScan(StringReader("<a>" + "x".repeat(XmlScan.MAX_TEXT_RUN * 3 + 5) + "</a>"))
        scan.next()
        var pieces = 0
        var total = 0
        while (scan.next() == XmlScan.Event.TEXT) {
            assertTrue(scan.text.length <= XmlScan.MAX_TEXT_RUN)
            total += scan.text.length
            pieces++
        }
        assertEquals(4, pieces)
        assertEquals(XmlScan.MAX_TEXT_RUN * 3 + 5, total)
    }

    @Test fun `a huge cdata comes out in pieces and stays literal`() {
        val scan = XmlScan(StringReader("<a><![CDATA[" + "<x>".repeat(XmlScan.MAX_TEXT_RUN) + "]]></a>"))
        scan.next()
        var total = 0
        while (scan.next() == XmlScan.Event.TEXT) total += scan.text.length
        assertEquals(XmlScan.MAX_TEXT_RUN * 3, total)
    }

    @Test fun `garbage ends instead of hanging`() {
        val random = Random(7)
        repeat(200) {
            val text = buildString { repeat(600) { append("<>/!?-[]=\"'&; a\n"[random.nextInt(16)]) } }
            val scan = XmlScan(StringReader(text), html = it % 2 == 0)
            var steps = 0
            while (scan.next() != XmlScan.Event.EOF) {
                steps++
                assertTrue("never ended", steps < 2_000)
            }
        }
    }

    @Test fun `an unterminated tag ends the scan`() {
        assertEquals(listOf("<a"), events("<a href=\"never closes"))
    }

    @Test fun `a stray less than in a page is text`() {
        assertEquals(listOf("<p", "1 < 2", ">p"), events("<p>1 < 2</p>", html = true))
    }

    @Test fun `script bodies are skipped whatever they hold`() {
        val scan = XmlScan(StringReader("<script>if (a<b) { '</div>' }</SCRIPT><p>after</p>"), html = true)
        scan.next()
        scan.skipRawUntilEndOf("script")
        assertEquals(XmlScan.Event.START, scan.next())
        assertEquals("p", scan.name)
    }

    @Test fun `declared encoding and byte order marks are honored`() {
        val latin = """<?xml version="1.0" encoding="ISO-8859-1"?><a>caf""".toByteArray() + 0xE9.toByte() + "</a>".toByteArray()
        val scan = XmlScan(XmlScan.readerFor(latin.inputStream()))
        scan.next()
        scan.next()
        assertEquals("café", scan.text)
        val wide = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>é</a>".toByteArray(Charsets.UTF_16LE)
        val wideScan = XmlScan(XmlScan.readerFor(wide.inputStream()))
        wideScan.next()
        wideScan.next()
        assertEquals("é", wideScan.text)
    }
}

class LimitsTest {
    @Test fun `a limited stream stops and says so`() {
        val limited = LimitedInputStream(ByteArray(100).inputStream(), 10)
        assertEquals(10, limited.readBytes().size)
        assertTrue(limited.hitLimit)
    }

    @Test fun `a stream under its limit reports nothing`() {
        val limited = LimitedInputStream(ByteArray(5).inputStream(), 10)
        assertEquals(5, limited.readBytes().size)
        assertFalse(limited.hitLimit)
    }

    @Test fun `paths inside a container can't climb out`() {
        assertEquals("ppt/slides/slide1.xml", SafeZip.resolve("ppt", "slides/slide1.xml"))
        assertEquals("ppt/slide.xml", SafeZip.resolve("ppt/slides", "../slide.xml"))
        assertEquals("etc/hosts", SafeZip.resolve("ppt", "../../../etc/hosts"))
        assertEquals("xl/sheet.xml", SafeZip.resolve("ignored", "/xl/sheet.xml"))
        assertEquals("a.xhtml", SafeZip.resolve("", "a.xhtml#part2"))
    }

    @Test fun `a very long paragraph is laid out in pieces`() {
        val builder = DocBuilder()
        builder.paragraph("word ".repeat(5_000))
        val blocks = builder.build().sections.single().blocks
        assertTrue(blocks.size >= 6)
        blocks.forEach { assertTrue((it as DocBlock.Paragraph).text.length <= DocLimits.MAX_PARAGRAPH_CHARS) }
    }

    @Test fun `styles follow their words into the pieces`() {
        val builder = DocBuilder()
        val text = "  " + "a".repeat(DocLimits.MAX_PARAGRAPH_CHARS) + "bold"
        builder.paragraph(text, listOf(InlineSpan(text.length - 4, text.length, InlineSpan.Style.BOLD)))
        val pieces = builder.build().sections.single().blocks.map { it as DocBlock.Paragraph }
        assertEquals(2, pieces.size)
        assertEquals(0, pieces[0].spans.size)
        assertEquals("bold", pieces[1].text.substring(pieces[1].spans.single().start, pieces[1].spans.single().end))
    }

    @Test(timeout = 5_000) fun `a huge paragraph with no spaces costs no more than its limit`() {
        val builder = DocBuilder()
        builder.paragraph("x".repeat(40_000_000))
        val doc = builder.build()
        assertTrue(doc.truncated)
        assertEquals(DocLimits.MAX_TEXT_CHARS, doc.sections.single().blocks.sumOf { (it as DocBlock.Paragraph).text.length })
    }

    @Test fun `a stream read by something nobody's waiting for stops`() {
        val limited = LimitedInputStream(ByteArray(1024).inputStream(), 1024)
        Thread.currentThread().interrupt()
        try {
            assertThrows(java.io.InterruptedIOException::class.java) { limited.read() }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun `the builder stops at its limit and says it did`() {
        val builder = DocBuilder()
        var added = 0
        while (!builder.isFull && added < 1_000_000) {
            builder.paragraph("x".repeat(1000))
            added++
        }
        assertTrue(builder.isFull)
        builder.paragraph("one more")
        assertTrue(builder.build().truncated)
    }

    @Test fun `tables keep a limited number of rows and count the rest`() {
        val builder = DocBuilder()
        builder.table(List(DocLimits.MAX_TABLE_ROWS + 7) { listOf("cell") })
        val table = builder.build().sections.single().blocks.single() as DocBlock.Table
        assertEquals(DocLimits.MAX_TABLE_ROWS, table.rows.size)
        assertEquals(7, table.more)
    }
}

class WordTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real docx`() {
        val doc = DocxReader.read(fixture("sample.docx", folder))
        val text = allText(doc)
        assertTrue(text, "Quarterly notes" in text)
        assertTrue(text, "Café prices" in text)
        assertTrue(text, "First bullet" in text)
        assertTrue(text, "Alice" in text)
        assertTrue(text, "Last paragraph of the document." in text)
        assertFalse(doc.truncated)
    }

    @Test fun `reads a docx with real styles, lists and a table`() {
        val blocks = DocxReader.read(fixture("styled.docx", folder)).sections.single().blocks
        assertEquals(DocBlock.Heading(1, "Project Report"), blocks[0])
        assertEquals(DocBlock.Heading(1, "Summary"), blocks[1])
        val para = blocks[2] as DocBlock.Paragraph
        assertEquals("Normal text with bold and italic words.", para.text)
        assertEquals(setOf(InlineSpan.Style.BOLD, InlineSpan.Style.ITALIC), para.spans.map { it.style }.toSet())
        assertEquals("1.", (blocks[3] as DocBlock.Bullet).marker)
        assertEquals("2.", (blocks[4] as DocBlock.Bullet).marker)
        assertEquals("•", (blocks[5] as DocBlock.Bullet).marker)
        assertEquals(DocBlock.Heading(2, "Data"), blocks[6])
        assertEquals(listOf(listOf("Name", "Qty", "Cost"), listOf("Bolt", "12", "3.50")), (blocks[7] as DocBlock.Table).rows)
        assertEquals("Closing paragraph.", (blocks[8] as DocBlock.Paragraph).text)
    }

    @Test fun `headings lists tables and styles come through`() {
        val file = folder.textZip(
            "a.docx",
            "word/styles.xml" to """<w:styles xmlns:w="w"><w:style w:styleId="Ueberschrift1"><w:name w:val="heading 1"/></w:style></w:styles>""",
            "word/document.xml" to """<w:document xmlns:w="w"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Ueberschrift1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>
                <w:p><w:pPr><w:numPr><w:ilvl w:val="2"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t> plain</w:t></w:r><w:r><w:tab/><w:t>x</w:t></w:r></w:p>
                <w:p><w:r><w:delText>gone</w:delText><w:instrText>HYPERLINK</w:instrText><w:t>kept</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                <mc:AlternateContent><mc:Choice><w:p><w:r><w:t>Once</w:t></w:r></w:p></mc:Choice><mc:Fallback><w:p><w:r><w:t>Twice</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
            </w:body></w:document>"""
        )
        val blocks = DocxReader.read(file).sections.single().blocks
        assertEquals(DocBlock.Heading(1, "Title"), blocks[0])
        assertEquals("Item", (blocks[1] as DocBlock.Bullet).text)
        assertEquals(2, (blocks[1] as DocBlock.Bullet).depth)
        val styled = blocks[2] as DocBlock.Paragraph
        assertEquals("Bold plain\tx", styled.text)
        assertEquals(InlineSpan(0, 4, InlineSpan.Style.BOLD), styled.spans.single())
        assertEquals("kept", (blocks[3] as DocBlock.Paragraph).text)
        assertEquals(listOf(listOf("A\nB", "C")), (blocks[4] as DocBlock.Table).rows)
        val text = allText(DocxReader.read(file))
        assertTrue(text, "Once" in text)
        assertFalse(text, "Twice" in text)
    }

    @Test fun `lists count the way the document says`() {
        val file = folder.textZip(
            "n.docx",
            "word/numbering.xml" to """<w:numbering>
                <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl><w:lvl w:ilvl="1"><w:numFmt w:val="lowerLetter"/></w:lvl></w:abstractNum>
                <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
                <w:num w:numId="5"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="6"><w:abstractNumId w:val="1"/></w:num>
            </w:numbering>""",
            "word/document.xml" to """<w:document><w:body>${
                listOf(5 to 0, 5 to 1, 5 to 1, 5 to 0, 5 to 1, 6 to 0, 99 to 0).joinToString("") { (list, level) ->
                    """<w:p><w:pPr><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$list"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
                }
            }</w:body></w:document>"""
        )
        val markers = DocxReader.read(file).sections.single().blocks.map { (it as DocBlock.Bullet).marker }
        assertEquals(listOf("1.", "a.", "b.", "2.", "a.", "•", "•"), markers)
    }

    @Test fun `counters read in every style`() {
        val numbering = DocxReader.Numbering(mapOf("r" to mapOf(0 to "upperRoman"), "l" to mapOf(0 to "lowerLetter")))
        assertEquals(listOf("I.", "II.", "III.", "IV."), List(4) { numbering.marker("r", 0) })
        repeat(25) { numbering.marker("l", 0) }
        assertEquals("z.", numbering.marker("l", 0))
        assertEquals("aa.", numbering.marker("l", 0))
        assertEquals("•", numbering.marker(null, 0))
    }

    @Test(timeout = 15_000) fun `a zip bomb stops at the limit`() {
        val file = folder.newFile("bomb.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:body><w:p><w:r><w:t>".toByteArray())
            val chunk = ByteArray(1024 * 1024) { 'A'.code.toByte() }
            repeat(256) { zip.write(chunk) }
            zip.write("</w:t></w:r></w:p></w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
        assertTrue(file.length() < 2 * 1024 * 1024)
        val doc = DocxReader.read(file)
        assertTrue(doc.truncated)
        val chars = doc.sections.single().blocks.sumOf { (it as DocBlock.Paragraph).text.length }
        assertTrue("kept $chars", chars <= DocLimits.MAX_TEXT_CHARS)
        assertTrue("kept $chars", chars >= DocLimits.MAX_TEXT_CHARS / 2)
    }

    @Test fun `entities in a document stay harmless`() {
        val file = folder.textZip(
            "e.docx",
            "word/document.xml" to """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY a "lol"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;"><!ENTITY x SYSTEM "file:///etc/hosts">]>
                <w:document><w:body><w:p><w:r><w:t>Before &b; &x; after</w:t></w:r></w:p></w:body></w:document>"""
        )
        assertEquals("Before &b; &x; after", allText(DocxReader.read(file)))
    }

    @Test fun `a zip with no document part fails with the normal error`() {
        val file = folder.textZip("x.docx", "other.txt" to "hello")
        assertThrows(UnreadableDocumentException::class.java) { DocxReader.read(file) }
    }

    @Test fun `garbage named docx fails with an exception`() {
        val file = folder.newFile("g.docx")
        file.writeBytes(ByteArray(3000).also { Random(1).nextBytes(it) })
        assertThrows(Exception::class.java) { DocxReader.read(file) }
    }

    @Test fun `reads a real binary doc`() {
        val doc = CompoundFile.open(fixture("sample.doc", folder)).use(WordBinaryReader::read)
        val text = allText(doc)
        assertTrue(text, "Quarterly notes" in text)
        assertTrue(text, "Café prices" in text)
        assertTrue(text, "First bullet" in text)
        assertTrue(text, "Alice" in text)
        assertTrue(text, "Last paragraph of the document." in text)
    }

    @Test fun `control characters in a doc become structure`() {
        val builder = DocBuilder()
        WordBinaryReader.addText("One\rTwo\u000BLine\r\u0013 HYPERLINK x \u0014shown\u0015 end\rCell\u0007More\u0007\u0007Tail\r", builder)
        val blocks = builder.build().sections.single().blocks
        assertEquals("One", (blocks[0] as DocBlock.Paragraph).text)
        assertEquals("Two\nLine", (blocks[1] as DocBlock.Paragraph).text)
        assertEquals("shown end", (blocks[2] as DocBlock.Paragraph).text)
        assertEquals(listOf(listOf("Cell", "More")), (blocks[3] as DocBlock.Table).rows)
        assertEquals("Tail", (blocks[4] as DocBlock.Paragraph).text)
    }
}

class SlidesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real pptx slide by slide`() {
        val doc = PptxReader.read(fixture("sample.pptx", folder))
        assertTrue(doc.paged)
        assertEquals(3, doc.sections.size)
        assertEquals(listOf(1, 2, 3), doc.sections.map { it.slide })
        val first = doc.sections[0].blocks
        assertEquals(DocBlock.Heading(2, "Launch plan"), first[0])
        assertEquals("Subtitle of the first slide", (first[1] as DocBlock.Paragraph).text)
        val nested = doc.sections[1].blocks.last() as DocBlock.Bullet
        assertEquals("A nested point", nested.text)
        val table = doc.sections[2].blocks.last() as DocBlock.Table
        assertEquals(listOf(listOf("Key", "Value"), listOf("Colour", "Blue")), table.rows)
    }

    @Test fun `follows the deck's order rather than part names`() {
        val file = folder.textZip(
            "o.pptx",
            "ppt/presentation.xml" to """<p:presentation xmlns:r="r"><p:sldIdLst><p:sldId r:id="b"/><p:sldId r:id="a"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="a" Target="slides/slide1.xml"/><Relationship Id="b" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>one</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>two</a:t></a:r></a:p></p:sld>"
        )
        val doc = PptxReader.read(file)
        assertEquals("two", (doc.sections[0].blocks.single() as DocBlock.Paragraph).text)
        assertEquals("one", (doc.sections[1].blocks.single() as DocBlock.Paragraph).text)
    }

    @Test fun `numbers parts sensibly when the deck lists none`() {
        val parts = listOf("ppt/slides/slide10.xml", "ppt/slides/slide2.xml", "ppt/slides/slide1.xml", "ppt/slides/_rels/slide1.xml.rels")
        assertEquals(
            listOf("ppt/slides/slide1.xml", "ppt/slides/slide2.xml", "ppt/slides/slide10.xml"),
            OoxmlRelations.numbered(parts, "ppt/slides/slide")
        )
    }

    @Test fun `a deck with no slides fails with the normal error`() {
        val file = folder.textZip("x.pptx", "ppt/presentation.xml" to "<p:presentation/>")
        assertThrows(UnreadableDocumentException::class.java) { PptxReader.read(file) }
    }

    @Test fun `reads a binary ppt built to the published format`() {
        val doc = CompoundFile.open(fixture("sample.ppt", folder)).use(PowerPointBinaryReader::read)
        assertTrue(doc.paged)
        assertEquals(2, doc.sections.size)
        val first = allText(ReadableDocument(listOf(doc.sections[0])))
        assertTrue(first, "Binary deck title" in first)
        assertTrue(first, "Written byte by byte" in first)
        assertTrue(first, "Second line" in first)
        assertTrue(first, "Loose text box on the first slide" in first)
        val second = allText(ReadableDocument(listOf(doc.sections[1])))
        assertTrue(second, "Café 日本 slide two" in second)
        assertFalse(allText(doc), "Speaker notes" in allText(doc))
    }

    @Test fun `an edit chain shows the current slide, not the old copy`() {
        // Slide 7 exists twice in the stream. Only the newer copy is in the current directory.
        val slideOld = record(15, 0x03EE, record(0, 0x0FA0, "old words".toByteArray(Charsets.UTF_16LE)))
        val slideNew = record(15, 0x03EE, record(0, 0x0FA0, "new words".toByteArray(Charsets.UTF_16LE)))
        val persist = record(0, 0x03F3, intBytes(7) + ByteArray(16))
        val list = record(15, 0x0FF0, persist)
        val document = record(15, 0x03E8, list)
        val stream = ByteArrayOutputStream()
        stream.write(slideOld)
        val documentAt = stream.size()
        stream.write(document)
        val newSlideAt = stream.size()
        stream.write(slideNew)
        val directoryAt = stream.size()
        val entries = intBytes(1 or (1 shl 20)) + intBytes(documentAt) + intBytes(7 or (1 shl 20)) + intBytes(newSlideAt)
        stream.write(record(0, 0x1772, entries))
        val editAt = stream.size()
        stream.write(record(0, 0x0FF5, intBytes(0) + ByteArray(4) + intBytes(0) + intBytes(directoryAt) + intBytes(1) + ByteArray(8)))
        val currentUser = record(0, 0x0FF6, intBytes(20) + intBytes(0xE391C05F.toInt()) + intBytes(editAt) + ByteArray(8))
        val file = folder.newFile("edited.ppt")
        file.writeBytes(CompoundBuilder.build(listOf("PowerPoint Document" to stream.toByteArray(), "Current User" to currentUser)))
        val doc = CompoundFile.open(file).use(PowerPointBinaryReader::read)
        val text = allText(doc)
        assertTrue(text, "new words" in text)
        assertFalse(text, "old words" in text)
    }

    @Test fun `an encrypted deck says so`() {
        val currentUser = record(0, 0x0FF6, intBytes(20) + intBytes(0xF3D1C4DF.toInt()) + intBytes(0) + ByteArray(8))
        val file = folder.newFile("locked.ppt")
        file.writeBytes(CompoundBuilder.build(listOf("PowerPoint Document" to ByteArray(64), "Current User" to currentUser)))
        assertThrows(ProtectedDocumentException::class.java) { CompoundFile.open(file).use(PowerPointBinaryReader::read) }
    }

    @Test fun `records that lie about their size stop the walk`() {
        val lying = byteArrayOf(0x0F, 0, 0xEE.toByte(), 0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F) + ByteArray(32)
        val file = folder.newFile("lying.ppt")
        file.writeBytes(CompoundBuilder.build(listOf("PowerPoint Document" to lying)))
        assertThrows(UnreadableDocumentException::class.java) { CompoundFile.open(file).use(PowerPointBinaryReader::read) }
    }

    private fun intBytes(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun record(version: Int, type: Int, body: ByteArray): ByteArray =
        byteArrayOf(version.toByte(), 0, type.toByte(), (type shr 8).toByte()) + intBytes(body.size) + body
}

class SpreadsheetTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real xlsx with every sheet`() {
        val book = XlsxWorkbookReader.read(fixture("sample.xlsx", folder))
        assertEquals(listOf("Budget", "Summary"), book.sheets.map { it.name })
        val rows = book.sheets[0].table.rows
        assertEquals(listOf("Item", "Cost", "When", "Paid"), rows[0])
        assertEquals(listOf("Rent", "1200.5", "2026-01-31", "TRUE"), rows[1])
        assertEquals(listOf("Café 日本", "42", "2026-02-03 09:30", "FALSE"), rows[2])
        assertEquals(emptyList<String>(), rows[3])
        assertEquals("Gap above", rows[4][0])
        assertEquals(listOf("1", "2", "3"), book.sheets[1].table.rows[1])
    }

    @Test fun `a cell a million rows down costs nothing`() {
        val file = folder.textZip(
            "far.xlsx",
            "xl/workbook.xml" to """<workbook xmlns:r="r"><sheets><sheet name="F" r:id="s"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="s" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c></row><row r="1048576"><c r="XFD1048576"><v>2</v></c><c r="B1048576"><v>3</v></c></row></sheetData></worksheet>"""
        )
        val table = XlsxWorkbookReader.read(file).sheets.single().table
        assertTrue("rows ${table.rows.size}", table.rows.size <= SheetBuilder.MAX_BLANK_ROWS + 2)
        assertTrue(table.columnCount <= SheetBuilder.MAX_COLUMNS)
        assertTrue(table.truncated)
    }

    @Test fun `a bomb of shared strings stops at the limit`() {
        val file = folder.newFile("bomb.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write("""<workbook xmlns:r="r"><sheets><sheet name="B" r:id="s"/></sheets></workbook>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write("""<Relationships><Relationship Id="s" Target="worksheets/sheet1.xml"/></Relationships>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val one = "<si><t>x</t></si>".repeat(60_000).toByteArray()
            repeat(200) { zip.write(one) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write("""<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>99999999</v></c></row></sheetData></worksheet>""".toByteArray())
            zip.closeEntry()
        }
        val table = XlsxWorkbookReader.read(file).sheets.single().table
        assertEquals(listOf("x"), table.rows.single().filter { it.isNotEmpty() })
    }

    @Test fun `column references can't overflow`() {
        assertEquals(2, XlsxWorkbookReader.columnIndex("C7"))
        assertEquals(26, XlsxWorkbookReader.columnIndex("AA1"))
        assertEquals(Int.MAX_VALUE, XlsxWorkbookReader.columnIndex("ZZZZZZZZZZZZZZZZZZZZ1"))
        assertEquals(0, XlsxWorkbookReader.columnIndex("123"))
    }

    @Test fun `numbers read the way a person typed them`() {
        assertEquals("42", CellFormat.number(42.0))
        assertEquals("0.3", CellFormat.number(0.1 + 0.2))
        assertEquals("1000000", CellFormat.number(1_000_000.0))
        assertEquals("19.25", CellFormat.number(19.25))
        assertEquals("-3.5", CellFormat.number("-3.5"))
        assertEquals("abc", CellFormat.number("abc"))
        assertEquals("1.0E300", CellFormat.number(1e300))
    }

    @Test fun `dates come from the right epoch`() {
        assertEquals("2026-01-31", CellFormat.date(46053.0))
        assertEquals("2026-01-31 12:00", CellFormat.date(46053.5))
        assertEquals("09:30", CellFormat.date(0.3958333333))
        assertEquals("1904-01-02", CellFormat.date(1.0, date1904 = true))
        // A date too far out to mean anything just stays the number it is.
        assertEquals("99999999", CellFormat.date(99999999.0))
        assertEquals("-1", CellFormat.date(-1.0))
    }

    @Test fun `only date formats count as dates`() {
        assertTrue(CellFormat.isDateFormat(14, null))
        assertTrue(CellFormat.isDateFormat(200, "yyyy-mm-dd"))
        assertTrue(CellFormat.isDateFormat(200, "[\$-409]d mmmm yyyy"))
        assertFalse(CellFormat.isDateFormat(0, null))
        assertFalse(CellFormat.isDateFormat(200, "0.00"))
        assertFalse(CellFormat.isDateFormat(200, "#,##0 \"days\""))
        assertFalse(CellFormat.isDateFormat(200, "General"))
    }

    @Test fun `blank rows stay but long gaps close up`() {
        val builder = SheetBuilder(CellBudget())
        builder.put(0, 0, "a")
        builder.put(2, 1, "b")
        builder.put(5000, 0, "c")
        val rows = builder.build().rows
        assertEquals(listOf("a"), rows[0])
        assertEquals(emptyList<String>(), rows[1])
        assertEquals(listOf("", "b"), rows[2])
        assertEquals(3 + SheetBuilder.MAX_BLANK_ROWS + 1, rows.size)
        assertEquals(listOf("c"), rows.last())
    }

    @Test fun `all sheets share one budget`() {
        val budget = CellBudget(cells = 3)
        val one = SheetBuilder(budget)
        val two = SheetBuilder(budget)
        one.put(0, 0, "a")
        one.put(0, 1, "b")
        two.put(0, 0, "c")
        two.put(0, 1, "d")
        assertEquals(1, two.build().rows.single().size)
        assertTrue(two.truncated)
    }

    @Test fun `reads a real binary xls`() {
        val book = CompoundFile.open(fixture("sample.xls", folder)).use(ExcelBinaryReader::read)
        assertEquals(listOf("Sales", "Notes"), book.sheets.map { it.name })
        val rows = book.sheets[0].table.rows
        assertEquals(listOf("Region", "Units", "Price", "Date", "Paid"), rows[0])
        assertEquals(listOf("North", "12", "3.5", "2026-03-01", "FALSE"), rows[1])
        assertEquals(listOf("South", "7", "19.25", "2026-03-02", "TRUE"), rows[2])
        assertEquals("Café 日本", rows[3][0])
        assertEquals("1000000", rows[3][1])
        assertEquals("Total", rows[4][0])
        val notes = book.sheets[1].table.rows
        assertEquals("Second sheet text", notes[0][0])
        assertEquals("Skipped cells", notes[2][3])
    }

    @Test fun `compact numbers decode`() {
        assertEquals(1.0, ExcelBinaryReader.rk(0x3FF00000), 0.0)
        assertEquals(0.01, ExcelBinaryReader.rk(0x3FF00001), 1e-12)
        assertEquals(7.0, ExcelBinaryReader.rk((7 shl 2) or 2), 0.0)
        assertEquals(0.07, ExcelBinaryReader.rk((7 shl 2) or 3), 1e-12)
        assertEquals(-3.0, ExcelBinaryReader.rk((-3 shl 2) or 2), 0.0)
    }

    @Test fun `an encrypted xls says so`() {
        val stream = record(0x0809, shortBytes(0x0600) + shortBytes(5) + ByteArray(12)) + record(0x002F, ByteArray(10))
        val file = folder.newFile("locked.xls")
        file.writeBytes(CompoundBuilder.build(listOf("Workbook" to stream)))
        assertThrows(ProtectedDocumentException::class.java) { CompoundFile.open(file).use(ExcelBinaryReader::read) }
    }

    @Test fun `strings across record boundaries survive a width change`() {
        // "ab" in one-byte form, then a boundary, then "é日" restated as two-byte characters.
        val first = intBytes(1) + intBytes(1) + shortBytes(4) + byteArrayOf(0) + "ab".toByteArray()
        val second = byteArrayOf(1) + "é日".toByteArray(Charsets.UTF_16LE)
        val workbook = record(0x0809, shortBytes(0x0600) + shortBytes(5) + ByteArray(12)) +
            record(0x0085, ByteArray(0).let { intBytes(0) } + byteArrayOf(0, 0, 1, 0) + "S".toByteArray()) +
            record(0x00FC, first) + record(0x003C, second) + record(0x000A, ByteArray(0))
        // The sheet starts right after the globals, so its offset has to be patched in.
        val sheetAt = workbook.size
        workbook[4] = workbook[4]
        val sheet = record(0x0809, shortBytes(0x0600) + shortBytes(0x10) + ByteArray(12)) +
            record(0x00FD, shortBytes(0) + shortBytes(0) + shortBytes(0) + intBytes(0)) + record(0x000A, ByteArray(0))
        val patched = workbook.copyOf()
        val boundSheetBody = 4 + 16 + 4
        intBytes(sheetAt).copyInto(patched, boundSheetBody)
        val file = folder.newFile("wide.xls")
        file.writeBytes(CompoundBuilder.build(listOf("Workbook" to patched + sheet)))
        val book = CompoundFile.open(file).use(ExcelBinaryReader::read)
        assertEquals("abé日", book.sheets.single().table.rows.single().single())
    }

    private fun shortBytes(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun intBytes(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    private fun record(type: Int, body: ByteArray): ByteArray = shortBytes(type) + shortBytes(body.size) + body
}

class OpenDocumentTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real odt`() {
        val blocks = OpenDocumentReader.readText(fixture("sample.odt", folder)).sections.single().blocks
        assertEquals(DocBlock.Heading(1, "OpenDocument heading"), blocks[0])
        assertEquals("A plain paragraph with café in it.", (blocks[1] as DocBlock.Paragraph).text)
        assertEquals(DocBlock.Heading(2, "Smaller heading"), blocks[2])
        assertEquals("Alpha", (blocks[3] as DocBlock.Bullet).text)
        assertEquals(listOf(listOf("Name", "Score"), listOf("Alice", "42")), (blocks[5] as DocBlock.Table).rows)
        assertEquals("Paragraph after the table.", (blocks[6] as DocBlock.Paragraph).text)
    }

    @Test fun `reads a real ods with its sheets`() {
        val book = OpenDocumentReader.readSpreadsheet(fixture("sample.ods", folder))
        assertEquals(listOf("Inventory", "Other"), book.sheets.map { it.name })
        assertEquals(listOf("Item", "Qty", "Date"), book.sheets[0].table.rows[0])
        assertEquals(listOf("Bolt", "12", "2026-03-04"), book.sheets[0].table.rows[1])
    }

    @Test fun `reads a real odp slide by slide`() {
        val doc = OpenDocumentReader.readPresentation(fixture("sample.odp", folder))
        assertEquals(2, doc.sections.size)
        assertTrue(allText(ReadableDocument(listOf(doc.sections[1]))).contains("Second ODP slide"))
    }

    @Test fun `repeated cells can't multiply`() {
        val file = folder.textZip(
            "repeat.ods",
            "content.xml" to """<office:document-content><office:body><office:spreadsheet><table:table table:name="R">
                <table:table-row table:number-rows-repeated="1000000"><table:table-cell table:number-columns-repeated="1000000"><text:p>x</text:p></table:table-cell></table:table-row>
                <table:table-row><table:table-cell table:number-columns-repeated="99999"/><table:table-cell><text:p>far</text:p></table:table-cell></table:table-row>
                </table:table></office:spreadsheet></office:body></office:document-content>"""
        )
        val sheet = OpenDocumentReader.readSpreadsheet(file).sheets.single().table
        assertTrue(sheet.truncated)
        assertTrue(sheet.columnCount <= SheetBuilder.MAX_COLUMNS)
        assertTrue(sheet.rows.size < 200)
    }

    @Test fun `deep nesting reads fine`() {
        val file = folder.textZip(
            "deep.odt",
            "content.xml" to "<office:document-content><office:body><office:text><text:p>" + "<text:span>".repeat(200_000) + "deep" + "</text:span>".repeat(200_000) + "</text:p></office:text></office:body></office:document-content>"
        )
        assertEquals("deep", allText(OpenDocumentReader.readText(file)))
    }

    @Test fun `an encrypted document says so`() {
        val file = folder.textZip(
            "locked.odt",
            "META-INF/manifest.xml" to "<manifest:manifest><manifest:file-entry><manifest:encryption-data/></manifest:file-entry></manifest:manifest>",
            "content.xml" to "noise"
        )
        assertThrows(ProtectedDocumentException::class.java) { OpenDocumentReader.readText(file) }
    }

    @Test fun `notes and annotations stay out of the text`() {
        val file = folder.textZip(
            "n.odt",
            "content.xml" to """<office:document-content><office:body><office:text><text:p>Main<text:note><text:note-body><text:p>Foot</text:p></text:note-body></text:note></text:p><office:annotation><text:p>Comment</text:p></office:annotation></office:text></office:body></office:document-content>"""
        )
        assertEquals("Main", allText(OpenDocumentReader.readText(file)))
    }
}

class CompoundFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `random bytes after the signature fail with the normal error`() {
        val random = Random(3)
        repeat(100) { round ->
            val file = folder.newFile("g$round.doc")
            val body = ByteArray(2_000 + random.nextInt(4_000)).also(random::nextBytes)
            file.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + body)
            assertThrows(Exception::class.java) { CompoundFile.open(file).use { it.read("WordDocument") ?: error("none") } }
        }
    }

    @Test fun `a real file survives having its bytes damaged`() {
        val good = fixture("sample.xls", folder).readBytes()
        val random = Random(5)
        var survived = 0
        repeat(300) { round ->
            val damaged = good.copyOf()
            repeat(20) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            val file = folder.newFile("d$round.xls")
            file.writeBytes(damaged)
            try {
                CompoundFile.open(file).use(ExcelBinaryReader::read)
                survived++
            } catch (_: Exception) {
                // The normal way to fail. An error or a hang would end this test instead.
            }
        }
        assertTrue(survived > 0)
    }

    @Test(timeout = 10_000) fun `a looping sector chain ends`() {
        val file = folder.newFile("loop.doc")
        val bytes = CompoundBuilder.build(listOf("WordDocument" to ByteArray(8192)))
        // Point every table entry at sector 0, so following any chain would go around forever.
        val table = CompoundBuilder.fatOffset(bytes)
        for (i in 0 until 128) {
            bytes[table + i * 4] = 0
            bytes[table + i * 4 + 1] = 0
            bytes[table + i * 4 + 2] = 0
            bytes[table + i * 4 + 3] = 0
        }
        file.writeBytes(bytes)
        // Whether it fails or reads noise doesn't matter, as long as it ends.
        try {
            CompoundFile.open(file).use { it.read("WordDocument") }
        } catch (_: UnreadableDocumentException) {
        }
    }

    @Test fun `only top level streams count`() {
        val compound = CompoundFile.open(fixture("sample.doc", folder))
        compound.use {
            assertTrue(it.has("WordDocument"))
            assertFalse(it.has("Nonexistent"))
        }
    }
}

/** A minimal compound file, for the shapes of damage no real tool would ever write. */
private object CompoundBuilder {
    private const val SECTOR = 512
    private const val END = -2
    private const val FATSECT = -3
    private const val FREE = -1

    fun build(streams: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        val fat = ArrayList<Int>()
        fun chain(data: ByteArray): Int {
            val start = body.size() / SECTOR
            val count = (data.size + SECTOR - 1) / SECTOR
            body.write(data.copyOf(count * SECTOR))
            for (i in 0 until count) fat.add(if (i < count - 1) start + i + 1 else END)
            return start
        }
        val infos = streams.map { (name, data) ->
            val padded = if (data.size < 4096) data.copyOf(4096) else data
            Triple(name, chain(padded), padded.size)
        }
        val directory = ByteArrayOutputStream()
        directory.write(entry("Root Entry", 5, -1, -1, 1, END, 0))
        infos.forEachIndexed { index, (name, start, size) ->
            directory.write(entry(name, 2, -1, if (index + 1 < infos.size) index + 2 else -1, -1, start, size))
        }
        val dirBytes = directory.toByteArray()
        val dirStart = chain(dirBytes.copyOf(((dirBytes.size + SECTOR - 1) / SECTOR) * SECTOR))
        val fatStart = body.size() / SECTOR
        fat.add(FATSECT)
        val table = ByteArray(SECTOR) { 0xFF.toByte() }
        fat.forEachIndexed { index, value -> putInt(table, index * 4, value) }
        body.write(table)

        val header = ByteArray(512)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(header)
        putShort(header, 24, 0x3E)
        putShort(header, 26, 3)
        putShort(header, 28, 0xFFFE)
        putShort(header, 30, 9)
        putShort(header, 32, 6)
        putInt(header, 44, 1)
        putInt(header, 48, dirStart)
        putInt(header, 56, 4096)
        putInt(header, 60, END)
        putInt(header, 68, END)
        putInt(header, 76, fatStart)
        for (i in 1 until 109) putInt(header, 76 + i * 4, FREE)
        return header + body.toByteArray()
    }

    fun fatOffset(file: ByteArray): Int {
        val sector = (file[76].toInt() and 0xFF) or ((file[77].toInt() and 0xFF) shl 8)
        return (sector + 1) * SECTOR
    }

    private fun entry(name: String, type: Int, left: Int, right: Int, child: Int, start: Int, size: Int): ByteArray {
        val out = ByteArray(128)
        val encoded = (name + "\u0000").toByteArray(Charsets.UTF_16LE)
        encoded.copyInto(out)
        putShort(out, 64, encoded.size)
        out[66] = type.toByte()
        out[67] = 1
        putInt(out, 68, left)
        putInt(out, 72, right)
        putInt(out, 76, child)
        putInt(out, 116, start)
        putInt(out, 120, size)
        return out
    }

    private fun putShort(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value shr 8).toByte()
    }

    private fun putInt(out: ByteArray, at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value shr 8).toByte()
        out[at + 2] = (value shr 16).toByte()
        out[at + 3] = (value shr 24).toByte()
    }
}

class RtfTest {
    @get:Rule val folder = TemporaryFolder()

    private fun text(rtf: String) = allText(RtfReader.read(rtf.toByteArray(Charsets.ISO_8859_1)))

    @Test fun `reads a real rtf`() {
        val value = allText(RtfReader.read(fixture("sample.rtf", folder).readBytes()))
        assertTrue(value, "Quarterly notes" in value)
        assertTrue(value, "Café prices" in value)
        assertTrue(value, "naïve résumé" in value)
        assertFalse(value, "Times-Roman" in value)
        assertFalse(value, "fonttbl" in value)
    }

    @Test fun `escapes and unicode decode`() {
        assertEquals("café", text("""{\rtf1\ansi\ansicpg1252 caf\'e9}"""))
        assertEquals("日本", text("{\\rtf1\\uc1\\u26085?\\u26412?}"))
        assertEquals("a{b}c\\d", text("""{\rtf1 a\{b\}c\\d}"""))
        assertEquals("x\ty", text("""{\rtf1 x\tab y}"""))
    }

    @Test fun `east asian code pages decode their two byte characters`() {
        assertEquals("日本", text("""{\rtf1\ansi\ansicpg932 \'93\'fa\'96\'7b}"""))
        assertEquals("x", text("""{\rtf1\ansicpg99999 x}"""))
    }

    @Test fun `a negative unicode value wraps as the format says`() {
        assertEquals("\uFFFD", text("{\\rtf1\\uc0\\u-3}"))
    }

    @Test fun `metadata groups stay out`() {
        assertEquals("Body", text("""{\rtf1{\fonttbl{\f0 Arial;}}{\colortbl;\red0;}{\*\generator Nobody;}{\info{\title Secret}}Body}"""))
    }

    @Test fun `a million open braces end`() {
        val doc = RtfReader.read(("{\\rtf1 " + "{".repeat(1_000_000) + "text").toByteArray())
        assertEquals("text", allText(doc))
    }

    @Test fun `binary data with a lying length ends`() {
        assertEquals("before", text("""{\rtf1 before\bin999999999 }"""))
    }

    @Test fun `truncated escapes end`() {
        assertEquals("a", text("{\\rtf1 a\\'"))
        assertEquals("a", text("{\\rtf1 a\\"))
        assertEquals("a", text("{\\rtf1 a\\'4"))
    }

    @Test fun `tables become rows`() {
        val doc = RtfReader.read("""{\rtf1 a\cell b\cell\row c\cell d\cell\row\par after}""".toByteArray())
        val blocks = doc.sections.single().blocks
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), (blocks[0] as DocBlock.Table).rows)
        assertEquals("after", (blocks[1] as DocBlock.Paragraph).text)
    }

    @Test fun `not rtf at all is the normal error`() {
        assertThrows(UnreadableDocumentException::class.java) { RtfReader.read("hello".toByteArray()) }
    }
}

class HtmlTest {
    private fun read(html: String) = HtmlReader.readText(html)
    private fun blocks(html: String) = read(html).sections.single().blocks

    @Test fun `nothing active survives`() {
        val text = allText(
            read(
                """<html><head><title>T</title><script>alert('x')</script><style>p{}</style></head>
                <body onload="evil()"><p>Words</p><script src="https://example.com/x.js"></script>
                <iframe src="https://example.com/f">frame text</iframe><object data="x">obj</object>
                <noscript>noscript text</noscript><template><p>template</p></template>
                <video src="x">video text</video><svg><text>svg text</text></svg><p>More</p></body></html>"""
            )
        )
        assertEquals("Words\nMore", text)
    }

    @Test fun `script text can't fake its way out`() {
        assertEquals("After", allText(read("""<script>document.write("</p><p>Injected");</script><p>After</p>""")))
    }

    @Test fun `a picture is only its description`() {
        assertEquals("See [A chart] here", allText(read("""<p>See <img src="https://example.com/a.png" alt="A chart"> here</p>""")))
        assertEquals("No alt", allText(read("""<p>No alt<img src="https://example.com/track.gif"></p>""")))
    }

    @Test fun `links keep their target for tapping but fetch nothing`() {
        val paragraph = blocks("""<p>Go <a href="https://example.com/">there</a> now</p>""").single() as DocBlock.Paragraph
        val link = paragraph.spans.single()
        assertEquals("there", paragraph.text.substring(link.start, link.end))
        assertEquals("https://example.com/", link.url)
    }

    @Test fun `only ordinary links can be opened`() {
        assertTrue(HtmlReader.isOpenableLink("https://example.com"))
        assertTrue(HtmlReader.isOpenableLink("HTTP://example.com"))
        assertTrue(HtmlReader.isOpenableLink("mailto:a@b.c"))
        assertFalse(HtmlReader.isOpenableLink("javascript:alert(1)"))
        assertFalse(HtmlReader.isOpenableLink("intent://x#Intent;end"))
        assertFalse(HtmlReader.isOpenableLink("file:///sdcard/secret"))
        assertFalse(HtmlReader.isOpenableLink("content://x"))
        assertFalse(HtmlReader.isOpenableLink("chapter2.xhtml"))
    }

    @Test fun `structure comes through`() {
        val found = blocks(
            """<h1>Head</h1><h3>Sub</h3><ul><li>One</li><li>Two<ol><li>Inner</li></ol></li></ul>
            <blockquote>Quote</blockquote><pre>a  b
  c</pre><hr><table><tr><th>N</th><th>S</th></tr><tr><td>A</td><td>1</td></tr></table>"""
        )
        assertEquals(DocBlock.Heading(1, "Head"), found[0])
        assertEquals(DocBlock.Heading(3, "Sub"), found[1])
        assertEquals("One", (found[2] as DocBlock.Bullet).text)
        assertEquals("Two", (found[3] as DocBlock.Bullet).text)
        val inner = found[4] as DocBlock.Bullet
        assertEquals("Inner", inner.text)
        assertEquals("1.", inner.marker)
        assertEquals(1, inner.depth)
        assertEquals("Quote", (found[5] as DocBlock.Quote).text)
        assertEquals("a  b\n  c", (found[6] as DocBlock.Code).text)
        assertEquals(DocBlock.Rule, found[7])
        assertEquals(listOf(listOf("N", "S"), listOf("A", "1")), (found[8] as DocBlock.Table).rows)
    }

    @Test fun `tag soup still reads`() {
        assertEquals("One\nTwo", allText(read("<P>One<P>Two")))
        assertEquals("Body text", allText(read("<html><head><title>x<body>Body text")))
        assertEquals("Café & 日本", allText(read("<p>Caf&eacute; &amp; &#26085;&#26412;")))
    }

    @Test fun `white space collapses outside pre`() {
        assertEquals("a b c", allText(read("<p>a\n   b\t\tc</p>")))
    }

    @Test fun `bold and italic become spans`() {
        val paragraph = blocks("<p><b>B</b> and <em>E</em></p>").single() as DocBlock.Paragraph
        assertEquals("B and E", paragraph.text)
        assertEquals(InlineSpan.Style.BOLD, paragraph.spans[0].style)
        assertEquals(InlineSpan.Style.ITALIC, paragraph.spans[1].style)
    }

    @Test fun `a page of nothing but scripts is empty`() {
        assertTrue(read("<html><script>x</script></html>").isEmpty)
    }

    @Test fun `the declared charset is used only when the bytes need it`() {
        val latin = "<meta charset=\"windows-1251\"><p>".toByteArray() + 0xC4.toByte() + "</p>".toByteArray()
        assertEquals("Д", allText(HtmlReader.readBytes(latin)))
        val utf8 = "<meta charset=\"windows-1251\"><p>é</p>".toByteArray(Charsets.UTF_8)
        assertEquals("é", allText(HtmlReader.readBytes(utf8)))
    }

    @Test fun `a page opened by a file named for office reads the same`() {
        assertEquals(OpenAs.HTML, FileOpening.decide("export.xls", "<html><body>x</body></html>".toByteArray()))
    }
}

class MarkdownTest {
    private fun blocks(text: String) = MarkdownReader.read(text).sections.single().blocks

    @Test fun `headings paragraphs and rules`() {
        val found = blocks("# One\n\nSome text\nwrapped\n\n## Two ##\n\n---\n\nTitle\n=====\n\nSub\n---\n")
        assertEquals(DocBlock.Heading(1, "One"), found[0])
        assertEquals("Some text wrapped", (found[1] as DocBlock.Paragraph).text)
        assertEquals(DocBlock.Heading(2, "Two"), found[2])
        assertEquals(DocBlock.Rule, found[3])
        assertEquals(DocBlock.Heading(1, "Title"), found[4])
        assertEquals(DocBlock.Heading(2, "Sub"), found[5])
    }

    @Test fun `lists quotes and code`() {
        val found = blocks("- a\n  - b\n1. c\n\n> quoted\n> more\n\n```kotlin\nval x = *1*\n```\n")
        assertEquals("a", (found[0] as DocBlock.Bullet).text)
        assertEquals(1, (found[1] as DocBlock.Bullet).depth)
        assertEquals("1.", (found[2] as DocBlock.Bullet).marker)
        assertEquals("quoted more", (found[3] as DocBlock.Quote).text)
        assertEquals("val x = *1*", (found[4] as DocBlock.Code).text)
    }

    @Test fun `an unclosed fence runs to the end`() {
        assertEquals("code", (blocks("```\ncode\n").single() as DocBlock.Code).text)
    }

    @Test fun `inline styling`() {
        val (plain, spans) = MarkdownReader.inline("a **b** *c* `d` [e](https://x.test/)")
        assertEquals("a b c d e", plain)
        assertEquals(setOf(InlineSpan.Style.BOLD, InlineSpan.Style.ITALIC, InlineSpan.Style.CODE, InlineSpan.Style.LINK), spans.map { it.style }.toSet())
        assertEquals("https://x.test/", spans.first { it.style == InlineSpan.Style.LINK }.url)
    }

    @Test fun `words with marks in them stay as they are`() {
        assertEquals("snake_case_name", MarkdownReader.inline("snake_case_name").first)
        assertEquals("2 * 3 * 4", MarkdownReader.inline("2 * 3 * 4").first)
        assertEquals("a [b] c", MarkdownReader.inline("a [b] c").first)
        assertEquals("**", MarkdownReader.inline("**").first)
        assertEquals("*not closed", MarkdownReader.inline("*not closed").first)
    }

    @Test fun `an image is never a link`() {
        val (plain, spans) = MarkdownReader.inline("![a cat](https://example.com/cat.png)")
        assertEquals("[a cat]", plain)
        assertTrue(spans.isEmpty())
    }

    @Test fun `tables`() {
        val table = blocks("| N | S |\n|---|:-:|\n| A | 1 |\n\nAfter").first() as DocBlock.Table
        assertEquals(listOf(listOf("N", "S"), listOf("A", "1")), table.rows)
    }

    @Test fun `a pipe alone isn't a table`() {
        assertEquals("a | b next", (blocks("a | b\nnext").single() as DocBlock.Paragraph).text)
    }

    @Test(timeout = 20_000) fun `pathological lines stay quick`() {
        MarkdownReader.read("[".repeat(1_000_000))
        MarkdownReader.read("*a ".repeat(300_000))
        MarkdownReader.read("# " + " ".repeat(1_000_000) + "x")
        MarkdownReader.read("- ".repeat(500_000))
        MarkdownReader.read("|" + "-|".repeat(500_000))
    }
}

class EpubTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real epub`() {
        val file = fixture("sample.epub", folder)
        val book = EpubReader.readBook(file)
        assertEquals("Synthetic Book", book.title)
        assertEquals("Nobody", book.author)
        val titled = book.chapters.filter { it.title != null }
        assertEquals(listOf("The Beginning", "The Middle", "The End"), titled.map { it.title })
        val chapter = EpubReader.readChapter(file, titled[1])
        val text = allText(chapter)
        assertTrue(text, "The Middle" in text)
        assertTrue(text, "Bold words" in text)
        assertEquals(DocBlock.Heading(1, "The Middle"), chapter.sections.single().blocks[0])
    }

    @Test fun `a path out of the book only looks inside the book`() {
        val file = folder.textZip(
            "evil.epub",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="../../../../etc/hosts"/></rootfiles></container>"""
        )
        assertThrows(UnreadableDocumentException::class.java) { EpubReader.readBook(file) }
    }

    @Test fun `a missing container falls back to any package file`() {
        val file = folder.textZip(
            "x.epub",
            "book/content.opf" to """<package><metadata><dc:title>T</dc:title></metadata><manifest><item id="a" href="a%20b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""",
            "book/a b.xhtml" to "<html><body><p>Words</p></body></html>"
        )
        val book = EpubReader.readBook(file)
        assertEquals("T", book.title)
        assertEquals("Words", allText(EpubReader.readChapter(file, book.chapters.single())))
    }

    @Test fun `spine items that aren't there are left out`() {
        val file = folder.textZip(
            "x.epub",
            "a.opf" to """<package><manifest><item id="a" href="gone.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="c" href="c.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/><itemref idref="nope"/></spine></package>""",
            "b.xhtml" to "<p>x</p>",
            "c.jpg" to "x"
        )
        assertEquals(listOf("b.xhtml"), EpubReader.readBook(file).chapters.map { it.part })
    }

    @Test fun `encrypted chapters count as locked and scrambled fonts don't`() {
        val locked = folder.textZip(
            "l.epub",
            "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherReference URI="OEBPS/ch1.xhtml"/></EncryptedData></encryption>""",
            "a.opf" to "<package/>"
        )
        assertThrows(ProtectedDocumentException::class.java) { EpubReader.readBook(locked) }
        val fonts = folder.textZip(
            "f.epub",
            "META-INF/encryption.xml" to """<encryption><EncryptedData><CipherReference URI="fonts/a.otf"/></EncryptedData></encryption>""",
            "a.opf" to """<package><manifest><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="b"/></spine></package>""",
            "b.xhtml" to "<p>x</p>"
        )
        assertEquals(1, EpubReader.readBook(fonts).chapters.size)
    }
}

class PlaylistTest {
    @Test fun `m3u resolves local paths and keeps remote ones`() {
        val list = PlaylistReader.read(
            "m3u",
            "#EXTM3U\n#EXTINF:12,Artist - Song\nsong.mp3\nhttps://example.com/live.mp3\n#EXTINF:-1,Other\n../up/two.mp3\n/abs/three.mp3\nfile:///abs/four%20x.mp3\n",
            "/music/list"
        )
        val entries = list.entries
        assertEquals(5, entries.size)
        assertEquals("/music/list/song.mp3", entries[0].path)
        assertEquals("Song", entries[0].title)
        assertEquals("Artist", entries[0].artist)
        assertEquals(12, entries[0].durationSec)
        assertNull(entries[1].path)
        assertEquals("https://example.com/live.mp3", entries[1].remote)
        assertEquals("/music/up/two.mp3", entries[2].path)
        assertEquals("/abs/three.mp3", entries[3].path)
        assertEquals("/abs/four x.mp3", entries[4].path)
    }

    @Test fun `pls`() {
        val list = PlaylistReader.read(
            "pls",
            "[playlist]\nFile2=http://x.test/s\nTitle2=Radio\nFile1=a.mp3\nTitle1=First\nLength1=12\nNumberOfEntries=2\nVersion=2\n",
            "/m"
        )
        assertEquals(listOf("First", "Radio"), list.entries.map { it.title })
        assertEquals("/m/a.mp3", list.entries[0].path)
        assertEquals(12, list.entries[0].durationSec)
        assertEquals("http://x.test/s", list.entries[1].remote)
    }

    @Test fun `cue sheets say where each track starts`() {
        val list = PlaylistReader.read(
            "cue",
            "PERFORMER \"Band\"\nTITLE \"Album\"\nFILE \"album.flac\" WAVE\n  TRACK 01 AUDIO\n    TITLE \"One\"\n    INDEX 00 00:00:00\n    INDEX 01 00:02:00\n  TRACK 02 AUDIO\n    TITLE \"Two\"\n    PERFORMER \"Guest\"\n    INDEX 01 03:30:37\n",
            "/m"
        )
        assertEquals("Album", list.title)
        assertEquals(2, list.entries.size)
        assertEquals("One", list.entries[0].title)
        assertEquals("Band", list.entries[0].artist)
        assertEquals("/m/album.flac", list.entries[0].path)
        assertEquals(2_000L, list.entries[0].startMs)
        assertEquals("Guest", list.entries[1].artist)
        assertEquals(210_493L, list.entries[1].startMs)
    }

    @Test fun `bad times and empty lists don't throw`() {
        assertNull(PlaylistReader.cueTime("nope"))
        assertNull(PlaylistReader.cueTime("1:2"))
        assertNull(PlaylistReader.cueTime("-1:00:00"))
        assertTrue(PlaylistReader.read("m3u", "", null).entries.isEmpty())
        assertTrue(PlaylistReader.read("pls", "garbage\n=\n", null).entries.isEmpty())
        assertTrue(PlaylistReader.read("cue", "INDEX\nTRACK\nFILE\n", null).entries.isEmpty())
    }
}

class SmallFormatTest {
    private fun bencode(value: Any): ByteArray = when (value) {
        is Int -> "i${value}e".toByteArray()
        is Long -> "i${value}e".toByteArray()
        is String -> "${value.toByteArray().size}:$value".toByteArray()
        is ByteArray -> "${value.size}:".toByteArray() + value
        is List<*> -> "l".toByteArray() + value.fold(ByteArray(0)) { all, item -> all + bencode(item!!) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() + value.entries.fold(ByteArray(0)) { all, (k, v) -> all + bencode(k as String) + bencode(v!!) } + "e".toByteArray()
        else -> error("unsupported")
    }

    @Test fun `a torrent lists what it holds`() {
        val data = bencode(
            mapOf(
                "announce" to "http://tracker.test/a",
                "comment" to "Note",
                "info" to mapOf(
                    "name" to "Folder",
                    "pieces" to ByteArray(20) { 0xFF.toByte() },
                    "files" to listOf(
                        mapOf("length" to 1024, "path" to listOf("d", "one.bin")),
                        mapOf("length" to 2048, "path" to listOf("two.bin"))
                    )
                )
            )
        )
        val text = allText(TorrentReader.read(data, { "$it bytes" }))
        assertTrue(text, "Folder" in text)
        assertTrue(text, "3072 bytes" in text)
        assertTrue(text, "d/one.bin (1024 bytes)" in text)
        assertTrue(text, "Note" in text)
        assertTrue(text, "http://tracker.test/a" in text)
    }

    @Test fun `malformed bencode is the normal error`() {
        listOf("d".repeat(100_000), "l".repeat(100_000), "i", "99999999999:x", "5:ab", "xx", "d1:a", "i999999999999999999999e").forEach { bad ->
            assertThrows(bad.take(12), UnreadableDocumentException::class.java) { Bencode.parse(bad.toByteArray()) }
        }
    }

    @Test fun `a torrent with no info is the normal error`() {
        assertThrows(UnreadableDocumentException::class.java) { TorrentReader.read(bencode(mapOf("a" to 1)), { "" }) }
        assertThrows(UnreadableDocumentException::class.java) { TorrentReader.read(bencode(listOf(1, 2)), { "" }) }
    }

    @Test fun `only real torrents look like torrents`() {
        assertTrue(Bencode.looksLikeTorrent("d8:announce35:http://".toByteArray()))
        assertFalse(Bencode.looksLikeTorrent("dear diary".toByteArray()))
    }

    private val mail = """From: =?UTF-8?B?QWxpY2U=?= <a@x.test>
To: b@x.test
Subject: =?UTF-8?Q?Caf=C3=A9_time?=
Date: Thu, 05 Nov 2026 10:00:00 +0000
Content-Type: multipart/mixed; boundary="B"

--B
Content-Type: multipart/alternative; boundary="C"

--C
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: quoted-printable

Hello caf=C3=A9 =
joined.

Second part.
--C
Content-Type: text/html

<p>HTML version</p><img src="https://example.com/track.gif">
--C--
--B
Content-Type: application/pdf
Content-Disposition: attachment; filename="invoice.pdf"
Content-Transfer-Encoding: base64

JVBERi0xLjQK
--B--
"""

    @Test fun `an email reads as headers, body and attachments`() {
        val text = allText(EmailReader.read(mail.toByteArray()))
        assertTrue(text, "Café time" in text)
        assertTrue(text, "From | Alice <a@x.test>" in text)
        assertTrue(text, "Hello café joined." in text)
        assertTrue(text, "Second part." in text)
        assertTrue(text, "invoice.pdf" in text)
        assertFalse(text, "HTML version" in text)
    }

    @Test fun `an email with only html never fetches anything`() {
        val only = "From: a@x.test\nSubject: S\nContent-Type: text/html\n\n<p>Hi</p><img src=\"https://example.com/t.gif\"><script>x</script>"
        assertEquals("S\nFrom | a@x.test\nHi", allText(EmailReader.read(only.toByteArray())))
    }

    @Test fun `base64 bodies and bad encodings don't throw`() {
        val good = "From: a\nContent-Transfer-Encoding: base64\n\nSGVsbG8=\n"
        assertTrue("Hello" in allText(EmailReader.read(good.toByteArray())))
        val bad = "From: a\nContent-Transfer-Encoding: base64\n\n!!!not base64!!!\n"
        EmailReader.read(bad.toByteArray())
        assertEquals("=?nope?B?aGk=?=", EmailReader.decodeWords("=?nope?B?aGk=?="))
        assertEquals("=?UTF-8?B?***?=", EmailReader.decodeWords("=?UTF-8?B?***?="))
    }

    @Test fun `a boundary nested forever stops`() {
        val nested = buildString {
            append("From: a\nContent-Type: multipart/mixed; boundary=\"x\"\n\n")
            repeat(5_000) { append("--x\nContent-Type: multipart/mixed; boundary=\"x\"\n\n") }
            append("--x--\n")
        }
        EmailReader.read(nested.toByteArray())
    }

    @Test fun `no headers is the normal error`() {
        assertThrows(UnreadableDocumentException::class.java) { EmailReader.read("\n\njust a body".toByteArray()) }
    }

    @Test fun `quoted printable decodes and keeps what it can't`() {
        assertEquals("a=b", String(EmailReader.decodeQuotedPrintable("a=3Db".toByteArray())))
        assertEquals("ab", String(EmailReader.decodeQuotedPrintable("a=\nb".toByteArray())))
        assertEquals("a=", String(EmailReader.decodeQuotedPrintable("a=".toByteArray())))
        assertEquals("=ZZ", String(EmailReader.decodeQuotedPrintable("=ZZ".toByteArray())))
    }

    @get:Rule val folder = TemporaryFolder()

    @Test fun `reads a real binary plist`() {
        val text = allText(BinaryPlistReader.read(fixture("binary.plist", folder).readBytes()))
        assertTrue(text, "Name | Binary plist" in text)
        assertTrue(text, "Count | 3" in text)
        assertTrue(text, "Enabled | true" in text)
    }

    @Test fun `a damaged binary plist is the normal error or a short list`() {
        val good = fixture("binary.plist", folder).readBytes()
        val random = Random(11)
        repeat(500) {
            val damaged = good.copyOf()
            repeat(6) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            try {
                BinaryPlistReader.read(damaged)
            } catch (_: Exception) {
            }
        }
        assertThrows(UnreadableDocumentException::class.java) { BinaryPlistReader.read("bplist00".toByteArray()) }
    }
}

class WoffTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `a real woff unpacks into a font that can be read`() {
        val out = folder.newFile("font.ttf")
        assertTrue(WoffFont.unpackIfWoff(fixture("sample.woff", folder), out))
        val info = requireNotNull(FontInfo.parse(out))
        assertEquals("Synth Sans", info.family)
        assertEquals("TrueType", info.format)
    }

    @Test fun `other files aren't woffs`() {
        val out = folder.newFile("font.ttf")
        val plain = folder.newFile("plain.bin").also { it.writeBytes(ByteArray(100)) }
        assertFalse(WoffFont.unpackIfWoff(plain, out))
    }

    @Test fun `damage never does worse than failing`() {
        val good = fixture("sample.woff", folder).readBytes()
        val random = Random(9)
        repeat(500) {
            val damaged = good.copyOf()
            repeat(8) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
            WoffFont.unpack(damaged)
        }
    }

    @Test fun `tables that claim impossible sizes are refused`() {
        val good = fixture("sample.woff", folder).readBytes()
        val damaged = good.copyOf()
        // The first table's original length: past what any preview should unpack.
        for (i in 0 until 4) damaged[44 + 12 + i] = 0x7F
        assertNull(WoffFont.unpack(damaged))
    }
}

class FileOpeningTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val binary = ByteArray(5000).also { Random(2).nextBytes(it) }

    @get:Rule val folder = TemporaryFolder()

    @Test fun `every real office file is told apart by what's inside it`() {
        val expected = mapOf(
            "sample.doc" to OpenAs.WORD_BINARY,
            "sample.docx" to OpenAs.WORD_ZIP,
            "sample.xls" to OpenAs.SHEET_BINARY,
            "sample.xlsx" to OpenAs.SHEET_ZIP,
            "sample.ppt" to OpenAs.SLIDES_BINARY,
            "sample.pptx" to OpenAs.SLIDES_ZIP,
            "sample.odt" to OpenAs.ODT,
            "sample.ods" to OpenAs.ODS,
            "sample.odp" to OpenAs.ODP,
            "sample.epub" to OpenAs.EPUB,
            "sample.rtf" to OpenAs.RTF,
            "binary.plist" to OpenAs.BINARY_PLIST,
            "sample.woff" to OpenAs.FONT
        )
        for ((name, kind) in expected) {
            val real = fixture(name, folder)
            assertEquals(name, kind, FileOpening.decide(real))
            val renamed = java.io.File(real.parentFile, "renamed_$name.dat")
            real.copyTo(renamed)
            assertEquals("renamed $name", kind, FileOpening.decide(renamed))
        }
    }

    @Test fun `a zip that's nothing in particular is an archive`() {
        val file = folder.textZip("data.pak", "a.txt" to "x")
        assertEquals(OpenAs.ARCHIVE, FileOpening.decide(file))
    }

    @Test fun `a compound file that's nothing in particular gets its details`() {
        val file = folder.newFile("mail.msg")
        file.writeBytes(CompoundBuilder.build(listOf("__substg1.0_0037001F" to ByteArray(16))))
        assertEquals(OpenAs.INSPECT, FileOpening.decide(file))
    }

    @Test fun `a locked office file says so before anything tries to read it`() {
        val file = folder.newFile("locked.docx")
        file.writeBytes(CompoundBuilder.build(listOf("EncryptedPackage" to ByteArray(16), "EncryptionInfo" to ByteArray(16))))
        assertEquals(OpenAs.LOCKED, FileOpening.decide(file))
    }

    @Test fun `a file that can't be read gets its details`() {
        assertEquals(OpenAs.INSPECT, FileOpening.decide(java.io.File(folder.root, "missing.bin")))
    }

    @Test fun `plain pages get the same words`() {
        val text = requireNotNull(DocumentLoader.plainText(fixture("styled.docx", folder)))
        assertTrue(text, "Project Report" in text)
        assertTrue(text, "1. First step" in text)
        assertTrue(text, "Bolt  |  12  |  3.50" in text)
        val garbage = folder.newFile("g.docx").also { it.writeBytes(binary) }
        assertNull(DocumentLoader.plainText(garbage))
    }

    @Test fun `every failure comes back as a value`() {
        val labels = DocLabels()
        val garbage = folder.newFile("g2.docx").also { it.writeBytes(binary) }
        for (kind in listOf(OpenAs.WORD_ZIP, OpenAs.WORD_BINARY, OpenAs.ODT, OpenAs.SLIDES_ZIP, OpenAs.SLIDES_BINARY, OpenAs.ODP, OpenAs.RTF, OpenAs.TORRENT, OpenAs.BINARY_PLIST, OpenAs.INSPECT)) {
            assertEquals(kind.name, DocLoad.Failed, DocumentLoader.load(garbage, kind, { "" }, labels))
        }
        assertTrue(DocumentLoader.load(garbage, OpenAs.HTML, { "" }, labels) is DocLoad.Ready)
    }

    @Test fun `the bytes outrank the name`() {
        assertEquals(OpenAs.IMAGE, FileOpening.decide("photo.txt", png))
        assertEquals(OpenAs.TEXT, FileOpening.decide("notes.jpg", "plain words\n".toByteArray()))
        assertEquals(OpenAs.RTF, FileOpening.decide("letter.doc", "{\\rtf1 hello}".toByteArray()))
        assertEquals(OpenAs.RTF, FileOpening.decide("letter.txt", "{\\rtf1 hello}".toByteArray()))
        assertEquals(OpenAs.SQLITE, FileOpening.decide("data.bin", "SQLite format 3\u0000xx".toByteArray()))
        assertEquals(OpenAs.TORRENT, FileOpening.decide("x.dat", "d8:announce10:http://x/ae".toByteArray()))
        assertEquals(OpenAs.BINARY_PLIST, FileOpening.decide("a.plist", "bplist00\u0000\u0001".toByteArray()))
    }

    @Test fun `an archive under another name is still an archive`() {
        val gzip = byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 0, 0, 0, 0, 0, 3) + binary
        assertEquals(OpenAs.ARCHIVE, FileOpening.decide("backup.dat", gzip))
        val tar = ByteArray(1024)
        "ustar".toByteArray().copyInto(tar, 257)
        assertEquals(OpenAs.ARCHIVE, FileOpening.decide("backup.txt", tar))
    }

    @Test fun `any text opens as text, whatever it's called`() {
        for (name in listOf("README", "a.xyz", "b.kt", "c.py", "d.env", "e.toml", ".gitignore", "f.tex", "g.srt", "h.ass", "i.sql", "j.gpx", "k.geojson", "m.bat", "Makefile")) {
            assertEquals(name, OpenAs.TEXT, FileOpening.decide(name, "some text\nmore\n".toByteArray()))
        }
    }

    @Test fun `the name chooses among text formats`() {
        assertEquals(OpenAs.MARKDOWN, FileOpening.decide("a.md", "# hi".toByteArray()))
        assertEquals(OpenAs.HTML, FileOpening.decide("a.htm", "<p>hi".toByteArray()))
        assertEquals(OpenAs.CSV, FileOpening.decide("a.csv", "a,b".toByteArray()))
        assertEquals(OpenAs.CONTACTS, FileOpening.decide("a.vcf", "BEGIN:VCARD".toByteArray()))
        assertEquals(OpenAs.CALENDAR, FileOpening.decide("a.ics", "BEGIN:VCALENDAR".toByteArray()))
        assertEquals(OpenAs.PLAYLIST, FileOpening.decide("a.m3u8", "#EXTM3U".toByteArray()))
        assertEquals(OpenAs.EMAIL, FileOpening.decide("a.eml", "From: a\nSubject: b\n\nbody".toByteArray()))
        assertEquals(OpenAs.TEXT, FileOpening.decide("a.eml", "not really an email".toByteArray()))
    }

    @Test fun `binary with no mark gets its details`() {
        assertEquals(OpenAs.INSPECT, FileOpening.decide("blob", binary))
        assertEquals(OpenAs.INSPECT, FileOpening.decide("a.xyz", binary))
    }

    @Test fun `binary named for a format gets that format's own message`() {
        assertEquals(OpenAs.WORD_ZIP, FileOpening.decide("a.docx", binary))
        assertEquals(OpenAs.SHEET_ZIP, FileOpening.decide("a.xlsx", binary))
        assertEquals(OpenAs.SLIDES_ZIP, FileOpening.decide("a.pptx", binary))
        assertEquals(OpenAs.EPUB, FileOpening.decide("a.epub", binary))
        assertEquals(OpenAs.ODT, FileOpening.decide("a.odt", binary))
        assertEquals(OpenAs.TORRENT, FileOpening.decide("a.torrent", binary))
    }

    @Test fun `empty files`() {
        assertEquals(OpenAs.TEXT, FileOpening.decide("a.txt", ByteArray(0)))
        assertEquals(OpenAs.TEXT, FileOpening.decide("README", ByteArray(0)))
        assertEquals(OpenAs.INSPECT, FileOpening.decide("a.docx", ByteArray(0)))
    }

    @Test fun `text that starts like a magic number is still text`() {
        assertEquals(OpenAs.TEXT, FileOpening.decide("notes", "BMW service history\n".toByteArray()))
        assertEquals(OpenAs.TEXT, FileOpening.decide("notes", "MZ was the first to go\n".toByteArray()))
        assertEquals(OpenAs.TEXT, FileOpening.decide("notes", "OggS is a format\n".toByteArray()))
        assertTrue(FileSniffer.sniff("BMW service history\n".toByteArray())!!.isText)
    }

    @Test fun `new marks are named`() {
        assertEquals("Microsoft Office document (older format)", FileSniffer.sniff(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0, 0))?.label)
        assertEquals("Rich Text document", FileSniffer.sniff("{\\rtf1\u0000".toByteArray())?.label)
        val iso = ByteArray(0x8010)
        "CD001".toByteArray().copyInto(iso, 0x8001)
        assertEquals("Disc image (ISO)", FileSniffer.sniff(iso)?.label)
        assertEquals("Debian package", FileSniffer.sniff("!<arch>\ndebian-binary\u0000".toByteArray())?.label)
    }

    @Test fun `a sample is big enough to see the disc mark`() {
        assertTrue(FileSniffer.SAMPLE_SIZE >= 0x8006)
    }
}

class DatabaseQueryTest {
    @Test fun `names are quoted wherever they appear`() {
        assertEquals("\"plain\"", SqliteQueries.quote("plain"))
        assertEquals("\"odd name; drop\"", SqliteQueries.quote("odd name; drop"))
        assertEquals("\"a\"\"b\"", SqliteQueries.quote("a\"b"))
        assertEquals("SELECT COUNT(*) FROM \"a\"\"b\"", SqliteQueries.count("a\"b"))
        assertEquals("PRAGMA table_info(\"x; DROP TABLE y\")", SqliteQueries.columns("x; DROP TABLE y"))
    }

    @Test fun `cells never come back whole`() {
        val sql = SqliteQueries.rows("t", listOf("a", "b\"c"))
        assertTrue(sql, "typeof(\"a\") = 'blob'" in sql)
        assertTrue(sql, "substr(\"b\"\"c\", 1, 500)" in sql)
        assertTrue(sql, sql.endsWith("FROM \"t\" LIMIT 200"))
    }

    @Test fun `a table with too many columns is cut`() {
        val sql = SqliteQueries.rows("t", List(500) { "c$it" })
        assertNotNull(sql)
        assertEquals(SqliteQueries.MAX_COLUMNS, Regex("typeof").findAll(sql).count())
    }
}
