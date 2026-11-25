package com.kobblo.gallery.file.explorer.viewer.pdf

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.StringWriter
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * What the system's renderer can't tell us, read with PDFBox: the outline, the document's information, every
 * character's position, and on older Android versions text search and unlocking. Only in the PDF process,
 * where running out of memory on a monster of a file costs a search and not the app.
 */
internal class PdfBoxDocument private constructor(
    private val document: PDDocument,
    private val source: FdRandomAccess
) : AutoCloseable {
    private var cachedInfo: Bundle? = null

    @Synchronized
    fun info(): Bundle {
        cachedInfo?.let { return it }
        val out = Bundle()
        val titles = ArrayList<String>()
        val pages = ArrayList<Int>()
        val depths = ArrayList<Int>()
        try {
            val outline = document.documentCatalog?.documentOutline
            if (outline != null) {
                val seen = IdentityHashMap<PDOutlineItem, Boolean>()
                fun walk(first: PDOutlineItem?, depth: Int) {
                    var item = first
                    while (item != null && titles.size < MaxOutlineItems) {
                        // A broken file can loop its outline round on itself.
                        if (seen.put(item, true) != null) return
                        val title = item.title?.trim().orEmpty()
                        if (title.isNotEmpty()) {
                            titles.add(title.take(MaxTitleLength))
                            pages.add(destinationOf(item))
                            depths.add(depth)
                        }
                        if (depth < MaxOutlineDepth) walk(item.firstChild, depth + 1)
                        item = item.nextSibling
                    }
                }
                walk(outline.firstChild, 0)
            }
        } catch (_: Exception) {
            // A broken outline only means no outline. What was read of it stays.
        }
        if (titles.isNotEmpty()) {
            out.putStringArray(KeyTitles, titles.toTypedArray())
            out.putIntArray(KeyPages, pages.toIntArray())
            out.putIntArray(KeyDepths, depths.toIntArray())
        }
        try {
            document.documentInformation?.let { information ->
                information.title?.trim()?.takeIf { it.isNotEmpty() }?.let { out.putString(KeyTitle, it.take(MaxTitleLength)) }
                information.author?.trim()?.takeIf { it.isNotEmpty() }?.let { out.putString(KeyAuthor, it.take(MaxTitleLength)) }
            }
        } catch (_: Exception) {
        }
        cachedInfo = out
        return out
    }

    /** Going through the tree once beats asking for each page's place, which walks the tree every time. */
    private val indexes: IdentityHashMap<Any, Int> by lazy {
        val out = IdentityHashMap<Any, Int>()
        allPages.forEachIndexed { index, page -> out[page.cosObject] = index }
        out
    }

    /**
     * Whether this document is one with links in it, going by its first pages, as that's where a table of contents
     * goes. Looking at every page would cost about what keeping the document open does.
     */
    fun hasLinksNearStart(): Boolean = synchronized(this) {
        try {
            allPages.take(LinkSamplePages).any { page -> page.annotations.any { it is PDAnnotationLink } }
        } catch (_: Exception) {
            false
        }
    }

    fun collectLinks(index: Int, out: PdfLinkList) {
        synchronized(this) {
            val page = allPages.getOrNull(index) ?: return
            val crop = page.cropBox
            val rotation = page.rotation
            for (annotation in page.annotations) {
                val link = annotation as? PDAnnotationLink ?: continue
                val box = link.rectangle ?: continue
                val (uri, target) = linkTarget(link)
                if (uri == null && target < 0) continue
                val shown = PdfLinkGeometry.toDisplay(
                    box.lowerLeftX - crop.lowerLeftX,
                    box.lowerLeftY - crop.lowerLeftY,
                    box.upperRightX - crop.lowerLeftX,
                    box.upperRightY - crop.lowerLeftY,
                    crop.width,
                    crop.height,
                    rotation
                )
                out.add(shown.left, shown.top, shown.right, shown.bottom, target, uri.orEmpty())
            }
        }
    }

    private fun linkTarget(link: PDAnnotationLink): Pair<String?, Int> {
        val action = try { link.action } catch (_: Exception) { null }
        (action as? PDActionURI)?.uri?.let { return it to -1 }
        val destination = try {
            (action as? PDActionGoTo)?.destination ?: link.destination
        } catch (_: Exception) {
            null
        }
        return null to pageOf(destination)
    }

    private fun pageOf(destination: PDDestination?): Int = try {
        val direct = when (destination) {
            is PDNamedDestination -> document.documentCatalog.findNamedDestinationPage(destination)
            is PDPageDestination -> destination
            else -> null
        }
        val page = direct?.page
        if (page != null) indexes[page.cosObject] ?: -1 else direct?.pageNumber ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun destinationOf(item: PDOutlineItem): Int = try {
        val direct = item.findDestinationPage(document)
        if (direct != null) {
            indexes[direct.cosObject] ?: -1
        } else {
            (item.destination as? PDPageDestination)?.pageNumber ?: -1
        }
    } catch (_: Exception) {
        -1
    }

    fun pageChars(index: Int): PdfPageChars {
        var result = PdfPageChars("", FloatArray(0))
        forEachPage(intArrayOf(index), keepGoing = { true }) { _, chars -> result = chars }
        return result
    }

    /**
     * Reads pages in the order given, and returns false if [keepGoing] stopped it early. The document is only
     * held per page, so that a long scan on an older phone doesn't keep Copy Page Text waiting for it to end.
     */
    fun forEachPage(order: IntArray, keepGoing: () -> Boolean, onPage: (Int, PdfPageChars) -> Unit): Boolean {
        val stripper = CharsStripper(onPage)
        for (index in order) {
            if (!keepGoing()) return false
            synchronized(this) {
                val page = allPages.getOrNull(index)
                if (page == null) {
                    onPage(index, PdfPageChars("", FloatArray(0)))
                } else {
                    stripper.pageIndex = index
                    stripper.page = page
                    stripper.writeText(document, StringWriter())
                }
            }
        }
        return true
    }

    // Asking the tree for one page counts through its branches every time, which adds up to minutes in a long
    // file with a flat tree. Going through it once costs about as much as asking for one.
    private val allPages: List<PDPage> by lazy { document.pages.toList() }

    override fun close() {
        try { document.close() } catch (_: Exception) {}
    }

    private class CharsStripper(private val onPage: (Int, PdfPageChars) -> Unit) : PDFTextStripper() {
        var pageIndex = 0
        var page: PDPage? = null
        private val text = StringBuilder()
        private var rects = FloatArray(16 * 1024)
        private var used = 0

        init {
            // Text comes from processPage directly, so the page counter this stripper keeps never moves.
            startPage = 0
        }

        override fun processPages(pages: PDPageTree) {
            text.setLength(0)
            used = 0
            try {
                page?.takeIf { it.hasContents() }?.let { processPage(it) }
            } catch (_: IOException) {
                // One bad page shouldn't lose the rest.
            } catch (_: IllegalStateException) {
            } catch (_: IndexOutOfBoundsException) {
            }
            onPage(pageIndex, PdfPageChars(trimmedText(), rects.copyOf(used)))
        }

        // A last separator would be a character with no rectangle to mark the end of the text.
        private fun trimmedText(): String {
            var end = text.length
            while (end > 0 && text[end - 1] == ' ') end--
            return text.substring(0, end)
        }

        override fun writeString(string: String, positions: MutableList<TextPosition>) {
            for (position in positions) {
                val unicode = position.unicode ?: continue
                if (unicode.isEmpty()) continue
                // Along the text however it's turned, which plain width isn't on a page the file turns sideways.
                val width = position.widthDirAdj / unicode.length
                val height = position.height.coerceAtLeast(position.fontSizeInPt * 0.6f)
                val angle = PdfCharBox.displayAngle(position.rotation, position.dir.toInt())
                for (at in unicode.indices) {
                    val char = unicode[at]
                    if (char.isWhitespace()) {
                        addSeparator()
                    } else {
                        text.append(char)
                        val box = PdfCharBox.rect(
                            angle,
                            position.x,
                            position.y,
                            width * at,
                            width * (at + 1),
                            height * TopReach,
                            height * BottomReach
                        )
                        add(box.left, box.top, box.right, box.bottom)
                    }
                }
            }
        }

        override fun writeWordSeparator() = addSeparator()
        override fun writeLineSeparator() = addSeparator()

        private fun addSeparator() {
            if (text.isEmpty() || text[text.length - 1] == ' ') return
            text.append(' ')
            add(0f, 0f, 0f, 0f)
        }

        private fun add(left: Float, top: Float, right: Float, bottom: Float) {
            if (used + 4 > rects.size) rects = rects.copyOf(rects.size * 2)
            rects[used++] = left
            rects[used++] = top
            rects[used++] = right
            rects[used++] = bottom
        }
    }

    companion object {
        const val KeyTitles = "titles"
        const val KeyPages = "pages"
        const val KeyDepths = "depths"
        const val KeyTitle = "title"
        const val KeyAuthor = "author"
        const val KeyUris = "uris"

        private const val LinkSamplePages = 40
        private const val MaxOutlineItems = 5000
        private const val MaxOutlineDepth = 16
        private const val MaxTitleLength = 300

        // A TextPosition's height runs from the baseline to about the top of the capitals, and a mark looks
        // right when it covers what hangs below as well.
        private const val TopReach = 1.05f
        private const val BottomReach = 0.28f

        // What the parser reads into memory before spilling to the cache: enough for ordinary files, and not
        // so much that images in a large one fill the heap, as it copies those even though it doesn't draw them.
        private const val ScratchMemoryBytes = 16L * 1024 * 1024
        private const val ScratchTotalBytes = 1024L * 1024 * 1024

        private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun load(context: Context, file: ParcelFileDescriptor, password: String?): Pair<PDDocument, FdRandomAccess> {
            if (initialized.compareAndSet(false, true)) PDFBoxResourceLoader.init(context.applicationContext)
            val source = FdRandomAccess(file)
            val scratch = ScratchFile(MemoryUsageSetting.setupMixed(ScratchMemoryBytes, ScratchTotalBytes).setTempDir(context.cacheDir))
            try {
                val parser = PDFParser(source, password ?: "", scratch)
                parser.parse()
                return parser.pdDocument to source
            } catch (error: InvalidPasswordException) {
                throw WrongPdfPassword()
            }
        }

        fun open(context: Context, file: ParcelFileDescriptor, password: String?): PdfBoxDocument {
            val (document, source) = load(context, file, password)
            return PdfBoxDocument(document, source)
        }

        fun writeUnlocked(context: Context, file: ParcelFileDescriptor, password: String, target: File) {
            val (document, _) = load(context, file, password)
            try {
                document.isAllSecurityToBeRemoved = true
                document.save(target)
            } finally {
                try { document.close() } catch (_: Exception) {}
            }
        }
    }
}

/**
 * PDFBox reading through a file descriptor, because a file opened from another app has no path. Positional
 * reads leave the shared offset alone, and it keeps a few recent blocks since parsing jumps about a lot.
 */
internal class FdRandomAccess(file: ParcelFileDescriptor) : RandomAccessRead {
    // Not closed here. The descriptor belongs to the session, and closing this would close it with it.
    private val channel = FileInputStream(file.fileDescriptor).channel
    private val length = channel.size()
    private var position = 0L
    private var closed = false
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MaxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean = size > MaxBlocks
    }

    private fun block(index: Long): ByteArray = blocks.getOrPut(index) {
        val buffer = ByteBuffer.allocate(BlockSize)
        var at = index * BlockSize
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read <= 0) break
            at += read
        }
        buffer.array()
    }

    override fun read(): Int {
        if (position >= length) return -1
        val value = block(position / BlockSize)[(position % BlockSize).toInt()].toInt() and 0xFF
        position++
        return value
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= length) return -1
        var written = 0
        while (written < len && position < length) {
            val inBlock = (position % BlockSize).toInt()
            val count = minOf(len - written, BlockSize - inBlock, (length - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            System.arraycopy(block(position / BlockSize), inBlock, b, off + written, count)
            written += count
            position += count
        }
        return written
    }

    override fun getPosition(): Long = position

    override fun seek(position: Long) {
        this.position = position.coerceAtLeast(0L)
    }

    override fun length(): Long = length

    override fun isClosed(): Boolean = closed

    override fun peek(): Int {
        val value = read()
        if (value != -1) position--
        return value
    }

    override fun rewind(bytes: Int) {
        position = (position - bytes).coerceAtLeast(0L)
    }

    override fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = read(out, read, length - read)
            if (count <= 0) throw IOException("End of file")
            read += count
        }
        return out
    }

    override fun isEOF(): Boolean = position >= length

    override fun available(): Int = (length - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)

    override fun close() {
        closed = true
        blocks.clear()
    }

    private companion object {
        const val BlockSize = 16 * 1024
        const val MaxBlocks = 128
    }
}

/** Links gathered for a page, to send across as plain arrays. */
internal class PdfLinkList {
    private val rects = ArrayList<Float>()
    private val pages = ArrayList<Int>()
    private val uris = ArrayList<String>()

    fun add(left: Float, top: Float, right: Float, bottom: Float, page: Int, uri: String) {
        rects.add(left)
        rects.add(top)
        rects.add(right)
        rects.add(bottom)
        pages.add(page)
        uris.add(uri)
    }

    fun toBundle(): Bundle {
        val out = Bundle()
        if (pages.isNotEmpty()) {
            out.putFloatArray(PdfService.KeyRects, rects.toFloatArray())
            out.putIntArray(PdfBoxDocument.KeyPages, pages.toIntArray())
            out.putStringArray(PdfBoxDocument.KeyUris, uris.toTypedArray())
        }
        return out
    }
}
