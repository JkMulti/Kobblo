package com.kobblo.gallery.file.explorer.viewer.pdf

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.Collections

internal fun isPdfProcess(application: Application): Boolean {
    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        try {
            File("/proc/self/cmdline").readText().trimEnd('\u0000')
        } catch (_: Exception) {
            ""
        }
    }
    return name.endsWith(":pdf")
}

/**
 * The one binding to [PdfService]. It lasts while anything is using it and a little beyond, so that
 * scrolling through a folder of PDFs doesn't start a process for each.
 */
internal object PdfConnection {
    private const val IdleUnbindMs = 45_000L
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var connection: ServiceConnection? = null
    private var ready = CompletableDeferred<IPdfService>()
    private var users = 0

    private val unbind = Runnable {
        synchronized(this) {
            if (users == 0) disconnect()
        }
    }

    private fun disconnect() {
        connection?.let { current ->
            try { appContext?.unbindService(current) } catch (_: Exception) {}
        }
        connection = null
        renewReady()
    }

    /** Someone may already be waiting on an unfinished one, so that's left for them. */
    private fun renewReady() {
        if (ready.isCompleted) ready = CompletableDeferred()
    }

    @Synchronized
    private fun bind(context: Context): CompletableDeferred<IPdfService> {
        handler.removeCallbacks(unbind)
        if (connection == null) {
            val app = context.applicationContext
            appContext = app
            val fresh = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    synchronized(this@PdfConnection) {
                        if (connection !== this) return
                        // After a crash the system brings the process back and this runs again.
                        renewReady()
                        ready.complete(IPdfService.Stub.asInterface(service))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(this@PdfConnection) {
                        if (connection === this) renewReady()
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    synchronized(this@PdfConnection) {
                        if (connection === this) disconnect()
                    }
                }
            }
            connection = fresh
            renewReady()
            app.bindService(Intent(app, PdfService::class.java), fresh, Context.BIND_AUTO_CREATE)
        }
        return ready
    }

    @Synchronized
    fun retain(context: Context) {
        users++
        bind(context)
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) handler.postDelayed(unbind, IdleUnbindMs)
    }

    /** Suspends until the process is up. Throws if it doesn't arrive in time. */
    suspend fun service(context: Context): IPdfService = withTimeout(ConnectTimeoutMs) {
        val current = bind(context)
        val service = current.await()
        if (service.asBinder().isBinderAlive) {
            service
        } else {
            // It died, and whoever noticed is here before the news that it's gone reached the main thread.
            synchronized(this@PdfConnection) { if (ready === current) ready = CompletableDeferred() }
            bind(context).await()
        }
    }

    private const val ConnectTimeoutMs = 15_000L
}

/** The result of asking for a document. */
internal sealed interface PdfOpenOutcome {
    class Ready(val doc: PdfDoc) : PdfOpenOutcome
    data object NeedsPassword : PdfOpenOutcome
    data object WrongPassword : PdfOpenOutcome
    data object Damaged : PdfOpenOutcome
    data object Missing : PdfOpenOutcome
    data object NoAccess : PdfOpenOutcome
}

/** An outline entry. A page of -1 means the file doesn't say where it goes. */
internal class PdfOutlineItem(val title: String, val page: Int, val depth: Int)

internal class PdfInfo(val outline: List<PdfOutlineItem>, val title: String?, val author: String?) {
    companion object {
        val Empty = PdfInfo(emptyList(), null, null)

        fun from(bundle: Bundle): PdfInfo {
            val titles = bundle.getStringArray(PdfBoxDocument.KeyTitles)
            val pages = bundle.getIntArray(PdfBoxDocument.KeyPages)
            val depths = bundle.getIntArray(PdfBoxDocument.KeyDepths)
            val outline = if (titles != null && pages != null && depths != null) {
                titles.indices.map { PdfOutlineItem(titles[it], pages[it], depths[it]) }
            } else {
                emptyList()
            }
            return PdfInfo(outline, bundle.getString(PdfBoxDocument.KeyTitle), bundle.getString(PdfBoxDocument.KeyAuthor))
        }
    }
}

