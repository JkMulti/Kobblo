package com.kobblo.gallery.file.explorer.viewer.video

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Locale

/** How big subtitles are, in scaled pixels, so that the phone's font size setting still counts. */
enum class SubtitleSize(val sp: Float) { Small(14f), Medium(18f), Large(24f) }

/** How far up from the bottom of the picture subtitles sit. */
enum class SubtitlePosition(val liftDp: Int) { Low(0), Middle(48), High(112) }

/** How the screen turns while a video is showing. */
internal enum class VideoRotation { Automatic, MatchVideo, Locked }

/** The player's few lasting choices. */
internal object VideoPrefs {
    private const val Store = "viewer_video_prefs"
    private const val KeySize = "subtitle_size"
    private const val KeyPosition = "subtitle_position"
    private const val KeyRotation = "rotation"

    private fun prefs(context: Context) = context.getSharedPreferences(Store, Context.MODE_PRIVATE)

    fun subtitleSize(context: Context): SubtitleSize =
        SubtitleSize.entries.firstOrNull { it.name == prefs(context).getString(KeySize, null) } ?: SubtitleSize.Medium

    fun setSubtitleSize(context: Context, size: SubtitleSize) {
        prefs(context).edit().putString(KeySize, size.name).apply()
    }

    fun subtitlePosition(context: Context): SubtitlePosition =
        SubtitlePosition.entries.firstOrNull { it.name == prefs(context).getString(KeyPosition, null) } ?: SubtitlePosition.Low

    fun setSubtitlePosition(context: Context, position: SubtitlePosition) {
        prefs(context).edit().putString(KeyPosition, position.name).apply()
    }

    fun rotation(context: Context): VideoRotation =
        VideoRotation.entries.firstOrNull { it.name == prefs(context).getString(KeyRotation, null) } ?: VideoRotation.Automatic

    fun setRotation(context: Context, rotation: VideoRotation) {
        prefs(context).edit().putString(KeyRotation, rotation.name).apply()
    }
}

/** Getting subtitle files into a form the player reads properly. */
internal object SubtitleFiles {
    internal class Picked(val file: File, val mimeType: String, val label: String)

    private const val MaxBytes = 8L * 1024 * 1024

    fun readable(context: Context, source: File, language: String?): File {
        val bytes = try {
            if (source.length() > MaxBytes) return source
            source.readBytes()
        } catch (_: Exception) {
            return source
        }
        val charset = SubtitleEncoding.needsConverting(bytes, language, Locale.getDefault().toLanguageTag()) ?: return source
        return try {
            val folder = File(context.cacheDir, Folder).apply { mkdirs() }
            val copy = File(folder, "${(source.path + source.lastModified()).hashCode().toUInt()}.${source.extension}")
            copy.writeText(String(bytes, charset), Charsets.UTF_8)
            copy
        } catch (_: Exception) {
            source
        }
    }

    /** A file from the system's picker may stop being readable once its permission lapses, so a copy is what's kept. */
    fun copyPicked(context: Context, uri: Uri): Picked? = try {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
        val extension = FileKinds.extensionOf(fileName)
        val mime = SidecarSubtitles.Mimes[extension]
        if (mime == null) {
            null
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < MaxBytes) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes == null) {
                null
            } else {
                val folder = File(context.cacheDir, Folder).apply { mkdirs() }
                val copy = File(folder, "picked_${uri.toString().hashCode().toUInt()}.$extension")
                val charset = SubtitleEncoding.needsConverting(bytes, null, Locale.getDefault().toLanguageTag())
                if (charset == null) copy.writeBytes(bytes) else copy.writeText(String(bytes, charset), Charsets.UTF_8)
                Picked(copy, mime, fileName.substringBeforeLast('.'))
            }
        }
    } catch (_: Exception) {
        null
    }

    private const val Folder = "subtitles"
}
