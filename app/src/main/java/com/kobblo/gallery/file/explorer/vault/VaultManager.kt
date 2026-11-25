package com.kobblo.gallery.file.explorer.vault

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import coil.imageLoader
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID

internal enum class VaultNotice { NoKeyYet, NotEnoughSpace, ShortPassword }

/**
 * The Vault, for everything outside this package and for its own screens: setting one up, opening
 * and locking it, and putting files in and taking them out.
 *
 * It owns the unlocked session and decides what each request means for it. The work itself is done
 * by what it hands out to: `VaultKeyring` for the secrets, `VaultStore` for sealed files,
 * [VaultFileMoves] for carrying files across the edge and [VaultListing] for showing what's inside.
 */
object VaultManager {
    private const val DECRYPTED_COPIES_DIRECTORY = "vault_open"
    private const val LEGACY_ORIGINS_FILE = "vault_metadata.json"
    private const val NO_MEDIA = ".nomedia"

    // Password-protected PDFs get a second copy with their password removed, made by the viewers.
    private val PDF_VIEWER_COPY = Regex("""(pdf_)?decrypted_\d+\.pdf""")

    /**
     * Only applies to choosing a password. Vaults with a shorter one keep opening, and get a nudge.
     * This password is what an offline attacker with a copy of the vault's files has to guess.
     */
    const val MIN_PASSWORD_LENGTH = 8

    // The unlocked session. The key lives only in memory, and only from unlock until lock.
    private val sessionLock = Any()
    private var vaultKey: ByteArray? = null
    @Volatile private var store: VaultStore? = null
    @Volatile private var isSessionOpen = false
    @Volatile private var isPasswordShort = false
    @Volatile private var failedToEncrypt = 0

    internal fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(VaultSettings.FILE_NAME, Context.MODE_PRIVATE)

    internal fun keyring(context: Context) = VaultKeyring(SharedPreferencesKeyStore(prefs(context)))

    private fun settings(context: Context) = VaultSettings(prefs(context))

    // ── Setting up, and the two secrets ──────────────────────────────────────────────────────────

    fun isVaultSetup(context: Context): Boolean = settings(context).isSetUp && keyring(context).hasPassword

    fun getVaultPasswordHint(context: Context): String = settings(context).hint

    fun hasRecoveryKey(context: Context): Boolean = keyring(context).hasRecoveryKey

    // The calls below that take a password run a slow key derivation: call them off the main thread.

    /** Makes the vault and opens it. False if any part of it couldn't be saved, and then there's no vault. */
    fun setupVault(context: Context, password: String, hint: String, recoveryKey: String): Boolean {
        if (!keyring(context).create(password, recoveryKey)) return false
        if (!settings(context).markSetUp(hint, System.currentTimeMillis())) return false

        val folder = getVaultDirectory(context)
        folder.mkdirs()
        hideFromMediaScans(folder)
        return unlock(context, password)
    }

    /** Opens the vault for this session. False means the password is wrong. */
    fun unlock(context: Context, password: String): Boolean {
        return when (val result = keyring(context).unlock(password)) {
            is VaultUnlock.Opened -> {
                openSession(context, result.vaultKey)
                isPasswordShort = password.length < MIN_PASSWORD_LENGTH
                true
            }
            VaultUnlock.NoKeyYet -> {
                openSessionWithoutKey()
                true
            }
            VaultUnlock.WrongPassword -> false
        }
    }

    fun verifyPassword(context: Context, password: String): Boolean =
        keyring(context).verifyPassword(password)

    fun setRecoveryKey(context: Context, password: String, recoveryKey: String): Boolean =
        keyring(context).setRecoveryKey(password, recoveryKey)

    fun changePassword(context: Context, currentPassword: String, newPassword: String, hint: String): Boolean {
        if (!keyring(context).changePassword(currentPassword, newPassword)) return false
        settings(context).hint = hint
        isPasswordShort = false
        return true
    }

    internal fun checkRecoveryKey(context: Context, recoveryKey: String): RecoveryKeyCheck =
        keyring(context).checkRecoveryKey(recoveryKey)

