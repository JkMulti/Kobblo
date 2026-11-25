package com.kobblo.gallery.file.explorer.data

import com.kobblo.gallery.file.explorer.data.core.FileSizes
import nz.mega.sdk.MegaItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MegaItemsTest {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun file(name: String?, sizeBytes: Long = 0L) = MegaItems.item(
        handle = 7L,
        name = name,
        isFolder = false,
        sizeBytes = sizeBytes,
        modifiedSeconds = 0L,
        isEmptyFolder = false,
        owner = "me",
        day = day
    )

    @Test
    fun `a file's row carries what MEGA said about it`() {
        val handle = 123_456_789_012_345L
        val item = MegaItems.item(handle, "Holiday.JPG", false, 1_234L, 1_700_000_000L, false, "anna@example.com", day)
        assertEquals(handle.hashCode(), item.id)
        assertEquals("mega_id:123456789012345", item.path)
        assertEquals("Holiday.JPG", item.name)
        assertEquals("File", item.kind)
        assertEquals(FileSizes.label(1_234L), item.size)
        assertEquals("2023-11-14", item.changed)
        assertEquals("anna@example.com", item.owner)
        assertEquals(1_234L, item.sizeBytes)
        assertEquals(1_700_000_000_000L, item.lastModified)
        assertFalse(item.isPinned)
        assertFalse(item.isEmptyFolder)
    }

    @Test
    fun `a file's type is its extension as written`() {
        assertEquals("JPG", file("Holiday.JPG").type)
        assertEquals("gz", file("site.tar.gz").type)
        assertEquals("bashrc", file(".bashrc").type)
        assertEquals("", file("README").type)
        assertEquals("", file("ends with dot.").type)
    }

    @Test
    fun `a node with no name is still listed`() {
        val item = file(null)
        assertEquals("Unknown", item.name)
        assertEquals("", item.type)
    }

    @Test
    fun `a folder reads Folder where a file has its size`() {
        val item = MegaItems.item(9L, "Trips.2024", true, 5_000L, 1_700_000_000L, true, "me", day)
        assertEquals("folder", item.type)
        assertEquals("Folder", item.kind)
        assertEquals("Folder", item.size)
        assertEquals(0L, item.sizeBytes)
        assertTrue(item.isEmptyFolder)
    }

    @Test
    fun `a negative handle keeps its sign in the path`() {
        assertEquals("mega_id:-42", MegaItems.item(-42L, "a", false, 0L, 0L, false, "me", day).path)
    }

    @Test
    fun `a handle is a number carried as text`() {
        assertEquals(42L, MegaItems.handleOf("42"))
        assertEquals(-5L, MegaItems.handleOf("-5"))
        assertEquals(Long.MAX_VALUE, MegaItems.handleOf("9223372036854775807"))
    }

    @Test
    fun `text that isn't a handle becomes the handle nothing has`() {
        for (text in listOf("", "abc", "12 ", "mega_id:12", "99999999999999999999", "1.5")) {
            assertEquals(text, 0L, MegaItems.handleOf(text))
        }
    }

    @Test
    fun `each account keeps its state in a folder named for it`() {
        assertEquals("mega_anna.b-c_1_example.com", MegaItems.stateFolderName("anna.b-c_1@example.com"))
        assertEquals("mega_a_b_c_d", MegaItems.stateFolderName("a b+c@d"))
        assertEquals("mega_.._.._etc", MegaItems.stateFolderName("../../etc"))
        assertEquals("mega___x", MegaItems.stateFolderName("é@x"))
    }

    @Test
    fun `no account and the default account share one folder`() {
        assertEquals("mega_default", MegaItems.stateFolderName(null))
        assertEquals("mega_default", MegaItems.stateFolderName(""))
        assertEquals("mega_default", MegaItems.stateFolderName("   "))
        assertEquals("mega_default", MegaItems.stateFolderName("default"))
    }
}