/**
 * A document open in the PDF process, from this side. If that process dies, because of this file or because
 * the system needed its memory, the session comes back by itself. A page that was being drawn at the time is
 * reported as failed rather than tried again, since it may well be the cause, and after a few deaths the
 * document as a whole is given up on.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class PdfDoc private constructor(
    private val context: Context,
    private val path: String,
    private val password: String?,
    private var service: IPdfService,
    private var session: Int,
    val pageCount: Int,
    val firstWidth: Float,
    val firstHeight: Float,
    /** Stands for what's in the file, whatever it's called and wherever it's moved. */
    val fingerprint: String
) {
    private val renderThread = Dispatchers.IO.limitedParallelism(1)
    private val otherWork = Dispatchers.IO.limitedParallelism(2)
    private var deaths = 0
    private var closed = false

    /** True once the process has died too often to keep trying. */
    @Volatile
    var isDead = false
        private set

    val isLocked: Boolean get() = password != null

    val isProcessAlive: Boolean get() = service.asBinder().isBinderAlive

    /** Draws a page. Null means this page failed and shouldn't be asked for again. */
    suspend fun render(page: Int, spec: PdfRenderSpec): Bitmap? = withContext(renderThread) {
        if (isDead) return@withContext null
        val active = service
        val activeSession = session
        // Native code that never comes back can't be interrupted. All that can be done is end its process.
        val watchdog = watchdogScope.launch {
            delay(RenderTimeoutMs)
            try { Process.killProcess(active.pid()) } catch (_: Exception) {}
        }
        try {
            active.render(activeSession, page, spec.width, spec.height, spec.matrix)?.bitmap
        } catch (_: RemoteException) {
            recover()
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    /** Sizes for up to [count] pages from [from]. Null if the process died meanwhile, which ends the scan. */
    suspend fun pageSizes(from: Int, count: Int): FloatArray? = withContext(otherWork) {
        try {
            service.pageSizes(session, from, count)
        } catch (_: RemoteException) {
            recover()
            null
        }
    }

    suspend fun pageText(page: Int): String = withContext(otherWork) {
        try {
            service.pageText(session, page).orEmpty()
        } catch (_: RemoteException) {
            recover()
            ""
        }
    }

    suspend fun pageChars(page: Int): PdfPageChars? = withContext(otherWork) {
        try {
            val bundle = service.pageChars(session, page)
            val text = bundle.getString(PdfService.KeyText)
            val rects = bundle.getFloatArray(PdfService.KeyRects)
            if (text != null && rects != null) PdfPageChars(text, rects) else null
        } catch (_: RemoteException) {
            recover()
            null
        }
    }

    suspend fun pageLinks(page: Int): List<PdfLink> = withContext(otherWork) {
        try {
            val bundle = service.pageLinks(session, page)
            val rects = bundle.getFloatArray(PdfService.KeyRects)
            val pages = bundle.getIntArray(PdfBoxDocument.KeyPages)
            val uris = bundle.getStringArray(PdfBoxDocument.KeyUris)
            if (rects == null || pages == null || uris == null) {
                emptyList()
            } else {
                pages.indices.map { at ->
                    PdfLink(
                        PdfRect(rects[at * 4], rects[at * 4 + 1], rects[at * 4 + 2], rects[at * 4 + 3]),
                        pages[at],
                        uris[at].ifEmpty { null }
                    )
                }
            }
        } catch (_: RemoteException) {
            recover()
            emptyList()
        }
    }

    suspend fun info(): PdfInfo = withContext(otherWork) {
        try {
            PdfInfo.from(service.info(session))
        } catch (_: RemoteException) {
            recover()
            PdfInfo.Empty
        }
    }

    suspend fun unlockedCopy(): File? = withContext(otherWork) {
        try {
            service.unlockedCopy(session)?.let(::File)
        } catch (_: RemoteException) {
            recover()
            null
        }
    }

    suspend fun search(id: Int, query: String, startPage: Int, callback: IPdfSearchCallback.Stub) {
        withContext(otherWork) {
            try {
                service.search(session, id, query, startPage, callback)
            } catch (_: RemoteException) {
                recover()
                callback.onFinished(id, false)
            }
        }
    }

    fun cancelSearch() {
        watchdogScope.launch {
            try { service.cancelSearch(session) } catch (_: RemoteException) {}
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val active = service
        val activeSession = session
        watchdogScope.launch {
            try { active.close(activeSession) } catch (_: RemoteException) {}
            PdfConnection.release()
        }
    }

    private suspend fun recover() {
        if (closed) return
        deaths++
        if (deaths > MaxDeaths) {
            isDead = true
            return
        }
        try {
            val fresh = PdfConnection.service(context)
            val file = openDescriptor(context, path)
            val reopened = fresh.open(file, password)
            try { file.close() } catch (_: Exception) {}
            if (reopened < 0) {
                isDead = true
            } else {
                service = fresh
                session = reopened
            }
        } catch (_: Exception) {
            isDead = true
        }
    }

    companion object {
        private const val MaxDeaths = 3

        // A page that takes longer than this is either a monster or stuck, and either way can't be read.
        private const val RenderTimeoutMs = 45_000L

        private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun open(context: Context, path: String, password: String?): PdfOpenOutcome = withContext(Dispatchers.IO) {
            val file = try {
                openDescriptor(context, path)
            } catch (_: FileNotFoundException) {
                return@withContext PdfOpenOutcome.Missing
            } catch (_: SecurityException) {
                return@withContext PdfOpenOutcome.NoAccess
            } catch (_: Exception) {
                return@withContext PdfOpenOutcome.Damaged
            }
            PdfConnection.retain(context)
            var kept = false
            try {
                val readable = seekableCopyIfNeeded(context, file, path)
                val fingerprint = try {
                    val stream = FileInputStream(readable.fileDescriptor)
                    fingerprintOf(stream.channel, stream.channel.size())
                } catch (_: Exception) {
                    ""
                }
                val service = PdfConnection.service(context)
                val session = service.open(readable, password)
                try { readable.close() } catch (_: Exception) {}
                when (session) {
                    PdfOpenResult.NeedsPassword -> return@withContext PdfOpenOutcome.NeedsPassword
                    PdfOpenResult.WrongPassword -> return@withContext PdfOpenOutcome.WrongPassword
                    PdfOpenResult.Damaged -> return@withContext PdfOpenOutcome.Damaged
                }
                val count = service.pageCount(session)
                if (count <= 0) {
                    service.close(session)
                    return@withContext PdfOpenOutcome.Damaged
                }
                val first = service.pageSizes(session, 0, 1)
                kept = true
                PdfOpenOutcome.Ready(
                    PdfDoc(
                        context.applicationContext,
                        path,
                        password,
                        service,
                        session,
                        count,
                        first.getOrElse(0) { 612f },
                        first.getOrElse(1) { 792f },
                        fingerprint
                    )
                )
            } catch (_: Exception) {
                // Its process died on the file, or never came up.
                PdfOpenOutcome.Damaged
            } finally {
                if (!kept) PdfConnection.release()
            }
        }

        /** The first page of a file for a list, drawn across the wall. Null if it can't be, which shows a glyph instead. */
        suspend fun thumbnail(context: Context, file: File, maxWidth: Int, maxHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
            // A file that crashed the process once would do it again on each scroll past.
            if (file.path in crashedThumbnails) return@withContext null
            PdfConnection.retain(context)
            try {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfConnection.service(context).thumbnail(descriptor, maxWidth, maxHeight)?.bitmap
                } catch (_: RemoteException) {
                    crashedThumbnails.add(file.path)
                    null
                } finally {
                    try { descriptor.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                null
            } finally {
                PdfConnection.release()
            }
        }

        private val crashedThumbnails: MutableSet<String> = Collections.synchronizedSet(HashSet())

        internal fun openDescriptor(context: Context, path: String): ParcelFileDescriptor {
            if (path.startsWith("content://")) {
                return context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                    ?: throw FileNotFoundException("No stream")
            }
            val file = File(path)
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (notFound: FileNotFoundException) {
                if (!file.exists()) throw notFound
                // A file this app can't open by path, without All Files Access, may still come through the provider.
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.contentResolver.openFileDescriptor(uri, "r") ?: throw notFound
            }
        }

        /**
         * The renderer has to be able to jump around, and a stream from some other apps can't. Those are copied
         * first, into the same place the rest of the app keeps files it was handed.
         */
        private fun seekableCopyIfNeeded(context: Context, file: ParcelFileDescriptor, path: String): ParcelFileDescriptor {
            val seekable = try {
                Os.lseek(file.fileDescriptor, 0L, OsConstants.SEEK_SET)
                true
            } catch (_: ErrnoException) {
                false
            }
            if (seekable) return file
            val folder = File(context.cacheDir, "external_view").apply { mkdirs() }
            val copy = File(folder, "stream_${path.hashCode()}.pdf")
            ParcelFileDescriptor.AutoCloseInputStream(file).use { input ->
                copy.outputStream().use { output -> input.copyTo(output) }
            }
            return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
