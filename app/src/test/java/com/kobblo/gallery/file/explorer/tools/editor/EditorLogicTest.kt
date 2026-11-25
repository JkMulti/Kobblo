package com.kobblo.gallery.file.explorer.tools.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class EditHistoryTest {
    private fun apply(text: String, edit: TextEdit) =
        text.substring(0, edit.start) + edit.inserted + text.substring(edit.start + edit.removed.length)

    @Test fun `typing merges into one undo step`() {
        val history = EditHistory()
        history.record(TextEdit(0, "", "h"), now = 0)
        history.record(TextEdit(1, "", "i"), now = 100)
        val undo = history.undo()!!
        assertEquals(TextEdit(0, "hi", ""), undo)
        assertFalse(history.canUndo)
    }

    @Test fun `a pause starts a new undo step`() {
        val history = EditHistory(mergeWindowMs = 1000)
        history.record(TextEdit(0, "", "a"), now = 0)
        history.record(TextEdit(1, "", "b"), now = 5000)
        assertEquals("", history.undo()!!.inserted)
        assertTrue(history.canUndo)
    }

    @Test fun `a line break starts a new undo step`() {
        val history = EditHistory()
        history.record(TextEdit(0, "", "one"), now = 0)
        history.record(TextEdit(3, "", "\n"), now = 10)
        history.record(TextEdit(4, "", "two"), now = 20)
        assertEquals(TextEdit(4, "two", ""), history.undo())
    }

    @Test fun `an input method rewriting its word counts as one edit`() {
        val history = EditHistory()
        history.record(TextEdit(0, "", "he"), now = 0)
        history.record(TextEdit(0, "he", "hel"), now = 10)
        history.record(TextEdit(0, "hel", "hello"), now = 20)
        assertEquals(TextEdit(0, "hello", ""), history.undo())
        assertFalse(history.canUndo)
    }

    @Test fun `backspacing merges and restores in order`() {
        val history = EditHistory()
        history.record(TextEdit(4, "e", ""), now = 0)
        history.record(TextEdit(3, "d", ""), now = 10)
        assertEquals(TextEdit(3, "", "de"), history.undo())
    }

    @Test fun `undo and redo walk back and forth to the same text`() {
        val history = EditHistory(mergeWindowMs = 0)
        var text = "start"
        fun edit(e: TextEdit) {
            text = apply(text, e)
            history.record(e, now = 0)
        }
        edit(TextEdit(5, "", " one"))
        history.markSaved()
        edit(TextEdit(0, "start", "begin"))
        assertEquals("begin one", text)
        text = apply(text, history.undo()!!)
        assertEquals("start one", text)
        text = apply(text, history.redo()!!)
        assertEquals("begin one", text)
    }

    @Test fun `a new edit clears redo`() {
        val history = EditHistory()
        history.record(TextEdit(0, "", "a"), now = 0)
        history.undo()
        assertTrue(history.canRedo)
        history.record(TextEdit(0, "", "b"), now = 10_000)
        assertFalse(history.canRedo)
    }

    @Test fun `undoing back to the save clears the unsaved mark`() {
        val history = EditHistory()
        assertFalse(history.isDirty)
        history.record(TextEdit(0, "", "a"), now = 0)
        assertTrue(history.isDirty)
        history.markSaved()
        assertFalse(history.isDirty)
        // Doesn't merge into the saved step, so undoing returns exactly to what's on disk.
        history.record(TextEdit(1, "", "b"), now = 1)
        assertTrue(history.isDirty)
        history.undo()
        assertFalse(history.isDirty)
        history.undo()
        assertTrue(history.isDirty)
        history.redo()
        assertFalse(history.isDirty)
    }

    @Test fun `a saved state dropped from history can't be reached by undoing`() {
        val history = EditHistory(maxEdits = 2, mergeWindowMs = 0)
        history.markSaved()
        repeat(4) { history.record(TextEdit(it, "", "x\n"), now = it * 10_000L) }
        repeat(2) { history.undo() }
        assertFalse(history.canUndo)
        assertTrue(history.isDirty)
    }

    @Test fun `undoing back to a save whose own step was dropped is still clean`() {
        val history = EditHistory(maxEdits = 2, mergeWindowMs = 0)
        history.record(TextEdit(0, "", "saved\n"), now = 0)
        history.markSaved()
        repeat(2) { history.record(TextEdit(it, "", "x\n"), now = (it + 1) * 10_000L) }
        repeat(2) { history.undo() }
        assertFalse(history.canUndo)
        assertFalse(history.isDirty)
    }

    @Test fun `history gives up old steps past its memory limit`() {
        val history = EditHistory(maxChars = 10, mergeWindowMs = 0)
        history.record(TextEdit(0, "", "x".repeat(8)), now = 0)
        history.record(TextEdit(8, "", "y".repeat(8)), now = 10_000)
        assertEquals(8, history.undo()!!.removed.length)
        assertFalse(history.canUndo)
    }

    @Test fun `no-op edits aren't recorded`() {
        val history = EditHistory()
        history.record(TextEdit(0, "same", "same"), now = 0)
        assertFalse(history.canUndo)
    }

    @Test fun `empty history has nothing to give`() {
        val history = EditHistory()
        assertNull(history.undo())
        assertNull(history.redo())
    }
}

