package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Random

class VaultStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var directory: File
    private val vaultKey = ByteArray(32) { (it * 3).toByte() }

    /** Dies the way a killed process would: an Error skips all of the store's cleanup. */
    private class SimulatedCrash : Error()

    @Before
    fun createVault() {
        directory = folder.newFolder("vault_files")
        File(directory, ".nomedia").createNewFile()
    }

    private fun bytes(size: Int, seed: Long) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun store(checkpoint: (VaultCheckpoint) -> Unit = {}) = VaultStore(directory, vaultKey, checkpoint)

    /** The vault an older version would have left behind, as name to contents. */
    private fun seedPlaintext(): Map<String, ByteArray> {
        val files = mapOf(
            "passport.jpg" to bytes(3_000, seed = 1),
            "empty.txt" to ByteArray(0),
            "holiday video.mp4" to bytes(700_000, seed = 2),
            "notes.pdf" to bytes(70_000, seed = 3),
            ".hidden" to bytes(100, seed = 4),
            "no_extension" to bytes(10, seed = 5)
        )
        files.forEach { (name, contents) -> File(directory, name).writeBytes(contents) }
        return files
    }

    /** Everything a fresh process would find, as name to decrypted contents. */
    private fun readEverything(): Map<String, ByteArray> {
        val store = store()
        return VaultStore.encryptedFiles(directory).associate { file ->
            val name = checkNotNull(store.info(file)) { "${file.name} is unreadable" }.name
            name to store.open(file).use { it.readBytes() }
        }
    }

    private fun assertVaultHolds(expected: Map<String, ByteArray>, message: String = "") {
        val actual = readEverything()
        assertEquals("$message: names", expected.keys, actual.keys)
        expected.forEach { (name, contents) ->
            assertArrayEquals("$message: contents of $name", contents, actual[name])
        }
    }

    private fun assertNoPlaintextLeft() {
        assertEquals(emptyList<File>(), VaultStore.plaintextFiles(directory))
    }

    private fun assertNoLeftoverWrites() {
        val leftovers = VaultStore.encryptedDirectory(directory).listFiles { file -> file.name.endsWith(".tmp") }
        assertEquals(emptyList<File>(), leftovers?.toList().orEmpty())
    }

    // ── Migration ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun migration_encryptsEveryFileAndRemovesThePlaintext() {
        val files = seedPlaintext()

        val result = store().migrate()

        assertEquals(files.size, result.encrypted)
        assertEquals(0, result.failed)
        assertFalse(result.wasCancelled)
        assertNoPlaintextLeft()
        assertNoLeftoverWrites()
        assertVaultHolds(files)
        assertTrue("Migration shouldn't touch .nomedia", File(directory, ".nomedia").exists())
    }

    @Test
    fun migration_leavesNothingReadableOnDisk() {
        val secret = "my secret diary entry".toByteArray()
        File(directory, "diary.txt").writeBytes(secret)

        store().migrate()

        val onDisk = directory.walkTopDown().filter { it.isFile }.toList()
        for (file in onDisk) {
            assertFalse("A file name leaked: ${file.name}", file.name.contains("diary"))
            val raw = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("Contents leaked in ${file.name}", raw.contains("secret diary"))
            assertFalse("The name leaked in ${file.name}", raw.contains("diary.txt"))
        }
    }

    @Test
    fun migration_keepsDatesAndOriginalLocations() {
        val file = File(directory, "photo.jpg").apply { writeBytes(bytes(100, seed = 9)) }
        val modified = 1_600_000_000_000L
        file.setLastModified(modified)

        store().migrate(originalPathOf = { "/sdcard/DCIM/$it" })

        val encrypted = VaultStore.encryptedFiles(directory).single()
        assertEquals(modified, encrypted.lastModified())
        assertEquals("/sdcard/DCIM/photo.jpg", store().info(encrypted)?.originalPath)
    }

    @Test
    fun migration_ofAnEmptyVault_doesNothing() {
        val result = store().migrate()
        assertEquals(0, result.encrypted)
        assertEquals(0, result.failed)
    }

    @Test
    fun migration_reportsProgressThroughToTheEnd() {
        val files = seedPlaintext()
        val reported = mutableListOf<Pair<Int, Int>>()

        store().migrate(onProgress = { done, total -> reported += done to total })

        assertEquals(files.size + 1, reported.size)
        assertEquals(0 to files.size, reported.first())
        assertEquals(files.size to files.size, reported.last())
    }

    // ── Interrupted migration ────────────────────────────────────────────────────────────────────

    /**
     * Kills the migration at every step of every file: while writing, after writing, after checking,
     * after the rename, and after removing the original. Each time, a new store on the same directory
     * has to finish the job with every file intact and nothing left over.
     */
    @Test
    fun migrationInterruptedAtAnyStep_losesNothing() {
        // Count the steps in an uninterrupted run, so the loop below can stop at each one.
        val files = seedPlaintext()
        var steps = 0
        store { steps++ }.migrate()
        assertTrue("Expected every step to be visited, got $steps", steps >= files.size * 4)

        for (crashAt in 0 until steps) {
            directory.deleteRecursively()
            directory.mkdirs()
            File(directory, ".nomedia").createNewFile()
            seedPlaintext()

            var step = 0
            var crashed = false
            try {
                store {
                    if (step++ == crashAt) throw SimulatedCrash()
                }.migrate()
            } catch (_: SimulatedCrash) {
                crashed = true
            }
            assertTrue("Step $crashAt should have been reached", crashed)

            // Before recovery: every file is readable in at least one place.
            val encrypted = readableEncryptedNames()
            files.keys.forEach { name ->
                assertTrue(
                    "Step $crashAt: $name exists nowhere",
                    File(directory, name).exists() || name in encrypted
                )
            }

            store().migrate()

            assertNoPlaintextLeft()
            assertNoLeftoverWrites()
            assertVaultHolds(files, "After a crash at step $crashAt")
        }
    }

    @Test
    fun migrationInterruptedRepeatedly_stillFinishes() {
        val files = seedPlaintext()

        // Crash partway through each run. Each run must make progress on files it reached.
        var runs = 0
        while (VaultStore.plaintextFiles(directory).isNotEmpty()) {
            runs++
            assertTrue("Migration never finished", runs < 50)
            var step = 0
            try {
                store {
                    if (it == VaultCheckpoint.OriginalRemoved && step++ == 0) throw SimulatedCrash()
                }.migrate()
            } catch (_: SimulatedCrash) {
            }
        }

        assertNoLeftoverWrites()
        assertVaultHolds(files)
    }

    @Test
    fun crashBetweenRenameAndDelete_doesNotDuplicateTheFile() {
        File(directory, "photo.jpg").writeBytes(bytes(5000, seed = 1))
        try {
            store {
                if (it == VaultCheckpoint.Committed) throw SimulatedCrash()
            }.migrate()
            fail("Expected the crash")
        } catch (_: SimulatedCrash) {
        }
        assertTrue("The original should still be there", File(directory, "photo.jpg").exists())
        assertEquals(1, VaultStore.encryptedFiles(directory).size)

        store().migrate()

        assertNoPlaintextLeft()
        assertEquals("Recovery must not encrypt it a second time", 1, VaultStore.encryptedFiles(directory).size)
        assertEquals("photo.jpg", store().info(VaultStore.encryptedFiles(directory).single())?.name)
    }

    @Test
    fun unfinishedWrites_areNeverListedAndAreSweptOnTheNextRun() {
        File(directory, "photo.jpg").writeBytes(bytes(5000, seed = 1))
        try {
            store {
                if (it == VaultCheckpoint.Written) throw SimulatedCrash()
            }.migrate()
            fail("Expected the crash")
        } catch (_: SimulatedCrash) {
        }
        assertEquals("A write in progress isn't a file", emptyList<File>(), VaultStore.encryptedFiles(directory))
        assertTrue(File(directory, "photo.jpg").exists())

        store().removeUnfinishedWrites()

        assertNoLeftoverWrites()
        assertTrue(File(directory, "photo.jpg").exists())
    }

    @Test
    fun cancellingMigration_leavesEveryFileInOnePiece() {
        val files = seedPlaintext()
        var checks = 0

        val result = store().migrate(isCancelled = { checks++ > 4 })

        assertTrue(result.wasCancelled)
        assertNoLeftoverWrites()
        val encrypted = readableEncryptedNames()
        files.forEach { (name, contents) ->
            val plain = File(directory, name)
            if (plain.exists()) {
                assertArrayEquals("$name was touched", contents, plain.readBytes())
            } else {
                assertTrue("$name is gone", name in encrypted)
            }
        }

        store().migrate()

        assertNoPlaintextLeft()
        assertVaultHolds(files)
    }

    // ── Failures that aren't crashes ─────────────────────────────────────────────────────────────

    @Test
    fun aDiskErrorOnOneFile_keepsItsOriginalAndCarriesOnWithTheRest() {
        val small = bytes(1000, seed = 1)
        val large = bytes(400_000, seed = 2)
        File(directory, "small.bin").writeBytes(small)
        File(directory, "large.bin").writeBytes(large)
        var writes = 0

        // Smallest goes first, so the second write of chunks belongs to the large file.
        val result = store {
            if (it == VaultCheckpoint.Writing && ++writes == 2) throw IOException("No space left on device")
        }.migrate()

        assertEquals(1, result.encrypted)
        assertEquals(1, result.failed)
        assertNoLeftoverWrites()
        assertArrayEquals("The original must survive", large, File(directory, "large.bin").readBytes())
        assertFalse(File(directory, "small.bin").exists())
        assertVaultHolds(mapOf("small.bin" to small))

        // Once there's room, the next unlock finishes the job.
        assertEquals(1, store().migrate().encrypted)
        assertNoPlaintextLeft()
        assertVaultHolds(mapOf("small.bin" to small, "large.bin" to large))
    }

    @Test
    fun aWriteThatComesBackWrong_keepsTheOriginal() {
        val contents = bytes(100_000, seed = 1)
        File(directory, "photo.jpg").writeBytes(contents)
        val encryptedDirectory = VaultStore.encryptedDirectory(directory)

        val result = store {
            if (it == VaultCheckpoint.Written) {
                // Storage quietly damaged what was just written.
                val temp = encryptedDirectory.listFiles()!!.single { file -> file.name.endsWith(".tmp") }
                val damaged = temp.readBytes()
                damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 1).toByte()
                temp.writeBytes(damaged)
            }
        }.migrate()

        assertEquals(1, result.failed)
        assertEquals(0, result.encrypted)
        assertArrayEquals(contents, File(directory, "photo.jpg").readBytes())
        assertEquals(emptyList<File>(), VaultStore.encryptedFiles(directory))
        assertNoLeftoverWrites()
    }

    @Test
    fun aDifferentFileUnderTheSameName_isKeptAlongsideIt() {
        val added = bytes(1000, seed = 1)
        val plain = bytes(1000, seed = 2)
        store().add("photo.jpg", ByteArrayInputStream(added))
        File(directory, "photo.jpg").writeBytes(plain)

        store().migrate()

        assertNoPlaintextLeft()
        assertVaultHolds(mapOf("photo.jpg" to added, "photo_1.jpg" to plain))
    }

    @Test
    fun aDamagedEncryptedCopy_neverCostsTheOriginal() {
        val contents = bytes(100_000, seed = 1)
        File(directory, "photo.jpg").writeBytes(contents)
        try {
            store {
                if (it == VaultCheckpoint.Committed) throw SimulatedCrash()
            }.migrate()
            fail("Expected the crash")
        } catch (_: SimulatedCrash) {
        }
        val encrypted = VaultStore.encryptedFiles(directory).single()
        val damaged = encrypted.readBytes()
        damaged[damaged.size / 2] = (damaged[damaged.size / 2].toInt() xor 1).toByte()
        encrypted.writeBytes(damaged)

        store().migrate()

        assertNoPlaintextLeft()
        val copies = readableNames().mapNotNull { name ->
            VaultStore.encryptedFiles(directory).firstOrNull { store().info(it)?.name == name }
        }
        val intact = copies.count { file ->
            try {
                store().open(file).use { it.readBytes() }.contentEquals(contents)
            } catch (_: IOException) {
                false
            }
        }
        assertEquals("One copy has to be whole", 1, intact)
    }

    private fun readableNames(): List<String> {
        val store = store()
        return VaultStore.encryptedFiles(directory).mapNotNull { store.info(it)?.name }
    }

    private fun readableEncryptedNames(): Set<String> = readableNames().toSet()

    // ── Adding and reading ───────────────────────────────────────────────────────────────────────

    @Test
    fun add_encryptsAndRoundTrips() {
        val contents = bytes(250_000, seed = 1)

        val file = store().add("photo.jpg", ByteArrayInputStream(contents), originalPath = "/sdcard/photo.jpg")

        assertEquals("/sdcard/photo.jpg", store().info(file)?.originalPath)
        assertEquals(contents.size.toLong(), store().summarize(file)?.sizeBytes)
        assertVaultHolds(mapOf("photo.jpg" to contents))
    }

    @Test
    fun add_neverOverwritesAFileWithTheSameName() {
        val first = bytes(100, seed = 1)
        val second = bytes(100, seed = 2)
        val third = bytes(100, seed = 3)

        store().add("photo.jpg", ByteArrayInputStream(first))
        store().add("photo.jpg", ByteArrayInputStream(second))
        store().add("photo.jpg", ByteArrayInputStream(third))

        assertVaultHolds(mapOf("photo.jpg" to first, "photo_1.jpg" to second, "photo_2.jpg" to third))
    }

    @Test
    fun add_steersClearOfPlaintextStillWaitingToMigrate() {
        val waiting = bytes(100, seed = 1)
        val added = bytes(100, seed = 2)
        File(directory, "photo.jpg").writeBytes(waiting)

        store().add("photo.jpg", ByteArrayInputStream(added))
        store().migrate()

        assertVaultHolds(mapOf("photo.jpg" to waiting, "photo_1.jpg" to added))
    }

    @Test
    fun add_thatFailsPartWay_leavesNothingBehind() {
        val source = object : java.io.InputStream() {
            private var left = 300_000
            override fun read(): Int = if (left-- > 0) 0 else throw IOException("The provider went away")
        }

        try {
            store().add("photo.jpg", source)
            fail("Expected the read to fail")
        } catch (_: IOException) {
        }

        assertEquals(emptyList<File>(), VaultStore.encryptedFiles(directory))
        assertNoLeftoverWrites()
    }

    @Test
    fun decryptTo_writesTheWholeFileOrNothing() {
        val contents = bytes(200_000, seed = 1)
        val file = store().add("photo.jpg", ByteArrayInputStream(contents))
        val out = folder.newFolder("out")

        val target = File(out, "photo.jpg")
        store().decryptTo(file, target)
        assertArrayEquals(contents, target.readBytes())
        assertEquals(listOf("photo.jpg"), out.list()!!.toList())

        // A damaged file: nothing should appear, not even a partial one.
        val damaged = file.readBytes()
        damaged[damaged.size - 5] = (damaged[damaged.size - 5].toInt() xor 1).toByte()
        file.writeBytes(damaged)
        try {
            store().decryptTo(file, File(out, "again.jpg"))
            fail("Expected decrypting to fail")
        } catch (_: IOException) {
        }
        assertEquals(listOf("photo.jpg"), out.list()!!.toList())
    }

    @Test
    fun cancelledDecrypt_leavesNothingBehind() {
        val file = store().add("photo.jpg", ByteArrayInputStream(bytes(200_000, seed = 1)))
        val out = folder.newFolder("out")

        try {
            store().decryptTo(file, File(out, "photo.jpg"), isCancelled = { true })
            fail("Expected the cancellation")
        } catch (_: VaultCancelledException) {
        }

        assertEquals(emptyList<String>(), out.list()!!.toList())
    }

    // ── End to end with the keyring ──────────────────────────────────────────────────────────────

    private class MemoryKeyStore : VaultKeyStore {
        val entries = mutableMapOf<String, String>()
        override fun read(name: String) = entries[name]
        override fun write(changes: Map<String, String?>): Boolean {
            changes.forEach { (name, value) ->
                if (value == null) entries.remove(name) else entries[name] = value
            }
            return true
        }
    }

    private val cheapCost = KdfCost(memoryKib = 64, iterations = 1, parallelism = 1)

    @Test
    fun aRecoveryKeyIssuedBeforeEncryption_opensEncryptedFiles() {
        val keyStore = MemoryKeyStore()
        val keyring = VaultKeyring(keyStore, cheapCost)
        val recoveryKey = RecoveryKey.generate()
        assertTrue(keyring.create("the password", recoveryKey))
        val files = seedPlaintext()

        // The first unlock after updating, with the password, migrates the plaintext.
        val opened = keyring.unlock("the password") as VaultUnlock.Opened
        VaultStore(directory, opened.vaultKey).migrate()
        assertNoPlaintextLeft()

        // Later, someone who has forgotten the password comes back with the key they wrote down.
        val recovered = assertNotNullKey(VaultKeyring(keyStore, cheapCost).unlockWithRecoveryKey(recoveryKey))
        val recoveredStore = VaultStore(directory, recovered)
        val found = VaultStore.encryptedFiles(directory).associate { file ->
            recoveredStore.info(file)!!.name to recoveredStore.open(file).use { it.readBytes() }
        }
        assertEquals(files.keys, found.keys)
        files.forEach { (name, contents) -> assertArrayEquals(contents, found[name]) }
    }

    @Test
    fun changingThePasswordOrRecoveryKey_keepsFilesReadable() {
        val keyStore = MemoryKeyStore()
        val keyring = VaultKeyring(keyStore, cheapCost)
        val firstRecoveryKey = RecoveryKey.generate()
        assertTrue(keyring.create("first password", firstRecoveryKey))
        val before = (keyring.unlock("first password") as VaultUnlock.Opened).vaultKey
        val contents = bytes(1000, seed = 1)
        val file = VaultStore(directory, before).add("photo.jpg", ByteArrayInputStream(contents))

        assertTrue(keyring.changePassword("first password", "second password"))
        val secondRecoveryKey = RecoveryKey.generate()
        assertTrue(keyring.setRecoveryKey("second password", secondRecoveryKey))
        assertTrue(keyring.resetPassword(secondRecoveryKey, "third password"))

        val after = (keyring.unlock("third password") as VaultUnlock.Opened).vaultKey
        assertArrayEquals(contents, VaultStore(directory, after).open(file).use { it.readBytes() })
    }

    @Test
    fun aVaultFromBeforeRecoveryKeys_getsAKeyAndMigrates() {
        val keyStore = MemoryKeyStore()
        keyStore.entries["vault_password_hash"] = java.security.MessageDigest.getInstance("SHA-256")
            .digest("old password".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val keyring = VaultKeyring(keyStore, cheapCost)
        val files = seedPlaintext()

        val opened = keyring.unlock("old password") as VaultUnlock.Opened
        VaultStore(directory, opened.vaultKey).migrate()

        assertNoPlaintextLeft()
        val again = (VaultKeyring(keyStore, cheapCost).unlock("old password") as VaultUnlock.Opened).vaultKey
        val store = VaultStore(directory, again)
        VaultStore.encryptedFiles(directory).forEach { file ->
            assertArrayEquals(files[store.info(file)!!.name], store.open(file).use { it.readBytes() })
        }
    }

    private fun assertNotNullKey(key: ByteArray?): ByteArray {
        assertNotNull(key)
        return key!!
    }
}
