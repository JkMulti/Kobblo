package com.kobblo.gallery.file.explorer.archive

import be.stef.rar.ExtractionError
import be.stef.rar.ExtractionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The rules around the RAR library, with a stand-in for the library itself: it writes what it's told into
 * the folder it's given and reports what it's told.
 */
class RarArchivesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val archive = File("/somewhere/photos.rar")
    private val passwordRejected = 2

    private class StandIn(
        private val headersSayEncrypted: () -> Boolean = { false },
        private val unpack: (into: File, password: String?) -> ExtractionResult
    ) : RarLibrary {
        val passwordsTried = ArrayList<String?>()

        override fun isEncrypted(archivePath: String): Boolean = headersSayEncrypted()

        override fun extract(archivePath: String, intoPath: String, password: String?): ExtractionResult {
            passwordsTried += password
            return unpack(File(intoPath), password)
        }
    }

    private fun result(
        total: Int = 0,
        ok: Int = 0,
        failed: List<String> = emptyList(),
        errorCount: Int = failed.size,
        passwordStatus: Int = 0,
        errors: List<ExtractionError> = emptyList()
    ) = ExtractionResult().also {
        it.totalFiles = total
        it.successCount = ok
        it.errorCount = errorCount
        it.passwordStatus = passwordStatus
        it.failedFiles.addAll(failed)
        it.errors.addAll(errors)
    }

    private fun scratch() = File(temp.root, "scratch-${System.nanoTime()}")

    private fun File.put(path: String, text: String) = resolve(path).also { it.parentFile?.mkdirs(); it.writeText(text) }

    // What the library's result says about a password

    @Test
    fun `the library's own verdict on the password is believed`() {
        assertTrue(RarArchives.refusedPassword(result(passwordStatus = passwordRejected)))
        assertFalse(RarArchives.refusedPassword(result(passwordStatus = 0)))
        assertFalse(RarArchives.refusedPassword(result(passwordStatus = 1)))
    }

    @Test
    fun `one error that speaks of a password is enough`() {
        val encryptedBlock = ExtractionError("a.jpg", 10L, 0, false, false, true, "could not read", null)
        assertTrue(RarArchives.refusedPassword(result(errors = listOf(encryptedBlock))))
        assertTrue(RarArchives.refusedPassword(result(errors = listOf(ExtractionError("a.jpg", "Wrong Password?")))))
        assertTrue(RarArchives.refusedPassword(result(errors = listOf(ExtractionError("a.jpg", "failed", IOException("bad password"))))))
        assertTrue(
            RarArchives.refusedPassword(
                result(errors = listOf(ExtractionError("a.jpg", "CRC mismatch"), ExtractionError("b.jpg", "password needed")))
            )
        )
    }

    @Test
    fun `errors about anything else aren't about the password`() {
        assertFalse(RarArchives.refusedPassword(result(errors = listOf(ExtractionError("a.jpg", "CRC mismatch")))))
        assertFalse(RarArchives.refusedPassword(result(errors = listOf(ExtractionError("a.jpg", null, IOException())))))
        assertFalse(RarArchives.refusedPassword(result(errors = listOf(ExtractionError("a.jpg", "failed", IOException("disk full"))))))
    }

    @Test
    fun `what the library throws is about the password if it says password or encrypted`() {
        assertTrue(RarArchives.isAboutPassword(IOException("Password required")))
        assertTrue(RarArchives.isAboutPassword(IOException("Encrypted headers can't be read")))
        assertFalse(RarArchives.isAboutPassword(IOException("Unknown or unsupported archive format")))
        assertFalse(RarArchives.isAboutPassword(IOException()))
    }

    // How a finished run is judged

    @Test
    fun `a refused password comes before any other complaint`() {
        val ending = RarArchives.endingOf(result(total = 3, failed = listOf("a", "b", "c"), passwordStatus = passwordRejected)) { true }
        assertSame(RarArchives.Ending.NeedsPassword, ending)
    }

    @Test
    fun `some good files and some bad is a failure naming the first few bad ones`() {
        val bad = listOf("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg", "7.jpg")
        val ending = RarArchives.endingOf(result(total = 20, ok = 13, failed = bad)) { false }
        assertEquals(bad.take(5), (ending as RarArchives.Ending.Incomplete).failedNames)
    }

    @Test
    fun `errors with no file named are still a failure`() {
        val ending = RarArchives.endingOf(result(total = 2, ok = 1, errorCount = 1)) { false }
        assertEquals(emptyList<String>(), (ending as RarArchives.Ending.Incomplete).failedNames)
    }

    @Test
    fun `no files reported and none in the folder is an archive that can't be read`() {
        assertSame(RarArchives.Ending.Unreadable, RarArchives.endingOf(result()) { true })
    }

    @Test
    fun `no files reported but some in the folder is taken as done`() {
        val ending = RarArchives.endingOf(result()) { false } as RarArchives.Ending.Done
        assertEquals(0, ending.unpacked)
        assertEquals(0, ending.total)
    }

    @Test
    fun `a clean run is done, counted against the total when there is one`() {
        val counted = RarArchives.endingOf(result(total = 8, ok = 8)) { fail("The folder shouldn't be looked at"); true }
        assertEquals(8, (counted as RarArchives.Ending.Done).unpacked)
        assertEquals(8, counted.total)
        val uncounted = RarArchives.endingOf(result(total = 0, ok = 4)) { fail("The folder shouldn't be looked at"); true }
        assertEquals(4, (uncounted as RarArchives.Ending.Done).unpacked)
        assertEquals(4, uncounted.total)
    }

    // Listing what was unpacked

    @Test
    fun `what was unpacked is listed by its path, a folder ahead of what's in it`() {
        val folder = temp.newFolder()
        folder.put("top.txt", "12345")
        folder.put("Trip/day one/a.jpg", "abc")
        File(folder, "Trip/empty").mkdirs()

        val entries = RarArchives.entriesIn(folder, encrypted = false)
        val byName = entries.associateBy { it.name }
        assertEquals(setOf("top.txt", "Trip", "Trip/day one", "Trip/day one/a.jpg", "Trip/empty"), byName.keys)
        assertEquals(5L, byName.getValue("top.txt").sizeBytes)
        assertEquals(3L, byName.getValue("Trip/day one/a.jpg").sizeBytes)
        assertEquals(0L, byName.getValue("Trip").sizeBytes)
        assertTrue(byName.getValue("Trip").isDirectory)
        assertTrue(byName.getValue("Trip/empty").isDirectory)
        assertFalse(byName.getValue("top.txt").isDirectory)
        assertTrue(entries.none { it.isEncrypted })

        val names = entries.map { it.name }
        assertTrue(names.indexOf("Trip") < names.indexOf("Trip/day one"))
        assertTrue(names.indexOf("Trip/day one") < names.indexOf("Trip/day one/a.jpg"))
    }

    @Test
    fun `an empty folder lists as nothing, and the encrypted mark goes on everything`() {
        assertEquals(emptyList<ArchiveEntryInfo>(), RarArchives.entriesIn(temp.newFolder(), encrypted = true))
        val folder = temp.newFolder().also { it.put("a/b.txt", "x") }
        assertTrue(RarArchives.entriesIn(folder, encrypted = true).all { it.isEncrypted })
    }

    // Whether an archive wants a password

    @Test
    fun `headers that say encrypted settle it without unpacking`() {
        val library = StandIn(headersSayEncrypted = { true }) { _, _ -> fail("It shouldn't unpack"); result() }
        val scratch = scratch()
        assertTrue(RarArchives(library).isEncrypted(archive, scratch))
        assertFalse(scratch.exists())
    }

    @Test
    fun `an archive that unpacks with no password isn't encrypted`() {
        val library = StandIn { into, _ -> into.put("a.txt", "a"); result(total = 1, ok = 1) }
        val scratch = scratch()
        assertFalse(RarArchives(library).isEncrypted(archive, scratch))
        assertEquals(listOf<String?>(null), library.passwordsTried)
        assertFalse("The scratch folder should be gone", scratch.exists())
    }

    @Test
    fun `being refused, or getting nothing out at all, means it wants a password`() {
        val refused = StandIn { _, _ -> result(passwordStatus = passwordRejected) }
        assertTrue(RarArchives(refused).isEncrypted(archive, scratch()))
        val nothingOut = StandIn { _, _ -> result(total = 2, ok = 0, failed = listOf("a", "b")) }
        assertTrue(RarArchives(nothingOut).isEncrypted(archive, scratch()))
        val someOut = StandIn { _, _ -> result(total = 2, ok = 1, failed = listOf("b")) }
        assertFalse(RarArchives(someOut).isEncrypted(archive, scratch()))
    }

    @Test
    fun `a library that throws is asked by its words`() {
        val headersThrow = StandIn(headersSayEncrypted = { throw IOException("password protected headers") }) { _, _ -> result() }
        assertTrue(RarArchives(headersThrow).isEncrypted(archive, scratch()))
        val scratch = scratch()
        val unpackThrows = StandIn { into, _ -> into.put("half.bin", "x"); throw IOException("Encrypted data") }
        assertTrue(RarArchives(unpackThrows).isEncrypted(archive, scratch))
        assertFalse("The scratch folder should be gone", scratch.exists())
        val damaged = StandIn { _, _ -> throw IOException("Unknown or unsupported archive format") }
        assertFalse(RarArchives(damaged).isEncrypted(archive, scratch()))
    }

    // Listing an archive

    @Test
    fun `a listing is what unpacking leaves, and the scratch folder goes afterwards`() {
        val library = StandIn { into, _ ->
            into.put("Trip/a.jpg", "abc")
            result(total = 1, ok = 1)
        }
        val scratch = scratch()
        val listing = RarArchives(library).list(archive, password = null, scratch = scratch) as RarArchives.Listing.Entries
        assertEquals(listOf("Trip", "Trip/a.jpg"), listing.entries.map { it.name })
        assertTrue(listing.entries.none { it.isEncrypted })
        assertFalse(scratch.exists())
    }

    @Test
    fun `with no password, an archive that needs one lists as locked`() {
        val refused = StandIn { _, _ -> result(passwordStatus = passwordRejected) }
        assertSame(RarArchives.Listing.Locked, RarArchives(refused).list(archive, null, scratch()))
        val silent = StandIn(headersSayEncrypted = { true }) { _, _ -> result(total = 3, ok = 0) }
        assertSame(RarArchives.Listing.Locked, RarArchives(silent).list(archive, null, scratch()))
    }

    @Test
    fun `with the password, an encrypted archive lists and every entry carries the mark`() {
        val library = StandIn(headersSayEncrypted = { true }) { into, password ->
            if (password == "hunter2") into.put("secret.txt", "s")
            result(total = 1, ok = 1, passwordStatus = 1)
        }
        val listing = RarArchives(library).list(archive, "hunter2", scratch()) as RarArchives.Listing.Entries
        assertEquals(listOf("secret.txt"), listing.entries.map { it.name })
        assertTrue(listing.entries.all { it.isEncrypted })
        assertEquals(listOf<String?>("hunter2"), library.passwordsTried)
    }

    @Test
    fun `with a wrong password the listing is whatever came out, and not locked`() {
        val library = StandIn(headersSayEncrypted = { true }) { _, _ -> result(total = 1, ok = 0, passwordStatus = passwordRejected) }
        val listing = RarArchives(library).list(archive, "wrong", scratch()) as RarArchives.Listing.Entries
        assertEquals(emptyList<ArchiveEntryInfo>(), listing.entries)
    }

    @Test
    fun `headers that can't be read count as not encrypted when listing`() {
        val library = StandIn(headersSayEncrypted = { throw IOException("short read") }) { into, _ ->
            into.put("a.txt", "a")
            result(total = 1, ok = 1)
        }
        val listing = RarArchives(library).list(archive, null, scratch()) as RarArchives.Listing.Entries
        assertEquals(listOf("a.txt"), listing.entries.map { it.name })
        assertFalse(listing.entries.single().isEncrypted)
    }

    @Test
    fun `a library that throws while listing is passed on, with nothing left behind`() {
        val scratch = scratch()
        val library = StandIn { into, _ -> into.put("half.bin", "x"); throw IOException("Unknown or unsupported archive format") }
        try {
            RarArchives(library).list(archive, null, scratch)
            fail("It should have thrown")
        } catch (e: IOException) {
            assertEquals("Unknown or unsupported archive format", e.message)
        }
        assertFalse(scratch.exists())
    }

    // Trying a password

    @Test
    fun `a password is right when everything unpacks with it`() {
        val library = StandIn { into, _ -> into.put("a.txt", "a"); result(total = 1, ok = 1, passwordStatus = 1) }
        val scratch = scratch()
        assertTrue(RarArchives(library).opensWith(archive, "hunter2", scratch))
        assertEquals(listOf<String?>("hunter2"), library.passwordsTried)
        assertFalse(scratch.exists())
    }

    @Test
    fun `a password is wrong when it's refused, when anything fails, or when the library throws`() {
        val refused = StandIn { _, _ -> result(passwordStatus = passwordRejected) }
        assertFalse(RarArchives(refused).opensWith(archive, "x", scratch()))
        val oneFailed = StandIn { _, _ -> result(total = 5, ok = 4, failed = listOf("e.jpg")) }
        assertFalse(RarArchives(oneFailed).opensWith(archive, "x", scratch()))
        val scratch = scratch()
        val throwing = StandIn { into, _ -> into.put("half.bin", "x"); throw IllegalStateException("broken") }
        assertFalse(RarArchives(throwing).opensWith(archive, "x", scratch))
        assertFalse(scratch.exists())
    }

    // Unpacking for real

    @Test
    fun `unpacking leaves the files where they were asked for`() {
        val into = temp.newFolder()
        val library = StandIn { folder, _ ->
            folder.put("Trip/a.jpg", "abc")
            result(total = 1, ok = 1)
        }
        val ending = RarArchives(library).unpack(archive, into, password = null) as RarArchives.Ending.Done
        assertEquals(1, ending.unpacked)
        assertEquals("abc", File(into, "Trip/a.jpg").readText())
    }

    @Test
    fun `an archive that reports nothing and writes nothing can't be read`() {
        val into = temp.newFolder()
        val library = StandIn { _, _ -> result() }
        assertSame(RarArchives.Ending.Unreadable, RarArchives(library).unpack(archive, into, null))
        // The folder was asked for by someone else, so it stays.
        assertTrue(into.isDirectory)
    }

    @Test
    fun `an archive that reports nothing but writes something is done`() {
        val library = StandIn { folder, _ -> folder.put("a.txt", "a"); result() }
        assertTrue(RarArchives(library).unpack(archive, temp.newFolder(), null) is RarArchives.Ending.Done)
    }
}
