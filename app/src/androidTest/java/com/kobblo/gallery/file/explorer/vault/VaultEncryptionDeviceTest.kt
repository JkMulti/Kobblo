package com.kobblo.gallery.file.explorer.vault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Runs the Vault's storage on a real filesystem with real preferences, so like
 * `VaultRecoveryFlowTest` it refuses to run if a vault is already here, and it only belongs on a
 * throwaway install. Run it under a separate application id on any device someone actually uses.
 */
@RunWith(AndroidJUnit4::class)
class VaultEncryptionDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("kobblo_vault_prefs", Context.MODE_PRIVATE)
    private val appPrefs get() = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE)
    private val vaultDirectory get() = VaultManager.getVaultDirectory(context)
    private val metadataFile get() = File(context.filesDir, "vault_metadata.json")
    private val copiesDirectory get() = File(context.cacheDir, "vault_open")
    private val scratch get() = File(context.getExternalFilesDir(null), "vault-device-test")
    private var ownsVault = false

    private class SimulatedCrash : Error()

    @Before
    fun requireNoExistingVault() {
        assumeFalse("A vault already exists on this install", prefs.all.isNotEmpty())
        assumeTrue("Vault files already exist on this install", VaultManager.getVaultFiles(context).isEmpty())
        ownsVault = true
        scratch.mkdirs()
    }

    @After
    fun removeThrowawayVault() {
        if (!ownsVault) return
        VaultManager.lock(context)
        prefs.edit().clear().commit()
        vaultDirectory.deleteRecursively()
        metadataFile.delete()
        scratch.deleteRecursively()
        appPrefs.edit().remove("recent_actions").remove("pinned_files").commit()
    }

    private fun bytes(size: Int, seed: Long = 1L) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Exactly what the app wrote before recovery keys, and before files were encrypted. */
    private fun seedLegacyVault(password: String, files: Map<String, ByteArray>) {
        prefs.edit()
            .putBoolean("vault_setup_completed", true)
            .putString("vault_password_hash", sha256(password))
            .putLong("vault_created_at", 1_700_000_000_000L)
            .commit()
        files.forEach { (name, contents) -> File(vaultDirectory, name).writeBytes(contents) }
    }

    private fun encryptEverything() {
        VaultManager.encryptPendingFiles(context, isCancelled = { false }, onProgress = { _, _ -> })
    }

    private fun item(name: String): FileItem = VaultManager.getVaultFiles(context).single { it.name == name }

    private fun readThroughCopy(file: FileItem): ByteArray {
        val copy = assertNotNullAndReturn(VaultManager.openDecryptedCopy(context, file))
        return File(copy.path).readBytes().also { VaultManager.discardDecryptedCopy(copy.path) }
    }

    private fun <T> assertNotNullAndReturn(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test
    fun legacyVault_migratesAndForgetsWhatItLeakedInPlaintext() {
        val photo = bytes(300_000, seed = 1)
        val video = bytes(1_500_000, seed = 2)
        seedLegacyVault("legacy-password", mapOf("passport.jpg" to photo, "secret video.mp4" to video))
        val original = File(scratch, "Pictures/passport.jpg").absolutePath
        metadataFile.writeText(
            JSONObject().put(
                "passport.jpg",
                JSONObject().put("originalPath", original).put("originalName", "passport.jpg").put("timestamp", 1L)
            ).toString()
        )
        val vaultPath = File(vaultDirectory, "passport.jpg").absolutePath
        appPrefs.edit()
            .putString(
                "recent_actions",
                JSONArray()
                    .put(JSONObject().put("path", vaultPath).put("action", "Opened").put("timestamp", 1L))
                    .put(JSONObject().put("path", "/sdcard/kept.txt").put("action", "Opened").put("timestamp", 1L))
                    .toString()
            )
            .putStringSet("pinned_files", setOf(vaultPath, "/sdcard/kept.txt"))
            .commit()

        assertFalse("Wrong password opened the vault", VaultManager.unlock(context, "wrong-password"))
        assertTrue("Plaintext shouldn't move before the key exists", File(vaultDirectory, "passport.jpg").exists())

        assertTrue(VaultManager.unlock(context, "legacy-password"))
        assertTrue(VaultManager.canEncrypt)
        assertTrue(VaultManager.needsEncryption(context))
        encryptEverything()

        // Nothing in the app's storage reads as the file, its name, or where it came from.
        assertFalse(VaultManager.needsEncryption(context))
        assertFalse("The old metadata should be gone", metadataFile.exists())
        val onDisk = vaultDirectory.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.toList()
        assertEquals(2, onDisk.size)
        onDisk.forEach { file ->
            assertFalse("Name on disk: ${file.name}", file.name.contains("passport"))
            val raw = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse(raw.contains("passport"))
            assertFalse(raw.contains("Pictures"))
        }

        // History from before encryption no longer names the file, and everything else stays.
        val recents = appPrefs.getString("recent_actions", "")!!
        assertFalse(recents, recents.contains("passport"))
        assertTrue(recents, recents.contains("kept.txt"))
        assertEquals(setOf("/sdcard/kept.txt"), appPrefs.getStringSet("pinned_files", emptySet()))

        // The list, and the bytes behind it, are what they always were.
        val listed = VaultManager.getVaultFiles(context)
        assertEquals(setOf("passport.jpg", "secret video.mp4"), listed.map { it.name }.toSet())
        assertEquals("image", item("passport.jpg").type)
        assertEquals("video", item("secret video.mp4").type)
        assertEquals(photo.size.toLong(), item("passport.jpg").sizeBytes)
        assertArrayEquals(photo, readThroughCopy(item("passport.jpg")))
        assertArrayEquals(video, readThroughCopy(item("secret video.mp4")))

        // Restoring still finds its way home, from a location that's now sealed inside the file.
        assertTrue(VaultManager.restoreFile(context, item("passport.jpg")))
        assertArrayEquals(photo, File(original).readBytes())
        assertEquals(listOf("secret video.mp4"), VaultManager.getVaultFiles(context).map { it.name })
    }

    @Test
    fun recoveryKey_opensAVaultThatWasEncryptedAfterItWasIssued() {
        val recoveryKey = RecoveryKey.generate()
        assertTrue(VaultManager.setupVault(context, "first-password", "", recoveryKey))
        val contents = bytes(100_000)
        File(vaultDirectory, "kept.jpg").writeBytes(contents)
        encryptEverything()
        VaultManager.lock(context)

        // Locked, there's nothing to read: not a name, and not the contents.
        val locked = VaultManager.getVaultFiles(context).single()
        assertEquals("Unreadable file", locked.name)
        assertNull(VaultManager.openDecryptedCopy(context, locked))
        assertFalse(VaultManager.canEncrypt)

        assertEquals(RecoveryKeyCheck.Valid, VaultManager.checkRecoveryKey(context, recoveryKey))
        assertTrue(VaultManager.resetPassword(context, recoveryKey, "second-password", ""))
        assertTrue("The recovery key should open the vault", VaultManager.canEncrypt)
        assertArrayEquals(contents, readThroughCopy(item("kept.jpg")))

        // And the new password opens the very same files.
        VaultManager.lock(context)
        assertFalse(VaultManager.unlock(context, "first-password"))
        assertTrue(VaultManager.unlock(context, "second-password"))
        assertArrayEquals(contents, readThroughCopy(item("kept.jpg")))
    }

    @Test
    fun aRealCrashAtEveryStep_losesNothingOnThisFilesystem() {
        val files = mapOf(
            "small.txt" to bytes(100, seed = 1),
            "empty" to ByteArray(0),
            "large.bin" to bytes(600_000, seed = 2)
        )
        val key = ByteArray(32) { it.toByte() }
        fun reset() {
            vaultDirectory.deleteRecursively()
            files.forEach { (name, contents) -> File(vaultDirectory.also { it.mkdirs() }, name).writeBytes(contents) }
        }

        reset()
        var steps = 0
        VaultStore(vaultDirectory, key) { steps++ }.migrate()
        assertTrue(steps > 0)

        for (crashAt in 0 until steps) {
            reset()
            var step = 0
            try {
                VaultStore(vaultDirectory, key) { if (step++ == crashAt) throw SimulatedCrash() }.migrate()
            } catch (_: SimulatedCrash) {
            }

            val store = VaultStore(vaultDirectory, key)
            assertEquals(0, store.migrate().failed)

            assertEquals("Step $crashAt", emptyList<File>(), VaultStore.plaintextFiles(vaultDirectory))
            val found = VaultStore.encryptedFiles(vaultDirectory).associate { file ->
                store.info(file)!!.name to store.open(file).use { it.readBytes() }
            }
            assertEquals("Step $crashAt", files.keys, found.keys)
            files.forEach { (name, contents) -> assertArrayEquals("Step $crashAt: $name", contents, found[name]) }
        }
    }

    @Test
    fun decryptedCopies_goAwayWhenTheyShould() {
        val recoveryKey = RecoveryKey.generate()
        assertTrue(VaultManager.setupVault(context, "the-password", "", recoveryKey))
        File(vaultDirectory, "a.txt").writeBytes(bytes(100, seed = 1))
        File(vaultDirectory, "b.txt").writeBytes(bytes(100, seed = 2))
        encryptEverything()

        // Closing a viewer removes that copy only.
        val first = assertNotNullAndReturn(VaultManager.openDecryptedCopy(context, item("a.txt")))
        val second = assertNotNullAndReturn(VaultManager.openDecryptedCopy(context, item("b.txt")))
        assertTrue(VaultManager.isDecryptedCopy(first.path))
        assertFalse(VaultManager.isDecryptedCopy(item("a.txt").path))
        assertFalse(VaultManager.isDecryptedCopy(null))
        assertEquals("a.txt", File(first.path).name)
        VaultManager.discardDecryptedCopy(first.path)
        assertFalse(File(first.path).exists())
        assertTrue(File(second.path).exists())

        // Discarding only ever touches copies.
        VaultManager.discardDecryptedCopy(item("b.txt").path)
        assertTrue(File(item("b.txt").path).exists())

        // An open session protects its copies from startup's sweep.
        VaultManager.clearLeftoverCopies(context)
        assertTrue(File(second.path).exists())

        // Locking removes what's left, including what the PDF viewers made.
        val pdfCopy = File(context.cacheDir, "pdf_decrypted_123.pdf").apply { writeText("plain") }
        val otherPdfCopy = File(context.cacheDir, "decrypted_456.pdf").apply { writeText("plain") }
        val notOurs = File(context.cacheDir, "something_else.pdf").apply { writeText("plain") }
        VaultManager.lock(context)
        assertFalse(File(second.path).exists())
        assertFalse(pdfCopy.exists())
        assertFalse(otherPdfCopy.exists())
        assertTrue(notOurs.delete())

        // A copy left behind by a crash goes the next time the app starts.
        val orphan = File(copiesDirectory, "left-behind/secret.jpg").apply {
            parentFile!!.mkdirs()
            writeText("plain")
        }
        VaultManager.clearLeftoverCopies(context)
        assertFalse(orphan.exists())
    }

    @Test
    fun theBackgroundLockAlarm_endsTheSessionWithNoScreenInvolved() {
        val recoveryKey = RecoveryKey.generate()
        assertTrue(VaultManager.setupVault(context, "the-password", "", recoveryKey))
        File(vaultDirectory, "a.txt").writeBytes(bytes(100))
        encryptEverything()
        val copy = assertNotNullAndReturn(VaultManager.openDecryptedCopy(context, item("a.txt")))
        assertTrue(File(copy.path).exists())

        // Goes through the manifest, so this also catches the receiver not being registered.
        context.sendBroadcast(Intent(context, VaultLockReceiver::class.java))

        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (VaultManager.isUnlocked && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertFalse("Still unlocked after the alarm", VaultManager.isUnlocked)
        while (File(copy.path).exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertFalse("Copy outlived the alarm", File(copy.path).exists())
        assertNull(VaultManager.fileStore())
    }

    @Test
    fun thumbnails_comeFromTheCiphertextWithoutLeavingCopies() = runBlocking {
        assertTrue(VaultManager.setupVault(context, "the-password", "", RecoveryKey.generate()))
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val pdf = ByteArrayOutputStream().also { out ->
            PdfDocument().apply {
                val page = startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
                page.canvas.drawText("Hello", 20f, 40f, Paint())
                finishPage(page)
                writeTo(out)
                close()
            }
        }.toByteArray()
        File(vaultDirectory, "picture.png").writeBytes(png)
        File(vaultDirectory, "letter.pdf").writeBytes(pdf)
        File(vaultDirectory, "note.txt").writeText("A note")
        encryptEverything()

        val loader = ImageLoader.Builder(context)
            .components { add(VaultThumbnailFetcher.Factory()) }
            .build()
        for (name in listOf("picture.png", "letter.pdf", "note.txt")) {
            val request = ImageRequest.Builder(context).data(File(item(name).path)).size(96).build()
            val result = loader.execute(request)
            assertTrue("$name: $result", result is SuccessResult)
            assertEquals("A thumbnail left a copy behind: $name", 0, copiesDirectory.list()?.size ?: 0)
        }

        // A user's own folder that happens to share the name is none of the fetcher's business.
        val lookalike = File(scratch, ".encrypted/photo.png").apply {
            parentFile!!.mkdirs()
            writeBytes(png)
        }
        assertFalse(VaultManager.isVaultCiphertext(context, lookalike))
        assertTrue(VaultManager.isVaultCiphertext(context, File(item("picture.png").path)))
        assertTrue(loader.execute(ImageRequest.Builder(context).data(lookalike).size(96).build()) is SuccessResult)

        // Locked, the same request has nothing to work with.
        VaultManager.lock(context)
        val locked = loader.execute(ImageRequest.Builder(context).data(File(VaultStore.encryptedFiles(vaultDirectory).first().path)).build())
        assertFalse(locked is SuccessResult)
    }

    @Test
    fun backupRules_leaveTheVaultOutOfEveryRoute() {
        val expected = setOf(
            "file" to "vault_files",
            "file" to "vault_metadata.json",
            "sharedpref" to "kobblo_vault_prefs.xml"
        )

        fun excludes(resource: Int, section: String?): Set<Pair<String, String>> {
            val found = mutableSetOf<Pair<String, String>>()
            var inSection = section == null
            context.resources.getXml(resource).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name == section) inSection = true
                        if (parser.name == "exclude" && inSection) {
                            found += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == section) {
                        inSection = false
                    }
                }
            }
            return found
        }

        // Other things stay behind too, such as the crash log and this phone's identity for sending. A
        // folder can be written with or without its trailing slash.
        fun vaultExcludes(resource: Int, section: String?) =
            excludes(resource, section).map { (domain, path) -> domain to path.removeSuffix("/") }.toSet()
        for ((resource, section) in listOf(
            R.xml.backup_rules to null,
            R.xml.data_extraction_rules to "cloud-backup",
            R.xml.data_extraction_rules to "device-transfer"
        )) {
            val found = vaultExcludes(resource, section)
            assertTrue("$section is missing ${expected - found}", found.containsAll(expected))
        }
    }

    @Test
    fun shortPasswords_stillOpenAndGetANudge() {
        // A vault set up back when four characters was the minimum.
        seedLegacyVault("1234", emptyMap())

        assertTrue(VaultManager.unlock(context, "1234"))
        assertEquals(VaultNotice.ShortPassword, VaultManager.currentNotice(context))

        assertTrue(VaultManager.changePassword(context, "1234", "a much longer password", ""))
        assertNull(VaultManager.currentNotice(context))
    }

    @Test
    fun withoutTheKey_plaintextStaysReadableAndNothingCanBeAdded() {
        val contents = bytes(1000)
        seedLegacyVault("legacy-password", mapOf("kept.jpg" to contents))

        VaultManager.openSessionWithoutKey()

        assertTrue(VaultManager.isUnlocked)
        assertFalse(VaultManager.canEncrypt)
        assertFalse(VaultManager.needsEncryption(context))
        assertEquals(VaultNotice.NoKeyYet, VaultManager.currentNotice(context))
        assertArrayEquals(contents, readThroughCopy(item("kept.jpg")))
        assertEquals(0, VaultManager.importFilesFromPaths(context, listOf(File(vaultDirectory, "kept.jpg").path)))
        assertArrayEquals("A refused import must not touch the source", contents, File(vaultDirectory, "kept.jpg").readBytes())
    }

    @Test
    fun importingFromAPath_encryptsItAndRemovesTheSource() {
        assertTrue(VaultManager.setupVault(context, "the-password", "", RecoveryKey.generate()))
        val contents = bytes(100_000)
        val source = File(scratch, "from-storage.jpg").apply { writeBytes(contents) }

        assertEquals(1, VaultManager.importFilesFromPaths(context, listOf(source.path)))

        assertFalse("A vault file shouldn't also stay out in the open", source.exists())
        assertEquals(emptyList<File>(), VaultStore.plaintextFiles(vaultDirectory))
        assertArrayEquals(contents, readThroughCopy(item("from-storage.jpg")))

        // It knows where to go back to.
        assertTrue(VaultManager.restoreFile(context, item("from-storage.jpg")))
        assertArrayEquals(contents, source.readBytes())
        assertEquals(emptyList<FileItem>(), VaultManager.getVaultFiles(context))
    }
}
