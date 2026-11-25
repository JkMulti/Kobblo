package com.kobblo.gallery.file.explorer.vault

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Locale

/**
 * What's in the Vault, as rows for a list. Sealed files give up their real names and sizes only to
 * the store that holds the key; without it each one is listed under a stand-in name, at its size on
 * disk. Anything an older version left unencrypted is listed as it is.
 */
internal object VaultListing {
    private val Documents = setOf("pdf", "doc", "docx", "txt", "xlsx", "xls", "pptx", "ppt", "md")
    private val Archives = setOf("zip", "rar", "7z", "tar", "gz")

    /** Row ids count up from here, well clear of the ones the file browser hands out. */
    private const val FirstId = 900000

    private class Entry(val file: File, val name: String, val sizeBytes: Long, val isFolder: Boolean) {
        val lastModified = file.lastModified()
    }

    /** The `FileItem.type` for a file called [name]: what picks its icon, its filter and how it opens. */
    fun typeOf(name: String, isFolder: Boolean = false): String {
        if (isFolder) return "folder"
        return when (name.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
            in FileKinds.Images -> "image"
            in FileKinds.Videos -> "video"
            in FileKinds.Audio -> "audio"
            in Documents -> "doc"
            in Archives -> "archive"
            "apk" -> "apk"
            else -> "file"
        }
    }

    /**
     * Everything in [folder], newest first. [store] is null while the vault is locked or open without
     * its key, and then sealed files are listed as [unreadableName].
     */
    fun list(
        folder: File,
        store: VaultStore?,
        unreadableName: String,
        sizeLabel: (Long) -> String,
        dateLabel: (Long) -> String
    ): List<FileItem> {
        val unencrypted = folder.listFiles().orEmpty()
            .filterNot { it.name.startsWith(".") }
            .map { Entry(it, it.name, it.length(), it.isDirectory) }
        val sealed = VaultStore.encryptedFiles(folder).map { file ->
            val summary = store?.summarize(file)
            Entry(file, summary?.info?.name ?: unreadableName, summary?.sizeBytes ?: file.length(), isFolder = false)
        }
        return (unencrypted + sealed)
            .mapIndexed { position, entry -> entry.toRow(FirstId + position, sizeLabel, dateLabel) }
            .sortedByDescending { it.lastModified }
    }

    private fun Entry.toRow(id: Int, sizeLabel: (Long) -> String, dateLabel: (Long) -> String) = FileItem(
        id = id,
        path = file.absolutePath,
        name = name,
        type = typeOf(name, isFolder),
        kind = if (isFolder) "folder" else "file",
        size = sizeLabel(sizeBytes),
        changed = dateLabel(lastModified),
        owner = "",
        isPinned = false,
        // To the rest of the app a Vault file is a locked file, wherever one of these rows ends up.
        isLocked = true,
        isEmptyFolder = isFolder && file.list()?.isEmpty() == true,
        sizeBytes = sizeBytes,
        lastModified = lastModified
    )
}
