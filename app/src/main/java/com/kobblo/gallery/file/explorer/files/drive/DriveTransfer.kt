package com.kobblo.gallery.file.explorer.files.drive

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.kobblo.gallery.file.explorer.fileops.DriveRules
import com.kobblo.gallery.file.explorer.fileops.FailReason
import com.kobblo.gallery.file.explorer.fileops.FileOpException
import com.kobblo.gallery.file.explorer.fileops.SafeFiles
import com.kobblo.gallery.file.explorer.fileops.ShellCommand
import com.kobblo.gallery.file.explorer.fileops.TransferReport
import com.kobblo.gallery.file.explorer.files.DriveLocation
import com.kobblo.gallery.file.explorer.files.ElevatedShell
import com.kobblo.gallery.file.explorer.files.TransferTarget
import com.kobblo.gallery.file.explorer.files.mustHandle
import java.io.File
import java.io.FileOutputStream
import com.google.api.services.drive.model.File as DriveFile

/**
 * Copies and moves between the phone and Google Drive, and within Drive. A move only takes the original away
 * once Drive has said, or the phone has shown, that all of it arrived.
 *
 * @param connection the Drive of the account in use, or null when there's none.
 * @param cacheRoot the app's cache folder, for what has to be handed to the shell.
 */
internal class DriveTransfer(
    private val connection: () -> Drive?,
    private val shell: ElevatedShell,
    private val cacheRoot: () -> File
) {
    /** Throws when it can't be done, with nothing changed at the source. */
    fun carry(path: String, target: TransferTarget, move: Boolean): TransferReport {
        val drive = connection() ?: throw FileOpException(FailReason.NOT_POSSIBLE, "Google Drive isn't connected")
        val id = DriveLocation.idIn(path)
        return when (target) {
            is TransferTarget.OnPhone -> download(drive, requireNotNull(id) { "$path isn't on Drive" }, target.folder, move)
            is TransferTarget.OnDrive ->
                if (id == null) upload(drive, File(path), target.parentId, move) else withinDrive(drive, id, target.parentId, move)
        }
    }

    private fun withinDrive(drive: Drive, id: String, parentId: String, move: Boolean): TransferReport {
        if (move) {
            val parents = drive.files().get(id).setFields("parents").execute().parents
            drive.files().update(id, null)
                .setAddParents(parentId)
                .setRemoveParents(parents?.joinToString(","))
                .setFields("id, parents")
                .execute()
        } else {
            drive.files().copy(id, DriveFile().setParents(listOf(parentId))).execute()
        }
        return TransferReport(done = 1)
    }

    private fun upload(drive: Drive, source: File, parentId: String, move: Boolean): TransferReport {
        if (!source.isFile) throw FileOpException(if (source.exists()) FailReason.NOT_POSSIBLE else FailReason.MISSING, source.path)
        val entry = DriveFile().setName(source.name).setParents(listOf(parentId))
        val content = FileContent("application/octet-stream", source)
        val uploaded = drive.files().create(entry, content).setFields("id, size").execute()
        if (!move) return TransferReport(done = 1)
        if (!DriveRules.isComplete(uploaded.getSize(), source.length())) throw FileOpException(FailReason.CHECK_FAILED, source.path)
        if (!SafeFiles.deleteTree(source)) throw FileOpException(FailReason.DENIED, source.path)
        return TransferReport(done = 1, movedFrom = listOf(source.path))
    }

    /** It arrives under a temporary name, is checked, and only then does anything happen to the original. */
    private fun download(drive: Drive, id: String, folder: File, move: Boolean): TransferReport {
        val info = drive.files().get(id).setSupportsAllDrives(true).setFields("name, size, mimeType").execute()
        if (DriveRules.isNativeGoogleType(info.mimeType)) throw FileOpException(FailReason.NOT_POSSIBLE, info.name ?: id)
        val wanted = info.name ?: id.replace(":", "_")
        val place = if (shell.mustHandle(folder.path)) ThroughShell(folder) else Directly(folder)
        place.prepare()
        val part = SafeFiles.partFileIn(place.landing)
        val target = File(folder, SafeFiles.freeName(wanted) { place.isTaken(File(folder, it)) })
        try {
            receive(drive, id, info, part)
            if (!DriveRules.isComplete(info.getSize(), part.length())) throw FileOpException(FailReason.CHECK_FAILED, wanted)
            if (!place.settle(part, target)) throw FileOpException(FailReason.DENIED, target.path)
        } finally {
            if (part.exists()) part.delete()
        }
        if (move) drive.files().delete(id).setSupportsAllDrives(true).execute()
        return TransferReport(done = 1, renamed = if (target.name == wanted) 0 else 1, outputs = listOf(target.path))
    }

    /** Drive has nothing to send for an empty file, and answers with an error when asked to. */
    private fun receive(drive: Drive, id: String, info: DriveFile, part: File) {
        FileOutputStream(part).use { out ->
            if ((info.getSize() ?: 0L) > 0L) drive.files().get(id).setSupportsAllDrives(true).executeMediaAndDownloadTo(out)
            out.fd.sync()
        }
    }

    /** A folder a download goes into. It [landing]s somewhere the app can write, and then settles in the folder. */
    private interface Place {
        val landing: File
        fun prepare()
        fun isTaken(file: File): Boolean
        fun settle(part: File, target: File): Boolean
    }

    private class Directly(private val folder: File) : Place {
        override val landing get() = folder

        override fun prepare() {
            if (!folder.isDirectory && !folder.mkdirs()) throw FileOpException(FailReason.DENIED, folder.path)
        }

        override fun isTaken(file: File) = SafeFiles.existsAtAll(file)

        override fun settle(part: File, target: File) = part.renameTo(target)
    }

    private inner class ThroughShell(private val folder: File) : Place {
        override val landing get() = cacheRoot()

        override fun prepare() = Unit

        override fun isTaken(file: File) = shell.run(ShellCommand.exists(file.path))

        override fun settle(part: File, target: File) = shell.run(ShellCommand.copyTree(part.path, target.path))
    }
}
