package com.kobblo.gallery.file.explorer.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class DocumentFormatTest {
    @Test
    fun `both of Word's extensions mean Word, in any case`() {
        assertEquals(DocumentFormat.Word, DocumentFormat.of("Letter.docx"))
        assertEquals(DocumentFormat.Word, DocumentFormat.of("old.DOC"))
        assertEquals(DocumentFormat.Word, DocumentFormat.of(".docx"))
    }

    @Test
    fun `everything else is read as text`() {
        for (name in listOf("notes.txt", "README.md", "data.json", "Main.kt", "no extension", "docx", "report.docx.bak")) {
            assertEquals(name, DocumentFormat.Text, DocumentFormat.of(name))
        }
    }

    @Test
    fun `a PDF is known by the end of its name, in any case`() {
        assertTrue(isPdfName("Manual.pdf"))
        assertTrue(isPdfName("SCAN.PDF"))
        assertFalse(isPdfName("pdf"))
        assertFalse(isPdfName("manual.pdf.txt"))
        assertFalse(isPdfName("notes.txt"))
    }

    @Test
    fun `other apps are told the type of the documents that have one`() {
        assertEquals("application/pdf", documentMimeType("scan.PDF"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", documentMimeType("a.docx"))
        assertEquals("application/json", documentMimeType("a.json"))
        assertEquals("text/plain", documentMimeType("a.md"))
        assertEquals("text/plain", documentMimeType("a.txt"))
    }

    @Test
    fun `a document with no type to name goes to any app`() {
        for (name in listOf("old.doc", "page.html", "Main.kt", "server.log", "no extension")) {
            assertEquals(name, "*/*", documentMimeType(name))
        }
    }
}

class CappedReadTest {
    private fun bytes(count: Int) = ByteArray(count) { (it % 251).toByte() }

    @Test
    fun `a stream shorter than the limit comes back whole`() {
        val read = ByteArrayInputStream(bytes(1000)).readCapped(4096)
        assertArrayEquals(bytes(1000), read.bytes)
        assertFalse(read.cutShort)
    }

    @Test
    fun `a stream of exactly the limit isn't cut short`() {
        val read = ByteArrayInputStream(bytes(4096)).readCapped(4096)
        assertEquals(4096, read.bytes.size)
        assertFalse(read.cutShort)
    }

    @Test
    fun `one byte more than the limit is cut to the limit, and says so`() {
        val read = ByteArrayInputStream(bytes(4097)).readCapped(4096)
        assertArrayEquals(bytes(4097).copyOf(4096), read.bytes)
        assertTrue(read.cutShort)
    }

    @Test
    fun `a limit past one block still cuts exactly`() {
        val read = ByteArrayInputStream(bytes(300_000)).readCapped(200_000)
        assertArrayEquals(bytes(300_000).copyOf(200_000), read.bytes)
        assertTrue(read.cutShort)
    }

    @Test
    fun `an empty stream gives nothing`() {
        val read = ByteArrayInputStream(ByteArray(0)).readCapped(4096)
        assertEquals(0, read.bytes.size)
        assertFalse(read.cutShort)
    }

    @Test
    fun `a stream that hands over a little at a time is still read to the end`() {
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(bytes(1000))
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 7))
        }
        val read = trickle.readCapped(4096)
        assertArrayEquals(bytes(1000), read.bytes)
        assertFalse(read.cutShort)
    }
}

