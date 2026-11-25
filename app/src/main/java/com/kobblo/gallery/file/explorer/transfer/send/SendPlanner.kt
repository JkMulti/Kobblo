package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.localsend.TransferFileItem
import com.kobblo.gallery.file.explorer.transfer.core.FolderWalker
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens what is being sent, wherever it lives. */
object TransferSources {
    fun open(context: Context, source: String): InputStream =
        if (source.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(source))
                ?: throw java.io.FileNotFoundException("Provider gave nothing")
        } else {
            FileInputStream(File(source))
        }
}

/**
 * Turns what was picked into the exact list of files to send. It happens when sending starts, so a
 * folder sends what's in it now rather than what was in it when it was picked.
 */
object SendPlanner {
    /** Marks a staged [FileItem] as an installed app, with its package name in `owner`. */
    const val APP_KIND = "app"

    data class Plan(
        val items: List<TransferFileItem>,
        /** Names of what can't be sent, such as a file that's since gone or lives in the cloud. */
        val skipped: List<String>
    ) {
        val totalBytes: Long get() = items.sumOf { it.size }
    }

    /**
     * With [mayCopy] off, a shared item that won't say its size counts as empty rather than being read
     * in full, and a split app is only added up. That's for showing a total, where a guess is better than
     * a long wait.
     */
    fun plan(context: Context, staged: List<FileItem>, mayCopy: Boolean = true): Plan {
        val items = ArrayList<TransferFileItem>()
        val skipped = ArrayList<String>()
        for (entry in staged) {
            when {
                entry.kind == Notes.KIND -> planNote(entry)?.let { items.add(it) } ?: skipped.add(entry.name)
                entry.kind == APP_KIND -> {
                    val parts = planApp(context, entry, mayCopy)
                    if (parts.isEmpty()) skipped.add(entry.name) else items.addAll(parts)
                }
                entry.path.startsWith("content://") -> {
                    planContent(context, entry, mayCopy)?.let { items.add(it) } ?: skipped.add(entry.name)
                }
                else -> {
                    val file = File(entry.path)
                    when {
                        !file.exists() || !file.canRead() -> skipped.add(entry.name)
                        file.isDirectory -> {
                            // A locked file inside stays behind: it has to be unlocked to be sent.
                            val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
                            val walked = FolderWalker.walk(file, skip = { locks.covers(it.path) })
                            walked.forEach { items.add(item(it.file, it.relativePath)) }
                        }
                        else -> items.add(item(file, entry.name.ifBlank { file.name }))
                    }
                }
            }
        }
        return Plan(items, skipped)
    }

    private fun planNote(entry: FileItem): TransferFileItem? {
        val file = File(entry.path)
        if (!file.isFile) return null
        val base = item(file, Notes.FILE_NAME).copy(fileType = "text/plain")
        return if (file.length() <= Notes.MAX_PREVIEW_BYTES) base.copy(preview = file.readText()) else base
    }

    /** What an installed app is on disk: one APK, or several if it was installed in split parts. */
    fun appParts(context: Context, packageName: String): List<File> = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        listOfNotNull(info.sourceDir).plus(info.splitSourceDirs.orEmpty()).map(::File).filter { it.canRead() }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * An app in split parts won't install from its base alone. They travel as one `.apks` bundle, the
     * same thing App Manager makes, so the other phone gets one thing to tap and installing it is one
     * tap. A computer gets one file instead of a folder of parts it can't do anything with.
     */
    private fun planApp(context: Context, entry: FileItem, mayCopy: Boolean): List<TransferFileItem> {
        val parts = appParts(context, entry.owner)
        if (parts.size <= 1) return parts.map { item(it, entry.name) }
        val bundleName = "${entry.name.removeSuffix(".apk")}.apks"
        if (!mayCopy) {
            return listOf(
                TransferFileItem(
                    fileId = UUID.randomUUID().toString(),
                    fileName = bundleName,
                    size = parts.sumOf { it.length() },
                    fileType = mimeOf(bundleName)
                )
            )
        }
        return try {
            listOf(item(bundleFor(context, entry.owner, parts), bundleName))
        } catch (e: java.io.IOException) {
            // No room to build it, most likely. Its parts together can still be installed by hand.
            val folder = entry.name.removeSuffix(".apk")
            parts.map { item(it, "$folder/${it.name}") }
        }
    }

    // Kept until the cache is cleared, so sending the same app to a second device doesn't build it again.
    private fun bundleFor(context: Context, packageName: String, parts: List<File>): File {
        val updated = runCatching { context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val target = File(dir, "$packageName-$updated.apks")
        if (target.isFile) return target
        val temp = File(dir, "${target.name}.tmp")
        try {
            writeBundle(parts, temp)
            if (!temp.renameTo(target)) throw java.io.IOException("Couldn't keep the bundle")
        } finally {
            temp.delete()
        }
        return target
    }

    /** Stored, not compressed. Sending is about to move these over a fast link, and starting is what's slow. */
    internal fun writeBundle(parts: List<File>, target: File) {
        ZipOutputStream(FileOutputStream(target).buffered(BUNDLE_BUFFER)).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            parts.forEach { part ->
                zip.putNextEntry(ZipEntry(part.name))
                FileInputStream(part).use { input -> input.copyTo(zip, BUNDLE_BUFFER) }
                zip.closeEntry()
            }
        }
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun item(file: File, name: String) = TransferFileItem(
        fileId = UUID.randomUUID().toString(),
        fileName = name,
        size = file.length(),
        fileType = mimeOf(name),
        localPath = file.absolutePath,
        modifiedMs = file.lastModified()
    )

    private fun planContent(context: Context, entry: FileItem, mayCopy: Boolean): TransferFileItem? = try {
        val uri = Uri.parse(entry.path)
        var name = entry.name
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        if (size > 0 || !mayCopy) {
            TransferFileItem(
                fileId = UUID.randomUUID().toString(),
                fileName = name,
                size = size.coerceAtLeast(0),
                fileType = context.contentResolver.getType(uri) ?: mimeOf(name),
                localPath = entry.path
            )
        } else {
            // The protocol needs a length up front, and only reading it all will tell.
            val copy = copyToCache(context, uri, name)
            item(copy, name)
        }
    } catch (e: Exception) {
        null
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): File {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString())
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(name)
        input.use { source -> FileOutputStream(target).use { source.copyTo(it) } }
        return target
    }

    /** Clears copies left from earlier sends. Only called when nothing is sending. */
    fun clearCache(context: Context) {
        File(context.cacheDir, STAGING_DIR).listFiles()?.forEach { it.delete() }
    }

    private const val STAGING_DIR = "send_staging"
    private const val BUNDLE_BUFFER = 256 * 1024
}
