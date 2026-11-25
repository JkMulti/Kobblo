package com.kobblo.gallery.file.explorer.vault

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the vault knows about a file. It's sealed into the file it describes. */
internal class VaultFileInfo(val name: String, val originalPath: String?)

internal class VaultFileSummary(val info: VaultFileInfo, val sizeBytes: Long)

/**
 * The on-disk form of one file:
 *
 *     "KBVF" | version (1) | info length (2) | info IV (12) | sealed info | Tink stream
 *
 * Contents use Tink's AES-GCM-HKDF streaming AEAD, which gives each file its own key and detects
 * tampering, truncation, and reordered segments. Nothing here reads a whole file into memory, and
 * the seekable form lets a video be read without decrypting all of it first.
 *
 * Files sit under a name derived from their real one, and both the info and the stream are bound to
 * that name, so swapping two files on disk makes both unreadable rather than quietly exchanging them.
 *
 * Changing any of this strands existing vaults: `VaultFileCipherTest` pins the format.
 */
internal class VaultFileCipher(vaultKey: ByteArray) {
    private val infoKey = VaultCrypto.hkdfSha256(vaultKey, KDF_SALT, INFO_KEY_LABEL)
    private val nameKey = VaultCrypto.hkdfSha256(vaultKey, KDF_SALT, NAME_KEY_LABEL)
    private val streaming = VaultCrypto.hkdfSha256(vaultKey, KDF_SALT, STREAM_KEY_LABEL).let { key ->
        // Tink keeps its own copy, so this one can go.
        AesGcmHkdfStreaming(key, "HmacSha256", KEY_BYTES, SEGMENT_BYTES, 0).also { key.fill(0) }
    }
    private val random = SecureRandom()

    /** The name a file with this real name is stored under. */
    fun storedName(name: String): String {
        val hmac = VaultCrypto.hmacSha256(nameKey, name.toByteArray(Charsets.UTF_8))
        return hmac.joinToString("", postfix = STORED_SUFFIX) { "%02x".format(it) }
    }

    /** Writes the header, then returns a stream for the contents. Closing it finishes the file and closes [output]. */
    fun encrypt(output: OutputStream, storedName: String, info: VaultFileInfo): OutputStream {
        try {
            output.write(sealInfo(info, storedName))
            return streaming.newEncryptingStream(output, boundName(storedName))
        } catch (e: GeneralSecurityException) {
            output.close()
            throw IOException("Couldn't start encrypting", e)
        } catch (e: IOException) {
            output.close()
            throw e
        }
    }

