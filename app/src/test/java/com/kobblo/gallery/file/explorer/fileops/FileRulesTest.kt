package com.kobblo.gallery.file.explorer.fileops

import com.kobblo.gallery.file.explorer.stableFileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRulesTest {
    @Test
    fun `a name has to be one path segment that fits`() {
        assertTrue(isUsableFileName("photo.jpg"))
        assertTrue(isUsableFileName(".hidden"))
        assertTrue(isUsableFileName("name with \$(odd) 'characters'"))
        assertTrue(isUsableFileName("x".repeat(255)))
        assertFalse(isUsableFileName("x".repeat(256)))
        assertFalse(isUsableFileName("日".repeat(86)))
        assertFalse(isUsableFileName(""))
        assertFalse(isUsableFileName("   "))
        assertFalse(isUsableFileName("."))
        assertFalse(isUsableFileName(".."))
        assertFalse(isUsableFileName("../escape.txt"))
        assertFalse(isUsableFileName("a/b.txt"))
        assertFalse(isUsableFileName("nul\u0000.txt"))
    }

    @Test
    fun `google's own documents and folders have nothing to download`() {
        assertTrue(DriveRules.isNativeGoogleType("application/vnd.google-apps.document"))
        assertTrue(DriveRules.isNativeGoogleType("application/vnd.google-apps.spreadsheet"))
        assertTrue(DriveRules.isNativeGoogleType("application/vnd.google-apps.folder"))
        assertFalse(DriveRules.isNativeGoogleType("image/jpeg"))
        assertFalse(DriveRules.isNativeGoogleType(null))
    }

    @Test
    fun `only a matching size proves a download finished`() {
        assertTrue(DriveRules.isComplete(100L, 100L))
        assertTrue(DriveRules.isComplete(0L, 0L))
        assertFalse(DriveRules.isComplete(100L, 99L))
        assertFalse("Not knowing the size proves nothing", DriveRules.isComplete(null, 100L))
    }

    @Test
    fun `like wildcards in a path mean themselves`() {
        assertEquals("/sdcard/my\\_photos", escapeForLike("/sdcard/my_photos"))
        assertEquals("100\\%", escapeForLike("100%"))
        assertEquals("a\\\\b", escapeForLike("a\\b"))
        assertEquals("/plain/path", escapeForLike("/plain/path"))
    }

    @Test
    fun `an id comes from the path, so a reload can't move a selection onto another file`() {
        val first = HashSet<Int>()
        val idA = stableFileId("/sdcard/a.jpg", first)
        val idB = stableFileId("/sdcard/b.jpg", first)
        // Read again, with a new file that sorts ahead of both. Numbering by position would have shifted.
        val second = HashSet<Int>()
        stableFileId("/sdcard/0-new.jpg", second)
        assertEquals(idA, stableFileId("/sdcard/a.jpg", second))
        assertEquals(idB, stableFileId("/sdcard/b.jpg", second))
        assertNotEquals(idA, idB)
    }

    @Test
    fun `ids are never negative and never repeat, even for paths that hash alike`() {
        val used = HashSet<Int>()
        // These two strings have the same hashCode.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val one = stableFileId("Aa", used)
        val two = stableFileId("BB", used)
        assertNotEquals(one, two)
        assertTrue(one >= 0 && two >= 0)
        assertTrue(stableFileId("-".repeat(50), used) >= 0)
    }
}
