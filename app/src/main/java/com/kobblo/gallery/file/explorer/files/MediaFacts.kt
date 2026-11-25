package com.kobblo.gallery.file.explorer.files

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import android.util.Log
import java.util.Locale

/** What only a look inside a picture, a video or a recording can say about it. */
internal class MediaFacts(val resolution: String? = null, val duration: String? = null) {
    companion object {
        val None = MediaFacts()

        fun ofPicture(path: String): MediaFacts {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            return MediaFacts(resolution = pixels(bounds.outWidth, bounds.outHeight))
        }

        /** For a video, or for music, which has no pictures and so no resolution. */
        fun ofRecording(path: String): MediaFacts {
            val (width, height, millis) = tagsOf(path, METADATA_KEY_VIDEO_WIDTH, METADATA_KEY_VIDEO_HEIGHT, METADATA_KEY_DURATION)
            return MediaFacts(
                resolution = if (width != null && height != null) "$width x $height" else null,
                duration = millis?.toLongOrNull()?.let(PlayingTime::padded)
            )
        }

        /** The tags asked for, in order. Those it doesn't have are null, and all are when it can't be opened. */
        private fun tagsOf(path: String, vararg keys: Int): List<String?> {
            val recording = MediaMetadataRetriever()
            val tags = try {
                recording.setDataSource(path)
                keys.map(recording::extractMetadata)
            } catch (e: Exception) {
                Log.w("MediaFacts", "Couldn't look inside a recording", e)
                keys.map { null }
            }
            closeQuietly(recording)
            return tags
        }

        /** Nothing to be done if it won't close, and what it said is still good. */
        private fun closeQuietly(recording: MediaMetadataRetriever) = try {
            recording.release()
        } catch (_: Exception) {
        }

        private fun pixels(width: Int, height: Int): String? = if (width > 0 && height > 0) "$width x $height" else null
    }
}

/** How long something plays, as text. */
internal object PlayingTime {
    /** For a tile in the gallery: "1:05" or "1:02:05". Null when the length isn't known. */
    fun short(millis: Long): String? {
        if (millis <= 0L) return null
        val (hours, minutes, seconds) = parts(millis)
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /** For details and Recents: "01:05" or "01:02:05", in the digits of the language in use. */
    fun padded(millis: Long): String {
        val (hours, minutes, seconds) = parts(millis)
        val ending = "%02d:%02d".format(minutes, seconds)
        return if (hours > 0) "%02d:".format(hours) + ending else ending
    }

    private fun parts(millis: Long): Triple<Long, Long, Long> {
        val seconds = millis / 1000
        return Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}