class DecodeDocumentTextTest {
    private fun bytesOf(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `plain UTF-8 is read as UTF-8`() {
        assertEquals("naïve café ☕", decodeDocumentText("naïve café ☕".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a UTF-8 mark is taken off the front`() {
        assertEquals("héllo", decodeDocumentText(bytesOf(0xEF, 0xBB, 0xBF) + "héllo".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a UTF-16 mark says which way round the bytes are`() {
        assertEquals("héllo", decodeDocumentText(bytesOf(0xFF, 0xFE) + "héllo".toByteArray(Charsets.UTF_16LE)))
        assertEquals("héllo", decodeDocumentText(bytesOf(0xFE, 0xFF) + "héllo".toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `bytes that aren't UTF-8 fall back to Latin-1, one character a byte`() {
        // "café" as an old Windows or Latin-1 editor would save it: the é is a lone 0xE9.
        assertEquals("café", decodeDocumentText(bytesOf(0x63, 0x61, 0x66, 0xE9)))
    }

    @Test
    fun `every byte value decodes to something`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(256, decodeDocumentText(all).length)
    }

    @Test
    fun `no bytes is no text`() {
        assertEquals("", decodeDocumentText(ByteArray(0)))
    }

    @Test
    fun `a mark with nothing after it is no text`() {
        assertEquals("", decodeDocumentText(bytesOf(0xEF, 0xBB, 0xBF)))
        assertEquals("", decodeDocumentText(bytesOf(0xFF, 0xFE)))
    }
}

class WordTextCollectorTest {
    /** Feeds markup written as words: `<p` opens, `p>` closes, anything else is a run's text. */
    private fun collect(vararg steps: String): String {
        val collector = WordTextCollector()
        for (step in steps) {
            when {
                step.startsWith("<") -> collector.open(step.drop(1))
                step.endsWith(">") -> collector.close(step.dropLast(1))
                else -> collector.text(step)
            }
        }
        return collector.result()
    }

    @Test
    fun `paragraphs are set apart by a blank line`() {
        assertEquals("One\n\nTwo", collect("<p", "One", "p>", "<p", "Two", "p>"))
    }

    @Test
    fun `runs of one paragraph join up`() {
        assertEquals("Hello, world", collect("<p", "<r", "Hello, ", "r>", "<r", "world", "r>", "p>"))
    }

    @Test
    fun `tabs and breaks written as tags become characters`() {
        assertEquals("a\tb\nc\nd", collect("<p", "a", "<tab", "tab>", "b", "<br", "br>", "c", "<cr", "cr>", "d", "p>"))
    }

    @Test
    fun `a table is a line to a row, with bars after its cells`() {
        val text = collect(
            "<tbl",
            "<tr", "<tc", "<p", "Name", "p>", "tc>", "<tc", "<p", "Age", "p>", "tc>", "tr>",
            "<tr", "<tc", "<p", "Ada", "p>", "tc>", "<tc", "<p", "36", "p>", "tc>", "tr>",
            "tbl>"
        )
        assertEquals("Name  |  Age  |  \nAda  |  36  |", text)
    }

    @Test
    fun `paragraphs inside a cell run together`() {
        assertEquals("onetwo  |", collect("<tr", "<tc", "<p", "one", "p>", "<p", "two", "p>", "tc>", "tr>"))
    }

    @Test
    fun `an empty cell leaves no bar`() {
        assertEquals("only  |", collect("<tr", "<tc", "<p", "  ", "p>", "tc>", "<tc", "<p", "only", "p>", "tc>", "tr>"))
    }

    @Test
    fun `text after a table is body text again`() {
        assertEquals("cell  |  \nafter", collect("<tr", "<tc", "cell", "tc>", "tr>", "<p", "after", "p>"))
    }

    @Test
    fun `tags it has no use for change nothing`() {
        assertEquals("text", collect("<body", "<sectPr", "sectPr>", "<p", "<pPr", "pPr>", "text", "p>", "body>"))
    }

    @Test
    fun `nothing collected is nothing`() {
        assertEquals("", collect("<p", "p>", "<p", "   ", "p>"))
    }
}

class StripWordMarkupTest {
    @Test
    fun `paragraphs, rows and cells leave their marks, and other tags leave none`() {
        val markup = "<w:body><w:p><w:r><w:t>One</w:t></w:r></w:p><w:p><w:r><w:t>Two</w:t></w:r></w:p></w:body>"
        assertEquals("One\n\nTwo", stripWordMarkup(markup))
    }

    @Test
    fun `a table keeps its shape`() {
        val markup = "<w:tbl><w:tr><w:tc><w:t>a</w:t></w:tc><w:tc><w:t>b</w:t></w:tc></w:tr></w:tbl>"
        assertEquals("|  a  |  b", stripWordMarkup(markup))
    }

    @Test
    fun `the five entities are decoded`() {
        assertEquals("a < b > c & d \"e\" 'f'", stripWordMarkup("<w:t>a &lt; b &gt; c &amp; d &quot;e&quot; &apos;f&apos;</w:t>"))
    }

    @Test
    fun `an ampersand that spells out a bracket stays spelled out`() {
        assertEquals("&lt;tag&gt;", stripWordMarkup("<w:t>&amp;lt;tag&amp;gt;</w:t>"))
    }

    @Test
    fun `markup with no words comes to nothing`() {
        assertEquals("", stripWordMarkup("<w:body><w:sectPr/></w:body>"))
    }
}

class PagePaperTest {
    @Test
    fun `the sheet has A4's proportions`() {
        assertEquals(1200, PagePaper.Width)
        assertEquals(1696, PagePaper.Height)
    }

    @Test
    fun `text keeps inside the margins`() {
        assertEquals(1032, PagePaper.TextWidth)
        assertEquals(1528, PagePaper.TextHeight)
    }

    @Test
    fun `a page drawn at another width keeps the proportions, rounding down`() {
        assertEquals(1696, PagePaper.heightFor(1200))
        assertEquals(282, PagePaper.heightFor(200))
        assertEquals(1, PagePaper.heightFor(0))
    }

    @Test
    fun `a thumbnail is the sheet scaled down`() {
        assertEquals(1f, PagePaper.scaleFor(1200), 0f)
        assertEquals(1f / 6f, PagePaper.scaleFor(200), 0.0001f)
    }
}

class PaginateTest {
    private val text = "abcdefghijklmnopqrstuvwxyz"

    @Test
    fun `pages follow one another with nothing lost or repeated`() {
        val split = paginate(text) { 10 }
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uvwxyz"), split.pages.map { it.toString() })
        assertFalse(split.cutShort)
    }

    @Test
    fun `a page that would take nothing takes one character, so the text still ends`() {
        val split = paginate("abc") { 0 }
        assertEquals(listOf("a", "b", "c"), split.pages.map { it.toString() })
    }

    @Test
    fun `a page can't take more than there is`() {
        val split = paginate("abc") { 1000 }
        assertEquals(listOf("abc"), split.pages.map { it.toString() })
    }

    @Test
    fun `text left over at the page limit is reported`() {
        val split = paginate(text, pageLimit = 2) { 10 }
        assertEquals(listOf("abcdefghij", "klmnopqrst"), split.pages.map { it.toString() })
        assertTrue(split.cutShort)
    }

    @Test
    fun `text that ends exactly at the page limit isn't cut short`() {
        val split = paginate("abcdefghij" + "klmnopqrst", pageLimit = 2) { 10 }
        assertEquals(2, split.pages.size)
        assertFalse(split.cutShort)
    }

    @Test
    fun `no text is no pages`() {
        val split = paginate("") { 10 }
        assertTrue(split.pages.isEmpty())
        assertFalse(split.cutShort)
    }

    @Test
    fun `each page is measured from where it starts, a stretch at a time`() {
        val long = "x".repeat(20_000)
        val seen = ArrayList<Int>()
        val split = paginate(long) { ahead ->
            seen += ahead.length
            ahead.length
        }
        // The whole of each stretch fitted, so each page is a stretch: 8000, 8000, then the rest.
        assertEquals(listOf(8000, 8000, 4000), seen)
        assertEquals(listOf(8000, 8000, 4000), split.pages.map { it.length })
    }

    @Test
    fun `a long document stops at 250 pages`() {
        val split = paginate("x".repeat(1000)) { 2 }
        assertEquals(MaxDocumentPages, split.pages.size)
        assertEquals(250, MaxDocumentPages)
        assertTrue(split.cutShort)
    }
}
