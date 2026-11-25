package com.kobblo.gallery.file.explorer.tools.archive

import android.content.Context
import android.net.Uri
import com.kobblo.gallery.file.explorer.archive.ArchiveEntryInfo
import com.kobblo.gallery.file.explorer.archive.ArchiveHandler
import com.kobblo.gallery.file.explorer.archive.ArchiveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File

/** Connects [CompressedArchives] to the app's existing archive screens and extraction worker. */
class CompressedArchiveHandler(
    private val context: Context,
    private val format: CompressedFormat
) : ArchiveHandler {

    /** 7z needs to seek, and the others read a file just as well, so a content URI is copied first. */
    private suspend fun <T> withFile(source: Uri, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        if (source.scheme == "file" || source.scheme == null) {
            block(File(source.path ?: source.toString()))
        } else {
            val name = source.lastPathSegment?.substringAfterLast('/') ?: "archive"
            val temp = File(context.cacheDir, "archive_${System.currentTimeMillis()}_${name.replace('/', '_')}")
            try {
                context.contentResolver.openInputStream(source)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw java.io.FileNotFoundException("Cannot read source")
                block(temp)
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun isEncrypted(source: Uri): Boolean {
        if (format != CompressedFormat.SEVEN_Z) return false
        return withFile(source) { file ->
            try {
                CompressedArchives.list(file, format)
                false
            } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun listEntries(source: Uri, password: String?): List<ArchiveEntryInfo> =
        withFile(source) { CompressedArchives.list(it, format, password) }

    override suspend fun testPassword(source: Uri, password: String): Boolean {
        if (format != CompressedFormat.SEVEN_Z) return true
        return withFile(source) { file ->
            try {
                CompressedArchives.list(file, format, password)
                // A 7z can keep its names in the clear and encrypt only the contents, so one
                // entry has to actually be read to know the password is right.
                val temp = File(context.cacheDir, "archive_test_${System.currentTimeMillis()}")
                try {
                    CompressedArchives.extract(file, format, temp, password) { _, index ->
                        if (index > 0) throw StopAfterFirst()
                    }
                } catch (_: StopAfterFirst) {
                } finally {
                    temp.deleteRecursively()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private class StopAfterFirst : Exception()

    override fun extract(source: Uri, destDir: Uri, password: String?): Flow<ArchiveProgress> = channelFlow {
        val destination = File(destDir.path ?: throw IllegalArgumentException("Invalid destination"))
        try {
            withFile(source) { file ->
                val listed = try {
                    CompressedArchives.list(file, format, password)
                } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                    send(ArchiveProgress.NeedsPassword(attemptFailed = password != null))
                    return@withFile
                }
                destination.mkdirs()
                // What it says it holds has to fit before a byte is written.
                com.kobblo.gallery.file.explorer.fileops.ExtractGuard.requireRoom(destination, listed.sumOf { it.sizeBytes.coerceAtLeast(0L) })
                val total = listed.count { !it.isDirectory }.coerceAtLeast(1)
                val job = currentCoroutineContext().job
                var lastReported = -1
                CompressedArchives.extract(file, format, destination, password) { name, index ->
                    job.ensureActive()
                    if (index != lastReported) {
                        lastReported = index
                        trySend(ArchiveProgress.Running(index, total, name))
                    }
                }
                send(ArchiveProgress.Complete(destination.path))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: org.apache.commons.compress.PasswordRequiredException) {
            send(ArchiveProgress.NeedsPassword(attemptFailed = password != null))
        } catch (e: java.io.IOException) {
            val message = e.message.orEmpty()
            if (password != null && (message.contains("password", ignoreCase = true) || message.contains("checksum", ignoreCase = true) || message.contains("CRC", ignoreCase = true))) {
                send(ArchiveProgress.NeedsPassword(attemptFailed = true))
            } else {
                android.util.Log.w("CompressedArchive", "Extraction failed", e)
                send(ArchiveProgress.Failed(context.getString(com.kobblo.gallery.file.explorer.R.string.polish_extract_failed)))
            }
        } catch (e: Exception) {
            android.util.Log.w("CompressedArchive", "Extraction failed", e)
            send(ArchiveProgress.Failed(context.getString(com.kobblo.gallery.file.explorer.R.string.polish_extract_failed)))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(sources: List<Uri>, destArchive: Uri): Flow<ArchiveProgress> =
        throw UnsupportedOperationException("Creating this format isn't supported")
}
