package com.kobblo.gallery.file.explorer.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesTheTestFile() {
        val lyrics = LrcParser.parse(
            "[ar:Aria Vale]\n[ti:First Light]\n[00:00.50]Morning comes in slowly\n[00:03.00]Over the harbor wall\n" +
                "[00:05.50]We keep the fire burning\n[00:08.00]Through it all\n[00:10.50]First light\n"
        )
        assertTrue(lyrics.synced)
        assertEquals(5, lyrics.lines.size)
        assertEquals(LyricLine(500L, "Morning comes in slowly"), lyrics.lines[0])
        assertEquals(LyricLine(10_500L, "First light"), lyrics.lines[4])
    }

    @Test
    fun readsEveryTimestampForm() {
        fun timeOf(tag: String) = LrcParser.parse("$tag x").lines.single().timeMs
        assertEquals(83_450L, timeOf("[01:23.45]"))
        assertEquals(83_456L, timeOf("[01:23.456]"))
        assertEquals(83_400L, timeOf("[01:23.4]"))
        assertEquals(83_000L, timeOf("[01:23]"))
        assertEquals(83_450L, timeOf("[01:23:45]"))
        assertEquals(3_683_450L, timeOf("[01:01:23.45]"))
        assertEquals(6_000_000L, timeOf("[100:00.00]"))
    }

    @Test
    fun oneLineCanHaveSeveralTimes() {
        val lines = LrcParser.parse("[00:20.00][00:10.00]Chorus\n[00:15.00]Verse").lines
        assertEquals(listOf(10_000L, 15_000L, 20_000L), lines.map { it.timeMs })
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lines.map { it.text })
    }

    @Test
    fun linesComeOutInTimeOrder() {
        val lines = LrcParser.parse("[00:09.00]c\n[00:01.00]a\n[00:05.00]b").lines
        assertEquals(listOf("a", "b", "c"), lines.map { it.text })
    }

    @Test
    fun offsetMovesEverythingAndNeverBelowZero() {
        val sooner = LrcParser.parse("[offset:+500]\n[00:01.00]a\n[00:00.20]b").lines
        assertEquals(listOf(0L, 500L), sooner.map { it.timeMs })
        val later = LrcParser.parse("[offset:-250]\n[00:01.00]a").lines
        assertEquals(1250L, later.single().timeMs)
    }

    @Test
    fun wordTimingsAreDropped() {
        val line = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>there").lines.single()
        assertEquals("Hello there", line.text)
    }

    @Test
    fun copesWithWindowsLineEndingsAndAByteOrderMark() {
        val lyrics = LrcParser.parse("﻿[00:01.00]one\r\n[00:02.00]two\r\n")
        assertEquals(listOf("one", "two"), lyrics.lines.map { it.text })
    }

    @Test
    fun blankTimedLinesAreKeptAsGaps() {
        val lines = LrcParser.parse("[00:01.00]one\n[00:02.00]\n[00:03.00]two").lines
        assertEquals(listOf("one", "", "two"), lines.map { it.text })
    }

    @Test
    fun textWithNoTimesIsPlainLyrics() {
        val lyrics = LrcParser.parse("\n\nFirst line\n\nSecond line\n[ti:Song]\n\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("First line", "", "Second line"), lyrics.lines.map { it.text })
    }

    @Test
    fun bracketsInsideALineStayPartOfIt() {
        val lyrics = LrcParser.parse("[00:01.00][Chorus] again")
        assertEquals("[Chorus] again", lyrics.lines.single().text)
    }

    @Test
    fun findsTheLineAtAPosition() {
        val lines = listOf(LyricLine(1000, "a"), LyricLine(2000, "b"), LyricLine(3000, "c"))
        assertEquals(-1, LrcParser.lineIndexAt(lines, 999))
        assertEquals(0, LrcParser.lineIndexAt(lines, 1000))
        assertEquals(0, LrcParser.lineIndexAt(lines, 1999))
        assertEquals(1, LrcParser.lineIndexAt(lines, 2000))
        assertEquals(2, LrcParser.lineIndexAt(lines, 99_000))
        assertEquals(-1, LrcParser.lineIndexAt(emptyList(), 5))
    }

    @Test
    fun decodesEveryEncodingOldFilesCameIn() {
        assertEquals("héllo", decodeLyricBytes("héllo".toByteArray(Charsets.UTF_8)))
        assertEquals("héllo", decodeLyricBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()))
        assertEquals("héllo", decodeLyricBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "héllo".toByteArray(Charsets.UTF_16LE)))
        assertEquals("héllo", decodeLyricBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "héllo".toByteArray(Charsets.UTF_16BE)))
        // Not valid UTF-8, so it must be the single-byte encoding, not a replacement character.
        assertEquals("héllo", decodeLyricBytes("héllo".toByteArray(charset("windows-1252"))))
    }

    @Test
    fun aByteOrderMarkIsNeverPartOfTheWords() {
        // A file that is nothing but its mark has no words in it.
        assertEquals("", decodeLyricBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
        assertEquals("", decodeLyricBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertEquals("", decodeLyricBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte())))
        assertEquals("", decodeLyricBytes(ByteArray(0)))
        // Two thirds of a mark isn't one. These bytes aren't UTF-8 either, so they are Western text.
        assertEquals("ï»", decodeLyricBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte())))
    }

    @Test
    fun decodesAnUnsyncedId3Frame() {
        val frame = byteArrayOf(3) + "eng".toByteArray() + "desc".toByteArray() + byteArrayOf(0) + "Some words".toByteArray()
        assertEquals("Some words", Id3Lyrics.decodeUslt(frame))
    }

    @Test
    fun decodesAnUnsyncedUtf16Id3Frame() {
        val description = byteArrayOf(0, 0)
        val text = "Words".toByteArray(Charsets.UTF_16BE)
        val frame = byteArrayOf(2) + "eng".toByteArray() + description + text
        assertEquals("Words", Id3Lyrics.decodeUslt(frame))
    }

    @Test
    fun aMalformedId3FrameIsNotAnError() {
        assertNull(Id3Lyrics.decodeUslt(byteArrayOf(0, 1)))
        assertNull(Id3Lyrics.decodeSylt(byteArrayOf(0, 1)))
    }

    @Test
    fun decodesASyncedId3Frame() {
        fun time(ms: Int) = byteArrayOf((ms ushr 24).toByte(), (ms ushr 16).toByte(), (ms ushr 8).toByte(), ms.toByte())
        val frame = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1) + byteArrayOf(0) +
            "two".toByteArray() + byteArrayOf(0) + time(2000) +
            "one".toByteArray() + byteArrayOf(0) + time(70_000)
        val lyrics = Id3Lyrics.decodeSylt(frame)
        assertNotNull(lyrics)
        assertTrue(lyrics!!.synced)
        assertEquals(listOf(LyricLine(2000, "two"), LyricLine(70_000, "one")), lyrics.lines)
    }

    @Test
    fun syncedFramesInMpegFramesAreLeftOut() {
        val frame = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(1, 1) + byteArrayOf(0) +
            "a".toByteArray() + byteArrayOf(0, 0, 0, 0, 1)
        assertNull(Id3Lyrics.decodeSylt(frame))
    }
}
