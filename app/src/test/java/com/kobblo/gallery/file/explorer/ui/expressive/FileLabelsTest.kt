package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import java.util.Date
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FileLabelsTest {
    private lateinit var locale: Locale

    @Before
    fun useEnglish() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
    }

    private fun file(
        size: String = "",
        bytes: Long = 0,
        modified: Long = 0,
        changed: String = "",
        type: String = "file",
        items: Int? = null
    ) = FileItem(
        id = 1,
        path = "/storage/emulated/0/a",
        name = "a",
        type = type,
        kind = type,
        size = size,
        changed = changed,
        owner = "Me",
        sizeBytes = bytes,
        lastModified = modified,
        folderItemCount = items
    )

    @Test
    fun `a listing's own size is used when it is one`() {
        assertEquals("1.2 MB", fileSizeText(file(size = "1.2 MB", bytes = 5)))
    }

    @Test
    fun `a count or a placeholder gives way to the bytes`() {
        for (listed in listOf("3 files", "12 items", "--", "Unknown", "")) {
            assertEquals(listed, FileSizes.label(1_234), fileSizeText(file(size = listed, bytes = 1_234)))
        }
    }

    @Test
    fun `with no size to give there is none`() {
        assertEquals("", fileSizeText(file(size = "--", bytes = 0)))
        assertEquals("", fileSizeText(file(size = "", bytes = 0)))
    }

    @Test
    fun `size and date are joined when both are known`() {
        val moment = 1_700_000_000_000L
        assertEquals("1.2 MB · " + DisplayDates.dateDotTime(moment), formatFileMeta(file(size = "1.2 MB", modified = moment)))
    }

    @Test
    fun `one of the two alone stands by itself`() {
        assertEquals("1.2 MB", formatFileMeta(file(size = "1.2 MB")))
        assertEquals("Last week", formatFileMeta(file(changed = "Last week")))
        assertEquals("", formatFileMeta(file()))
    }

    @Test
    fun `a timestamp is preferred to the listing's wording of it`() {
        val moment = 1_700_000_000_000L
        assertEquals(DisplayDates.dateDotTime(moment), formatFileMeta(file(modified = moment, changed = "Last week")))
        assertEquals(DisplayDates.date().format(Date(moment)), formatFileDate(file(modified = moment, changed = "Last week")))
        assertEquals("Last week", formatFileDate(file(changed = "Last week")))
    }

    @Test
    fun `a folder on the phone says how many things it holds`() {
        assertEquals(FolderNote.Items(4), deviceFolderNote(file(type = "folder", items = 4)))
        assertEquals(FolderNote.Items(0), deviceFolderNote(file(type = "folder", items = 0)))
    }

    @Test
    fun `a listing that counted and sized a folder itself is kept whole`() {
        val listed = "4 files · 2 MB"
        assertEquals(FolderNote.Listed(listed), deviceFolderNote(file(type = "folder", size = listed, items = 9)))
    }

    @Test
    fun `without a count a folder says what its listing wrote, and reads as empty when that is nothing`() {
        assertEquals(FolderNote.Listed("3 files"), deviceFolderNote(file(type = "folder", size = "3 files")))
        assertEquals(FolderNote.Items(0), deviceFolderNote(file(type = "folder", size = "")))
        assertEquals(FolderNote.Items(0), deviceFolderNote(file(type = "folder", size = "Folder")))
        assertEquals(FolderNote.Items(0), deviceFolderNote(file(type = "folder", size = "folder")))
    }

    @Test
    fun `a folder elsewhere is counted when the service says, else dated, else only named`() {
        assertEquals(FolderNote.Items(7), remoteFolderNote(file(type = "folder", items = 7, changed = "Yesterday")))
        assertEquals(FolderNote.Listed("Yesterday"), remoteFolderNote(file(type = "folder", changed = "Yesterday")))
        assertEquals(FolderNote.Unknown, remoteFolderNote(file(type = "folder", changed = "")))
        assertEquals(FolderNote.Unknown, remoteFolderNote(file(type = "folder", changed = "Unknown")))
    }
}
