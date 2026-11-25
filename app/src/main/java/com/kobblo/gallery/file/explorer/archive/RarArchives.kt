package com.kobblo.gallery.file.explorer.archive

import be.stef.rar.ExtractionResult
import be.stef.rar.Unrar5j
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import java.io.File

/** The two things asked of the RAR library. Behind an interface so the rules around them can be tried without it. */
internal interface RarLibrary {
    /** Whether the archive's headers mark it as encrypted. */
    fun isEncrypted(archivePath: String): Boolean

    /** Unpacks everything it can. What went wrong along the way is in the result. */
    fun extract(archivePath: String, intoPath: String, password: String?): ExtractionResult
}

internal object Unrar : RarLibrary {
    override fun isEncrypted(archivePath: String): Boolean = Unrar5j.isEncrypted(archivePath)

    override fun extract(archivePath: String, intoPath: String, password: String?): ExtractionResult =
        Unrar5j.extract(archivePath, intoPath, password)
}

/**
 * Reads RAR files, old format and new. The library can do one thing with an archive, which is unpack all
 * of it, so that's how every question here gets answered: to list one or to try a password on one, it's
 * unpacked into a scratch folder that's deleted again straight after.
 *
 * Works on plain files and knows nothing of Android. [RarArchiveHandler] puts the app's addresses and
 * words around it.
 */
internal class RarArchives(private val library: RarLibrary = Unrar) {

    /** How unpacking ended. */
    sealed class Ending {
        /** Encrypted, and the password was missing or wrong. */
        object NeedsPassword : Ending()

        /** Some of it came out and some didn't. [failedNames] is the first few that didn't, if the library named any. */
        class Incomplete(val failedNames: List<String>) : Ending()

        /** Nothing in it, and nothing came out. */
        object Unreadable : Ending()

        class Done(val unpacked: Int, val total: Int) : Ending()
    }

    /** What listing found: the entries, or that they can't be seen without a password. */
    sealed class Listing {
        object Locked : Listing()
        class Entries(val entries: List<ArchiveEntryInfo>) : Listing()
    }

    /**
     * Whether [archive] wants a password. The headers are asked first. When they say no, it's unpacked
     * with no password to see: being refused one, or getting nothing out of it at all, means yes.
     */
    fun isEncrypted(archive: File, scratch: File): Boolean = try {
        library.isEncrypted(archive.absolutePath) || unpackedBriefly(archive, null, scratch) { result ->
            refusedPassword(result) || (result.failedFiles.isNotEmpty() && result.successCount == 0)
        }
    } catch (e: Exception) {
        isAboutPassword(e)
    }

    /**
     * Everything in [archive], by unpacking it with [password]. Without a password, an archive that
     * turns out to need one is [Listing.Locked]. With a wrong one, the listing is whatever came out.
     */
    fun list(archive: File, password: String?, scratch: File): Listing {
        val saysEncrypted = try {
            library.isEncrypted(archive.absolutePath)
        } catch (_: Exception) {
            false
        }
        return unpackedBriefly(archive, password, scratch) { result ->
            val locked = refusedPassword(result) || (saysEncrypted && password == null && result.successCount == 0)
            if (locked && password == null) Listing.Locked else Listing.Entries(entriesIn(scratch, saysEncrypted))
        }
    }

    /** Whether [password] unpacks all of [archive] without a single error. */
    fun opensWith(archive: File, password: String, scratch: File): Boolean = try {
        unpackedBriefly(archive, password, scratch) { result -> !refusedPassword(result) && result.errorCount == 0 }
    } catch (_: Exception) {
        false
    }

    /** Unpacks [archive] into [destination], which is left as it is whatever happens. */
    fun unpack(archive: File, destination: File, password: String?): Ending =
        endingOf(library.extract(archive.absolutePath, destination.absolutePath, password)) {
            destination.listFiles().isNullOrEmpty()
        }

    private inline fun <T> unpackedBriefly(archive: File, password: String?, scratch: File, read: (ExtractionResult) -> T): T {
        scratch.mkdirs()
        try {
            return read(library.extract(archive.absolutePath, scratch.absolutePath, password))
        } finally {
            SafeFiles.deleteTree(scratch)
        }
    }

    companion object {
        /** [ExtractionResult.passwordStatus] once a password has been checked against the archive and failed. */
        private const val PasswordRejected = 2

        /** How many of the files that failed are worth naming in a message. */
        private const val NamesToMention = 5

        /** Whether a password stood in the way: the library's own verdict, or any one error that says so. */
        fun refusedPassword(result: ExtractionResult): Boolean =
            result.passwordStatus == PasswordRejected || result.errors.any { error ->
                error.isEncryptedBlock || error.errorMessage.saysAnyOf("password") || error.exception?.message.saysAnyOf("password")
            }

        /** The same question about something the library threw instead. */
        fun isAboutPassword(error: Throwable): Boolean = error.message.saysAnyOf("password", "encrypt")

        /**
         * What a finished run amounts to. Some good files and some bad is still a failure, since calling
         * that done would hide what's missing. [nothingThere] is asked only when the library reports no
         * files at all, to tell an empty result from one it just didn't count.
         */
        fun endingOf(result: ExtractionResult, nothingThere: () -> Boolean): Ending = when {
            refusedPassword(result) -> Ending.NeedsPassword
            result.errorCount > 0 -> Ending.Incomplete(result.failedFiles.take(NamesToMention))
            result.totalFiles == 0 && result.successCount == 0 && nothingThere() -> Ending.Unreadable
            else -> Ending.Done(result.successCount, if (result.totalFiles > 0) result.totalFiles else result.successCount)
        }

        /**
         * The listing of what was unpacked into [folder]: every file and folder under it, each by its path
         * from there, a folder ahead of what's in it.
         */
        fun entriesIn(folder: File, encrypted: Boolean): List<ArchiveEntryInfo> =
            folder.walkTopDown()
                .filter { it != folder }
                .map { found ->
                    ArchiveEntryInfo(
                        name = found.relativeTo(folder).path,
                        sizeBytes = if (found.isFile) found.length() else 0L,
                        isDirectory = found.isDirectory,
                        isEncrypted = encrypted
                    )
                }
                .toList()
    }
}
