package com.kobblo.gallery.file.explorer.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.ExtractGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * ZIP files, and the formats that are ZIPs under another name (JAR, APK and the rest), for the archive
 * screens and the extraction job. The reading is [ZipArchives]'. This supplies the file to read and the
 * words for how it went.
 */
class ZipArchiveHandler(private val context: Context) : ArchiveHandler {

    override suspend fun isEncrypted(source: Uri): Boolean = onFile(source) { ZipArchives.isEncrypted(it) }

    override suspend fun listEntries(source: Uri, password: String?): List<ArchiveEntryInfo> =
        onFile(source) { ZipArchives.list(it, password) }

    override suspend fun testPassword(source: Uri, password: String): Boolean =
        onFile(source) { ZipArchives.opensWith(it, password) }

    override fun extract(source: Uri, destDir: Uri, password: String?): Flow<ArchiveProgress> = flow {
        LocalArchive.open(context, source, ".zip").use { archive ->
            val ended = try {
                unpack(archive.file, destDir, password)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (e: Exception) {
                // Anything thrown on the way, a folder that can't be made or too little room included.
                afterError(e, password)
            }
            emit(ended)
        }
    }.flowOn(Dispatchers.IO)

    /** Emits the progress as it goes and returns how it ended, for [extract] to emit last. */
    private suspend fun FlowCollector<ArchiveProgress>.unpack(archive: File, destDir: Uri, password: String?): ArchiveProgress {
        val zip = ZipArchives.open(archive, password)
        if (!zip.isValidZipFile) return ArchiveProgress.Failed(context.getString(R.string.view_archive_invalid_zip))

        val destPath = destDir.path ?: throw Exception("Invalid destination")
        val destination = File(destPath)
        if (!destination.exists()) destination.mkdirs()
        // What the archive says it holds has to fit before a byte is written.
        ExtractGuard.requireRoom(destination, ZipArchives.unpackedSize(zip))

        val starting = context.getString(R.string.view_archive_extracting)
        val ending = ZipArchives.unpack(zip, destination) { percent, entryName ->
            emit(ArchiveProgress.Running(filesDone = percent, filesTotal = 100, currentEntryName = entryName ?: starting))
        }
        return when (ending) {
            ZipArchives.Ending.Done -> ArchiveProgress.Complete(destPath)
            ZipArchives.Ending.OutOfSpace -> failed(R.string.view_archive_storage_full)
            ZipArchives.Ending.Stopped -> failed(R.string.view_archive_extraction_cancelled)
            ZipArchives.Ending.NothingCameOut -> failed(R.string.view_archive_nothing_extracted)
            is ZipArchives.Ending.Broke -> afterError(ending.cause, password)
        }
    }

    /** A password that's missing or wrong is asked for again. Anything else is only worth a line in the log. */
    private fun afterError(error: Throwable?, password: String?): ArchiveProgress {
        if (ZipArchives.isAboutPassword(error)) return ArchiveProgress.NeedsPassword(attemptFailed = password != null)
        Log.w(Tag, "Extraction failed", error)
        return failed(R.string.polish_extract_failed)
    }

    private fun failed(reason: Int) = ArchiveProgress.Failed(context.getString(reason))

    override suspend fun create(sources: List<Uri>, destArchive: Uri): Flow<ArchiveProgress> {
        throw UnsupportedOperationException("ZIP Creation not implemented yet")
    }

    /** Runs [read] on [source] as a file, off the main thread, and clears up the copy if one had to be made. */
    private suspend fun <T> onFile(source: Uri, read: (File) -> T): T = withContext(Dispatchers.IO) {
        LocalArchive.open(context, source, ".zip").use { read(it.file) }
    }

    private companion object {
        const val Tag = "ZipArchiveHandler"
    }
}
