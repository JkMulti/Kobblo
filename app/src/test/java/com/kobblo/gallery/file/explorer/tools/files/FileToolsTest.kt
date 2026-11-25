package com.kobblo.gallery.file.explorer.tools.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HexFormatTest {
    private val bytes = "Synthetic notes".toByteArray()

    @Test fun `hex column splits at the middle and pads a short row`() {
        assertEquals("53 79 6E 74  68 65 74 69", HexFormat.hexColumn(bytes, 0, 8, 8))
        assertEquals("53 79 __ __  __ __ __ __".replace('_', ' '), HexFormat.hexColumn(bytes, 0, 2, 8))
    }

    @Test fun `hex digits cover the full byte range`() {
        val row = byteArrayOf(0x00, 0x0F, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x10, 0x01, 0xAB.toByte())
        assertEquals("00 0F 7F 80  FF 10 01 AB", HexFormat.hexColumn(row, 0, 8, 8))
    }

    @Test fun `text column shows only printable ascii`() {
        val row = byteArrayOf(0x41, 0x7E, 0x20, 0x1F, 0x7F, 0xE9.toByte())
        assertEquals("A~ ...", HexFormat.textColumn(row, 0, 6))
    }

    @Test fun `offsets are fixed width until the file needs more`() {
        assertEquals(8, HexFormat.offsetDigits(0xFFFFFFFFL))
        assertEquals(12, HexFormat.offsetDigits(0x100000000L))
        assertEquals("000001F0", HexFormat.offset(0x1F0, 8))
    }

    @Test fun `row count rounds up`() {
        assertEquals(0, HexFormat.rowCount(0, 8))
        assertEquals(1, HexFormat.rowCount(1, 8))
        assertEquals(1, HexFormat.rowCount(8, 8))
        assertEquals(2, HexFormat.rowCount(9, 8))
    }

    @Test fun `typed offsets read as hex unless marked decimal`() {
        assertEquals(0x1F0L, HexFormat.parseOffset("1f0"))
        assertEquals(0x1F0L, HexFormat.parseOffset("0x1F0"))
        assertEquals(100L, HexFormat.parseOffset("#100"))
        assertNull(HexFormat.parseOffset("nope"))
        assertNull(HexFormat.parseOffset(""))
        assertNull(HexFormat.parseOffset("#-1"))
    }
}

class ChecksumsTest {
    @Test fun `matches the published test vectors`() {
        val digests = Checksums.compute("abc".toByteArray().inputStream())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digests["MD5"])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digests["SHA-1"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digests["SHA-256"])
    }

    @Test fun `an empty file still has digests`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Checksums.compute(ByteArray(0).inputStream())["MD5"])
    }

    @Test fun `a file larger than the buffer is read whole`() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        var lastProgress = 0L
        val digests = Checksums.compute(data.inputStream(), onProgress = { lastProgress = it })
        assertEquals(Checksums.toHex(expected), digests["SHA-256"])
        assertEquals(1_000_000L, lastProgress)
    }

    @Test(expected = IllegalStateException::class)
    fun `cancelling stops the read`() {
        Checksums.compute(ByteArray(1024).inputStream(), checkCancelled = { throw IllegalStateException() })
    }
}

class FontInfoTest {
    @get:Rule val folder = TemporaryFolder()

