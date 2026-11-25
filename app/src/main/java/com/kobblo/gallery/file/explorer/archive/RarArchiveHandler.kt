package com.kobblo.gallery.file.explorer.archive

import android.content.Context
import android.net.Uri
import android.util.Log
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * RAR files for the archive screens and the extraction job. The reading is [RarArchives]'. This supplies
 * the file to read, a scratch folder in the cache for it to unpack into, and the words for how it went.
 */
class RarArchiveHandler(private val context: Context) : ArchiveHandler {

    private val rar = RarArchives()

    override suspend fun isEncrypted(source: Uri): Boolean =
        onFile(source) { rar.isEncrypted(it, scratch("check_enc")) }

    override suspend fun listEntries(source: Uri, password: String?): List<ArchiveEntryInfo> = onFile(source) { archive ->
        when (val listing = rar.list(archive, password, scratch("list"))) {
            is RarArchives.Listing.Entries -> listing.entries
            // One row standing for the whole archive and marked encrypted, which is what tells the
            // screens to ask for the password.
            RarArchives.Listing.Locked -> listOf(
                ArchiveEntryInfo(
                    name = context.getString(R.string.view_archive_encrypted_entry),
                    sizeBytes = archive.length(),
                    isDirectory = false,
                    isEncrypted = true
                )
            )
        }
    }

    override suspend fun testPassword(source: Uri, password: String): Boolean =
        onFile(source) { rar.opensWith(it, password, scratch("test")) }

    override fun extract(source: Uri, destDir: Uri, password: String?): Flow<ArchiveProgress> = flow {
        LocalArchive.open(context, source, ".rar").use { archive ->
            try {
                val destPath = destDir.path ?: throw Exception("Invalid destination")
                val destination = File(destPath)
                if (!destination.exists()) destination.mkdirs()

                // The library says nothing until it's finished, so this is all there is to show meanwhile.
                emit(ArchiveProgress.Running(0, 0, context.getString(R.string.view_archive_extracting)))
                when (val ending = rar.unpack(archive.file, destination, password)) {
                    RarArchives.Ending.NeedsPassword -> emit(ArchiveProgress.NeedsPassword(attemptFailed = password != null))
                    is RarArchives.Ending.Incomplete -> emit(ArchiveProgress.Failed(naming(ending.failedNames)))
                    RarArchives.Ending.Unreadable -> emit(failed(R.string.polish_archive_unreadable))
                    is RarArchives.Ending.Done -> {
                        emit(ArchiveProgress.Running(ending.unpacked, ending.total, context.getString(R.string.view_archive_done)))
                        emit(ArchiveProgress.Complete(destPath))
                    }
                }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (e: Exception) {
                if (RarArchives.isAboutPassword(e)) {
                    emit(ArchiveProgress.NeedsPassword(attemptFailed = password != null))
                } else {
                    Log.w(Tag, "Extraction failed", e)
                    emit(failed(R.string.polish_extract_failed))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /** The failure with the files that didn't come out named in it, when the library said which they were. */
    private fun naming(failedNames: List<String>): String =
        if (failedNames.isEmpty()) {
            context.getString(R.string.polish_extract_failed)
        } else {
            context.getString(R.string.polish_extract_failed_names, failedNames.joinToString(", "))
        }

    private fun failed(reason: Int) = ArchiveProgress.Failed(context.getString(reason))

    override suspend fun create(sources: List<Uri>, destArchive: Uri): Flow<ArchiveProgress> {
        throw UnsupportedOperationException("RAR creation not supported")
    }

    /** Runs [read] on [source] as a file, off the main thread, and clears up the copy if one had to be made. */
    private suspend fun <T> onFile(source: Uri, read: (File) -> T): T = withContext(Dispatchers.IO) {
        LocalArchive.open(context, source, ".rar").use { read(it.file) }
    }

    /** A folder for one unpacking, named for what it's for. [RarArchives] makes it and deletes it. */
    private fun scratch(purpose: String) = File(context.cacheDir, "rar_${purpose}_${System.currentTimeMillis()}")

    private companion object {
        const val Tag = "RarArchiveHandler"
    }
}
