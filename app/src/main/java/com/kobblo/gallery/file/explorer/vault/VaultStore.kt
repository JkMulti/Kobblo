package com.kobblo.gallery.file.explorer.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** The moments in writing a file that a crash could fall between. Tests stop the store at each one. */
internal enum class VaultCheckpoint { Writing, Written, Verified, Committed, OriginalRemoved }

internal class VaultCancelledException : IOException("Cancelled")

internal class VaultMigrationResult(val encrypted: Int, val failed: Int, val wasCancelled: Boolean)

/**
 * The vault's files, given its key.
 *
 *     vault_files/                       Plaintext left by older versions, until it's migrated
 *     vault_files/.encrypted/<hmac>.vault  Finished files
 *     vault_files/.encrypted/.<uuid>.tmp   A write in progress. Never read, deleted on the next unlock.
 *
 * The directory says which is which, so nothing has to guess from a file's contents. A file reaches
 * its final name in one atomic rename, only after it's on disk and has been read back and compared.
 * Only then is the original deleted. A crash at any point leaves the original, the finished file, or
 * both, and the next run works out which: see [migrate].
 *
 * Failures are [IOException]s, which clean up after themselves. Anything else, like the crashes tests
 * simulate, leaves the disk exactly as a killed process would.
 */
internal class VaultStore(
    private val directory: File,
    vaultKey: ByteArray,
    private val checkpoint: (VaultCheckpoint) -> Unit = {}
) {
    private val cipher = VaultFileCipher(vaultKey)
    private val encryptedDirectory = encryptedDirectory(directory)
    private val writeLock = Any()

    fun summarize(file: File): VaultFileSummary? = cipher.summarize(file)

    fun info(file: File): VaultFileInfo? = cipher.summarize(file)?.info

    fun open(file: File): InputStream = cipher.decrypt(file)

    fun openSeekable(file: File): SeekableByteChannel = cipher.openSeekable(file)

    /** Encrypts [source] into the vault under [name], or a variation of it if that's taken. */
    fun add(
        name: String,
        source: InputStream,
        originalPath: String? = null,
        isCancelled: () -> Boolean = { false }
    ): File = synchronized(writeLock) {
        ensureDirectory()
        val uniqueName = uniqueName(name)
        val storedName = cipher.storedName(uniqueName)
        val temp = writeVerified(storedName, VaultFileInfo(uniqueName, originalPath), source, isCancelled)
        commit(temp, storedName)
    }

    /**
     * Encrypts everything older versions left in plaintext, smallest first, so an interruption leaves
     * as much done as it can. One file failing, usually for lack of space, doesn't stop the others.
     * Run again after any interruption: each file is either untouched or finished.
     */
    fun migrate(
        originalPathOf: (String) -> String? = { null },
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): VaultMigrationResult {
        removeUnfinishedWrites()
        val pending = plaintextFiles(directory).sortedBy { it.length() }
        var encrypted = 0
        var failed = 0
        pending.forEachIndexed { index, plain ->
            onProgress(index, pending.size)
            try {
                synchronized(writeLock) { migrateFile(plain, originalPathOf(plain.name), isCancelled) }
                encrypted++
            } catch (_: VaultCancelledException) {
                return VaultMigrationResult(encrypted, failed, wasCancelled = true)
            } catch (_: IOException) {
                failed++
            }
        }
        onProgress(pending.size, pending.size)
        return VaultMigrationResult(encrypted, failed, wasCancelled = false)
    }

    /**
     * Decrypts to [target], which appears only once it's complete. Pass [durable] when the vault's
     * copy is about to be deleted, so a crash can't take both. Waiting for the disk costs real time
     * on a large file, and a throwaway copy has no need of it.
     */
    fun decryptTo(file: File, target: File, isCancelled: () -> Boolean = { false }, durable: Boolean = false) {
        val partial = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            cipher.decrypt(file).use { input ->
                val output = if (durable) SyncedFileOutput(partial) else FileOutputStream(partial)
                output.use { input.copyWithChecks(it, isCancelled) }
            }
            if (!partial.renameTo(target)) throw IOException("Couldn't finish writing ${target.name}")
        } catch (e: IOException) {
            partial.delete()
            throw e
        }
    }

    fun removeUnfinishedWrites() = synchronized(writeLock) {
        encryptedDirectory.listFiles { file -> file.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        Unit
    }

    private fun migrateFile(plain: File, originalPath: String?, isCancelled: () -> Boolean) {
        ensureDirectory()
        val existing = File(encryptedDirectory, cipher.storedName(plain.name))
        if (existing.exists() && hasSameContents(existing, plain)) {
            // An earlier run stopped between finishing this file and deleting its original.
            removeOriginal(plain)
            return
        }
        // Different contents under the same name: keep both. It should never happen, but this is
        // the one step that deletes something and there's no second chance if it guesses wrong.
        val name = if (existing.exists()) uniqueName(plain.name) else plain.name
        val storedName = cipher.storedName(name)
        val temp = plain.inputStream().use { source ->
            writeVerified(storedName, VaultFileInfo(name, originalPath), source, isCancelled)
        }
        temp.setLastModified(plain.lastModified())
        commit(temp, storedName)
        removeOriginal(plain)
    }

    private fun writeVerified(
        storedName: String,
        info: VaultFileInfo,
        source: InputStream,
        isCancelled: () -> Boolean
    ): File {
        val temp = File(encryptedDirectory, ".${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            val digest = MessageDigest.getInstance(DIGEST)
            cipher.encrypt(SyncedFileOutput(temp), storedName, info).use { output ->
                source.copyWithChecks(output, isCancelled) { buffer, count ->
                    digest.update(buffer, 0, count)
                    checkpoint(VaultCheckpoint.Writing)
                }
            }
            checkpoint(VaultCheckpoint.Written)

            // This copy is about to become the only one, so prove it decrypts to the same bytes.
            val written = cipher.decrypt(temp, storedName).use(::sha256)
            if (!MessageDigest.isEqual(digest.digest(), written)) {
                throw IOException("Encrypted copy doesn't match the original")
            }
            checkpoint(VaultCheckpoint.Verified)
            return temp
        } catch (e: IOException) {
            temp.delete()
            throw e
        }
    }

    private fun commit(temp: File, storedName: String): File {
        val target = File(encryptedDirectory, storedName)
        // renameTo replaces silently, and every path here has already checked that the name is free.
        if (target.exists() || !temp.renameTo(target)) {
            temp.delete()
            throw IOException("Couldn't finish writing $storedName")
        }
        syncDirectory(encryptedDirectory)
        checkpoint(VaultCheckpoint.Committed)
        return target
    }

    private fun removeOriginal(plain: File) {
        if (!plain.delete() && plain.exists()) throw IOException("Couldn't remove ${plain.name}")
        syncDirectory(directory)
        checkpoint(VaultCheckpoint.OriginalRemoved)
    }

    private fun hasSameContents(encrypted: File, plain: File): Boolean {
        return try {
            val encryptedHash = cipher.decrypt(encrypted).use(::sha256)
            val plainHash = plain.inputStream().use(::sha256)
            MessageDigest.isEqual(encryptedHash, plainHash)
        } catch (_: IOException) {
            false
        }
    }

    // A plaintext file still waiting to migrate holds its name too, or migrating it later would
    // collide with whatever was just added.
    private fun uniqueName(name: String): String {
        var candidate = name
        var counter = 1
        while (isNameTaken(candidate)) {
            val base = name.substringBeforeLast('.')
            val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
            candidate = "${base}_$counter$ext"
            counter++
        }
        return candidate
    }

    private fun isNameTaken(name: String) =
        File(directory, name).exists() || File(encryptedDirectory, cipher.storedName(name)).exists()

    private fun ensureDirectory() {
        if (!encryptedDirectory.exists()) {
            if (!encryptedDirectory.mkdirs() && !encryptedDirectory.isDirectory) {
                throw IOException("Couldn't create the vault's directory")
            }
            syncDirectory(directory)
        }
    }

    private fun InputStream.copyWithChecks(
        output: OutputStream,
        isCancelled: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit = { _, _ -> }
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            if (isCancelled()) throw VaultCancelledException()
            val count = read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            onChunk(buffer, count)
        }
    }

    private fun sha256(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance(DIGEST)
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest()
            digest.update(buffer, 0, count)
        }
    }

    private fun syncDirectory(target: File) {
        // Makes the rename durable before the delete that follows it. Android's filesystems journal
        // the two in order anyway, so a filesystem that can't do this is no reason to stop.
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        }
    }

    /** Reaches the disk before it closes, so a rename after it can't outlive its contents. */
    private class SyncedFileOutput(file: File) : OutputStream() {
        private val output = FileOutputStream(file)

        override fun write(byte: Int) = output.write(byte)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
        override fun flush() = output.flush()

        override fun close() {
            output.use {
                it.flush()
                it.fd.sync()
            }
        }
    }

    companion object {
        private const val ENCRYPTED_DIRECTORY = ".encrypted"
        private const val NO_MEDIA = ".nomedia"
        private const val TEMP_SUFFIX = ".tmp"
        private const val DIGEST = "SHA-256"
        private const val BUFFER_BYTES = 256 * 1024

        fun encryptedDirectory(directory: File) = File(directory, ENCRYPTED_DIRECTORY)

        fun isEncrypted(file: File): Boolean = file.parentFile?.name == ENCRYPTED_DIRECTORY

        /** Files older versions left in the vault that haven't been encrypted yet. */
        fun plaintextFiles(directory: File): List<File> =
            directory.listFiles { file -> file.isFile && file.name != NO_MEDIA }?.toList().orEmpty()

        fun encryptedFiles(directory: File): List<File> =
            encryptedDirectory(directory).listFiles { file -> !file.name.endsWith(TEMP_SUFFIX) }?.toList().orEmpty()
    }
}
