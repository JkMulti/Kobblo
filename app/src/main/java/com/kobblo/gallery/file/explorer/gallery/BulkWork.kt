package com.kobblo.gallery.file.explorer.gallery

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.JobControl
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.JobOutcome
import com.kobblo.gallery.file.explorer.fileops.JobPlan
import com.kobblo.gallery.file.explorer.fileops.JobResult
import com.kobblo.gallery.file.explorer.fileops.JobSpec
import com.kobblo.gallery.file.explorer.fileops.JobSurface
import com.kobblo.gallery.file.explorer.gallery.editor.EditorDecoder
import com.kobblo.gallery.file.explorer.gallery.editor.EditorSaver
import com.kobblo.gallery.file.explorer.viewer.metadata.MetadataActions
import com.kobblo.gallery.file.explorer.viewer.metadata.PhotoRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** What a copy is saved as, and how big. Zero means as big as the original. */
enum class CopyFormat(val extension: String) { JPEG("jpg"), PNG("png"), WEBP("webp") }

/**
 * Work on many selected photos at once, under the same job runner as any other long file operation, so it can be
 * stopped, it survives the app leaving the screen, and a kill is explained on the next launch. Originals are only
 * ever changed by the rotation tag, and everything else makes new files.
 */
internal object BulkWork {
    data class Progress(val title: String, val done: Int, val total: Int)

    private var jobId: Long? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    fun cancel() {
        jobId?.let(FileJobs::cancel)
    }

    /** The one sentence each run ends with. */
    private fun outcome(failed: Int, doneTitle: String, folder: String?) =
        JobResult(if (failed == 0) JobOutcome.DONE else JobOutcome.PROBLEM, doneTitle, null, null, folder)

