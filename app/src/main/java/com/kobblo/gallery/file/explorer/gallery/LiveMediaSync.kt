package com.kobblo.gallery.file.explorer.gallery

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

private const val QuietMillis = 700L
private const val MaxWaitMillis = 4_000L

/**
 * Keeps the feed in step with the device: anything that adds, changes or removes a photo or video
 * while this is on screen calls [onChange] once the burst settles. A camera shot or a download gives
 * a few notifications in a row, and an import can give thousands, so a long burst still refreshes
 * every few seconds rather than waiting for the end, with a longer gap the larger the library is.
 *
 * Leaving the tab stops this, and coming back reloads anyway.
 */
@Composable
internal fun LiveMediaSync(librarySize: Int, onChange: () -> Unit) {
    val context = LocalContext.current
    val currentOnChange by rememberUpdatedState(onChange)
    val currentSize by rememberUpdatedState(librarySize)

    DisposableEffect(context) {
        val handler = Handler(Looper.getMainLooper())
        var firstEvent = 0L
        var lastEvent = 0L
        var lastFire = 0L
        val fire = Runnable {
            firstEvent = 0L
            lastFire = android.os.SystemClock.uptimeMillis()
            currentOnChange()
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = android.os.SystemClock.uptimeMillis()
                if (firstEvent == 0L) firstEvent = now
                lastEvent = now
                handler.removeCallbacks(fire)
                handler.postAtTime(
                    fire,
                    burstFireTime(firstEvent, lastEvent, QuietMillis, MaxWaitMillis, lastFire, reloadGapMillis(currentSize))
                )
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        onDispose {
            resolver.unregisterContentObserver(observer)
            handler.removeCallbacks(fire)
        }
    }
}
