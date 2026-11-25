package com.kobblo.gallery.file.explorer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlainViewerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String?>): File {
        val file = folder.newFile("made.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (content != null) out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a file is shown by what its name ends in`() {
        assertEquals(PlainContent.Pdf, PlainContent.of("Manual.PDF"))
        assertEquals(PlainContent.Zip, PlainContent.of("backup.Zip"))
        assertEquals(PlainContent.Text, PlainContent.of("notes.ini"))
        assertEquals(PlainContent.Text, PlainContent.of("no extension"))
    }

    @Test
    fun `only the very end of the name counts`() {
        assertEquals(PlainContent.Text, PlainContent.of("photos.zip.part"))
        assertEquals(PlainContent.Text, PlainContent.of("pdf"))
        assertEquals(PlainContent.Zip, PlainContent.of("report.pdf.zip"))
    }

    @Test
    fun `a zip's files are listed by name, whatever order they were added in`() {
        val listed = zippedFiles(zip("b.txt" to "bb", "a/z.txt" to "z", "a/c.txt" to "ccc", "B.txt" to ""))
        assertEquals(listOf("B.txt", "a/c.txt", "a/z.txt", "b.txt"), listed.map { it.name })
    }

    @Test
    fun `folders get no row of their own`() {
        val listed = zippedFiles(zip("docs/" to null, "docs/read.me" to "hello", "empty/" to null))
        assertEquals(listOf("docs/read.me"), listed.map { it.name })
    }

    @Test
    fun `each file comes with the size it unpacks to`() {
        val listed = zippedFiles(zip("five.txt" to "12345", "none.txt" to ""))
        assertEquals(listOf(ZippedFile("five.txt", 5), ZippedFile("none.txt", 0)), listed)
    }

    @Test
    fun `an empty zip lists nothing`() {
        assertTrue(zippedFiles(zip()).isEmpty())
    }

    @Test
    fun `a file that isn't a zip fails, for the viewer to report`() {
        val impostor = folder.newFile("not.zip").apply { writeText("plain text, not an archive") }
        assertThrows(Exception::class.java) { zippedFiles(impostor) }
    }
}
