package com.kobblo.gallery.file.explorer.archive

import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

class ArchiveFormatsTest {
    @get:Rule val temp = TemporaryFolder()

    private val folderName = "Pictures"
    private val big = ByteArray(300_000).also { Random(7).nextBytes(it) }
    private val secret = "outside the selection".toByteArray()

    /** Name inside the archive to bytes, with null for a folder. */
    private val expected: Map<String, ByteArray?> = mapOf(
        "$folderName/" to null,
        "$folderName/日本語の写真.txt" to "こんにちは".toByteArray(),
        "$folderName/empty file.dat" to ByteArray(0),
        "$folderName/nested é/" to null,
        "$folderName/nested é/big.bin" to big,
        "$folderName/空のフォルダ/" to null,
        "lone 🎵.txt" to "alone".toByteArray()
    )

    private fun makeSources(): Pair<File, List<File>> {
        val root = temp.newFolder("work")
        val folder = File(root, folderName).apply { mkdirs() }
        File(folder, "日本語の写真.txt").writeBytes("こんにちは".toByteArray())
        File(folder, "empty file.dat").writeBytes(ByteArray(0))
        File(folder, "nested é").mkdirs()
        File(folder, "nested é/big.bin").writeBytes(big)
        File(folder, "空のフォルダ").mkdirs()
        val lone = File(root, "lone 🎵.txt").apply { writeBytes("alone".toByteArray()) }
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeBytes(secret)
        Files.createSymbolicLink(File(folder, "link to folder").toPath(), outside.toPath())
        Files.createSymbolicLink(File(folder, "link to file").toPath(), File(outside, "secret.txt").toPath())
        return root to listOf(folder, lone)
    }

    private fun create(root: File, sources: List<File>, format: ArchiveFormat, password: String? = null, stop: () -> Boolean = { false }) = runBlocking {
        ArchiveCreateManager.createArchive(
            sourceFiles = sources,
            destinationFile = File(root, format.nameFor("out")),
            config = ArchiveCreateConfig(format.nameFor("out"), password, format),
            onProgress = { _, _ -> },
            isCancelled = stop
        )
    }

    private fun assertContents(actual: Map<String, ByteArray?>) {
        assertEquals(expected.keys, actual.keys)
        for ((name, bytes) in expected) {
            if (bytes == null) assertEquals(name, null, actual[name]) else assertArrayEquals(name, bytes, actual[name])
        }
    }