    fun resetPassword(context: Context, recoveryKey: String, newPassword: String, hint: String): Boolean {
        val keyring = keyring(context)
        if (!keyring.resetPassword(recoveryKey, newPassword)) return false
        settings(context).hint = hint
        keyring.unlockWithRecoveryKey(recoveryKey)?.let { openSession(context, it) }
        return true
    }

    // ── Session ──────────────────────────────────────────────────────────────────────────────────

    val isUnlocked: Boolean get() = isSessionOpen

    /** False when the vault is locked, or open without its key. Files can't be added either way. */
    val canEncrypt: Boolean get() = store != null

    /** Takes ownership of [key], which is wiped when the vault locks. */
    internal fun openSession(context: Context, key: ByteArray) {
        val opened = VaultStore(getVaultDirectory(context), key)
        opened.removeUnfinishedWrites()
        synchronized(sessionLock) {
            vaultKey?.fill(0)
            vaultKey = key
            store = opened
            isSessionOpen = true
            failedToEncrypt = 0
        }
    }

    /**
     * Opens a vault whose files haven't been encrypted, for someone who proved who they are but
     * can't produce the key. It reads the plaintext that's there and can't add to it.
     */
    internal fun openSessionWithoutKey() {
        synchronized(sessionLock) { isSessionOpen = true }
    }

    /** Runs [block] with the key, for sealing it under another secret. Don't keep the array. */
    internal fun <T> withVaultKey(block: (ByteArray) -> T): T? =
        synchronized(sessionLock) { vaultKey?.let(block) }

    internal fun fileStore(): VaultStore? = store

    /** Forgets the key and everything decrypted with it. Work already running finishes safely. */
    fun lock(context: Context) {
        synchronized(sessionLock) {
            vaultKey?.fill(0)
            vaultKey = null
            store = null
            isSessionOpen = false
            isPasswordShort = false
            failedToEncrypt = 0
        }
        context.imageLoader.memoryCache?.clear()
        purgeDecryptedCopies(context)
    }

    // ── Plaintext from older versions ────────────────────────────────────────────────────────────

    internal fun needsEncryption(context: Context): Boolean {
        return canEncrypt && (
            VaultStore.plaintextFiles(getVaultDirectory(context)).isNotEmpty() || legacyOrigins(context).exists
        )
    }

    /** Safe to interrupt at any point. It carries on from there the next time the vault opens. */
    internal fun encryptPendingFiles(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ) {
        val store = store ?: return
        val originOf = legacyOrigins(context).lookup()
        val result = store.migrate(
            originalPathOf = { name -> originOf(name)?.ifBlank { null } },
            isCancelled = isCancelled,
            onProgress = onProgress
        )
        failedToEncrypt = result.failed
        forgetOriginsOfDepartedFiles(context)
        forgetVaultPaths(context)
    }

    internal fun currentNotice(context: Context): VaultNotice? = when {
        !canEncrypt && VaultStore.plaintextFiles(getVaultDirectory(context)).isNotEmpty() -> VaultNotice.NoKeyYet
        failedToEncrypt > 0 -> VaultNotice.NotEnoughSpace
        isPasswordShort -> VaultNotice.ShortPassword
        else -> null
    }

    internal fun hasOnlyPlaintextFiles(context: Context): Boolean {
        val dir = getVaultDirectory(context)
        return VaultStore.encryptedFiles(dir).isEmpty() && VaultStore.plaintextFiles(dir).isNotEmpty()
    }

    // Older versions kept each plaintext file's original location in a file of its own, in the clear.
    private fun legacyOrigins(context: Context) = VaultLegacyOrigins(File(context.filesDir, LEGACY_ORIGINS_FILE))

    /** Once a file has been encrypted, restored or deleted, the note of where it came from goes too. */
    private fun forgetOriginsOfDepartedFiles(context: Context) {
        val origins = legacyOrigins(context)
        if (!origins.exists) return
        val folder = getVaultDirectory(context)
        origins.keepOnly { name -> File(folder, name).exists() }
    }

