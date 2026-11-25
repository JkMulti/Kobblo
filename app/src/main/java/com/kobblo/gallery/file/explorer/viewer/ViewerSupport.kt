package com.kobblo.gallery.file.explorer.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.FileProvider
import androidx.core.util.Consumer
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import java.io.File

/**
 * The list a viewer pages through when a screen wants something other than its current folder, such
 * as an album. Set it just before opening the file. It clears itself when the viewer closes.
 */
object ViewerQueue {
    var files: List<FileItem>? = null
}

/** Whether videos open in here or in another app, in the same place as the viewer preferences beside it. */
object ViewerPrefs {
    const val InApp = "In-app"
    const val DeviceDefault = "Device default"
    private const val Store = "kobblo_prefs"
    private const val KeyVideo = "viewer_video"
    private const val KeyInterval = "viewer_slideshow_interval"
    private const val KeySkipVideos = "viewer_slideshow_skip_videos"

    fun videoViewer(context: Context): String =
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).getString(KeyVideo, InApp) ?: InApp

    fun setVideoViewer(context: Context, value: String) {
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).edit().putString(KeyVideo, value).apply()
    }

    fun slideshowInterval(context: Context): Int =
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).getInt(KeyInterval, Slideshow.DefaultSeconds)

    fun slideshowSkipsVideos(context: Context): Boolean =
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).getBoolean(KeySkipVideos, true)

    fun setSlideshow(context: Context, seconds: Int, skipVideos: Boolean) {
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).edit()
            .putInt(KeyInterval, seconds)
            .putBoolean(KeySkipVideos, skipVideos)
            .apply()
    }
}

/** Selections for the system's media tables. */
object MediaStoreQueries {
    /**
     * Rows the system holds as plain files, but whose extension is a photo or video to us. Written out
     * as literals because the extensions come from our own list, never from the outside.
     */
    fun unrecognizedMedia(): String {
        val names = (FileKinds.Images + FileKinds.Videos).joinToString(" OR ") { "_data LIKE '%.$it'" }
        return "media_type = 0 AND ($names)"
    }
}

/** A slideshow's order, which is the only part of it that isn't just waiting. */
object Slideshow {
    val IntervalChoices = listOf(2, 3, 5, 10)
    const val DefaultSeconds = 3

    /** The next page to show, wrapping round. Null when there's nothing else that could play. */
    fun next(current: Int, count: Int, skipVideos: Boolean, isVideo: (Int) -> Boolean): Int? {
        if (count <= 1) return null
        for (step in 1 until count) {
            val candidate = (current + step) % count
            if (!skipVideos || !isVideo(candidate)) return candidate
        }
        return null
    }
}

/** An app that opened a file by `content://` gives us a stream, and nothing but a stream, to read. */
object ExternalMedia {
    /**
     * Turns an incoming stream into a viewer item, or null if it isn't a photo or video. Checks it can
     * really be read first, so a revoked or dead link fails here rather than as an empty viewer.
     * Throws whatever the provider throws when the stream can't be opened.
     */
    fun fromContentUri(context: Context, uri: Uri, intentMime: String?): FileItem? {
        val resolver = context.contentResolver
        val mime = try { resolver.getType(uri) } catch (_: Exception) { null } ?: intentMime
        var name: String? = null
        var size = 0L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameAt >= 0 && !cursor.isNull(nameAt)) name = cursor.getString(nameAt)
                    val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
                }
            }
        } catch (_: Exception) {
            // Plenty of providers don't answer this. The name only matters for what it shows.
        }
        val rawName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        // A PDF opens from the stream too, rather than waiting for a copy of however large it is. Its reader goes by
        // the name, and some apps offer a stream with no extension on it.
        val isPdf = mime == "application/pdf" || rawName.endsWith(".pdf", ignoreCase = true)
        val displayName = if (isPdf && !rawName.endsWith(".pdf", ignoreCase = true)) "$rawName.pdf" else rawName
        val type = when {
            mime?.startsWith("image/") == true -> "image"
            mime?.startsWith("video/") == true -> "video"
            else -> FileKinds.typeOf(displayName)
        }
        if (type != "image" && type != "video" && !isPdf) return null
        resolver.openAssetFileDescriptor(uri, "r")?.close()
            ?: throw java.io.FileNotFoundException("No stream for $uri")
        return FileItem(
            id = uri.hashCode() and 0x7FFFFFFF,
            path = uri.toString(),
            name = displayName,
            type = type,
            kind = External,
            size = if (size > 0) com.kobblo.gallery.file.explorer.data.core.FileSizes.label(size) else "",
            changed = "",
            owner = "",
            sizeBytes = size
        )
    }

    /** The `kind` of a file opened from another app, which shows only itself in the viewer. */
    const val External = "external"
}