class TextSearchTest {
    private val plain = TextSearch.Options()

    @Test fun `finds every match ignoring case by default`() {
        val regex = TextSearch.compile("cat", plain)!!
        assertEquals(listOf(0..2, 8..10), TextSearch.findAll("Cat and cat", regex))
    }

    @Test fun `match case narrows it`() {
        val regex = TextSearch.compile("cat", TextSearch.Options(matchCase = true))!!
        assertEquals(listOf(8..10), TextSearch.findAll("Cat and cat", regex))
    }

    @Test fun `a plain search treats regex characters literally`() {
        val regex = TextSearch.compile("a.c", plain)!!
        assertEquals(listOf(4..6), TextSearch.findAll("abc a.c", regex))
    }

    @Test fun `a broken regex finds nothing rather than failing`() {
        assertNull(TextSearch.compile("(unclosed", TextSearch.Options(regex = true)))
        assertNull(TextSearch.compile("", plain))
    }

    @Test fun `whole word skips parts of words`() {
        val regex = TextSearch.compile("cat", TextSearch.Options(wholeWord = true))!!
        assertEquals(listOf(5..7), TextSearch.findAll("cats cat", regex))
    }

    @Test fun `zero length regex matches are skipped`() {
        val regex = TextSearch.compile("x*", TextSearch.Options(regex = true))!!
        assertEquals(listOf(1..2), TextSearch.findAll("axxb", regex))
    }

    @Test fun `nearest wraps around in both directions`() {
        val matches = listOf(2..3, 10..11, 20..21)
        assertEquals(1, TextSearch.nearest(matches, 5, forward = true))
        assertEquals(0, TextSearch.nearest(matches, 25, forward = true))
        assertEquals(0, TextSearch.nearest(matches, 5, forward = false))
        assertEquals(2, TextSearch.nearest(matches, 0, forward = false))
        assertEquals(-1, TextSearch.nearest(emptyList(), 0, forward = true))
    }

    @Test fun `plain replacements take dollar signs literally`() {
        val regex = TextSearch.compile("a", plain)!!
        assertEquals(listOf(0..0 to "\$1", 2..2 to "\$1"), TextSearch.planReplacements("aXa", regex, "\$1", plain))
    }

    @Test fun `regex replacements fill groups and escapes`() {
        val options = TextSearch.Options(regex = true)
        val regex = TextSearch.compile("(\\w+)=(\\w+)", options)!!
        assertEquals(listOf(0..2 to "b=a", 4..6 to "d=c"), TextSearch.planReplacements("a=b\nc=d", regex, "\$2=\$1", options))
        val comma = TextSearch.compile(",", options)!!
        assertEquals(listOf(1..1 to "\n"), TextSearch.planReplacements("a,b", comma, "\\n", options))
        // A group the pattern doesn't have is left out rather than failing.
        assertEquals(listOf(1..1 to "x"), TextSearch.planReplacements("a,b", comma, "x\$9", options))
    }

