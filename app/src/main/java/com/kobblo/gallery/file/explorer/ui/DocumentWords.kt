package com.kobblo.gallery.file.explorer.ui

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Xml
import androidx.core.content.FileProvider
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.documents.DocumentLoader
import com.kobblo.gallery.file.explorer.tools.documents.docLabelsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

/**
 * Reads what a file says, as plain text.
 *
 * Both viewers that use this put the answer straight on screen, so nothing here throws: a file that
 * is empty, too long or unreadable comes back as a sentence saying so, in the place its words would
 * have been.
 */
internal class DocumentWords(private val context: Context) {

    /** The words the paged viewer lays out. A Word file gets the readers that know both of its formats. */
    fun forPages(file: File, format: DocumentFormat): String = try {
        when (format) {
            DocumentFormat.Word -> wordFile(file)
            DocumentFormat.Text -> open(file).use(::plainText)
        }
    } catch (e: Exception) {
        text(R.string.view_doc_error_reading, e.message ?: text(R.string.view_doc_unable_to_read_content))
    }

    /** The words the plain viewer scrolls through. */
    suspend fun forScrolling(file: File, format: DocumentFormat): String = withContext(Dispatchers.IO) {
        when (format) {
            DocumentFormat.Word -> try {
                open(file).use(::wordArchive)
            } catch (e: Exception) {
                text(R.string.view_docx_error_reading, e.message ?: text(R.string.view_docx_unable_to_read))
            }
            DocumentFormat.Text -> try {
                open(file).use(::plainText)
            } catch (e: Exception) {
                text(R.string.view_file_error_reading, e.message ?: text(R.string.view_doc_unable_to_read_content))
            }
        }
    }

    private fun text(id: Int): String = context.getString(id)

    private fun text(id: Int, detail: String): String = context.getString(id, detail)

    // ── Plain text ────────────────────────────────────────────────────────────

    private fun plainText(stream: InputStream): String {
        val start = stream.readCapped(MaxTextBytes)
        if (start.bytes.isEmpty()) return text(R.string.view_doc_empty_file)
        val words = decodeDocumentText(start.bytes)
        return if (start.cutShort) "$words\n\n" + text(R.string.view_doc_truncated_size) else words
    }

    // ── Word ──────────────────────────────────────────────────────────────────

    private fun wordFile(file: File): String {
        // These know the old binary format as well as the new one, and stop at their own limits. They
        // need a real file, and say nothing about one they can't make sense of.
        if (file.isFile) {
            DocumentLoader.plainText(file, docLabelsFor(context))?.let { return it }
        }
        return open(file).use(::wordArchive)
    }

    /** A `.docx` read as the zip it is, for a file the loader above has no answer for. */
    private fun wordArchive(stream: InputStream): String = try {
        val markup = mainPart(stream)
        if (markup.isNullOrBlank()) text(R.string.view_docx_empty_no_text) else wordsIn(markup)
    } catch (e: Exception) {
        text(R.string.view_docx_error_reading_damaged, e.message ?: text(R.string.view_docx_unable_to_read))
    }

    /** The markup of the document proper, or null if the zip holds no such part. */
    private fun mainPart(stream: InputStream): String? {
        val zip = ZipInputStream(stream)
        while (true) {
            val entry = zip.nextEntry ?: return null
            // A compressed part can claim any size and unpack to another, so what comes out is counted.
            if (entry.name == WordMainPart) return String(zip.readCapped(MaxWordMarkupBytes).bytes, Charsets.UTF_8)
        }
    }

    private fun wordsIn(markup: String): String = try {
        val collector = WordTextCollector()
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(markup))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':').orEmpty()
            when (event) {
                // Reading a run's text also steps over its closing tag.
                XmlPullParser.START_TAG -> if (tag == "t") collector.text(parser.nextText()) else collector.open(tag)
                XmlPullParser.END_TAG -> collector.close(tag)
            }
            event = parser.next()
        }
        collector.result().ifBlank { text(R.string.view_docx_empty) }
    } catch (e: Exception) {
        stripWordMarkup(markup).ifBlank { text(R.string.view_doc_error_reading, e.message.toString()) }
    }

    // ── Getting at the bytes ──────────────────────────────────────────────────

    /**
     * Opens [file] whichever way works. A path the app may not read directly can still be one the
     * media library or the app's own file provider will hand over, so those are asked before giving
     * up, and giving up is the plain open failing with its own, accurate, error.
     */
    private fun open(file: File): InputStream =
        direct(file) ?: fromMediaLibrary(file) ?: fromOwnProvider(file) ?: FileInputStream(file)

    private fun direct(file: File): InputStream? = attempt {
        if (file.exists() && file.canRead()) FileInputStream(file) else null
    }

    private fun fromMediaLibrary(file: File): InputStream? = attempt {
        val library = MediaStore.Files.getContentUri("external")
        val id = context.contentResolver.query(
            library,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )?.use { found ->
            if (found.moveToFirst()) found.getLong(found.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)) else null
        }
        id?.let { context.contentResolver.openInputStream(ContentUris.withAppendedId(library, it)) }
    }

    private fun fromOwnProvider(file: File): InputStream? = attempt {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.contentResolver.openInputStream(uri)
    }

    private inline fun attempt(open: () -> InputStream?): InputStream? = try {
        open()
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** As much of a text file as is shown. More than this is more than anyone pages through. */
        const val MaxTextBytes = 4 * 1024 * 1024

        const val WordMainPart = "word/document.xml"
        const val MaxWordMarkupBytes = 32 * 1024 * 1024
    }
}
