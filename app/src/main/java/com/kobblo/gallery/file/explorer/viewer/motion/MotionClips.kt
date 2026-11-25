package com.kobblo.gallery.file.explorer.viewer.motion

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.decoder.pageCountOf
import com.kobblo.gallery.file.explorer.viewer.metadata.FileByteSource
import com.kobblo.gallery.file.explorer.viewer.metadata.JpegSegments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The extras a photo can have beyond its picture, worked out when it comes into view. */
class PhotoExtras(
    val motion: MotionClip? = null,
    val isPanorama: Boolean = false,
    val pageCount: Int = 1,
    /** Set only when the photo's own XMP says it wraps a sphere. */
    val sphere: com.kobblo.gallery.file.explorer.viewer.sphere.SphereProjection? = null
) {
    companion object {
        val None = PhotoExtras()

        /** Past this it's a panorama whatever its tags say, since nothing else is shaped like that. */
        private const val PanoramaAspect = 2.4f

        suspend fun load(file: FileItem): PhotoExtras = withContext(Dispatchers.IO) {
            if (file.path.startsWith("content://")) return@withContext None
            val real = File(file.path)
            if (!real.exists()) return@withContext None
            try {
                var motion: MotionClip? = null
                var taggedPanorama = false
                var sphere: com.kobblo.gallery.file.explorer.viewer.sphere.SphereProjection? = null
                if (FileKinds.extensionOf(file.name) in setOf("jpg", "jpeg", "jpe", "jfif")) {
                    FileByteSource(real).use { source ->
                        motion = MotionPhoto.locate(source)
                        val xmp = JpegSegments.xmp(source)
                        taggedPanorama = xmp?.let(MotionPhoto::isPanoramaXmp) == true
                        sphere = xmp?.let(com.kobblo.gallery.file.explorer.viewer.sphere.SphereProjection::parse)
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(real.path, bounds)
                val isWide = bounds.outHeight > 0 && bounds.outWidth.toFloat() / bounds.outHeight >= PanoramaAspect
                PhotoExtras(motion, taggedPanorama || isWide, pageCountOf(real), sphere)
            } catch (_: Exception) {
                None
            }
        }
    }
}

object MotionClips {
    private const val MaxCachedClips = 16

    /** Copies the clip out where the player can read it. A photo's clip rarely changes, so it's kept. */
    suspend fun extractToCache(context: Context, file: FileItem, clip: MotionClip): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "motion_clips")
        directory.mkdirs()
        val key = "${file.path}|${File(file.path).lastModified()}"
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(directory, "$name.mp4")
        if (!target.exists() || target.length() != clip.length) {
            copyClip(File(file.path), clip, target)
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MaxCachedClips)?.forEach { it.delete() }
        }
        target
    }

    /** Saves the clip as its own video beside the photo, and tells the system it exists. */
    suspend fun saveBeside(context: Context, file: FileItem, clip: MotionClip): File = withContext(Dispatchers.IO) {
        val source = File(file.path)
        var target = File(source.parentFile, "${source.nameWithoutExtension}_motion.mp4")
        var count = 2
        while (target.exists()) {
            target = File(source.parentFile, "${source.nameWithoutExtension}_motion $count.mp4")
            count++
        }
        try {
            copyClip(source, clip, target)
        } catch (e: java.io.IOException) {
            // Half a video would look like a whole one in the gallery.
            target.delete()
            throw e
        }
        target.setLastModified(source.lastModified())
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.path), arrayOf("video/mp4"), null)
        target
    }

    private fun copyClip(source: File, clip: MotionClip, target: File) {
        RandomAccessFile(source, "r").use { input ->
            input.seek(clip.offset)
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var left = clip.length
                while (left > 0) {
                    val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    left -= read
                }
            }
        }
    }
}

/**
 * Plays a motion photo's clip in place, over the still. It stays invisible until its first frame is
 * ready, so the photo never blinks, and gives the screen back when the clip ends.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MotionClipOverlay(
    file: FileItem,
    clip: MotionClip,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnEnded by rememberUpdatedState(onEnded)
    val clipFile by produceState<File?>(null, file.path) {
        value = try {
            MotionClips.extractToCache(context, file, clip)
        } catch (_: Exception) {
            null
        }
    }
    var aspect by remember { mutableFloatStateOf(0f) }
    var hasFrame by remember { mutableStateOf(false) }
    val player = remember { ExoPlayer.Builder(context.applicationContext).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onRenderedFirstFrame() {
                hasFrame = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentOnEnded()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                currentOnEnded()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(clipFile) {
        val ready = clipFile ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(ready)))
        player.prepare()
        player.playWhenReady = true
    }

    // Laid out from the start, because some decoders only report a size once they have a surface.
    AndroidView(
        factory = { TextureView(it).also(player::setVideoTextureView) },
        onRelease = { player.clearVideoTextureView(it) },
        modifier = modifier
            .then(if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize())
            .graphicsLayer { alpha = if (hasFrame) 1f else 0f }
    )
}