    private fun readSevenZ(file: File): Map<String, ByteArray?> {
        val result = LinkedHashMap<String, ByteArray?>()
        SevenZFile.builder().setFile(file).get().use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                if (entry.isDirectory) {
                    result["${entry.name}/"] = null
                } else {
                    val data = ByteArray(entry.size.toInt())
                    var at = 0
                    while (at < data.size) {
                        val read = archive.read(data, at, data.size - at)
                        if (read < 0) break
                        at += read
                    }
                    result[entry.name] = data
                }
            }
        }
        return result
    }

    private fun readTarGz(file: File): Map<String, ByteArray?> {
        val result = LinkedHashMap<String, ByteArray?>()
        TarArchiveInputStream(GZIPInputStream(file.inputStream()), "UTF-8").use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                result[entry.name] = if (entry.isDirectory) null else tar.readBytes()
            }
        }
        return result
    }

    private fun assertNothingLeftBehind(root: File, sources: List<File>) {
        val left = root.listFiles()!!.map { it.name }.toSet()
        assertEquals(sources.map { it.name }.toSet(), left)
    }

    @Test
    fun `7z holds folders, empty folders and unicode names, and no link was followed`() {
        val (root, sources) = makeSources()
        val made = create(root, sources, ArchiveFormat.SEVEN_Z)
        assertEquals("out.7z", made.name)
        assertContents(readSevenZ(made))
    }

    @Test
    fun `tar gz holds folders, empty folders and unicode names, and no link was followed`() {
        val (root, sources) = makeSources()
        val made = create(root, sources, ArchiveFormat.TAR_GZ)
        assertEquals("out.tar.gz", made.name)
        assertContents(readTarGz(made))
    }

    @Test
    fun `zip holds the same and reads the secret from nowhere`() {
        val (root, sources) = makeSources()
        val made = create(root, sources, ArchiveFormat.ZIP)
        val zip = ZipFile(made)
        val files = zip.fileHeaders.filter { !it.isDirectory }
        val names = zip.fileHeaders.map { it.fileName }.toSet()
        assertTrue(names.contains("$folderName/空のフォルダ/"))
        assertTrue(names.contains("$folderName/日本語の写真.txt"))
        for (header in files) {
            val bytes = zip.getInputStream(header).use { it.readBytes() }
            assertFalse(header.fileName, bytes.contentEquals(secret))
            expected[header.fileName]?.let { assertArrayEquals(header.fileName, it, bytes) }
        }
    }

    @Test
    fun `a zip with a password still works`() {
        val (root, sources) = makeSources()
        val made = create(root, sources, ArchiveFormat.ZIP, password = "hunter2")
        val zip = ZipFile(made, "hunter2".toCharArray())
        assertTrue(zip.isEncrypted)
        val header = zip.fileHeaders.first { it.fileName.endsWith("big.bin") }
        assertArrayEquals(big, zip.getInputStream(header).use { it.readBytes() })
    }

    @Test
    fun `a taken name gets a number and the first archive is left alone`() {
        for (format in listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val root = temp.newFolder()
            val source = File(root, "a.txt").apply { writeText("a") }
            val taken = File(root, format.nameFor("out")).apply { writeText("keep me") }
            val made = create(root, listOf(source), format)
            assertEquals(format.nameFor("out (1)"), made.name)
            assertEquals("keep me", taken.readText())
        }
    }

    @Test
    fun `stopping partway leaves nothing behind`() {
        for (format in listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val root = temp.newFolder()
            val folder = File(root, "photos").apply { mkdirs() }
            File(folder, "big.bin").writeBytes(big)
            File(folder, "other.bin").writeBytes(big)
            var asked = 0
            try {
                create(root, listOf(folder), format, stop = { ++asked > 8 })
                fail("It should have stopped")
            } catch (_: CancellationException) {
            }
            assertNothingLeftBehind(root, listOf(folder))
        }
    }

    @Test
    fun `a file that can't be read leaves nothing behind`() {
        for (format in listOf(ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val root = temp.newFolder()
            val folder = File(root, "photos").apply { mkdirs() }
            File(folder, "fine.bin").writeBytes(big)
            val locked = File(folder, "locked.bin").apply { writeBytes(big) }
            assertTrue(locked.setReadable(false))
            try {
                create(root, listOf(folder), format)
                fail("It should have failed")
            } catch (_: java.io.IOException) {
            } finally {
                locked.setReadable(true)
            }
            assertNothingLeftBehind(root, listOf(folder))
        }
    }

    @Test
    fun `the same name from two folders keeps both`() {
        val root = temp.newFolder()
        val one = File(root, "one").apply { mkdirs() }
        val two = File(root, "two").apply { mkdirs() }
        val first = File(one, "photo.jpg").apply { writeText("first") }
        val second = File(two, "photo.jpg").apply { writeText("second") }
        val made = runBlocking {
            ArchiveCreateManager.createArchive(
                sourceFiles = listOf(first, second),
                destinationFile = File(root, "both.tar.gz"),
                config = ArchiveCreateConfig("both.tar.gz", null, ArchiveFormat.TAR_GZ),
                onProgress = { _, _ -> }
            )
        }
        val read = readTarGz(made)
        assertEquals("first", read["photo.jpg"]?.decodeToString())
        assertEquals("second", read["photo (1).jpg"]?.decodeToString())
    }

    @Test
    fun `a selection of only links has nothing to archive`() {
        val root = temp.newFolder()
        val target = File(temp.newFolder(), "real.txt").apply { writeText("real") }
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        try {
            create(root, listOf(link), ArchiveFormat.SEVEN_Z)
            fail("There's nothing to put in it")
        } catch (_: java.io.IOException) {
        }
        assertNothingLeftBehind(root, listOf(link))
    }

    @Test
    fun `a name's ending follows the format`() {
        assertEquals("a.7z", ArchiveFormat.SEVEN_Z.nameFor("a.zip"))
        assertEquals("a.tar.gz", ArchiveFormat.TAR_GZ.nameFor("a.7Z"))
        assertEquals("a.zip", ArchiveFormat.ZIP.nameFor("a.TAR.GZ"))
        assertEquals("a.zip", ArchiveFormat.ZIP.nameFor("a"))
        assertEquals("my.photos.7z", ArchiveFormat.SEVEN_Z.nameFor("my.photos"))
        assertEquals("a.tar.gz", ArchiveFormat.TAR_GZ.nameFor("a.tgz"))
    }
}
