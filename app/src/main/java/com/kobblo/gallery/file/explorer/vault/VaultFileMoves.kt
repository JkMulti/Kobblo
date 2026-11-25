package com.kobblo.gallery.file.explorer.vault

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Names for files crossing the Vault's edge. They arrive from content providers and from inside sealed
 * files and end up in paths, so nothing here trusts one to be a plain file name.
 */
internal object VaultFileNames {
    /** The part of [name] after its last slash, or "file" when that leaves nothing a file could be called. */
    fun safe(name: String): String {
        val last = name.substringAfterLast('/')
        return if (last.isBlank() || last == "." || last == "..") "file" else last
    }

    /**
     * [name] in [folder] if nothing is there yet, otherwise the first of `name_1`, `name_2` and so on
     * that's free. The number goes before the last extension: `photo_1.jpg`.
     */
    fun free(folder: File, name: String): File {
        val wanted = File(folder, name)
        if (!wanted.exists()) return wanted
        val dot = name.lastIndexOf('.')
        val stem = if (dot < 0) name else name.substring(0, dot)
        val extension = if (dot < 0) "" else name.substring(dot)
        return generateSequence(1) { it + 1 }
            .map { File(folder, "${stem}_$it$extension") }
            .first { !it.exists() }
    }
}

/** How a move into the Vault went: how many files it sealed, and which originals it then removed. */
internal class MovedIn(val sealed: Int, val removedPaths: List<String>)

/**
 * Carries files across the Vault's edge, both ways, by one rule: the copy being made is whole and
 * proven before the copy being given up is touched.
 *
 * Going in, [VaultStore.add] writes the sealed file, reads it back, compares it and only then gives it
 * its final name; the original is deleted after that returns. Going out, the plain file is complete
 * under its real name before the sealed one is deleted. A failure or a stop at any point leaves a file
 * where it started. The worst a crash can do is leave it in both places.
 *
 * Everything is streamed, so a file's size is limited by free space and not by memory, and nothing
 * depends on renaming from one storage volume to another: the other end can be on an SD card.
 */
internal object VaultFileMoves {
    private enum class Intake {
        /** Missing, or a folder: nothing to take. */
        NotAFile,

        /** Couldn't be sealed, or was stopped partway. The original hasn't been touched. */
        Failed,

        /** Sealed, but the original refused to be deleted and is still where it was. */
        SealedOnly,
        Moved
    }

    /**
     * Seals each of [paths] in turn and deletes its original. [shouldStop] is asked before every file
     * and throughout each one; a file it interrupts stays outside, whole, and nothing of it stays inside.
     */
    fun moveIn(store: VaultStore, paths: List<String>, shouldStop: () -> Boolean): MovedIn {
        var sealed = 0
        val removed = mutableListOf<String>()
        for (path in paths) {
            if (shouldStop()) break
            val source = File(path)
            when (moveIn(store, source, shouldStop)) {
                Intake.Moved -> {
                    sealed++
                    removed += source.absolutePath
                }
                Intake.SealedOnly -> sealed++
                Intake.NotAFile, Intake.Failed -> Unit
            }
        }
        return MovedIn(sealed, removed)
    }

    private fun moveIn(store: VaultStore, source: File, isCancelled: () -> Boolean): Intake = try {
        if (!source.exists() || source.isDirectory) {
            Intake.NotAFile
        } else {
            // This returns only once the sealed copy is on disk and has been read back and compared,
            // because after the delete below it is the only copy there is.
            source.inputStream().use { contents ->
                store.add(
                    name = VaultFileNames.safe(source.name),
                    source = contents,
                    originalPath = source.absolutePath,
                    isCancelled = isCancelled
                )
            }
            if (source.delete()) Intake.Moved else Intake.SealedOnly
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Intake.Failed
    }

    /**
     * Seals a copy of whatever [open] yields, under [name]. For files another app hands over: there's
     * no original here to delete, and no place to send it back to. False if nothing was added.
     */
    fun copyIn(store: VaultStore, name: String, isCancelled: () -> Boolean, open: () -> InputStream?): Boolean = try {
        open()?.use { contents -> store.add(name, contents, isCancelled = isCancelled) } != null
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Where a file leaving the Vault for good should land: beside where it came from when that's
     * known and its folder is there or can be made again, otherwise in [fallbackFolder]. It never
     * lands on top of something: see [VaultFileNames.free].
     */
    fun landingPlace(originalPath: String?, name: String, fallbackFolder: () -> File): File {
        val home = originalPath?.takeUnless { it.isBlank() }?.let(::File)
        val homeFolder = home?.parentFile?.also { if (!it.exists()) it.mkdirs() }?.takeIf { it.exists() }
        return if (home != null && homeFolder != null) {
            VaultFileNames.free(homeFolder, home.name)
        } else {
            VaultFileNames.free(fallbackFolder(), VaultFileNames.safe(name))
        }
    }

    /**
     * Takes [vaultFile] out to [target] and removes it from the Vault. The plain copy is flushed to the
     * disk before the Vault's copy goes, so a crash between the two can't take both. Fails with an
     * [IOException], and then the Vault's copy is still there.
     */
    fun moveOut(store: VaultStore?, vaultFile: File, target: File) {
        writePlain(store, vaultFile, target, durable = true)
        vaultFile.delete()
    }

    /**
     * Writes what [source] holds to [target] in the clear, leaving [source] as it is. A sealed file
     * needs [store], so a locked vault throws; a file an older version left unencrypted is copied as it
     * stands. Either way a failure is an [IOException] that leaves no partial [target] behind.
     */
    fun writePlain(
        store: VaultStore?,
        source: File,
        target: File,
        durable: Boolean = false,
        isCancelled: () -> Boolean = { false }
    ) {
        if (VaultStore.isEncrypted(source)) {
            val unlocked = store ?: throw IOException("Vault is locked")
            unlocked.decryptTo(source, target, isCancelled, durable)
            return
        }
        try {
            source.copyTo(target, overwrite = false)
        } catch (e: IOException) {
            target.delete()
            throw e
        }
    }
}
