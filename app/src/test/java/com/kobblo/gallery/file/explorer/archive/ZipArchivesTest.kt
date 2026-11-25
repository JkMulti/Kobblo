package com.kobblo.gallery.file.explorer.archive

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Random

class ZipArchivesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val topBytes = ByteArray(50_000) { (it % 251).toByte() }

    /** Big and incompressible, so unpacking it is still going when the test looks. */
    private val slowBytes by lazy { ByteArray(24 * 1024 * 1024).also { Random(11).nextBytes(it) } }

    /** A ZIP of `docs/` (two files, one with a name outside ASCII, and an empty folder) and `top.bin`. */
    private fun makeZip(password: String? = null): File {
        val source = temp.newFolder()
        File(source, "docs/empty").mkdirs()
        File(source, "docs/readme.txt").writeText("hello")
        File(source, "docs/日本語.txt").writeText("こんにちは")
        val top = File(source, "top.bin").apply { writeBytes(topBytes) }
        val file = File(temp.newFolder(), "made.zip")
        val settings = ZipParameters().apply {
            isEncryptFiles = password != null
            if (password != null) encryptionMethod = EncryptionMethod.AES
        }
        val zip = if (password != null) ZipFile(file, password.toCharArray()) else ZipFile(file)
        zip.addFolder(File(source, "docs"), settings)
        zip.addFile(top, settings)
        return file
    }

    private fun makeSlowZip(): File {
        val big = File(temp.newFolder(), "big.bin").apply { writeBytes(slowBytes) }
        val file = File(temp.newFolder(), "slow.zip")
        ZipFile(file).addFile(big, ZipParameters().apply { compressionMethod = CompressionMethod.STORE })
        return file
    }

    private fun garbage(): File = File(temp.newFolder(), "garbage.zip").apply { writeText("this was never an archive") }

    private fun unpack(archive: File, password: String?, into: File = temp.newFolder()): Pair<ZipArchives.Ending, File> {
        val ending = runBlocking {
            ZipArchives.unpack(ZipArchives.open(archive, password), into, isLow = { false }) { _, _ -> }
        }
        return ending to into
    }

    private fun assertUnpacked(folder: File) {
        assertEquals("hello", File(folder, "docs/readme.txt").readText())
        assertEquals("こんにちは", File(folder, "docs/日本語.txt").readText())
        assertTrue(File(folder, "docs/empty").isDirectory)
        assertArrayEquals(topBytes, File(folder, "top.bin").readBytes())
    }

    @Test
    fun `a listing has every file and folder with its size`() {
        val entries = ZipArchives.list(makeZip(), password = null).associateBy { it.name }
        assertEquals(setOf("docs/", "docs/empty/", "docs/readme.txt", "docs/日本語.txt", "top.bin"), entries.keys)
        assertTrue(entries.getValue("docs/").isDirectory)
        assertTrue(entries.getValue("docs/empty/").isDirectory)
        assertFalse(entries.getValue("top.bin").isDirectory)
        assertEquals(5L, entries.getValue("docs/readme.txt").sizeBytes)
        assertEquals(15L, entries.getValue("docs/日本語.txt").sizeBytes)
        assertEquals(50_000L, entries.getValue("top.bin").sizeBytes)
        assertTrue(entries.values.none { it.isEncrypted })
    }

    @Test
    fun `a locked archive lists without its password and every entry says it's encrypted`() {
        val entries = ZipArchives.list(makeZip(password = "hunter2"), password = null)
        assertEquals(5, entries.size)
        assertTrue(entries.all { it.isEncrypted })
    }

    @Test
    fun `only an archive with something encrypted in it is encrypted`() {
        assertFalse(ZipArchives.isEncrypted(makeZip()))
        assertTrue(ZipArchives.isEncrypted(makeZip(password = "hunter2")))
    }

    @Test
    fun `a file that isn't a zip is not encrypted, and can't be listed`() {
        assertFalse(ZipArchives.isEncrypted(garbage()))
        assertFalse(ZipArchives.isEncrypted(File(temp.root, "not there.zip")))
        assertFalse(ZipArchives.open(garbage(), null).isValidZipFile)
        try {
            ZipArchives.list(garbage(), password = null)
            fail("It should have refused")
        } catch (e: Exception) {
            assertEquals("Invalid ZIP file", e.message)
        }
    }

    @Test
    fun `a zip cut short is not a zip any more`() {
        val cut = makeZip()
        RandomAccessFile(cut, "rw").use { it.setLength(cut.length() / 2) }
        assertFalse(ZipArchives.open(cut, null).isValidZipFile)
        assertFalse(ZipArchives.isEncrypted(cut))
        assertFalse(ZipArchives.opensWith(cut, "anything"))
    }

    @Test
    fun `the right password opens it and a wrong one doesn't`() {
        val locked = makeZip(password = "hunter2")
        assertTrue(ZipArchives.opensWith(locked, "hunter2"))
        assertFalse(ZipArchives.opensWith(locked, "hunter3"))
        assertFalse(ZipArchives.opensWith(locked, ""))
    }

    @Test
    fun `an archive with nothing locked takes any password`() {
        assertTrue(ZipArchives.opensWith(makeZip(), "whatever"))
    }

    @Test
    fun `the unpacked size is what the entries say they hold`() {
        assertEquals(5L + 15L + 50_000L, ZipArchives.unpackedSize(ZipArchives.open(makeZip(), null)))
    }

    @Test
    fun `unpacking brings out folders, empty folders and names outside ascii`() {
        val (ending, folder) = unpack(makeZip(), password = null)
        assertEquals(ZipArchives.Ending.Done, ending)
        assertUnpacked(folder)
    }

    @Test
    fun `unpacking with the right password brings out the same`() {
        val (ending, folder) = unpack(makeZip(password = "hunter2"), password = "hunter2")
        assertEquals(ZipArchives.Ending.Done, ending)
        assertUnpacked(folder)
    }

    @Test
    fun `a wrong password ends as a matter of the password`() {
        val (ending, _) = unpack(makeZip(password = "hunter2"), password = "hunter3")
        assertTrue(ending is ZipArchives.Ending.Broke)
        assertTrue(ZipArchives.isAboutPassword((ending as ZipArchives.Ending.Broke).cause))
    }

    @Test
    fun `no password for a locked archive ends as a matter of the password`() {
        val (ending, _) = unpack(makeZip(password = "hunter2"), password = null)
        assertTrue(ending is ZipArchives.Ending.Broke)
        assertTrue(ZipArchives.isAboutPassword((ending as ZipArchives.Ending.Broke).cause))
    }

    @Test
    fun `damaged contents fail without asking for a password`() {
        val damaged = makeSlowZip()
        // Well inside the one stored file, so its checksum no longer matches.
        RandomAccessFile(damaged, "rw").use { file ->
            file.seek(damaged.length() / 2)
            val was = file.read()
            file.seek(damaged.length() / 2)
            file.write(was xor 0xFF)
        }
        val (ending, _) = unpack(damaged, password = null)
        assertTrue(ending is ZipArchives.Ending.Broke)
        assertFalse(ZipArchives.isAboutPassword((ending as ZipArchives.Ending.Broke).cause))
    }

    @Test
    fun `what counts as being about the password`() {
        assertTrue(ZipArchives.isAboutPassword(Exception("Wrong password!")))
        assertTrue(ZipArchives.isAboutPassword(Exception("empty or null PASSWORD provided")))
        assertTrue(ZipArchives.isAboutPassword(Exception("could not initialise the Decrypter")))
        assertFalse(ZipArchives.isAboutPassword(Exception("Reached end of entry, but crc verification failed")))
        assertFalse(ZipArchives.isAboutPassword(Exception()))
        assertFalse(ZipArchives.isAboutPassword(null))
    }

    @Test
    fun `progress is a percent and the entry being written`() {
        val seen = ArrayList<Pair<Int, String?>>()
        val ending = runBlocking {
            ZipArchives.unpack(ZipArchives.open(makeSlowZip(), null), temp.newFolder(), isLow = { false }) { percent, name ->
                seen += percent to name
            }
        }
        assertEquals(ZipArchives.Ending.Done, ending)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.first in 0..100 })
        assertTrue(seen.mapNotNull { it.second }.all { it.endsWith("big.bin") })
    }

    @Test
    fun `running low on space stops it and says so`() {
        val into = temp.newFolder()
        val zip = ZipArchives.open(makeSlowZip(), null)
        val ending = runBlocking { ZipArchives.unpack(zip, into, isLow = { true }) { _, _ -> } }
        assertEquals(ZipArchives.Ending.OutOfSpace, ending)
        assertEquals(ProgressMonitor.State.READY, zip.progressMonitor.state)
    }

    @Test
    fun `cancelling waits for the writing to stop`() {
        val zip = ZipArchives.open(makeSlowZip(), null)
        val into = temp.newFolder()
        runBlocking {
            val underWay = CompletableDeferred<Unit>()
            val unpacking = launch(Dispatchers.Default) {
                ZipArchives.unpack(zip, into, isLow = { false }) { _, _ -> underWay.complete(Unit) }
                fail("It should have been cancelled")
            }
            underWay.await()
            unpacking.cancelAndJoin()
            assertTrue(unpacking.isCancelled)
        }
        // Nothing is writing into the folder any more by the time the cancel is over.
        assertEquals(ProgressMonitor.State.READY, zip.progressMonitor.state)
    }
}
