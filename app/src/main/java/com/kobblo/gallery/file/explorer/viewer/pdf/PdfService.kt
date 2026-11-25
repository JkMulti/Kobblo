package com.kobblo.gallery.file.explorer.viewer.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.LoadParams
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import com.kobblo.gallery.file.explorer.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal object PdfOpenResult {
    const val Damaged = -1
    const val NeedsPassword = -2
    const val WrongPassword = -3
}

/**
 * Parses and draws PDFs in a process of their own. The system's renderer is native code reading whatever
 * file it's handed, and on most Android versions it runs inside the app that calls it, so a file that
 * crashes it would take the whole app down, and for a thumbnail would do so every time the folder opened.
 * Over here, the worst a file can do is end this process. The reader's memory also stays out of the app's.
 *
 * Bitmaps cross over in shared memory, so there's no copy at the other end and no limit on their size.
 */
class PdfService : Service() {
    private val binder = PdfBinder()

    override fun onCreate() {
        super.onCreate()
        // Nothing here can be in use yet, as this process has only just started.
        cacheDir.listFiles { file -> file.name.startsWith("PDFBox") && file.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        binder.closeAll()
        return false
    }

    private inner class PdfBinder : IPdfService.Stub() {
        private val sessions = ConcurrentHashMap<Int, PdfSession>()
        private val nextSession = AtomicInteger(1)

        // The library behind the renderer isn't safe to enter from two threads, even for two documents.
        // Fair, so a long search hands over between pages, and what's on screen doesn't wait for it.
        private val nativeLock = ReentrantLock(true)
        private val searches = Executors.newSingleThreadExecutor()

        override fun open(file: ParcelFileDescriptor, password: String?): Int {
            val copy = try {
                ParcelFileDescriptor.dup(file.fileDescriptor)
            } catch (_: IOException) {
                closeQuietly(file)
                return PdfOpenResult.Damaged
            }
            val session = try {
                nativeLock.withLock { PdfSession.open(applicationContext, file, copy, password, legacyTextOnly()) }
            } catch (_: SecurityException) {
                closeQuietly(file)
                closeQuietly(copy)
                return if (password == null) PdfOpenResult.NeedsPassword else PdfOpenResult.WrongPassword
            } catch (_: WrongPdfPassword) {
                closeQuietly(file)
                closeQuietly(copy)
                return PdfOpenResult.WrongPassword
            } catch (_: Throwable) {
                val locked = password == null && looksEncrypted(copy)
                closeQuietly(file)
                closeQuietly(copy)
                return if (locked) PdfOpenResult.NeedsPassword else PdfOpenResult.Damaged
            }
            val id = nextSession.getAndIncrement()
            sessions[id] = session
            return id
        }

        override fun pageCount(session: Int): Int = sessions[session]?.pageCount ?: 0

        override fun pageSizes(session: Int, from: Int, count: Int): FloatArray {
            val document = sessions[session] ?: return FloatArray(0)
            val end = min(from + count, document.pageCount)
            if (from >= end) return FloatArray(0)
            val out = FloatArray((end - from) * 2)
            for (index in from until end) {
                // One page at a time, so that what's on screen can get in between.
                nativeLock.withLock { document.readPageSize(index, out, (index - from) * 2) }
            }
            return out
        }

        override fun render(session: Int, page: Int, width: Int, height: Int, matrix: FloatArray): PdfBitmap? {
            val document = sessions[session] ?: return null
            if (width <= 0 || height <= 0 || matrix.size < 6) return null
            return try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val transform = Matrix().apply {
                    setValues(floatArrayOf(matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5], 0f, 0f, 1f))
                }
                nativeLock.withLock {
                    document.page(page).render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    document.shedMemoryIfNeeded()
                }
                PdfBitmap(bitmap)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

        override fun pageText(session: Int, page: Int): String {
            val document = sessions[session] ?: return ""
            return try {
                document.pageText(page, nativeLock)
            } catch (_: Throwable) {
                ""
            }
        }

        override fun pageChars(session: Int, page: Int): Bundle {
            val out = Bundle()
            val document = sessions[session] ?: return out
            try {
                val chars = document.box().pageChars(page)
                out.putString(KeyText, chars.text)
                out.putFloatArray(KeyRects, chars.rects)
            } catch (_: Throwable) {
                // Selecting needs this, and without it there's just nothing to select.
            }
            return out
        }

        override fun pageLinks(session: Int, page: Int): Bundle {
            val document = sessions[session] ?: return Bundle()
            return try {
                document.pageLinks(page, nativeLock)
            } catch (_: Throwable) {
                Bundle()
            }
        }

        override fun search(session: Int, searchId: Int, query: String, startPage: Int, callback: IPdfSearchCallback) {
            val document = sessions[session]
            if (document == null) {
                callback.onFinished(searchId, false)
                return
            }
            document.searchId = searchId
            searches.execute {
                try {
                    document.search(searchId, query, startPage, nativeLock, callback)
                } catch (_: Throwable) {
                    // Most likely the extra memory the older route needs. What it found stands.
                    try { callback.onFinished(searchId, false) } catch (_: Exception) {}
                }
            }
        }

        override fun cancelSearch(session: Int) {
            sessions[session]?.searchId = -1
        }

        override fun info(session: Int): Bundle {
            val document = sessions[session] ?: return Bundle()
            return try {
                document.info()
            } catch (_: Throwable) {
                Bundle()
            }
        }

        override fun close(session: Int) {
            val document = sessions.remove(session) ?: return
            document.searchId = -1
            nativeLock.withLock { document.close() }
        }

        override fun thumbnail(file: ParcelFileDescriptor, maxWidth: Int, maxHeight: Int): PdfBitmap? {
            var renderer: PdfRenderer? = null
            return try {
                nativeLock.withLock {
                    renderer = PdfRenderer(file)
                    val document = renderer!!
                    if (document.pageCount == 0) return null
                    document.openPage(0).use { page ->
                        val pageWidth = page.width.toFloat().coerceAtLeast(1f)
                        val pageHeight = page.height.toFloat().coerceAtLeast(1f)
                        val scale = min(maxWidth / pageWidth, maxHeight / pageHeight)
                        val bitmap = Bitmap.createBitmap(
                            max(ceil(pageWidth * scale).toInt(), 1),
                            max(ceil(pageHeight * scale).toInt(), 1),
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        PdfBitmap(bitmap)
                    }
                }
            } catch (_: Throwable) {
                null
            } finally {
                nativeLock.withLock {
                    try { renderer?.close() } catch (_: Exception) {}
                }
                closeQuietly(file)
            }
        }

        override fun unlockedCopy(session: Int): String? = try {
            sessions[session]?.unlockedCopy()?.absolutePath
        } catch (_: Throwable) {
            null
        }

        override fun pid(): Int = Process.myPid()

        fun closeAll() {
            for (id in sessions.keys.toList()) close(id)
        }

        /** Lets a debug build walk the path older Android versions take, on a device new enough not to need it. */
        private fun legacyTextOnly(): Boolean =
            BuildConfig.DEBUG && File(filesDir, PdfSession.ForceLegacyMarker).exists()

        private fun looksEncrypted(file: ParcelFileDescriptor): Boolean = try {
            val channel = FileInputStream(file.fileDescriptor).channel
            val length = channel.size()
            val size = min(length, TailScanBytes.toLong()).toInt()
            val buffer = java.nio.ByteBuffer.allocate(size)
            channel.read(buffer, length - size)
            String(buffer.array(), 0, buffer.position(), Charsets.ISO_8859_1).contains("/Encrypt")
        } catch (_: Exception) {
            false
        }
    }

    internal companion object {
        const val KeyText = "text"
        const val KeyRects = "rects"
        private const val TailScanBytes = 1024 * 1024

        fun closeQuietly(file: ParcelFileDescriptor) {
            try { file.close() } catch (_: Exception) {}
        }
    }
}

internal class WrongPdfPassword : Exception()

/** One open document on this side of the wall. Everything touching [renderer] is under the caller's lock. */
internal class PdfSession private constructor(
    private val context: android.content.Context,
    private var renderer: PdfRenderer,
    private val boxFile: ParcelFileDescriptor,
    private val password: String?,
    private var unlocked: File?,
    /** The copy this session reads pages from, where the renderer couldn't take the password itself. */
    private val viewingCopy: File?,
    private val forceLegacy: Boolean
) {
    val pageCount: Int = renderer.pageCount
    private var openPage: PdfRenderer.Page? = null
    private var openIndex = -1
    private var boxDocument: PdfBoxDocument? = null
    private var cachedInfo: Bundle? = null
    private var sizeReader: PdfRenderer? = null
    private var sizesSinceFresh = 0
    private var pagesSinceFresh = 0

    private fun openFresh(): PdfRenderer {
        viewingCopy?.let { return PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
        val file = ParcelFileDescriptor.dup(boxFile.fileDescriptor)
        val secret = password
        return if (secret != null && Build.VERSION.SDK_INT >= 35 && !forceLegacy) openWithPassword(file, secret) else PdfRenderer(file)
    }

    /**
     * The renderer keeps everything it has ever parsed from a document, and every picture it decoded, until the
     * document closes. So a long read of a file full of images only ever grows. Closing and reopening costs little
     * and nothing on screen depends on it, which makes it the way to keep this process a fixed size.
     */
    fun shedMemoryIfNeeded() {
        pagesSinceFresh++
        if (pagesSinceFresh < MinPagesBetweenShedding || Debug.getNativeHeapAllocatedSize() < NativeHeapLimitBytes) return
        pagesSinceFresh = 0
        try { openPage?.close() } catch (_: Exception) {}
        openPage = null
        openIndex = -1
        val fresh = try {
            openFresh()
        } catch (_: Exception) {
            return
        }
        try { renderer.close() } catch (_: Exception) {}
        renderer = fresh
    }

    /**
     * Learning a page's size means loading it, so the scan through every page has a reader of its own, thrown away
     * as it goes. Done on the main one, a long file would stay parsed in memory for the whole session.
     */
    fun readPageSize(index: Int, out: FloatArray, at: Int) {
        try {
            val reader = sizeReader ?: openFresh().also { sizeReader = it }
            reader.openPage(index).use { page ->
                out[at] = page.width.toFloat()
                out[at + 1] = page.height.toFloat()
            }
            sizesSinceFresh++
            if (sizesSinceFresh >= SizesPerReader || index >= pageCount - 1) closeSizeReader()
        } catch (_: Exception) {
            // A page that won't open has nothing to say, and the reader assumes its neighbors' size.
        }
    }

    private fun closeSizeReader() {
        try { sizeReader?.close() } catch (_: Exception) {}
        sizeReader = null
        sizesSinceFresh = 0
    }

    /**
     * Parsing with PDFBox takes real memory in a long file, and on newer Android it may not be needed past this. It stays
     * open for a document that uses links, as those are what the system's renderer can't read all of. Otherwise it's
     * opened again only if someone starts selecting text.
     */
    fun info(): Bundle {
        synchronized(this) { cachedInfo }?.let { return it }
        val document = box()
        val read = document.info()
        val keepOpen = !hasNativeText || document.hasLinksNearStart()
        synchronized(this) {
            cachedInfo = read
            if (!keepOpen) {
                try { boxDocument?.close() } catch (_: Exception) {}
                boxDocument = null
            }
        }
        return read
    }

    /**
     * Newer Android finds addresses, even ones only written out as text, but only follows links within the document that
     * are written one of the two ways they can be. So those come from PDFBox, on every version, whenever it's open.
     */
    fun pageLinks(index: Int, lock: ReentrantLock): Bundle {
        val out = PdfLinkList()
        val usesBox = !hasNativeText || synchronized(this) { boxDocument != null || cachedInfo == null }
        if (usesBox) box().collectLinks(index, out)
        if (hasNativeText && Build.VERSION.SDK_INT >= 35) {
            lock.withLock { collectWebLinks(page(index), out) }
        }
        return out.toBundle()
    }

    @RequiresApi(35)
    private fun collectWebLinks(page: PdfRenderer.Page, out: PdfLinkList) {
        for (link in page.linkContents) {
            for (bound in link.bounds) {
                out.add(bound.left, bound.top, bound.right, bound.bottom, -1, link.uri.toString())
            }
        }
    }

    /** The lock is on the file, so printing a locked document needs a copy without it. */
    @Synchronized
    fun unlockedCopy(): File? {
        viewingCopy?.let { return it }
        unlocked?.let { return it }
        val secret = password ?: return null
        val folder = File(context.cacheDir, UnlockedFolder).apply { mkdirs() }
        val copy = File(folder, "${System.nanoTime()}.pdf")
        PdfBoxDocument.writeUnlocked(context, boxFile, secret, copy)
        unlocked = copy
        return copy
    }

    @Volatile
    var searchId: Int = 0

    /** The renderer allows one page open at a time. Keeping the last one saves parsing it again for the next call. */
    fun page(index: Int): PdfRenderer.Page {
        openPage?.let { if (openIndex == index) return it }
        openPage?.close()
        openPage = null
        openIndex = -1
        val page = renderer.openPage(index)
        openPage = page
        openIndex = index
        return page
    }

    @Synchronized
    fun box(): PdfBoxDocument {
        boxDocument?.let { return it }
        return PdfBoxDocument.open(context, boxFile, password).also { boxDocument = it }
    }

    private val hasNativeText: Boolean get() = Build.VERSION.SDK_INT >= 35 && !forceLegacy

    fun pageText(index: Int, lock: ReentrantLock): String {
        if (hasNativeText) {
            val text = lock.withLock { nativeText(page(index)) }
            if (text.isNotBlank()) return text
        }
        return box().pageChars(index).text
    }

    @RequiresApi(35)
    private fun nativeText(page: PdfRenderer.Page): String =
        page.textContents.joinToString("\n") { it.text }

    fun search(id: Int, query: String, startPage: Int, lock: ReentrantLock, callback: IPdfSearchCallback) {
        val count = pageCount
        val trimmed = PdfTextMatch.normalizeQuery(query)
        if (count == 0 || trimmed.isEmpty()) {
            callback.onFinished(id, true)
            return
        }
        val order = IntArray(count) { (startPage.coerceIn(0, count - 1) + it) % count }
        val complete = if (hasNativeText) {
            searchNatively(id, trimmed, order, lock, callback)
        } else {
            searchByExtraction(id, trimmed, order, callback)
        }
        callback.onFinished(id, complete)
    }

    private fun searchNatively(id: Int, query: String, order: IntArray, lock: ReentrantLock, callback: IPdfSearchCallback): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        var scanned = 0
        var lastProgressAt = 0L
        for (index in order) {
            if (searchId != id) return false
            val matches = try {
                lock.withLock {
                    page(index).searchText(query).also { shedMemoryIfNeeded() }
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (matches.isNotEmpty()) {
                val rects = ArrayList<Float>(matches.size * 4)
                val perMatch = IntArray(matches.size)
                matches.forEachIndexed { at, match ->
                    perMatch[at] = match.bounds.size
                    match.bounds.forEach { rects.add(it.left); rects.add(it.top); rects.add(it.right); rects.add(it.bottom) }
                }
                callback.onMatches(id, index, rects.toFloatArray(), perMatch)
            }
            scanned++
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > ProgressEveryMs) {
                lastProgressAt = now
                callback.onProgress(id, scanned)
            }
        }
        return true
    }

    private fun searchByExtraction(id: Int, query: String, order: IntArray, callback: IPdfSearchCallback): Boolean {
        var scanned = 0
        var lastProgressAt = 0L
        return box().forEachPage(order, keepGoing = { searchId == id }) { index, chars ->
            val matches = PdfTextMatch.find(chars.text, query)
            if (matches.isNotEmpty()) {
                val rects = ArrayList<Float>(matches.size * 4)
                val perMatch = IntArray(matches.size)
                matches.forEachIndexed { at, range ->
                    val found = PdfTextMatch.rectsFor(chars.rects, range.first, range.last + 1)
                    perMatch[at] = found.size / 4
                    found.forEach(rects::add)
                }
                callback.onMatches(id, index, rects.toFloatArray(), perMatch)
            }
            scanned++
            val now = System.currentTimeMillis()
            if (now - lastProgressAt > ProgressEveryMs) {
                lastProgressAt = now
                callback.onProgress(id, scanned)
            }
        }
    }

    fun close() {
        try { openPage?.close() } catch (_: Exception) {}
        openPage = null
        closeSizeReader()
        try { renderer.close() } catch (_: Exception) {}
        try { boxDocument?.close() } catch (_: Exception) {}
        PdfService.closeQuietly(boxFile)
        try { unlocked?.delete() } catch (_: Exception) {}
        try { viewingCopy?.delete() } catch (_: Exception) {}
    }

    companion object {
        const val ForceLegacyMarker = "pdf_force_legacy"
        private const val ProgressEveryMs = 120L
        private const val UnlockedFolder = "pdf_unlocked"
        private const val NativeHeapLimitBytes = 96L * 1024 * 1024
        private const val MinPagesBetweenShedding = 3
        private const val SizesPerReader = 500

        /**
         * Below Android 15 the renderer takes no password, so a locked file has to be opened by PDFBox and
         * written out unlocked. Privately, under this app's cache, and only for as long as it's open. A
         * crash can't run the clean up, so anything left from an earlier session goes first.
         */
        fun open(
            context: android.content.Context,
            file: ParcelFileDescriptor,
            boxFile: ParcelFileDescriptor,
            password: String?,
            forceLegacy: Boolean
        ): PdfSession {
            val folder = File(context.cacheDir, UnlockedFolder)
            folder.listFiles()?.forEach { it.delete() }
            if (password == null) {
                return PdfSession(context, PdfRenderer(file), boxFile, null, null, null, forceLegacy)
            }
            if (Build.VERSION.SDK_INT >= 35 && !forceLegacy) {
                return PdfSession(context, openWithPassword(file, password), boxFile, password, null, null, false)
            }
            folder.mkdirs()
            val copy = File(folder, "${System.nanoTime()}.pdf")
            try {
                PdfBoxDocument.writeUnlocked(context, boxFile, password, copy)
                PdfService.closeQuietly(file)
                val unlocked = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(unlocked)
                return PdfSession(context, renderer, boxFile, password, null, copy, forceLegacy)
            } catch (error: Throwable) {
                copy.delete()
                throw error
            }
        }

        @RequiresApi(35)
        private fun openWithPassword(file: ParcelFileDescriptor, password: String): PdfRenderer =
            PdfRenderer(file, LoadParams.Builder().setPassword(password).build())
    }
}
