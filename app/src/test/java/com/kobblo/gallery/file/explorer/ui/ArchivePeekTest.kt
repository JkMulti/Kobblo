package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.archive.ArchiveEntryInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ArchivePeekTest {
    private val readme = ArchiveEntryInfo("docs/readme.txt", sizeBytes = 120, isDirectory = false, isEncrypted = false)
    private val docs = ArchiveEntryInfo("docs/", sizeBytes = 0, isDirectory = true, isEncrypted = false)
    private val secret = ArchiveEntryInfo("secret.txt", sizeBytes = 9, isDirectory = false, isEncrypted = true)

    /** An archive that behaves as told, and remembers what it was asked. */
    private class Fake(
        private val encrypted: Boolean = false,
        private val password: String? = null,
        private val entries: List<ArchiveEntryInfo> = emptyList(),
        private val failEncryptedCheck: Exception? = null,
        private val failListing: Exception? = null,
        private val failPasswordCheck: Exception? = null
    ) : ArchiveInside {
        val listedWith = ArrayList<String?>()

        override suspend fun isEncrypted(): Boolean {
            failEncryptedCheck?.let { throw it }
            return encrypted
        }

        override suspend fun entries(password: String?): List<ArchiveEntryInfo> {
            listedWith += password
            failListing?.let { throw it }
            return entries
        }

        override suspend fun accepts(password: String): Boolean {
            failPasswordCheck?.let { throw it }
            return password == this.password
        }
    }

    private fun peek(archive: ArchiveInside): ArchivePeek = runBlocking { peekInto { archive } }

    private fun attempt(password: String, archive: ArchiveInside): UnlockAttempt = runBlocking { tryPassword(password) { archive } }

    // ── Looking without a password ────────────────────────────────────────────

    @Test
    fun `an ordinary archive opens, and extracting it needs no password`() {
        val archive = Fake(entries = listOf(docs, readme))
        assertEquals(ArchivePeek.Open(listOf(docs, readme), password = null), peek(archive))
        assertEquals(listOf<String?>(null), archive.listedWith)
    }

    @Test
    fun `an archive that says it's protected is locked without being listed`() {
        val archive = Fake(encrypted = true, entries = listOf(readme))
        assertEquals(ArchivePeek.Locked, peek(archive))
        assertEquals(emptyList<String?>(), archive.listedWith)
    }

    @Test
    fun `one protected file among the contents locks the whole look`() {
        assertEquals(ArchivePeek.Locked, peek(Fake(entries = listOf(readme, secret))))
    }

    @Test
    fun `a failure that mentions a password or encryption means locked`() {
        assertEquals(ArchivePeek.Locked, peek(Fake(failListing = IOException("Wrong Password for entry"))))
        assertEquals(ArchivePeek.Locked, peek(Fake(failEncryptedCheck = IOException("archive is ENCRYPTED"))))
    }

    @Test
    fun `any other failure means unreadable`() {
        assertEquals(ArchivePeek.Unreadable, peek(Fake(failListing = IOException("unexpected end of archive"))))
        assertEquals(ArchivePeek.Unreadable, peek(Fake(failEncryptedCheck = IllegalStateException())))
    }

    @Test
    fun `an archive that can't even be opened is unreadable`() {
        val result = runBlocking { peekInto { throw IOException("not an archive") } }
        assertEquals(ArchivePeek.Unreadable, result)
    }

    @Test
    fun `an empty archive is open, with nothing in it`() {
        val open = peek(Fake()) as ArchivePeek.Open
        assertEquals(0, open.fileCount)
        assertEquals(emptyList<ArchiveEntryInfo>(), open.entries)
    }

    @Test
    fun `folders are listed and not counted`() {
        val open = peek(Fake(entries = listOf(docs, readme, docs.copy(name = "empty/")))) as ArchivePeek.Open
        assertEquals(3, open.entries.size)
        assertEquals(1, open.fileCount)
    }

    @Test
    fun `entries keep the order the archive gives them in`() {
        val later = readme.copy(name = "a-first-by-name.txt")
        val open = peek(Fake(entries = listOf(readme, later))) as ArchivePeek.Open
        assertEquals(listOf(readme, later), open.entries)
    }

    @Test
    fun `leaving in the middle of a look isn't mistaken for a damaged archive`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { peekInto { throw CancellationException("gone") } }
        }
    }

    // ── Trying a password ─────────────────────────────────────────────────────

    @Test
    fun `the right password opens the archive and is kept for extracting`() {
        val archive = Fake(encrypted = true, password = "sesame", entries = listOf(secret))
        val opened = attempt("sesame", archive) as UnlockAttempt.Opened
        assertEquals(ArchivePeek.Open(listOf(secret), password = "sesame"), opened.contents)
        assertEquals(listOf<String?>("sesame"), archive.listedWith)
    }

    @Test
    fun `the wrong password is called wrong, and nothing is listed`() {
        val archive = Fake(encrypted = true, password = "sesame", entries = listOf(secret))
        assertEquals(UnlockAttempt.WrongPassword, attempt("barley", archive))
        assertEquals(emptyList<String?>(), archive.listedWith)
    }

    @Test
    fun `no password is not a wrong password`() {
        var opened = false
        val result = runBlocking {
            tryPassword("") {
                opened = true
                Fake(encrypted = true, password = "")
            }
        }
        assertEquals(UnlockAttempt.NoPassword, result)
        assertEquals(false, opened)
    }

    @Test
    fun `a check that fails is not a wrong password either`() {
        assertEquals(UnlockAttempt.CheckFailed, attempt("sesame", Fake(failPasswordCheck = IOException("read error"))))
        val unopenable = runBlocking { tryPassword("sesame") { throw IOException("not an archive") } }
        assertEquals(UnlockAttempt.CheckFailed, unopenable)
    }

    @Test
    fun `a right password over contents that can't be listed says so`() {
        val archive = Fake(encrypted = true, password = "sesame", failListing = IOException("damaged"))
        assertEquals(UnlockAttempt.ContentsUnreadable, attempt("sesame", archive))
    }

    @Test
    fun `an archive opened without a password has none to hand on`() {
        val open = peek(Fake(entries = listOf(readme))) as ArchivePeek.Open
        assertNull(open.password)
    }
}
