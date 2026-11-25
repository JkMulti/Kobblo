package com.kobblo.gallery.file.explorer.files

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File

/**
 * Lists a folder on the phone or the memory card, by whichever route can see it.
 *
 * @param indexed a folder's rows out of the system's index, for a folder the app can't open.
 * @param denied makes the error for a folder that no route can see.
 */
internal class FolderLists(
    private val storage: PhoneStorage,
    private val rows: LocalRows,
    private val shell: ShellFolders,
    private val indexed: (path: String) -> List<FileItem>,
    private val denied: (path: String) -> Exception
) {
    fun list(location: String, marks: PathMarks): List<FileItem> {
        val path = if (Places.isOpenableCard(location)) storage.cardPath ?: location else location
        // The app would be shown an empty folder here, so only the shell's word counts.
        if (isRestrictedPath(path)) return shell.list(path)
        filesIn(path)?.let { return rows.listed(it, marks) }
        return shell.list(path).ifEmpty { indexed(path) }.ifEmpty { throw denied(path) }
    }

    /** A removable storage is mounted in two places, and which of them an app may read depends on the phone. */
    private fun filesIn(path: String): Array<File>? =
        storage.folderAt(path).listFiles()
            ?: filesThroughOtherMount(path, RawMount, SharedMount)
            ?: filesThroughOtherMount(path, SharedMount, RawMount)

    private fun filesThroughOtherMount(path: String, mount: String, other: String): Array<File>? =
        if (path.startsWith(mount)) File(path.replace(mount, other)).listFiles() else null

    private companion object {
        const val RawMount = "/mnt/media_rw/"
        const val SharedMount = "/storage/"
    }
}

/** Lists folders through the elevated shell, which says what's there but not how big or how old it is. */
internal class ShellFolders(private val shell: ElevatedShell) {

    /** Empty when there's no such shell, or nothing it can see. */
    fun list(path: String): List<FileItem> = if (shell.isReady) ShellLines.rows(path, linesFor(path)) else emptyList()

    /** A listing that breaks off counts for nothing, since what's missing from it would look deleted. */
    private fun linesFor(path: String): List<String> = try {
        shell.linesOf(ShellCommand.listFolder(path)).orEmpty()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

/** What the shell prints for a folder: a name to a line, with a slash after the name of a folder. */
internal object ShellLines {
    private const val FolderEnd = "/"

    fun rows(folder: String, lines: List<String>): List<FileItem> {
        val ids = ListIds()
        val base = folder.removeSuffix("/")
        return lines.mapNotNull { line ->
            val name = line.removeSuffix(FolderEnd)
            if (line.isEmpty() || name == "." || name == "..") null
            else row("$base/$name", name, isFolder = line.endsWith(FolderEnd), ids)
        }
    }

    private fun row(path: String, name: String, isFolder: Boolean, ids: ListIds): FileItem {
        val type = if (isFolder) "folder" else FileKinds.typeOf(name)
        return FileItem(
            ids.of(path), path, name, type, listKindOf(type),
            size = if (isFolder) "" else FileSizes.label(0L),
            changed = "",
            owner = LocalOwner,
            isEmptyFolder = isFolder
        )
    }
}
