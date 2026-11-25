package com.kobblo.gallery.file.explorer.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Random

class VaultFileCipherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cipher = VaultFileCipher(VAULT_KEY)

    private fun bytes(size: Int, seed: Long = 1L) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun encryptToFile(
        contents: ByteArray,
        name: String = "holiday.jpg",
        originalPath: String? = "/sdcard/DCIM/holiday.jpg",
        using: VaultFileCipher = cipher
    ): File {
        val stored = using.storedName(name)
        val file = File(folder.root, stored)
        using.encrypt(file.outputStream(), stored, VaultFileInfo(name, originalPath)).use { it.write(contents) }
        return file
    }

    private fun assertDecryptFails(file: File, storedName: String = file.name) {
        try {
            cipher.decrypt(file, storedName).use { it.readBytes() }
            fail("Expected decrypting to fail")
        } catch (_: IOException) {
        }
    }

    @Test
    fun roundTrips_atEverySizeAroundASegmentBoundary() {
        // The first segment holds less than the others because the stream's header shares it.
        val firstSegment = 64 * 1024 - 16 - 40
        val otherSegment = 64 * 1024 - 16
        val sizes = listOf(
            0, 1, 2, 4096,
            firstSegment - 1, firstSegment, firstSegment + 1,
            firstSegment + otherSegment - 1, firstSegment + otherSegment, firstSegment + otherSegment + 1,
            1024 * 1024
        )
        for (size in sizes) {
            val contents = bytes(size, seed = size.toLong())
            val file = encryptToFile(contents, name = "size-$size.bin")
            assertArrayEquals("Size $size", contents, cipher.decrypt(file).use { it.readBytes() })
            assertEquals("Reported size for $size", size.toLong(), cipher.summarize(file)?.sizeBytes)
        }
    }

    @Test
    fun infoSurvivesWithAndWithoutAnOriginalPath() {
        val withPath = cipher.summarize(encryptToFile(bytes(10), name = "a.jpg", originalPath = "/sdcard/a.jpg"))!!.info
        assertEquals("a.jpg", withPath.name)
        assertEquals("/sdcard/a.jpg", withPath.originalPath)

        val withoutPath = cipher.summarize(encryptToFile(bytes(10), name = "b.jpg", originalPath = null))!!.info
        assertEquals("b.jpg", withoutPath.name)
        assertNull(withoutPath.originalPath)

        val unusual = "Résumé 履歴書 📄.pdf"
        assertEquals(unusual, cipher.summarize(encryptToFile(bytes(10), name = unusual))!!.info.name)
    }

    @Test
    fun nothingReadableReachesTheDisk() {
        val secret = "the combination is 12-34-56".repeat(100).toByteArray()
        val file = encryptToFile(secret, name = "safe-combination.txt", originalPath = "/sdcard/Private/safe-combination.txt")
        val raw = file.readBytes()
        val rawText = String(raw, Charsets.ISO_8859_1)

        assertFalse("Contents leaked", rawText.contains("the combination"))
        assertFalse("Name leaked", rawText.contains("safe-combination"))
        assertFalse("Original path leaked", rawText.contains("/sdcard/Private"))
        assertFalse("Name leaked through the file name", file.name.contains("safe"))
        assertEquals("Should look like <hmac>.vault", 64 + ".vault".length, file.name.length)
    }

    @Test
    fun sameContentsEncryptDifferentlyEachTime() {
        val contents = bytes(1000)
        val first = encryptToFile(contents, name = "one.bin").readBytes()
        val second = encryptToFile(contents, name = "one.bin").readBytes()
        assertFalse(first.contentEquals(second))
        assertArrayEquals(contents, cipher.decrypt(encryptToFile(contents, name = "one.bin")).use { it.readBytes() })
    }

    @Test
    fun anyFlippedByte_isDetected() {
        val contents = bytes(200 * 1024)
        val file = encryptToFile(contents)
        val intact = file.readBytes()
        // Header, sealed info, stream header, first segment, later segments, and the final byte.
        val offsets = listOf(0, 4, 5, 6, 8, 30, 80, 200, 70_000, 140_000, intact.size - 1)
        for (offset in offsets) {
            val damaged = intact.clone()
            damaged[offset] = (damaged[offset].toInt() xor 0x40).toByte()
            file.writeBytes(damaged)
            val reachedSameContents = try {
                cipher.decrypt(file).use { it.readBytes() }.contentEquals(contents)
            } catch (_: IOException) {
                false
            }
            // Bytes in the sealed info don't guard the stream, but they do make the info unreadable.
            val infoStillOpens = cipher.summarize(file) != null
            assertFalse("Flipped byte at $offset went unnoticed", reachedSameContents && infoStillOpens)
        }
    }

    @Test
    fun truncatedFiles_areDetected() {
        val file = encryptToFile(bytes(200 * 1024))
        val intact = file.readBytes()
        for (lost in listOf(1, 16, 1000, 65_536, 130_000, intact.size - 10)) {
            file.writeBytes(intact.copyOf(intact.size - lost))
            assertDecryptFails(file)
        }
        // Cut exactly on a segment boundary: the last surviving segment wasn't sealed as the last.
        val header = intact.size - cipher.summarize(file.also { it.writeBytes(intact) })!!.sizeBytes.toInt()
        file.writeBytes(intact.copyOf(header + 64 * 1024))
        assertDecryptFails(file)
    }

    @Test
    fun swappingTwoFiles_makesBothUnreadable() {
        val first = encryptToFile(bytes(500, seed = 1), name = "first.jpg")
        val second = encryptToFile(bytes(500, seed = 2), name = "second.jpg")
        val firstBytes = first.readBytes()
        first.writeBytes(second.readBytes())
        second.writeBytes(firstBytes)

        assertNull(cipher.summarize(first))
        assertNull(cipher.summarize(second))
        assertDecryptFails(first)
        assertDecryptFails(second)
    }

    @Test
    fun anotherVaultKey_readsNothing() {
        val file = encryptToFile(bytes(500))
        val other = VaultFileCipher(ByteArray(32) { 7 })

        assertNull(other.summarize(file))
        try {
            other.decrypt(file).use { it.readBytes() }
            fail("Expected decrypting to fail")
        } catch (_: IOException) {
        }
        assertNotEquals(cipher.storedName("holiday.jpg"), other.storedName("holiday.jpg"))
    }

    @Test
    fun plaintextFile_isRejectedRatherThanMisread() {
        val file = File(folder.root, "photo.jpg").apply { writeBytes(bytes(5000)) }
        assertNull(cipher.summarize(file))
        assertDecryptFails(file)
    }

    @Test
    fun seekableChannel_matchesTheFileAtAnyPosition() {
        val contents = bytes(300 * 1024)
        val file = encryptToFile(contents)
        cipher.openSeekable(file).use { channel ->
            assertEquals(contents.size.toLong(), channel.size())
            val random = Random(42)
            repeat(200) {
                val position = random.nextInt(contents.size)
                val length = minOf(1 + random.nextInt(100_000), contents.size - position)
                val buffer = ByteBuffer.allocate(length)
                channel.position(position.toLong())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                assertArrayEquals(
                    "At $position for $length",
                    contents.copyOfRange(position, position + length),
                    buffer.array()
                )
            }
        }
    }

    /**
     * A file written by this exact format. If this fails, the format changed and every vault in the
     * world just became unreadable: that needs a migration, not a new vector.
     */
    @Test
    fun aFileFromVersionOne_stillDecrypts() {
        val stored = cipher.storedName(GOLDEN_NAME)
        assertEquals(GOLDEN_STORED_NAME, stored)

        val file = File(folder.root, stored).apply { writeBytes(Base64.getDecoder().decode(GOLDEN_FILE)) }
        val summary = cipher.summarize(file)!!
        assertEquals(GOLDEN_NAME, summary.info.name)
        assertEquals(GOLDEN_ORIGINAL_PATH, summary.info.originalPath)
        assertEquals(GOLDEN_CONTENTS, cipher.decrypt(file).use { it.readBytes() }.toString(Charsets.UTF_8))
    }

    private companion object {
        val VAULT_KEY = ByteArray(32) { it.toByte() }

        const val GOLDEN_NAME = "golden.txt"
        const val GOLDEN_ORIGINAL_PATH = "/sdcard/Documents/golden.txt"
        const val GOLDEN_CONTENTS = "Vault format version one."
        const val GOLDEN_STORED_NAME = "cac1513da674d00dc8146ff5c75753b37daf13276dc40e0a2884e8b6395938d0.vault"
        const val GOLDEN_FILE =
            "S0JWRgEAR39R4Atls0VBi5JFZcD+LGqED8dNQeP6Yvb0+q8O3qo4RK8nCrr/cKm1ArRmI3Q4x+p7O2hToTQRBYJab2" +
            "UbHCRkiOapiBDUKDodjWL0MUETB00JaS927CQU2KiHMU+ReIUrU7Xnvp/Sotgej3AsNA/WAWQMo5syAsLKj73LPqHf" +
            "ukGTeC/+1CycysJ9j6eWeeJ5UPCbJctD"
    }
}
