package com.kobblo.gallery.file.explorer.archive

import com.kobblo.gallery.file.explorer.fileops.ExtractGuard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads ZIP files with zip4j, which is what handles their passwords, both the old ZIP kind and AES.
 * Works on plain files and knows nothing of Android, so it's tested on the JVM. [ZipArchiveHandler] puts
 * the app's addresses and words around it.
 */
internal object ZipArchives {

    /** How unpacking ended. */
    sealed class Ending {
        object Done : Ending()

        /** Stopped here because the storage was about to fill up. */
        object OutOfSpace : Ending()

        /** zip4j says it was told to stop, though not by this. */
        object Stopped : Ending()

        /** zip4j reported no result at all, and left nothing in the folder either. */
        object NothingCameOut : Ending()

        /** zip4j gave up, over [cause]. [isAboutPassword] says whether a password would have helped. */
        class Broke(val cause: Throwable?) : Ending()
    }

    /** zip4j reads nothing until asked, so this succeeds for any file. [ZipFile.isValidZipFile] is the first real look. */
    fun open(archive: File, password: String?): ZipFile =
        if (password != null) ZipFile(archive, password.toCharArray()) else ZipFile(archive)

    /** Whether anything in [archive] is encrypted. False for a file that can't be read as a ZIP at all. */
    fun isEncrypted(archive: File): Boolean = try {
        val zip = ZipFile(archive)
        zip.isValidZipFile && (zip.isEncrypted || zip.fileHeaders.any { it.isEncrypted })
    } catch (_: Exception) {
        false
    }

    /**
     * Everything in [archive]. A ZIP's names and sizes aren't encrypted even when its contents are, so
     * this works without the password. If any entry is encrypted, every entry is reported as encrypted.
     */
    fun list(archive: File, password: String?): List<ArchiveEntryInfo> {
        val zip = open(archive, password)
        if (!zip.isValidZipFile) throw Exception("Invalid ZIP file")
        val anyEncrypted by lazy { zip.isEncrypted }
        return zip.fileHeaders.map { header ->
            ArchiveEntryInfo(header.fileName, header.uncompressedSize, header.isDirectory, isEncrypted = header.isEncrypted || anyEncrypted)
        }
    }

    /**
     * Whether [password] opens [archive]. zip4j only finds out when it decrypts, so the first byte of the
     * first file is read. An archive with nothing encrypted, or nothing but folders, takes any password.
     */
    fun opensWith(archive: File, password: String): Boolean {
        val zip = open(archive, password)
        if (!zip.isValidZipFile) return false
        if (!zip.isEncrypted) return true
        val firstFile = zip.fileHeaders.firstOrNull { !it.isDirectory } ?: return true
        return try {
            zip.getInputStream(firstFile).use { it.read() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** What [zip] says its contents come to once unpacked. An archive can lie about this. */
    fun unpackedSize(zip: ZipFile): Long = zip.fileHeaders.sumOf { it.uncompressedSize.coerceAtLeast(0L) }

    /**
     * Unpacks all of [zip] into [destination]. zip4j does the writing on a thread of its own, and this
     * watches it: [onProgress] hears the percent done and the entry being written every [WatchMillis],
     * and the free space is looked at each time too, since the sizes an archive declares can't be trusted.
     *
     * Cancelling this tells that thread to stop and waits until it has. Otherwise it would go on writing
     * into a folder the caller is about to clean up.
     */
    suspend fun unpack(
        zip: ZipFile,
        destination: File,
        isLow: (File) -> Boolean = { ExtractGuard.isLow(it) },
        onProgress: suspend (percent: Int, entryName: String?) -> Unit
    ): Ending {
        zip.isRunInThread = true
        val writer = zip.progressMonitor
        zip.extractAll(destination.path)

        var ranLow = false
        try {
            while (writer.isBusy) {
                if (isLow(destination)) {
                    ranLow = true
                    writer.isCancelAllTasks = true
                }
                onProgress(writer.percentDone, writer.fileName)
                delay(WatchMillis)
            }
        } catch (stopped: CancellationException) {
            writer.isCancelAllTasks = true
            withContext(NonCancellable) {
                while (writer.isBusy) delay(SettleMillis)
            }
            throw stopped
        }

        if (ranLow) return Ending.OutOfSpace
        return when (writer.result) {
            ProgressMonitor.Result.SUCCESS -> Ending.Done
            ProgressMonitor.Result.ERROR -> Ending.Broke(writer.exception)
            ProgressMonitor.Result.CANCELLED -> Ending.Stopped
            // No verdict from the library. What's in the folder is the only evidence left.
            else -> if (destination.listFiles().isNullOrEmpty()) Ending.NothingCameOut else Ending.Done
        }
    }

    /** Whether [error] is zip4j saying the password was missing or wrong, which it does in its message. */
    fun isAboutPassword(error: Throwable?): Boolean = error?.message.saysAnyOf("password", "decrypter")

    private val ProgressMonitor.isBusy: Boolean get() = state == ProgressMonitor.State.BUSY

    private const val WatchMillis = 100L
    private const val SettleMillis = 20L
}