    /** The file's contents. Reading throws [IOException] as soon as it reaches anything damaged. */
    fun decrypt(file: File, storedName: String = file.name): InputStream {
        val input = FileInputStream(file)
        try {
            val infoLength = DataInputStream(input).readHeaderPrefix()
            var remaining = infoLength.toLong()
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw IOException("Damaged header")
                remaining -= skipped
            }
            return streaming.newDecryptingStream(input, boundName(storedName))
        } catch (e: GeneralSecurityException) {
            input.close()
            throw IOException("Couldn't start decrypting", e)
        } catch (e: IOException) {
            input.close()
            throw e
        }
    }

    /** The file's contents with random access, for readers that seek. */
    fun openSeekable(file: File): SeekableByteChannel {
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        try {
            val infoLength = FileInputStream(file).use { DataInputStream(it).readHeaderPrefix() }
            val offset = (HEADER_PREFIX_BYTES + infoLength).toLong()
            return streaming.newSeekableDecryptingChannel(OffsetChannel(channel, offset), boundName(file.name))
        } catch (e: GeneralSecurityException) {
            channel.close()
            throw IOException("Couldn't start decrypting", e)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
    }

    /** The name and original location, or null if this key can't read them. */
    fun summarize(file: File): VaultFileSummary? {
        return try {
            FileInputStream(file).use { input ->
                val data = DataInputStream(input)
                val sealedInfo = ByteArray(data.readHeaderPrefix())
                data.readFully(sealedInfo)
                val info = openInfo(sealedInfo, file.name)
                VaultFileSummary(info, plaintextSize(file.length() - HEADER_PREFIX_BYTES - sealedInfo.size))
            }
        } catch (_: IOException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun DataInputStream.readHeaderPrefix(): Int {
        val magic = ByteArray(MAGIC.size)
        readFully(magic)
        if (!magic.contentEquals(MAGIC) || readByte() != VERSION) throw IOException("Not a vault file")
        return readUnsignedShort()
    }

    private fun plaintextSize(streamBytes: Long): Long {
        val segments = (streamBytes + SEGMENT_BYTES - 1) / SEGMENT_BYTES
        return (streamBytes - streaming.headerLength - segments * TAG_BYTES).coerceAtLeast(0)
    }

    private fun sealInfo(info: VaultFileInfo, storedName: String): ByteArray {
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(info.name)
                data.writeBoolean(info.originalPath != null)
                info.originalPath?.let(data::writeUTF)
            }
        }.toByteArray()

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = infoCipher(Cipher.ENCRYPT_MODE, iv, storedName)
        val sealed = iv + cipher.doFinal(plain)
        if (sealed.size > MAX_INFO_BYTES) throw IOException("File name too long")

        return ByteArrayOutputStream(HEADER_PREFIX_BYTES + sealed.size).also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION.toInt())
                data.writeShort(sealed.size)
                data.write(sealed)
            }
        }.toByteArray()
    }

    private fun openInfo(sealed: ByteArray, storedName: String): VaultFileInfo {
        if (sealed.size < IV_BYTES) throw IOException("Damaged header")
        val cipher = infoCipher(Cipher.DECRYPT_MODE, sealed.copyOf(IV_BYTES), storedName)
        val plain = cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        DataInputStream(ByteArrayInputStream(plain)).use { data ->
            val name = data.readUTF()
            val originalPath = if (data.readBoolean()) data.readUTF() else null
            return VaultFileInfo(name, originalPath)
        }
    }

    private fun infoCipher(mode: Int, iv: ByteArray, storedName: String): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(infoKey, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(boundName(storedName))
        return cipher
    }

    private fun boundName(storedName: String) = storedName.toByteArray(Charsets.UTF_8)

    /** Shows Tink a channel that starts after the header. */
    private class OffsetChannel(
        private val channel: SeekableByteChannel,
        private val offset: Long
    ) : SeekableByteChannel {
        override fun read(destination: ByteBuffer): Int = channel.read(destination)
        override fun position(): Long = channel.position() - offset
        override fun position(newPosition: Long): SeekableByteChannel {
            channel.position(newPosition + offset)
            return this
        }
        override fun size(): Long = channel.size() - offset
        override fun isOpen(): Boolean = channel.isOpen
        override fun close() = channel.close()
        override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    }

    private companion object {
        val MAGIC = "KBVF".toByteArray(Charsets.US_ASCII)
        const val VERSION: Byte = 1
        const val HEADER_PREFIX_BYTES = 4 + 1 + 2
        const val MAX_INFO_BYTES = 0xFFFF
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val KEY_BYTES = 32
        const val SEGMENT_BYTES = 64 * 1024
        const val STORED_SUFFIX = ".vault"

        // RFC 5869: with no salt to hand, the salt is a string of zeros as long as the hash.
        val KDF_SALT = ByteArray(32)
        val STREAM_KEY_LABEL = "kobblo.vault.v1.file-contents".toByteArray(Charsets.US_ASCII)
        val INFO_KEY_LABEL = "kobblo.vault.v1.file-info".toByteArray(Charsets.US_ASCII)
        val NAME_KEY_LABEL = "kobblo.vault.v1.file-names".toByteArray(Charsets.US_ASCII)
    }
}
