package com.kobblo.gallery.file.explorer.data.share

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import java.io.File

/**
 * A file another app handed to Kobblo, described as far as that app let it be. [sizeBytes] is zero when
 * the size isn't known, and [mimeType] is null when no type was declared anywhere.
 */
@Immutable
data class SharedIncomingFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?
) {
    /** The kind of file, which picks its icon: one of the values of `FileItem.type`. */
    val type: String
        get() = IncomingFileRules.typeOf(name, mimeType)

    val formattedSize: String
        get() = IncomingFileRules.sizeLabel(sizeBytes)

    companion object {
        private const val Tag = "SharedIncomingFile"

        /**
         * Finds out what it can about the file at [uri]. Nothing a sender leaves out stops this: a missing
         * type falls back to [fallbackMime], the type the share itself was sent with, and a missing name is
         * made from the address.
         */
        fun fromUri(context: Context, uri: Uri, fallbackMime: String? = null): SharedIncomingFile {
            val resolver = context.contentResolver
            val mime = try {
                resolver.getType(uri) ?: fallbackMime
            } catch (_: Exception) {
                fallbackMime
            }
            val told = Told()
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> askProvider(resolver, uri, told)
                ContentResolver.SCHEME_FILE -> uri.path?.let { lookAt(File(it), told) }
            }
            val name = told.name?.takeUnless { it.isBlank() }
                ?: IncomingFileRules.nameFromAddress(uri.lastPathSegment, mime) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                }
            return SharedIncomingFile(uri, name, told.sizeBytes.coerceAtLeast(0L), mime)
        }

        /** What's been learned so far. Filled in piece by piece, so a provider that fails halfway keeps what it gave. */
        private class Told(var name: String? = null, var sizeBytes: Long = -1L)

        private fun askProvider(resolver: ContentResolver, uri: Uri, told: Told) {
            val wanted = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            try {
                resolver.query(uri, wanted, null, null, null)?.use { row ->
                    if (!row.moveToFirst()) return
                    told.name = row.textOf(OpenableColumns.DISPLAY_NAME)
                    row.longOf(OpenableColumns.SIZE)?.let { told.sizeBytes = it }
                }
            } catch (e: Exception) {
                // Providers refuse, time out, or answer with columns they don't have. The address still gives a name.
                Log.w(Tag, "A provider wouldn't describe a shared file: ${e.javaClass.simpleName}")
            }
        }

        private fun lookAt(file: File, told: Told) {
            told.name = file.name
            if (file.exists()) told.sizeBytes = file.length()
        }

        /** Null where the provider has no such column, which the contract lets it do. */
        private fun Cursor.textOf(column: String): String? {
            val index = getColumnIndex(column)
            return if (index < 0) null else getString(index)
        }

        private fun Cursor.longOf(column: String): Long? {
            val index = getColumnIndex(column)
            return if (index < 0 || isNull(index)) null else getLong(index)
        }
    }
}

/** The sheet that asks where shared files should be saved, and how the saving is going. */
@Immutable
data class IncomingSharePrompt(
    val files: List<SharedIncomingFile>,
    val selectedDestination: String,
    val isSaving: Boolean = false,
    val progress: Float? = null
)
