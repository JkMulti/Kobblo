package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.archive.ArchiveEntryInfo
import kotlinx.coroutines.CancellationException

/**
 * One archive, as far as looking inside it goes. It stands between the viewer and the archive engine,
 * so what the viewer decides can be tested without a file. Any of these can throw, as the engine can.
 */
internal interface ArchiveInside {
    suspend fun isEncrypted(): Boolean

    /** What's in the archive. [password] is null to try without one. */
    suspend fun entries(password: String?): List<ArchiveEntryInfo>

    suspend fun accepts(password: String): Boolean
}

/** How a look inside an archive stands. */
internal sealed interface ArchivePeek {
    data object Looking : ArchivePeek

    /** Damaged, or not an archive the engine knows. */
    data object Unreadable : ArchivePeek

    /** It gives nothing away without its password. */
    data object Locked : ArchivePeek

    /** Its contents. [password] is what opened it, and so what extracting it needs: null if it has none. */
    data class Open(val entries: List<ArchiveEntryInfo>, val password: String?) : ArchivePeek {
        /** Folders are listed but not counted: the count is of what extracting would write. */
        val fileCount: Int get() = entries.count { !it.isDirectory }
    }
}

/**
 * Looks inside an archive without a password. [open] is called once, and counts as part of the look:
 * an archive that can't even be opened is unreadable.
 *
 * An archive can say it's protected up front, or list its contents and mark some of them protected, or
 * simply fail in a way that mentions a password. All three mean the same to the reader.
 */
internal suspend fun peekInto(open: suspend () -> ArchiveInside): ArchivePeek = try {
    val archive = open()
    if (archive.isEncrypted()) {
        ArchivePeek.Locked
    } else {
        val entries = archive.entries(password = null)
        if (entries.any { it.isEncrypted }) ArchivePeek.Locked else ArchivePeek.Open(entries, password = null)
    }
} catch (gone: CancellationException) {
    throw gone
} catch (failure: Exception) {
    if (failure.blamesPassword()) ArchivePeek.Locked else ArchivePeek.Unreadable
}

private fun Exception.blamesPassword(): Boolean {
    val said = message.orEmpty()
    return said.contains("password", ignoreCase = true) || said.contains("encrypted", ignoreCase = true)
}

/** How trying a password on a locked archive went. */
internal sealed interface UnlockAttempt {
    data class Opened(val contents: ArchivePeek.Open) : UnlockAttempt

    /** There was nothing to try. */
    data object NoPassword : UnlockAttempt

    data object WrongPassword : UnlockAttempt

    /** The engine couldn't say whether the password is right. */
    data object CheckFailed : UnlockAttempt

    /** The password is right, and what it protects still can't be listed. */
    data object ContentsUnreadable : UnlockAttempt
}

/** Tries [password] on the archive [open] gives, and lists what's inside if it fits. */
internal suspend fun tryPassword(password: String, open: suspend () -> ArchiveInside): UnlockAttempt {
    if (password.isEmpty()) return UnlockAttempt.NoPassword
    val archive: ArchiveInside
    val fits = try {
        archive = open()
        archive.accepts(password)
    } catch (gone: CancellationException) {
        throw gone
    } catch (_: Exception) {
        return UnlockAttempt.CheckFailed
    }
    if (!fits) return UnlockAttempt.WrongPassword
    return try {
        UnlockAttempt.Opened(ArchivePeek.Open(archive.entries(password), password))
    } catch (gone: CancellationException) {
        throw gone
    } catch (_: Exception) {
        UnlockAttempt.ContentsUnreadable
    }
}
