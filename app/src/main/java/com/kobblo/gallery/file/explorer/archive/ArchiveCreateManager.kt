package com.kobblo.gallery.file.explorer.archive

import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** What the person asked for in the sheet: the archive's name, its format, and a password if it's to be locked. */
data class ArchiveCreateConfig(
    val name: String,
    val password: String? = null,
    val format: ArchiveFormat = ArchiveFormat.ZIP
)

/**
 * Makes an archive out of files and folders. Whatever the format, it's built under a temporary name, read
 * back to see that everything is in it, and only then given its real name, so nothing half-made ever looks
 * like a finished archive and a failure leaves nothing behind.
 */
object ArchiveCreateManager {
    /** The item reported with 100%: a marker, which the job shows in the app's language. */
    const val DoneItem = "Done"

    /**
     * @param destinationFile where the archive should go. If the name is taken it gets a number, and the
     * file returned is the one actually made.
     * @param onProgress how far along it is, and the file being added.
     * @param isCancelled asked again and again while it works. True stops it with a [CancellationException].
     * @param onBuilding told where it's being built, before there's anything there, so a kill can be cleaned up after.
     */
    suspend fun createArchive(
        sourceFiles: List<File>,
        destinationFile: File,
        config: ArchiveCreateConfig,
        onProgress: (percent: Int, currentFile: String) -> Unit,
        isCancelled: () -> Boolean = { false },
        onBuilding: (part: File, final: File) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        require(sourceFiles.isNotEmpty()) { "No files selected for archiving" }
        val folder = destinationFile.parentFile ?: throw IllegalArgumentException("No folder for ${destinationFile.name}")
        // A name that's taken gets a number. It could be another archive, or even one of the files going in.
        val finalFile = File(folder, SafeFiles.freeName(destinationFile.name) { SafeFiles.existsAtAll(File(folder, it)) })

        if (config.format == ArchiveFormat.ZIP) {
            createZip(sourceFiles, folder, finalFile, config.password, onProgress, isCancelled, onBuilding)
        } else {
            createWithoutZip4j(sourceFiles, folder, finalFile, config.format, onProgress, isCancelled, onBuilding)
        }
    }

    private suspend fun createZip(
        sources: List<File>,
        folder: File,
        finalFile: File,
        password: String?,
        onProgress: (percent: Int, currentFile: String) -> Unit,
        isCancelled: () -> Boolean,
        onBuilding: (part: File, final: File) -> Unit
    ): File {
        // Built under a temporary name, so nothing half-made ever looks like a finished archive.
        val part = File(SafeFiles.partFileIn(folder).path + ".zip")
        onBuilding(part, finalFile)
        val writing = ZipWriting(part, password, onProgress, isCancelled)
        try {
            // zip4j takes loose files in one go and each folder on its own.
            val (files, others) = sources.partition { it.isFile }
            if (files.isNotEmpty()) writing.add(files)
            for (source in others) if (source.isDirectory) writing.add(source)

            onProgress(100, DoneItem)
            checkHoldsEverything(part, password, sources)
            return finish(part, finalFile)
        } catch (e: Exception) {
            writing.stop()
            SafeFiles.deleteTree(part)
            throw e
        }
    }

    /** The other formats, on the same promises: a temporary name until it's whole and checked, and nothing left if not. */
    private suspend fun createWithoutZip4j(
        sources: List<File>,
        folder: File,
        finalFile: File,
        format: ArchiveFormat,
        onProgress: (percent: Int, currentFile: String) -> Unit,
        isCancelled: () -> Boolean,
        onBuilding: (part: File, final: File) -> Unit
    ): File {
        val part = SafeFiles.partFileIn(folder)
        onBuilding(part, finalFile)
        val context = currentCoroutineContext()
        val writer = ArchiveWriter(isStopped = { isCancelled() || !context.isActive }, onProgress = onProgress)
        try {
            val written = writer.write(format, writer.list(sources), part)
            onProgress(100, DoneItem)
            writer.check(format, part, written)
            return finish(part, finalFile)
        } catch (e: Throwable) {
            SafeFiles.deleteTree(part)
            // A job that runs out of memory should end as a failed job, not take the app down.
            throw if (e is OutOfMemoryError) IOException("Not enough memory to make ${finalFile.name}", e) else e
        }
    }

