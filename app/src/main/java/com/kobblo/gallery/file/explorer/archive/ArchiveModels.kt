package com.kobblo.gallery.file.explorer.archive

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/** One thing inside an archive, as a listing shows it. */
data class ArchiveEntryInfo(
    /** Its whole path inside the archive, with a slash between folders. */
    val name: String,
    /** Its size once unpacked. Below zero where the format doesn't record one. */
    val sizeBytes: Long,
    val isDirectory: Boolean,
    /** Whether a password stands between this and its contents. ZIP and RAR say the same for every entry of an archive. */
    val isEncrypted: Boolean
)

/** What an extraction says as it goes: any number of [Running], and then exactly one of the others. */
sealed class ArchiveProgress {
    /**
     * [filesDone] out of [filesTotal]. Some formats count files and some count percent, so only the two
     * together mean anything. [currentEntryName] is what's being unpacked now.
     */
    data class Running(val filesDone: Int, val filesTotal: Int, val currentEntryName: String) : ArchiveProgress()

    /** It can't go on without a password. [attemptFailed] says one was tried and was wrong. */
    data class NeedsPassword(val attemptFailed: Boolean) : ArchiveProgress()

    /** Everything is unpacked, into [outputDir]. */
    data class Complete(val outputDir: String) : ArchiveProgress()

    /** It gave up. [reason] is a sentence in the app's language, fit to show as it is. */
    data class Failed(val reason: String) : ArchiveProgress()
}

/**
 * Reads one family of archive formats. [ArchiveHandlerFactory] picks the one for a given file, and the
 * screens and the extraction job only ever talk to this.
 */
interface ArchiveHandler {
    /** Whether [source] wants a password. A handler that can't tell without unpacking may leave it to [listEntries]. */
    suspend fun isEncrypted(source: Uri): Boolean = false

    suspend fun listEntries(source: Uri): List<ArchiveEntryInfo> = listEntries(source, null)

    /** Everything in [source]. A [password] is only needed where the names themselves are encrypted. */
    suspend fun listEntries(source: Uri, password: String?): List<ArchiveEntryInfo>

    /** Whether [password] really opens [source], found out by decrypting something with it. */
    suspend fun testPassword(source: Uri, password: String): Boolean

    /**
     * Unpacks [source] into the folder [destDir]. Collecting the flow is what does the work, and
     * cancelling the collection is how it's stopped.
     */
    fun extract(source: Uri, destDir: Uri, password: String? = null): Flow<ArchiveProgress>

    /** Packs [sources] into [destArchive]. No handler does: archives are made by [ArchiveCreateManager]. */
    suspend fun create(sources: List<Uri>, destArchive: Uri): Flow<ArchiveProgress>
}