    /** The smallest font that has what the parser reads: a name table and a glyph count. */
    private fun font(tag: Int, family: String, style: String): ByteArray {
        val names = ByteArrayOutputStream()
        val strings = ByteArrayOutputStream()
        fun record(out: DataOutputStream, id: Int, text: String) {
            val encoded = text.toByteArray(Charsets.UTF_16BE)
            out.writeShort(3)
            out.writeShort(1)
            out.writeShort(0x0409)
            out.writeShort(id)
            out.writeShort(encoded.size)
            out.writeShort(strings.size())
            strings.write(encoded)
        }
        DataOutputStream(names).apply {
            writeShort(0)
            writeShort(2)
            writeShort(6 + 2 * 12)
            record(this, 1, family)
            record(this, 2, style)
            write(strings.toByteArray())
        }
        val nameTable = names.toByteArray()
        val maxp = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0x00010000)
                writeShort(321)
            }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(tag)
            writeShort(2)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            val first = 12 + 2 * 16
            writeBytes("name")
            writeInt(0)
            writeInt(first)
            writeInt(nameTable.size)
            writeBytes("maxp")
            writeInt(0)
            writeInt(first + nameTable.size)
            writeInt(maxp.size)
            write(nameTable)
            write(maxp)
        }
        return out.toByteArray()
    }

    @Test fun `reads a truetype font's names and glyph count`() {
        val file = folder.newFile("a.ttf").apply { writeBytes(font(0x00010000, "Test Sans", "Bold")) }
        val info = FontInfo.parse(file)!!
        assertEquals("TrueType", info.format)
        assertEquals("Test Sans", info.family)
        assertEquals("Bold", info.style)
        assertEquals(321, info.glyphCount)
    }

    @Test fun `reads an opentype font`() {
        val file = folder.newFile("a.otf").apply { writeBytes(font(0x4F54544F, "Test Serif", "Regular")) }
        assertEquals("OpenType", FontInfo.parse(file)!!.format)
    }

    @Test fun `something else isn't a font`() {
        assertNull(FontInfo.parse(folder.newFile("a.ttf").apply { writeBytes("not a font at all, sorry".toByteArray()) }))
        assertNull(FontInfo.parse(folder.newFile("b.ttf")))
    }
}

class FileSnifferTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun `recognizes common formats from their first bytes`() {
        assertEquals("PNG image", FileSniffer.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D))?.label)
        assertEquals("JPEG image", FileSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))?.label)
        assertEquals("PDF document", FileSniffer.sniff("%PDF-1.7".toByteArray())?.label)
        assertEquals("7-Zip archive", FileSniffer.sniff(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4))?.label)
        assertEquals("SQLite database", FileSniffer.sniff("SQLite format 3\u0000".toByteArray())?.label)
    }

    @Test fun `tells apart formats sharing a wrapper`() {
        assertEquals("WebP image", FileSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))?.label)
        assertEquals("WAV audio", FileSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1))?.label)
        assertEquals("HEIF image", FileSniffer.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1))?.label)
        assertEquals("MPEG-4 video", FileSniffer.sniff("\u0000\u0000\u0000\u0018ftypisom".toByteArray(Charsets.ISO_8859_1))?.label)
    }

    @Test fun `looks inside a zip for what it really is`() {
        val zip = bytes(0x50, 0x4B, 0x03, 0x04)
        assertEquals("ZIP archive", FileSniffer.sniff(zip + "readme.txt".toByteArray())?.label)
        assertEquals("Android app (APK)", FileSniffer.sniff(zip + "AndroidManifest.xml".toByteArray())?.label)
        assertEquals("Word document", FileSniffer.sniff(zip + "word/document.xml".toByteArray())?.label)
    }

    @Test fun `finds tar's mark past the start`() {
        val tar = ByteArray(300).also { "ustar".toByteArray().copyInto(it, 257) }
        assertEquals("Tar archive", FileSniffer.sniff(tar)?.label)
    }

    @Test fun `text counts as text and noise counts as nothing`() {
        assertTrue(FileSniffer.sniff("just words\n".toByteArray())!!.isText)
        assertNull(FileSniffer.sniff(ByteArray(64) { ((it * 37) % 256).toByte() }))
        assertNull(FileSniffer.sniff(ByteArray(0)))
    }
}

