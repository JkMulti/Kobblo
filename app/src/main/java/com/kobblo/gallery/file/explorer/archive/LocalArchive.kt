package com.kobblo.gallery.file.explorer.archive

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * An archive as a file, which is the only thing the ZIP and RAR libraries will read. A `file:` address or
 * a bare path is that file. Anything else, such as a document another app is showing, is copied into the
 * cache first, and the copy goes when this is closed.
 */
internal class LocalArchive private constructor(
    val file: File,
    private val isCopy: Boolean
) : Closeable {

    override fun close() {
        if (isCopy) file.delete()
    }

    companion object {
        /** @param extension the ending the copy's name gets, dot included. */
        suspend fun open(context: Context, source: Uri, extension: String): LocalArchive = withContext(Dispatchers.IO) {
            if (source.scheme == "file" || source.scheme == null) {
                LocalArchive(File(source.path ?: source.toString()), isCopy = false)
            } else {
                LocalArchive(copyToCache(context, source, extension), isCopy = true)
            }
        }

        private fun copyToCache(context: Context, source: Uri, extension: String): File {
            val copy = File.createTempFile("archive_", extension, context.cacheDir)
            try {
                val input = context.contentResolver.openInputStream(source) ?: throw Exception("Cannot read source URI")
                input.use { from -> copy.outputStream().use { to -> from.copyTo(to) } }
                return copy
            } catch (e: Exception) {
                copy.delete()
                throw e
            }
        }
    }
}

/**
 * Whether a library's error text has any of [words] in it. That is how a missing or wrong password is
 * told from any other failure: each library says so in words, and each in words of its own.
 */
internal fun String?.saysAnyOf(vararg words: String): Boolean =
    this != null && words.any { contains(it, ignoreCase = true) }