    /** The whole, checked archive takes its real name, which has to be free still. */
    private fun finish(part: File, finalFile: File): File {
        if (SafeFiles.existsAtAll(finalFile) || !part.renameTo(finalFile)) throw IOException("Couldn't finish ${finalFile.name}")
        return finalFile
    }

    /** Someone may delete the originals next, so the archive has to be readable and have all of them in it. */
    private fun checkHoldsEverything(archive: File, password: String?, sources: List<File>) {
        val reread = if (!password.isNullOrBlank()) ZipFile(archive, password.toCharArray()) else ZipFile(archive)
        if (!reread.isValidZipFile) throw IOException("The archive didn't come out readable")
        val expected = SafeFiles.TreeSize()
        sources.forEach { SafeFiles.sizeOf(it, expected) }
        val entries = reread.fileHeaders.filter { !it.isDirectory }
        if (entries.size < expected.files || entries.sumOf { it.uncompressedSize } < expected.bytes) {
            throw IOException("The archive is missing some of the files")
        }
    }
}

/**
 * One ZIP being written by zip4j, which works on a thread of its own once it's told what to add. Each
 * [add] waits that thread out, passing on how far it's got and stopping it if that's asked for.
 */
private class ZipWriting(
    part: File,
    password: String?,
    private val onProgress: (percent: Int, currentFile: String) -> Unit,
    private val isCancelled: () -> Boolean
) {
    /** Null for an archive anyone can open. A blank password counts as none. */
    private val key: CharArray? = password?.takeUnless { it.isBlank() }?.toCharArray()

    private val zip = (if (key != null) ZipFile(part, key) else ZipFile(part)).apply { isRunInThread = true }
    private val progress: ProgressMonitor = zip.progressMonitor

    private val settings = ZipParameters().apply {
        compressionMethod = CompressionMethod.DEFLATE
        compressionLevel = CompressionLevel.NORMAL
        // Left to itself it reads through a link, which could put something from outside the selection in.
        symbolicLinkAction = ZipParameters.SymbolicLinkAction.INCLUDE_LINK_ONLY
        if (key != null) {
            isEncryptFiles = true
            // Not the format's original encryption, which can be broken by anyone who knows part of a file.
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
    }

    suspend fun add(files: List<File>) {
        zip.addFiles(files, settings)
        waitOut(nameUntilKnown = files.first().name, failure = "Failed to add files to archive")
    }

    suspend fun add(folder: File) {
        stopIfAsked()
        zip.addFolder(folder, settings)
        waitOut(nameUntilKnown = folder.name, failure = "Failed to add folder '${folder.name}' to archive")
    }

    /** Its thread keeps writing until it notices, so the file can't be deleted before then. */
    suspend fun stop() {
        if (progress.isBusy) progress.isCancelAllTasks = true
        withContext(NonCancellable) {
            while (progress.isBusy) delay(SettleMillis)
        }
    }

    private suspend fun waitOut(nameUntilKnown: String, failure: String) {
        while (progress.isBusy) {
            stopIfAsked()
            val current = progress.fileName?.substringAfterLast(File.separator) ?: nameUntilKnown
            onProgress(progress.percentDone.coerceIn(0, 100), current)
            delay(ReportMillis)
        }
        if (progress.result == ProgressMonitor.Result.ERROR) throw progress.exception ?: Exception(failure)
    }

    private fun stopIfAsked() {
        if (isCancelled()) throw CancellationException("Archiving was cancelled")
    }

    private val ProgressMonitor.isBusy: Boolean get() = state == ProgressMonitor.State.BUSY

    private companion object {
        const val ReportMillis = 50L
        const val SettleMillis = 20L
    }
}