    @Test fun `many matches in a long text stay quick`() {
        val text = "word ".repeat(200_000)
        val regex = TextSearch.compile("word", plain)!!
        assertEquals(100_000, TextSearch.findAll(text, regex).size)
        assertEquals(200_000, TextSearch.planReplacements(text, regex, "x", plain).size)
    }

    @Test fun `a replacement plan lists each match in order`() {
        val options = TextSearch.Options(regex = true)
        val regex = TextSearch.compile("(a)(b)", options)!!
        assertEquals(
            listOf(0..1 to "ba", 3..4 to "ba"),
            TextSearch.planReplacements("ab ab", regex, "\$2\$1", options)
        )
        assertEquals(listOf(0..0 to "\$1"), TextSearch.planReplacements("x", TextSearch.compile("x", plain)!!, "\$1", plain))
    }

    @Test fun `replacing one match uses its groups`() {
        val options = TextSearch.Options(regex = true)
        val regex = TextSearch.compile("(a)(b)", options)!!
        assertEquals("ba", TextSearch.replacementFor("xab", 1..2, regex, "\$2\$1", options))
        assertEquals("plain", TextSearch.replacementFor("xab", 1..2, regex, "plain", plain))
    }
}

class LineIndexTest {
    @get:Rule val folder = TemporaryFolder()

    private fun lines(index: LineIndex): List<String> {
        RandomAccessFile(index.run { folder.root.listFiles()!!.first() }, "r").use { source ->
            return List(index.lineCount) { index.read(it, source) }
        }
    }

    private fun index(bytes: ByteArray, charset: java.nio.charset.Charset = Charsets.UTF_8): LineIndex {
        val file = folder.newFile()
        file.writeBytes(bytes)
        return LineIndex.build(file, charset)
    }

    @Test fun `finds each line`() {
        val index = index("one\ntwo\r\nthree".toByteArray())
        assertEquals(listOf("one", "two", "three"), lines(index))
    }

    @Test fun `a trailing line break makes no phantom line`() {
        assertEquals(listOf("one", "two"), lines(index("one\ntwo\n".toByteArray())))
    }

    @Test fun `an empty file has one empty line`() {
        val index = index(ByteArray(0))
        assertEquals(1, index.lineCount)
        assertEquals("", lines(index).single())
    }

    @Test fun `a very long line is cut on a character boundary`() {
        // Three bytes per character, so most fixed cuts would land inside one.
        val long = "日".repeat(5000)
        val index = index(long.toByteArray())
        assertTrue(index.lineCount > 1)
        val joined = lines(index).joinToString("")
        assertEquals(long, joined)
        assertFalse("�" in joined)
    }

    @Test fun `utf-16 lines split on real line breaks only`() {
        // U+0A0A has a 0x0A byte in it that must not count as a line break.
        val text = "firstਊword\nsecond"
        val index = index(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE), Charsets.UTF_16LE)
        assertEquals(listOf("firstਊword", "second"), lines(index))
    }

    @Test fun `utf-8 mark isn't part of the first line`() {
        val index = index(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi\nyo".toByteArray())
        assertEquals(listOf("hi", "yo"), lines(index))
    }

    @Test fun `finding moves on from where it is`() {
        val index = index("alpha\nbeta\ngamma BETA\n".toByteArray())
        assertEquals(1, index.findNext("beta", 0))
        assertEquals(2, index.findNext("beta", 2))
        assertEquals(-1, index.findNext("missing", 0))
    }

    @Test fun `scanning stops when told to`() {
        val file = folder.newFile()
        file.writeBytes("x\n".repeat(1_000_000).toByteArray())
        val index = LineIndex.build(file, Charsets.UTF_8, isActive = { false })
        assertEquals(1, index.lineCount)
    }
}