class CsvTest {
    @Test fun `splits rows and fields`() {
        val table = Csv.parse("a,b\n1,2\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), table.rows)
        assertEquals(2, table.columnCount)
        assertFalse(table.truncated)
    }

    @Test fun `quotes protect separators, line breaks and quotes`() {
        val table = Csv.parse("\"a,b\",\"line\nbreak\",\"say \"\"hi\"\"\"\n")
        assertEquals(listOf(listOf("a,b", "line\nbreak", "say \"hi\"")), table.rows)
    }

    @Test fun `keeps empty fields and handles crlf`() {
        assertEquals(listOf(listOf("a", "", "c"), listOf("", "", "")), Csv.parse("a,,c\r\n,,\r\n").rows)
    }

    @Test fun `a last line without a break still counts`() {
        assertEquals(listOf(listOf("a"), listOf("b")), Csv.parse("a\nb").rows)
    }

    @Test fun `detects the separator`() {
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals('\t', Csv.detectDelimiter("a\tb\n"))
        assertEquals(',', Csv.detectDelimiter("one column\n"))
        assertEquals(';', Csv.detectDelimiter("\"a,b,c\";d\n"))
    }

    @Test fun `a byte order mark isn't part of the first field`() {
        assertEquals("a", Csv.parse("﻿a,b\n").rows[0][0])
    }

    @Test fun `stops at the row limit and says so`() {
        val table = Csv.parse("1\n2\n3\n", maxRows = 2)
        assertEquals(2, table.rows.size)
        assertTrue(table.truncated)
    }

    @Test fun `blank lines are skipped`() {
        assertEquals(listOf(listOf("a"), listOf("b")), Csv.parse("a\n\nb\n").rows)
    }
}

class VCardTest {
    @Test fun `reads a contact`() {
        val card = VCard.parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Ada Lovelace
            N:Lovelace;Ada;;;
            ORG:Analytical Engines;Research
            TITLE:Mathematician
            TEL;TYPE=CELL:+1 555 0100
            TEL;TYPE=WORK:+1 555 0101
            EMAIL:ada@example.com
            ADR;TYPE=HOME:;;12 St James Sq;London;;SW1;UK
            BDAY:1815-12-10
            NOTE:First\, and\nforemost
            END:VCARD
            """.trimIndent()
        ).single()
        assertEquals("Ada Lovelace", card.name)
        assertEquals("Analytical Engines, Research", card.organization)
        assertEquals("Mathematician", card.title)
        assertEquals(listOf("+1 555 0100", "+1 555 0101"), card.phones)
        assertEquals(listOf("ada@example.com"), card.emails)
        assertEquals(listOf("12 St James Sq, London, SW1, UK"), card.addresses)
        assertEquals("1815-12-10", card.birthday)
        assertEquals("First, and\nforemost", card.note)
    }

    @Test fun `several contacts, folded lines and grouped properties`() {
        val cards = VCard.parse("BEGIN:VCARD\r\nFN:One\r\nitem1.EMAIL:long@exam\r\n ple.com\r\nEND:VCARD\r\nBEGIN:VCARD\r\nN:Two;Second;;;\r\nEND:VCARD\r\n")
        assertEquals(2, cards.size)
        assertEquals(listOf("long@example.com"), cards[0].emails)
        assertEquals("Second Two", cards[1].name)
    }

    @Test fun `text outside a card is ignored`() {
        assertTrue(VCard.parse("FN:Nobody\nnonsense").isEmpty())
    }
}

class ICalendarTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun date(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: TimeZone = utc): Date =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.time

    @Test fun `reads events and sorts them by time`() {
        val events = ICalendar.parse(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:Later
            DTSTART:20261201T100000Z
            DTEND:20261201T110000Z
            LOCATION:Room\, 2
            DESCRIPTION:Bring\nnotes
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:Sooner
            DTSTART;VALUE=DATE:20261101
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )
        assertEquals(listOf("Sooner", "Later"), events.map { it.title })
        assertTrue(events[0].allDay)
        assertEquals(date(2026, 12, 1, 10, 0), events[1].start)
        assertEquals(date(2026, 12, 1, 11, 0), events[1].end)
        assertEquals("Room, 2", events[1].location)
        assertEquals("Bring\nnotes", events[1].description)
        assertFalse(events[1].allDay)
    }

    @Test fun `a named time zone is honored`() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val event = ICalendar.parse("BEGIN:VEVENT\nDTSTART;TZID=Asia/Tokyo:20260101T090000\nEND:VEVENT").single()
        assertEquals(date(2026, 1, 1, 9, 0, tokyo), event.start)
    }

    @Test fun `a broken date leaves the time out rather than failing`() {
        val event = ICalendar.parse("BEGIN:VEVENT\nSUMMARY:x\nDTSTART:soon\nEND:VEVENT").single()
        assertNull(event.start)
        assertEquals("", ICalendar.describeTime(event))
    }

    @Test fun `times read naturally`() {
        val event = CalendarEvent("x", date(2026, 3, 4, 10, 0), date(2026, 3, 4, 11, 0), false, null, null)
        val saved = TimeZone.getDefault()
        TimeZone.setDefault(utc)
        try {
            val text = ICalendar.describeTime(event, Locale.US)
            assertTrue(text, text.startsWith("Mar 4, 2026"))
            assertTrue(text, "–" in text)
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}

class MimeGuessTest {
    @Test fun `answers from its own table first, then the platform's`() {
        assertEquals("text/plain", MimeGuess.forName("notes.TXT"))
        assertEquals("font/ttf", MimeGuess.forName("a.ttf"))
        assertEquals("application/x-custom", MimeGuess.forName("a.custom") { "application/x-custom" })
        assertEquals(MimeGuess.FALLBACK, MimeGuess.forName("a.custom"))
        assertEquals(MimeGuess.FALLBACK, MimeGuess.forName("noextension"))
    }
}

class ContentSearcherTest {
    @get:Rule val folder = TemporaryFolder()

    private fun search(query: String): List<ContentMatch> {
        val found = ArrayList<ContentMatch>()
        ContentSearcher.search(folder.root, query) { found.add(it) }
        return found
    }

    @Test fun `finds lines in nested text files, ignoring case`() {
        folder.newFile("a.txt").writeText("first line\nHello world\nlast\n")
        folder.newFolder("deep", "er")
        java.io.File(folder.root, "deep/er/b.md").writeText("hello again\n")
        val found = search("hello")
        assertEquals(2, found.size)
        assertEquals(2, found.first { it.path.endsWith("a.txt") }.line)
        assertEquals(1, found.first { it.path.endsWith("b.md") }.line)
        assertEquals("Hello world", found.first { it.path.endsWith("a.txt") }.text)
    }

    @Test fun `skips binary and hidden files`() {
        folder.newFile("bin.dat").writeBytes(byteArrayOf(0, 1, 2) + "needle".toByteArray())
        folder.newFile(".secret").writeText("needle")
        folder.newFolder(".hidden")
        java.io.File(folder.root, ".hidden/x.txt").writeText("needle")
        assertTrue(search("needle").isEmpty())
    }

    @Test fun `a long line is cut around the match`() {
        folder.newFile("long.txt").writeText("x".repeat(500) + "needle" + "y".repeat(500))
        val text = search("needle").single().text
        assertTrue(text.length < 200)
        assertTrue("needle" in text)
    }

    @Test fun `stops when told enough has been found`() {
        folder.newFile("a.txt").writeText("hit\nhit\nhit\n")
        var seen = 0
        ContentSearcher.search(folder.root, "hit") {
            seen++
            false
        }
        assertEquals(1, seen)
    }

    @Test fun `an empty query finds nothing`() {
        folder.newFile("a.txt").writeText("anything")
        assertTrue(search("").isEmpty())
    }
}

class LineDiffTest {
    private fun kinds(old: String, new: String) =
        LineDiff.diff(old.split("\n"), new.split("\n")).map { it.kind to it.text }

    @Test fun `identical text has no changes`() {
        assertTrue(LineDiff.changesOnly(LineDiff.diff(listOf("a", "b"), listOf("a", "b"))).isEmpty())
    }

    @Test fun `shows a changed line as removed and added`() {
        assertEquals(
            listOf(
                DiffLine.Kind.SAME to "a",
                DiffLine.Kind.REMOVED to "b",
                DiffLine.Kind.ADDED to "x",
                DiffLine.Kind.SAME to "c"
            ),
            kinds("a\nb\nc", "a\nx\nc")
        )
    }

    @Test fun `line numbers follow each side`() {
        val lines = LineDiff.diff(listOf("a", "b"), listOf("new", "a", "b"))
        assertEquals(DiffLine(DiffLine.Kind.ADDED, "new", null, 1), lines[0])
        assertEquals(DiffLine(DiffLine.Kind.SAME, "a", 1, 2), lines[1])
        assertEquals(DiffLine(DiffLine.Kind.SAME, "b", 2, 3), lines[2])
    }

    @Test fun `changes keep some lines of context`() {
        val old = (1..10).map { "line $it" }
        val new = old.toMutableList().also { it[4] = "changed" }
        val shown = LineDiff.changesOnly(LineDiff.diff(old, new), context = 1)
        assertEquals(listOf("line 4", "line 5", "changed", "line 6"), shown.map { it.text })
    }

    @Test fun `handles everything added and everything removed`() {
        assertEquals(listOf(DiffLine.Kind.ADDED to "a"), LineDiff.diff(emptyList(), listOf("a")).map { it.kind to it.text })
        assertEquals(listOf(DiffLine.Kind.REMOVED to "a"), LineDiff.diff(listOf("a"), emptyList()).map { it.kind to it.text })
    }

    @Test fun `says when a text is too long to compare`() {
        assertTrue(LineDiff.isTooLarge(5000, 1))
        assertFalse(LineDiff.isTooLarge(10, 10))
    }
}
