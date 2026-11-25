package com.kobblo.gallery.file.explorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A text or Word document set out on sheets of paper, to leaf through rather than scroll.
 *
 * [open] reads the file and decides once where every page ends. After that a page costs nothing until
 * it's asked for, and can be drawn at any width: a thumbnail and the full page show the same words.
 */
internal class DocumentRenderer private constructor(
    /** What the file says, as it was read, for copying. */
    val text: String,
    private val pages: List<CharSequence>,
    private val typesetter: Typesetter
) {
    val pageCount: Int get() = pages.size

    /** Page [index] drawn [width] pixels wide, or null if the document has no such page. */
    suspend fun renderPage(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val words = pages.getOrNull(index) ?: return@withContext null
        val sheet = Bitmap.createBitmap(width, PagePaper.heightFor(width), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val shrink = PagePaper.scaleFor(width)
        canvas.scale(shrink, shrink)
        canvas.translate(PagePaper.Margin.toFloat(), PagePaper.Margin.toFloat())
        typesetter.set(words).draw(canvas)
        sheet
    }

    /** Sets text in the document's one typeface, within a page's margins. */
    private class Typesetter {
        /** A book's serif in a soft black, sized for the full sheet. */
        private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            textSize = 38f
            color = Color.rgb(28, 27, 31)
        }

        /** Lines start at the leading margin, a quarter again as far apart as the type is tall. */
        fun set(words: CharSequence): StaticLayout =
            StaticLayout.Builder.obtain(words, 0, words.length, ink, PagePaper.TextWidth)
                .setLineSpacing(0f, 1.25f)
                .setIncludePad(false)
                .build()

        /** How many of [ahead]'s characters go on one page: every whole line that fits above the bottom margin. */
        fun fill(ahead: CharSequence): Int {
            val lines = set(ahead)
            var fitting = 0
            while (fitting < lines.lineCount && lines.getLineBottom(fitting) <= PagePaper.TextHeight) fitting++
            // A line too tall for any page still has to go on one.
            return lines.getLineEnd(fitting.coerceAtLeast(1) - 1)
        }
    }

    companion object {
        /**
         * Reads [file] and lays it out. Measuring the text is most of the work, so the whole job runs
         * where computation belongs. A file that can't be read still makes a document: one page saying why.
         */
        suspend fun open(context: Context, file: File, format: DocumentFormat): DocumentRenderer =
            withContext(Dispatchers.Default) {
                val text = DocumentWords(context).forPages(file, format)
                val typesetter = Typesetter()
                val split = paginate(text.ifBlank { context.getString(R.string.view_doc_empty) }, fill = typesetter::fill)
                val pages = when {
                    split.cutShort -> split.pages + ("\n\n" + context.getString(R.string.view_doc_truncated_pages))
                    split.pages.isEmpty() -> listOf(context.getString(R.string.view_doc_empty))
                    else -> split.pages
                }
                DocumentRenderer(text, pages, typesetter)
            }
    }
}