    // Older versions recorded where these files came from, and they showed up in recents and pins.
    private fun forgetVaultPaths(context: Context) {
        val vaultPath = getVaultDirectory(context).absolutePath + "/"
        val prefs = context.getSharedPreferences("kobblo_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        try {
            val recents = JSONArray(prefs.getString("recent_actions", "[]"))
            val kept = JSONArray()
            for (i in 0 until recents.length()) {
                val entry = recents.getJSONObject(i)
                if (!entry.optString("path").startsWith(vaultPath)) kept.put(entry)
            }
            editor.putString("recent_actions", kept.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val pinned = prefs.getStringSet("pinned_files", emptySet()).orEmpty()
        editor.putStringSet("pinned_files", pinned.filterNot { it.startsWith(vaultPath) }.toSet())
        editor.apply()
    }

    // ── What's inside ────────────────────────────────────────────────────────────────────────────

    fun getVaultDirectory(context: Context): File {
        val dir = File(context.filesDir, VaultPaths.FolderName)
        if (!dir.exists()) {
            dir.mkdirs()
            hideFromMediaScans(dir)
        }
        return dir
    }

    /** The vault's folder is private to the app already. The marker is for anything that gets to look anyway. */
    private fun hideFromMediaScans(folder: File) {
        try {
            File(folder, NO_MEDIA).createNewFile()
        } catch (_: Exception) {
        }
    }

    /** Coil asks this about every file it loads, so it doesn't create anything on the way. */
    internal fun isVaultCiphertext(context: Context, file: File): Boolean =
        file.parentFile == VaultStore.encryptedDirectory(File(context.filesDir, VaultPaths.FolderName))

    /** Reads every sealed file's name, which takes the key and a moment: call it off the main thread. */
    fun getVaultFiles(context: Context): List<FileItem> {
        val folder = getVaultDirectory(context)
        val dates = DisplayDates.date()
        return VaultListing.list(
            folder = folder,
            store = store,
            unreadableName = context.getString(R.string.vault_unreadable_file),
            sizeLabel = { bytes -> FileSizes.label(bytes) },
            dateLabel = { millis -> dates.format(Date(millis)) }
        )
    }

    // ── Files going in ───────────────────────────────────────────────────────────────────────────

    /**
     * Seals a copy of each document another app handed over, and returns how many made it. The
     * documents stay where they are: they aren't this app's to delete.
     */
    fun importFiles(context: Context, uris: List<Uri>, shouldStop: () -> Boolean = { false }): Int {
        val store = store ?: return 0
        var added = 0
        for (uri in uris) {
            if (shouldStop()) break
            val name = VaultFileNames.safe(displayName(context, uri) ?: "imported_file_${System.currentTimeMillis()}")
            if (VaultFileMoves.copyIn(store, name, shouldStop) { context.contentResolver.openInputStream(uri) }) added++
        }
        return added
    }

    /**
     * Moves the files at [filePaths] off shared storage and into the vault, and returns how many are
     * now sealed. Each original is deleted only once its sealed copy has been proven: see [VaultFileMoves].
     */
    fun importFilesFromPaths(context: Context, filePaths: List<String>, shouldStop: () -> Boolean = { false }): Int {
        val store = store ?: return 0
        val moved = VaultFileMoves.moveIn(store, filePaths, shouldStop)
        // Galleries and the system's own index would otherwise go on listing what just left.
        tellMediaIndex(context, moved.removedPaths)
        return moved.sealed
    }

    /** What a picked document calls itself, or failing that the end of its address. */
    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
                context.contentResolver.query(uri, columns, null, null, null)?.use { row ->
                    val column = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    // A provider that lists the document settles it, even when the name it gives is empty.
                    if (column != -1 && row.moveToFirst()) return row.getString(column)
                }
            } catch (_: Exception) {
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    // ── Files coming out ─────────────────────────────────────────────────────────────────────────

    /**
     * Puts a file back where it was moved in from, or in Downloads when that isn't known or can't be
     * reached, and removes it from the vault. False means it's still in the vault, untouched.
     */
    fun restoreFile(context: Context, fileItem: FileItem): Boolean {
        val inVault = File(fileItem.path)
        return try {
            if (inVault.exists()) {
                takeOut(context, inVault, shownName = fileItem.name)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Throws when the file can't be decrypted or written out, and then it hasn't left the vault. */
    private fun takeOut(context: Context, inVault: File, shownName: String) {
        val target = VaultFileMoves.landingPlace(originalPathOf(context, inVault), shownName, ::downloadsDirectory)
        VaultFileMoves.moveOut(store, inVault, target)
        forgetOriginsOfDepartedFiles(context)
        tellMediaIndex(context, listOf(target.absolutePath))
    }

    fun restoreFiles(context: Context, fileItems: List<FileItem>): Int = fileItems.count { restoreFile(context, it) }

    /** Leaves a plain copy in Downloads. The vault keeps its own. */
    internal fun exportFile(fileItem: FileItem, isCancelled: () -> Boolean = { false }): Boolean {
        val inVault = File(fileItem.path)
        return try {
            if (inVault.exists()) {
                val target = VaultFileNames.free(downloadsDirectory(), VaultFileNames.safe(fileItem.name))
                VaultFileMoves.writePlain(store, inVault, target, isCancelled = isCancelled)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun deleteFile(fileItem: FileItem): Boolean = try {
        File(fileItem.path).let { it.exists() && it.delete() }
    } catch (_: Exception) {
        false
    }

    fun deleteFile(context: Context, fileItem: FileItem): Boolean = try {
        deleteFile(fileItem).also { deleted -> if (deleted) forgetOriginsOfDepartedFiles(context) }
    } catch (_: Exception) {
        false
    }

    /** A sealed file says where it came from itself. One still in plaintext had it written down beside the vault. */
    private fun originalPathOf(context: Context, vaultFile: File): String? =
        if (VaultStore.isEncrypted(vaultFile)) {
            store?.info(vaultFile)?.originalPath
        } else {
            legacyOrigins(context).lookup()(vaultFile.name)
        }

    private fun downloadsDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        return downloads
    }

    /** Has the system look at [paths] again, so a file that came or went shows or stops showing in galleries. */
    private fun tellMediaIndex(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }

    // ── Decrypted copies, for what can only read a real file ─────────────────────────────────────

    /**
     * A plaintext copy of [fileItem] in private cache, for handing to a viewer or another app, or
     * null if it can't be made. Copies last until the viewer closes or the vault locks, and any a
     * crash leaves behind go the next time the app starts.
     */
    internal fun openDecryptedCopy(
        context: Context,
        fileItem: FileItem,
        isCancelled: () -> Boolean = { false }
    ): FileItem? {
        val copy = decryptToCache(context, File(fileItem.path), fileItem.name, isCancelled) ?: return null
        return fileItem.copy(path = copy.absolutePath)
    }

    internal fun decryptToCache(
        context: Context,
        source: File,
        name: String,
        isCancelled: () -> Boolean = { false }
    ): File? {
        val directory = File(File(context.cacheDir, DECRYPTED_COPIES_DIRECTORY), UUID.randomUUID().toString())
        return try {
            if (!directory.mkdirs()) throw IOException("Couldn't create a place for the copy")
            File(directory, VaultFileNames.safe(name)).also {
                VaultFileMoves.writePlain(store, source, it, isCancelled = isCancelled)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            directory.deleteRecursively()
            null
        }
    }

    fun isDecryptedCopy(path: String?): Boolean {
        if (path == null) return false
        return File(path).parentFile?.parentFile?.name == DECRYPTED_COPIES_DIRECTORY
    }

    fun discardDecryptedCopy(path: String?) {
        if (isDecryptedCopy(path)) File(path!!).parentFile?.deleteRecursively()
    }

    /** At startup nothing can still be using a copy, unless the activity was only recreated. */
    fun clearLeftoverCopies(context: Context) {
        if (!isSessionOpen) purgeDecryptedCopies(context)
    }

    private fun purgeDecryptedCopies(context: Context) {
        File(context.cacheDir, DECRYPTED_COPIES_DIRECTORY).deleteRecursively()
        context.cacheDir.listFiles { file -> PDF_VIEWER_COPY.matches(file.name) }?.forEach { it.delete() }
    }
}
