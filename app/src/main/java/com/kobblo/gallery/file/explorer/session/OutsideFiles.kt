package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.archive.ArchiveFormats
import com.kobblo.gallery.file.explorer.viewer.ExternalMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Files another app asks this one to open. */
internal class OutsideFiles(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val messages: Messages,
    private val music: MusicControls,
    private val opening: FileOpening
) {
    private val resolver: ContentResolver get() = app.contentResolver

    /** @param declaredType the type the other app says it is, which its own provider may know better. */
    fun open(uri: Uri, declaredType: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                openNow(uri, declaredType)
            } catch (e: SecurityException) {
                Log.e(Tag, "SecurityException opening file from intent: $uri", e)
                messages.post(words.text(R.string.app_open_access_denied))
            } catch (e: Exception) {
                Log.e(Tag, "Error opening file from intent: $uri", e)
                messages.post(words.text(R.string.app_open_failed))
            }
        }
    }

    private suspend fun openNow(uri: Uri, declaredType: String?) {
        val type = typeOf(uri, declaredType)
        val onPhone = fileAt(uri)
        if (onPhone != null) return show(onPhone, type)
        // A photo or video plays straight from the stream, so a long video opens at once instead of first
        // being copied into the cache.
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val stream = ExternalMedia.fromContentUri(app, uri, declaredType)
            if (stream != null) return live.update { it.copy(viewingFile = stream) }
        }
        show(copyOf(uri, type), type)
    }

    private fun typeOf(uri: Uri, declaredType: String?): String {
        val known = try {
            resolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return known ?: declaredType ?: "*/*"
    }

    private fun fileAt(uri: Uri): File? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return null
        return uri.path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }
    }

    private suspend fun show(file: File, type: String) {
        val row = localFileItem(file).copy(kind = ExternalMedia.External)
        withContext(Dispatchers.Main) {
            when {
                row.type == "audio" || type.startsWith("audio/") -> {
                    music.play(row)
                    music.showFullScreen(true)
                }
                ArchiveFormats.isArchiveName(row.name) -> opening.offer(row)
                else -> opening.view(row)
            }
        }
    }

    /** Anything else is read from a copy, since what opens it needs a file and a stream may be gone by then. */
    private fun copyOf(uri: Uri, type: String): File {
        val folder = File(app.cacheDir, "external_view").apply { mkdirs() }
        clearOldest(folder)
        val name = providedName(uri)?.takeIf { it.isNotBlank() } ?: nameFromAddress(uri, type)
        val copy = File(folder, name)
        val source = resolver.openInputStream(uri) ?: throw IOException("Nothing to read for $name")
        source.use { from -> copy.outputStream().use { to -> from.copyTo(to) } }
        return copy
    }

    private fun clearOldest(folder: File) {
        try {
            val copies = folder.listFiles().orEmpty()
            if (copies.size > MostCopies) copies.sortedBy { it.lastModified() }.take(MostCopies / 2).forEach { it.delete() }
        } catch (_: Exception) {
            // They're in the cache, so at worst the system clears them.
        }
    }

    private fun providedName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                val column = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column < 0 || !row.moveToFirst()) null else row.getString(column)
            }
        } catch (e: Exception) {
            Log.w(Tag, "Could not query OpenableColumns for $uri", e)
            null
        }
    }

    private fun nameFromAddress(uri: Uri, type: String): String {
        val last = uri.lastPathSegment?.substringAfterLast('/') ?: Unnamed
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
        val name = if ('.' in last || extension.isNullOrBlank()) last else "$last.$extension"
        return name.ifBlank { Unnamed }
    }

    private companion object {
        const val Tag = "OutsideFiles"
        const val Unnamed = "external_file"

        /** Past this many copies, the older half goes. */
        const val MostCopies = 20
    }
}