val FileItem.isExternalStream: Boolean get() = path.startsWith("content://")
val FileItem.isOpenedFromOutside: Boolean get() = kind == ExternalMedia.External

/** Inside the Vault's private folder, where favorites, the Bin and the editor would each carry a copy or a path out. */
val FileItem.isInVault: Boolean get() = com.kobblo.gallery.file.explorer.vault.VaultPaths.isVaultPath(path)

/**
 * Whether this is someone's own file in shared storage. A cloud file reaches the viewer as a download in the app's
 * cache, and renaming or turning that would only change a copy that's about to be thrown away.
 */
val FileItem.isOnSharedStorage: Boolean
    get() = path.startsWith("/") && !path.startsWith("/data/") && !isInVault

/** A URI another app can read, whichever way this file got here. */
fun viewerUriFor(context: Context, file: FileItem): Uri =
    if (file.isExternalStream) Uri.parse(file.path)
    else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path))

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

fun openWithChooser(context: Context, file: FileItem) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(viewerUriFor(context, file), FileKinds.mimeTypeOf(file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        // This is how to get out of Kobblo, so it would be no use offering it.
        val self = android.content.ComponentName(context, "com.kobblo.gallery.file.explorer.MainActivity")
        context.startActivity(
            Intent.createChooser(view, context.getString(R.string.media_open_with)).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(self))
        )
    } catch (_: Exception) {
    }
}

/**
 * Hands a photo to the system's wallpaper cropper. Asking for "set as" in general would also offer
 * contacts and anything else that takes a picture, and on a device with nothing else, go right there.
 */
fun setAsWallpaper(context: Context, file: FileItem) {
    val uri = viewerUriFor(context, file)
    try {
        val crop = android.app.WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(uri)
        crop.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(crop)
        return
    } catch (_: Exception) {
        // Some devices have no cropper, and it refuses a file it can't read. The general route still works.
    }
    launch(
        context,
        Intent(Intent.ACTION_ATTACH_DATA).apply {
            setDataAndType(uri, FileKinds.mimeTypeOf(file.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("mimeType", FileKinds.mimeTypeOf(file.name))
        },
        context.getString(R.string.media_set_as)
    )
}

fun shareFile(context: Context, file: FileItem) {
    launch(
        context,
        Intent(Intent.ACTION_SEND).apply {
            type = FileKinds.mimeTypeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, viewerUriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        context.getString(R.string.share)
    )
}

private fun launch(context: Context, intent: Intent, title: String) {
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: Exception) {
    }
}

/** Whether the system offers Picture in Picture at all, which some devices turn off. */
fun supportsPictureInPicture(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** Enters Picture in Picture at the video's own shape, within the range the system allows. */
fun enterPictureInPicture(activity: Activity, aspect: Float): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val clamped = if (aspect > 0f) aspect.coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
    val params = android.app.PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational((clamped * 1000).toInt(), 1000))
        .build()
    return try {
        activity.enterPictureInPictureMode(params)
    } catch (_: Exception) {
        false
    }
}

/** True while this activity is the small floating window. */
@Composable
fun rememberIsInPictureInPicture(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? ComponentActivity }
    var isInPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { isInPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return isInPip
}

/** Swings the screen round for one video, whatever the phone's rotation lock says. */
fun toggleForcedOrientation(activity: Activity) {
    val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    activity.requestedOrientation = if (isLandscape) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
