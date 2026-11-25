package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Random

class VaultFileMovesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var vault: File
    private lateinit var storage: File
    private lateinit var downloads: File
    private lateinit var store: VaultStore
    private val vaultKey = ByteArray(32) { (it * 5 + 1).toByte() }

    @Before
    fun createVaultAndStorage() {
        vault = folder.newFolder("vault_files")
        storage = folder.newFolder("storage")
        downloads = folder.newFolder("Download")
        store = VaultStore(vault, vaultKey)
    }

    private fun bytes(size: Int, seed: Long) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun outside(name: String, contents: ByteArray) = File(storage, name).apply { writeBytes(contents) }

    /** Everything sealed in the vault, as name to contents. */
    private fun sealed(): Map<String, ByteArray> = VaultStore.encryptedFiles(vault).associate { file ->
        checkNotNull(store.info(file)).name to store.open(file).use { it.readBytes() }
    }

    private fun sealedFile(name: String): File =
        VaultStore.encryptedFiles(vault).single { store.info(it)?.name == name }

    private fun leftoverWrites(): List<File> =
        VaultStore.encryptedDirectory(vault).listFiles { file -> file.name.endsWith(".tmp") }?.toList().orEmpty()

    // ── Names ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun safeName_keepsOnlyWhatCanBeAFileName() {
        assertEquals("photo.jpg", VaultFileNames.safe("photo.jpg"))
        assertEquals("photo.jpg", VaultFileNames.safe("/storage/emulated/0/DCIM/photo.jpg"))
        assertEquals("passwd", VaultFileNames.safe("../../etc/passwd"))
        assertEquals("file", VaultFileNames.safe(""))
        assertEquals("file", VaultFileNames.safe("   "))
        assertEquals("file", VaultFileNames.safe("."))
        assertEquals("file", VaultFileNames.safe(".."))
        assertEquals("file", VaultFileNames.safe("folder/"))
        assertEquals("file", VaultFileNames.safe("folder/.."))
    }

    @Test
    fun freeName_isTheNameItselfWhenNothingIsThere() {
        assertEquals(File(storage, "photo.jpg"), VaultFileNames.free(storage, "photo.jpg"))
    }

    @Test
    fun freeName_countsUpBeforeTheExtensionUntilOneIsFree() {
        outside("photo.jpg", ByteArray(1))
        assertEquals(File(storage, "photo_1.jpg"), VaultFileNames.free(storage, "photo.jpg"))

        outside("photo_1.jpg", ByteArray(1))
        outside("photo_2.jpg", ByteArray(1))
        assertEquals(File(storage, "photo_3.jpg"), VaultFileNames.free(storage, "photo.jpg"))
    }

    @Test
    fun freeName_handlesNamesWithNoExtensionSeveralOrOnlyOne() {
        outside("README", ByteArray(1))
        outside("backup.tar.gz", ByteArray(1))
        outside(".profile", ByteArray(1))

        assertEquals("README_1", VaultFileNames.free(storage, "README").name)
        assertEquals("backup.tar_1.gz", VaultFileNames.free(storage, "backup.tar.gz").name)
        assertEquals("_1.profile", VaultFileNames.free(storage, ".profile").name)
    }

    // ── Going in ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun movingIn_sealsEachFileAndThenRemovesItsOriginal() {
        val photo = bytes(3_000, seed = 1)
        val video = bytes(700_000, seed = 2)
        val first = outside("photo.jpg", photo)
        val second = outside("video.mp4", video)

        val moved = VaultFileMoves.moveIn(store, listOf(first.path, second.path)) { false }

        assertEquals(2, moved.sealed)
        assertEquals(listOf(first.absolutePath, second.absolutePath), moved.removedPaths)
        assertFalse(first.exists())
        assertFalse(second.exists())
        val inside = sealed()
        assertEquals(setOf("photo.jpg", "video.mp4"), inside.keys)
        assertArrayEquals(photo, inside["photo.jpg"])
        assertArrayEquals(video, inside["video.mp4"])
        assertEquals(emptyList<File>(), VaultStore.plaintextFiles(vault))
    }

    @Test
    fun movingIn_remembersWhereEachFileCameFrom() {
        val source = outside("letter.pdf", bytes(500, seed = 3))

        VaultFileMoves.moveIn(store, listOf(source.path)) { false }

        assertEquals(source.absolutePath, store.info(sealedFile("letter.pdf"))?.originalPath)
    }

    @Test
    fun movingIn_passesOverWhatIsNotAFile() {
        val real = outside("real.txt", bytes(100, seed = 4))
        val aFolder = File(storage, "Pictures").apply { mkdirs() }
        val missing = File(storage, "gone.txt")

        val moved = VaultFileMoves.moveIn(store, listOf(missing.path, aFolder.path, real.path)) { false }

        assertEquals(1, moved.sealed)
        assertEquals(listOf(real.absolutePath), moved.removedPaths)
        assertTrue("A folder isn't taken, or touched", aFolder.isDirectory)
        assertEquals(setOf("real.txt"), sealed().keys)
    }

    @Test
    fun movingIn_keepsBothWhenTheNameIsAlreadyInTheVault() {
        val earlier = bytes(200, seed = 5)
        val later = bytes(300, seed = 6)
        store.add("scan.png", ByteArrayInputStream(earlier))
        val source = outside("scan.png", later)

        val moved = VaultFileMoves.moveIn(store, listOf(source.path)) { false }

        assertEquals(1, moved.sealed)
        val inside = sealed()
        assertArrayEquals(earlier, inside["scan.png"])
        assertArrayEquals(later, inside["scan_1.png"])
    }

    @Test
    fun movingIn_askedToStopBeforeItStartsTouchesNothing() {
        val source = outside("kept.txt", bytes(100, seed = 7))

        val moved = VaultFileMoves.moveIn(store, listOf(source.path)) { true }

        assertEquals(0, moved.sealed)
        assertTrue(source.exists())
        assertEquals(emptyMap<String, ByteArray>(), sealed())
    }

    @Test
    fun movingIn_stoppedBetweenFilesLeavesTheRestWhereTheyWere() {
        val first = outside("first.txt", bytes(100, seed = 8))
        val untouched = bytes(100, seed = 9)
        val second = outside("second.txt", untouched)

        // True from the moment the first file has gone in and its original has been removed.
        val moved = VaultFileMoves.moveIn(store, listOf(first.path, second.path)) { !first.exists() }

        assertEquals(1, moved.sealed)
        assertEquals(listOf(first.absolutePath), moved.removedPaths)
        assertArrayEquals(untouched, second.readBytes())
        assertEquals(setOf("first.txt"), sealed().keys)
    }

    @Test
    fun movingIn_stoppedPartwayThroughAFileLeavesItWholeOutsideAndNothingInside() {
        // Large enough to be read in several pieces, so the stop lands in the middle of it.
        val contents = bytes(900_000, seed = 10)
        val source = outside("large.bin", contents)
        var asked = 0

        val moved = VaultFileMoves.moveIn(store, listOf(source.path)) { ++asked > 2 }

        assertEquals(0, moved.sealed)
        assertEquals(emptyList<String>(), moved.removedPaths)
        assertArrayEquals("The original must survive a stop", contents, source.readBytes())
        assertEquals(emptyMap<String, ByteArray>(), sealed())
        assertEquals(emptyList<File>(), leftoverWrites())
    }

    @Test
    fun movingIn_countsAFileItSealedButCouldNotRemove() {
        val contents = bytes(100, seed = 11)
        val lockedFolder = File(storage, "read-only").apply { mkdirs() }
        val source = File(lockedFolder, "stuck.txt").apply { writeBytes(contents) }
        assumeTrue("Needs a filesystem that can forbid deleting", lockedFolder.setWritable(false))
        try {
            assumeTrue("Running as a user the permission doesn't bind", !File(lockedFolder, "probe").runCatching { createNewFile() }.getOrDefault(false))

            val moved = VaultFileMoves.moveIn(store, listOf(source.path)) { false }

            assertEquals("It's safe in the vault, so it counts", 1, moved.sealed)
            assertEquals("It never left, so nothing has to forget it", emptyList<String>(), moved.removedPaths)
            assertArrayEquals(contents, source.readBytes())
            assertArrayEquals(contents, sealed()["stuck.txt"])
        } finally {
            lockedFolder.setWritable(true)
        }
    }

    @Test
    fun copyingIn_sealsACopyUnderTheGivenName() {
        val contents = bytes(2_000, seed = 12)

        val added = VaultFileMoves.copyIn(store, "from another app.pdf", { false }) { ByteArrayInputStream(contents) }

        assertTrue(added)
        assertArrayEquals(contents, sealed()["from another app.pdf"])
        assertNull("There's nowhere to restore a handed-over document to", store.info(sealedFile("from another app.pdf"))?.originalPath)
    }

    @Test
    fun copyingIn_addsNothingWhenThereIsNothingToRead() {
        assertFalse(VaultFileMoves.copyIn(store, "empty-handed.txt", { false }) { null })
        assertFalse(VaultFileMoves.copyIn(store, "refused.txt", { false }) { throw SecurityException("No access") })
        assertFalse(VaultFileMoves.copyIn(store, "stopped.txt", { true }) { ByteArrayInputStream(bytes(100, seed = 13)) })

        assertEquals(emptyMap<String, ByteArray>(), sealed())
        assertEquals(emptyList<File>(), leftoverWrites())
    }

    // ── Coming out ───────────────────────────────────────────────────────────────────────────────

    private val noFallback: () -> File = { fail("The original folder was usable"); downloads }

    @Test
    fun landingPlace_isWhereTheFileCameFrom() {
        val home = File(storage, "DCIM/photo.jpg")
        home.parentFile!!.mkdirs()

        assertEquals(home, VaultFileMoves.landingPlace(home.path, "photo.jpg", noFallback))
    }

    @Test
    fun landingPlace_makesTheOriginalFolderAgainIfItHasGone() {
        val home = File(storage, "Deleted Album/2024/photo.jpg")

        val target = VaultFileMoves.landingPlace(home.path, "photo.jpg", noFallback)

        assertEquals(home, target)
        assertTrue(home.parentFile!!.isDirectory)
    }

    @Test
    fun landingPlace_neverLandsOnAFileThatIsThereNow() {
        val home = outside("photo.jpg", bytes(10, seed = 14))

        val target = VaultFileMoves.landingPlace(home.path, "photo.jpg", noFallback)

        assertEquals(File(storage, "photo_1.jpg"), target)
    }

    @Test
    fun landingPlace_usesTheOriginalNameNotTheOneShownInTheVault() {
        val home = File(storage, "scan.png")

        val target = VaultFileMoves.landingPlace(home.path, "scan_1.png", noFallback)

        assertEquals(home, target)
    }

    @Test
    fun landingPlace_fallsBackWhenTheOriginIsUnknown() {
        assertEquals(File(downloads, "photo.jpg"), VaultFileMoves.landingPlace(null, "photo.jpg") { downloads })
        assertEquals(File(downloads, "photo.jpg"), VaultFileMoves.landingPlace("", "photo.jpg") { downloads })
        assertEquals(File(downloads, "photo.jpg"), VaultFileMoves.landingPlace("   ", "photo.jpg") { downloads })
    }

    @Test
    fun landingPlace_fallsBackWhenTheOriginalFolderCannotBeMade() {
        // A file sits in the way of the folder that would have to be made.
        val blocker = outside("Album", bytes(10, seed = 15))
        val home = File(File(blocker, "2024"), "photo.jpg")

        val target = VaultFileMoves.landingPlace(home.path, "photo.jpg") { downloads }

        assertEquals(File(downloads, "photo.jpg"), target)
    }

    @Test
    fun landingPlace_inTheFallbackTrustsNeitherTheNameNorAFreeSpot() {
        File(downloads, "passwd").writeBytes(ByteArray(1))

        val target = VaultFileMoves.landingPlace(null, "../../etc/passwd") { downloads }

        assertEquals(File(downloads, "passwd_1"), target)
    }

    @Test
    fun movingOut_writesThePlainFileAndThenRemovesTheSealedOne() {
        val contents = bytes(400_000, seed = 16)
        val inside = store.add("report.docx", ByteArrayInputStream(contents))
        val target = File(storage, "report.docx")

        VaultFileMoves.moveOut(store, inside, target)

        assertArrayEquals(contents, target.readBytes())
        assertFalse(inside.exists())
        assertEquals(listOf("report.docx"), storage.list()!!.toList())
    }

    @Test
    fun movingOut_ofALockedVaultFailsAndLeavesTheFileInIt() {
        val inside = store.add("report.docx", ByteArrayInputStream(bytes(1_000, seed = 17)))
        val target = File(storage, "report.docx")

        try {
            VaultFileMoves.moveOut(null, inside, target)
            fail("A locked vault can't decrypt")
        } catch (_: IOException) {
        }

        assertTrue(inside.exists())
        assertFalse(target.exists())
    }

    @Test
    fun movingOut_thatCannotFinishLeavesTheFileInTheVaultAndNoPieceOutside() {
        val inside = store.add("report.docx", ByteArrayInputStream(bytes(1_000, seed = 18)))
        val target = File(File(storage, "no such folder"), "report.docx")

        try {
            VaultFileMoves.moveOut(store, inside, target)
            fail("There's no folder to write into")
        } catch (_: IOException) {
        }

        assertTrue(inside.exists())
        assertEquals(emptyList<String>(), storage.list()!!.toList())
    }

    @Test
    fun movingOut_copiesAFileThatWasNeverEncrypted() {
        val contents = bytes(5_000, seed = 19)
        val legacy = File(vault, "old photo.jpg").apply { writeBytes(contents) }
        val target = File(storage, "old photo.jpg")

        // No store: this is how a vault opened without its key hands such a file back.
        VaultFileMoves.moveOut(null, legacy, target)

        assertArrayEquals(contents, target.readBytes())
        assertFalse(legacy.exists())
    }

    @Test
    fun writingPlain_leavesTheVaultsCopyWhereItIs() {
        val contents = bytes(5_000, seed = 20)
        val inside = store.add("keep.txt", ByteArrayInputStream(contents))
        val target = File(downloads, "keep.txt")

        VaultFileMoves.writePlain(store, inside, target)

        assertArrayEquals(contents, target.readBytes())
        assertTrue(inside.exists())
        assertArrayEquals(contents, sealed()["keep.txt"])
    }

    @Test
    fun writingPlain_stoppedPartwayLeavesNothingBehind() {
        val inside = store.add("large.bin", ByteArrayInputStream(bytes(900_000, seed = 21)))
        val target = File(downloads, "large.bin")
        var asked = 0

        try {
            VaultFileMoves.writePlain(store, inside, target, isCancelled = { ++asked > 1 })
            fail("It was told to stop")
        } catch (_: IOException) {
        }

        assertEquals(emptyList<String>(), downloads.list()!!.toList())
        assertTrue(inside.exists())
    }

    @Test
    fun aFileMovedInAndOutAgain_endsUpWhereItStartedUnchanged() {
        val contents = bytes(300_000, seed = 22)
        val source = File(storage, "Pictures/trip/beach.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(contents)
        }

        assertEquals(1, VaultFileMoves.moveIn(store, listOf(source.path)) { false }.sealed)
        assertFalse(source.exists())

        val inside = sealedFile("beach.jpg")
        val target = VaultFileMoves.landingPlace(store.info(inside)?.originalPath, "beach.jpg", noFallback)
        VaultFileMoves.moveOut(store, inside, target)

        assertEquals(source, target)
        assertArrayEquals(contents, source.readBytes())
        assertEquals(emptyList<File>(), VaultStore.encryptedFiles(vault))
    }
}
