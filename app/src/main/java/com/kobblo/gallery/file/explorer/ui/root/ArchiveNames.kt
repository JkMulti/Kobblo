package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.FileItem
import java.io.File

/**
 * A folder in [parent] to extract into, named [name]. A folder already there and empty will do. One with
 * something in it won't, as what's extracted would be lost among it, so a number is added until one is free.
 */
internal fun freeFolder(parent: File, name: String): File {
    val candidates = sequenceOf(File(parent, name)) + generateSequence(1) { it + 1 }.map { File(parent, "$name ($it)") }
    return candidates.first { it.list().isNullOrEmpty() }
}

/** What an archive of [files] is called unless it's given another name. */
internal fun archiveNameFor(files: List<FileItem>): String {
    val first = files.first()
    val name = if (first.type == "directory") first.name else first.name.substringBeforeLast('.')
    return if (files.size == 1) "$name.zip" else "${name}_archive.zip"
}

/** An archive that has just been made, as an item to open. No listing has it yet. */
internal fun madeArchive(archive: File, sizeLabel: String): FileItem = FileItem(
    id = archive.absolutePath.hashCode(), path = archive.absolutePath, name = archive.name,
    type = if (looksLikeRar(archive.name)) "rar" else "zip",
    kind = "Archive", owner = "Me", changed = "",
    size = sizeLabel, sizeBytes = archive.length(), lastModified = archive.lastModified()
)

/** Whether an archive's name says it's a RAR. Its bytes have the last word. */
internal fun looksLikeRar(name: String): Boolean = name.lowercase().endsWith(".rar")
