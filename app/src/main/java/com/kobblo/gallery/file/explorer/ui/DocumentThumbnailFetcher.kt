package com.kobblo.gallery.file.explorer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.kobblo.gallery.file.explorer.tools.documents.LimitedInputStream
import com.kobblo.gallery.file.explorer.tools.editor.TextCodec
import com.kobblo.gallery.file.explorer.viewer.pdf.PdfDoc
import java.io.File
import java.util.zip.ZipFile

/** The ways a file that isn't a picture can still be shown as one in a list, and the file types each is for. */
internal enum class DocumentPreview(vararg extensions: String) {
    /** A PDF's first page. */
    FirstPage("pdf"),

    /** The picture of its first page that an Office file carries inside it. */
    EmbeddedPicture("docx", "pptx", "xlsx", "odt", "ods", "odp"),

    /** An app's own icon. */
    AppIcon("apk"),

    /** The opening lines of a text file, set on a white card. */
    OpeningLines("txt", "log", "md", "json", "xml", "kt", "java", "py", "css", "js");

    private val extensions = extensions.toSet()

    companion object {
        /** The preview for a file with this extension, in any case, or null if it gets a plain icon. */
        fun of(extension: String): DocumentPreview? {
            val wanted = extension.lowercase()
            return entries.firstOrNull { wanted in it.extensions }
        }
    }
}

/** How many times a [width] by [height] picture has to be halved to fit a square of [side]. */
internal fun sampleSizeToFit(width: Int, height: Int, side: Int): Int {
    var sample = 1
    while (width / sample > side || height / sample > side) sample *= 2
    return sample
}

/** The white card a text file's opening lines are set on. */
internal object TextCard {
    const val Width = 400
    const val Height = 500
    const val TextSize = 24f
    const val Left = 20f

    private const val Edge = 40f
    private const val LineHeight = TextSize + 10f
    private const val LineLength = 50

    class Line(val words: String, val baseline: Float)

    /** As many of [text]'s lines as the card has room for, each cut off where the card ends. */
    fun lines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var baseline = Edge
        for (line in text.lineSequence()) {
            if (baseline >= Height - Edge) break
            lines += Line(line.take(LineLength), baseline)
            baseline += LineHeight
        }
        return lines
    }
}

/**
 * Gives an image loader a picture for a document, an app or a text file, so lists and grids show what
 * a file looks like and not only what type it is. A file it can't make a picture of gets none, and the
 * loader goes on to whatever else it knows.
 */
class DocumentThumbnailFetcher(private val file: File, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val picture = when (DocumentPreview.of(file.extension)) {
            // Drawn in the PDF process. Otherwise a list would run native code over every file in a folder, however broken.
            DocumentPreview.FirstPage -> PdfDoc.thumbnail(options.context, file, 400, 600)
            DocumentPreview.EmbeddedPicture -> quietly(::embeddedPicture)
            DocumentPreview.AppIcon -> quietly(::appIcon)
            DocumentPreview.OpeningLines -> quietly(::openingLines)
            null -> null
        } ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, picture),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    /** A preview that fails is a preview that isn't there. */
    private inline fun quietly(make: () -> Bitmap?): Bitmap? = try {
        make()
    } catch (_: Exception) {
        null
    }

    /**
     * The picture is as untrusted as the file around it, so it's read to a limit and decoded at tile
     * size: a tiny file can hold a picture too big for memory.
     */
    private fun embeddedPicture(): Bitmap? {
        val bytes = ZipFile(file).use { zip ->
            val entry = EmbeddedPictureNames.firstNotNullOfOrNull { zip.getEntry(it) } ?: return null
            LimitedInputStream(zip.getInputStream(entry), MaxEmbeddedPictureBytes).use { it.readBytes() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val shrunk = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeToFit(bounds.outWidth, bounds.outHeight, EmbeddedPictureSide)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, shrunk)
    }

    private fun appIcon(): Bitmap? {
        val packages = options.context.packageManager
        val app = packages.getPackageArchiveInfo(file.absolutePath, 0)?.applicationInfo ?: return null
        // A package that isn't installed has to be told where its own resources are.
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val icon = app.loadIcon(packages) ?: return null
        (icon as? BitmapDrawable)?.bitmap?.let { return it }
        val drawn = Bitmap.createBitmap(
            icon.intrinsicWidth.takeIf { it > 0 } ?: FallbackIconSide,
            icon.intrinsicHeight.takeIf { it > 0 } ?: FallbackIconSide,
            Bitmap.Config.ARGB_8888
        )
        icon.setBounds(0, 0, drawn.width, drawn.height)
        icon.draw(Canvas(drawn))
        return drawn
    }

    private fun openingLines(): Bitmap? {
        // Only the head of the file, because one long line could be gigabytes.
        val head = file.inputStream().use { input ->
            val sample = ByteArray(TextSampleBytes)
            val count = input.read(sample)
            if (count <= 0) ByteArray(0) else sample.copyOf(count)
        }
        // If it isn't text, whatever else it is gets its turn: a PNG named .txt still makes a picture.
        if (!TextCodec.looksLikeText(head)) return null
        val card = Bitmap.createBitmap(TextCard.Width, TextCard.Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(card)
        canvas.drawColor(Color.WHITE)
        val ink = Paint().apply {
            color = Color.BLACK
            textSize = TextCard.TextSize
            isAntiAlias = true
        }
        for (line in TextCard.lines(TextCodec.decode(head).text)) {
            canvas.drawText(line.words, TextCard.Left, line.baseline, ink)
        }
        return card
    }

    /** For files the loader is handed as files. */
    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (DocumentPreview.of(data.extension) != null) DocumentThumbnailFetcher(data, options) else null
    }

    /** For files the loader is handed as `file:` addresses. */
    class UriFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val path = data.path?.takeIf { data.scheme == "file" } ?: return null
            return Factory().create(File(path), options, imageLoader)
        }
    }

    private companion object {
        const val TextSampleBytes = 4096
        const val MaxEmbeddedPictureBytes = 4L * 1024 * 1024
        const val EmbeddedPictureSide = 512
        const val FallbackIconSide = 128

        /** Where Office's formats and OpenDocument's keep the picture, in the order they're tried. */
        val EmbeddedPictureNames = listOf("docProps/thumbnail.jpeg", "docProps/thumbnail.png", "Thumbnails/thumbnail.png")
    }
}