    private fun start(
        context: Context,
        kind: JobKind,
        title: String,
        stoppedTitle: String,
        files: List<FileItem>,
        work: suspend (JobControl) -> JobResult
    ) {
        val app = context.applicationContext
        val folder = files.firstOrNull()?.path?.substringBeforeLast('/')
        jobId = FileJobs.enqueueAndSay(
            JobSpec(
                kind = kind,
                surface = JobSurface.OWN,
                title = title,
                total = files.size,
                plan = JobPlan(kind, title, folder, files.map { it.path }),
                onStopped = { JobResult(JobOutcome.STOPPED, stoppedTitle, null, null, folder) },
                onFailed = { error ->
                    val text = error.message ?: app.getString(R.string.safety_reason_unknown)
                    JobResult(JobOutcome.PROBLEM, stoppedTitle, text, text, folder)
                },
                work = work
            )
        ) { message -> android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** Turns every JPEG by its tag. Anything else is left as it is and counted, for saying so. */
    fun rotate(context: Context, files: List<FileItem>, degrees: Int, onDone: suspend (done: Int, skipped: Int, failed: Int) -> Unit) {
        val app = context.applicationContext
        val title = app.getString(R.string.gallery_bulk_rotating)
        start(app, JobKind.ORGANIZE, title, app.getString(R.string.gallery_bulk_rotate_stopped), files) { control ->
            var done = 0
            var skipped = 0
            var failed = 0
            _progress.value = Progress(title, 0, files.size)
            try {
                for (file in files) {
                    control.checkpoint()
                    if (!PhotoRotation.canRotate(file)) {
                        skipped++
                    } else {
                        control.setItem(file.name)
                        val temp = File(file.path).let { File(it.parentFile, ".${it.name}.turning") }
                        control.journal.started(file.path, temp.path)
                        try {
                            PhotoRotation.rotate(app, file.path, degrees)
                            done++
                        } catch (e: Exception) {
                            android.util.Log.e("BulkWork", "Couldn't turn a photo", e)
                            failed++
                        }
                        control.journal.finished(file.path, temp.path)
                    }
                    control.setCount(done + skipped + failed)
                    _progress.value = Progress(title, done + skipped + failed, files.size)
                }
            } finally {
                _progress.value = null
            }
            withContext(Dispatchers.Main) { onDone(done, skipped, failed) }
            outcome(failed, app.resources.getQuantityString(R.plurals.gallery_bulk_rotated, done, done), files.firstOrNull()?.path?.substringBeforeLast('/'))
        }
    }

    /**
     * Sets files' dates to what their names say. Only the date on the file changes, what was there is written down
     * first so it can be put back, and the system is told, so the feed moves them to where they belong.
     */
    fun fixDates(context: Context, corrections: List<Pair<FileItem, Long>>, onDone: suspend (done: Int, failed: Int) -> Unit) {
        val app = context.applicationContext
        val title = app.getString(R.string.gallery_bulk_fixing_dates)
        val files = corrections.map { it.first }
        start(app, JobKind.ORGANIZE, title, app.getString(R.string.gallery_bulk_dates_stopped), files) { control ->
            DateFixRecord.write(app, files.map { it.path to File(it.path).lastModified() })
            setDates(app, corrections.map { (file, millis) -> file.path to millis }, title, control, onDone)
            outcome(0, title, files.firstOrNull()?.path?.substringBeforeLast('/'))
        }
    }

    /** Puts back what the last fix changed. A file that has moved since keeps the date it has. */
    fun undoDateFix(context: Context, onDone: suspend (done: Int, failed: Int) -> Unit) {
        val app = context.applicationContext
        val title = app.getString(R.string.gallery_bulk_undoing_dates)
        val changes = DateFixRecord.read(app).filter { File(it.first).exists() }
        val files = changes.map { (path, _) -> FileItem(0, path, path.substringAfterLast('/'), "image", "media", "", "", "") }
        start(app, JobKind.ORGANIZE, title, app.getString(R.string.gallery_bulk_dates_stopped), files) { control ->
            setDates(app, changes, title, control, onDone)
            DateFixRecord.clear(app)
            outcome(0, title, files.firstOrNull()?.path?.substringBeforeLast('/'))
        }
    }

    private suspend fun setDates(
        app: Context,
        changes: List<Pair<String, Long>>,
        title: String,
        control: JobControl,
        onDone: suspend (done: Int, failed: Int) -> Unit
    ) {
        var done = 0
        var failed = 0
        val touched = ArrayList<String>(changes.size)
        _progress.value = Progress(title, 0, changes.size)
        try {
            for ((path, millis) in changes) {
                control.checkpoint()
                if (File(path).setLastModified(millis)) {
                    touched.add(path)
                    done++
                } else {
                    failed++
                }
                control.setCount(done + failed)
                _progress.value = Progress(title, done + failed, changes.size)
            }
        } finally {
            if (touched.isNotEmpty()) MediaScannerConnection.scanFile(app, touched.toTypedArray(), null, null)
            _progress.value = null
        }
        withContext(Dispatchers.Main) { onDone(done, failed) }
    }

    /**
     * New photos beside the originals, in another format or smaller, keeping their dates and details. Anything that
     * can't be decoded, such as a video, is skipped and counted.
     */
    fun saveCopies(context: Context, files: List<FileItem>, format: CopyFormat, longestSide: Int, onDone: suspend (done: Int, failed: Int) -> Unit) {
        val app = context.applicationContext
        val title = app.getString(R.string.gallery_bulk_saving)
        start(app, JobKind.COPY, title, app.getString(R.string.gallery_bulk_save_stopped), files) { control ->
            var done = 0
            var failed = 0
            val touched = ArrayList<String>()
            _progress.value = Progress(title, 0, files.size)
            try {
                for (file in files) {
                    control.checkpoint()
                    control.setItem(file.name)
                    val made = saveCopy(app, file, format, longestSide, control)
                    if (made != null) {
                        touched.add(made.path)
                        done++
                    } else {
                        failed++
                    }
                    control.setCount(done + failed)
                    _progress.value = Progress(title, done + failed, files.size)
                }
            } finally {
                if (touched.isNotEmpty()) MediaScannerConnection.scanFile(app, touched.toTypedArray(), null, null)
                _progress.value = null
            }
            withContext(Dispatchers.Main) { onDone(done, failed) }
            outcome(failed, app.resources.getQuantityString(R.plurals.gallery_bulk_saved, done, done), files.firstOrNull()?.path?.substringBeforeLast('/'))
        }
    }

    private suspend fun saveCopy(app: Context, file: FileItem, format: CopyFormat, longestSide: Int, control: JobControl): File? {
        if (file.type != "image") return null
        val source = File(file.path)
        val folder = source.parentFile ?: return null
        val bitmap = decodeFitting(app, file.path, longestSide) ?: return null
        val taken = folder.list().orEmpty().toHashSet()
        val suffix = if (longestSide > 0) "_${longestSide}" else ""
        val target = File(folder, freeFileName("${source.nameWithoutExtension}$suffix.${format.extension}") { it in taken })
        val temp = File(folder, ".${target.name}.saving")
        control.journal.started(file.path, temp.path)
        try {
            temp.outputStream().buffered().use { output ->
                if (!bitmap.compress(compressFormat(format), if (format == CopyFormat.PNG) 100 else 92, output)) throw IOException("Couldn't encode")
            }
            EditorSaver.copyExif(file.path, temp.path, bitmap.width, bitmap.height)
            RandomAccessFile(temp, "rw").use { it.fd.sync() }
            temp.setLastModified(source.lastModified())
            if (!temp.renameTo(target)) throw IOException("Couldn't finish the copy")
            return target
        } catch (e: IOException) {
            android.util.Log.e("BulkWork", "Couldn't save a copy", e)
            return null
        } finally {
            temp.delete()
            bitmap.recycle()
            control.journal.finished(file.path, temp.path)
        }
    }

    private fun compressFormat(format: CopyFormat): Bitmap.CompressFormat = when (format) {
        CopyFormat.JPEG -> Bitmap.CompressFormat.JPEG
        CopyFormat.PNG -> Bitmap.CompressFormat.PNG
        CopyFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    /** Upright, and no bigger than asked. A photo too large for memory comes down in size rather than failing. */
    suspend fun decodeFitting(app: Context, path: String, longestSide: Int): Bitmap? {
        val bounds = EditorDecoder.boundsOf(path)
        if (bounds == null) return EditorDecoder.decodeThroughCoil(app, path, if (longestSide > 0) longestSide else 4096)?.let { fitted(it, longestSide) }
        var sample = 1
        val longest = maxOf(bounds.width, bounds.height)
        if (longestSide > 0) while (longest / (sample * 2) >= longestSide) sample *= 2
        while (sample <= 16) {
            try {
                val decoded = EditorDecoder.decode(path, sample) ?: return null
                return fitted(decoded, longestSide)
            } catch (_: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    private fun fitted(bitmap: Bitmap, longestSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= 0 || longest <= longestSide) return bitmap
        val scale = longestSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Metadata-free copies to send, in the cache, and the files sent as they are because there was nothing to remove
     * or no way to. Originals aren't touched. Reports how many couldn't be cleaned, which were left out of the share.
     */
    suspend fun cleanCopies(context: Context, files: List<FileItem>): Pair<List<File>, Int> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val made = MetadataActions.cleanCopiesForSharing(app, files)
        made to (files.size - made.size)
    }
}
