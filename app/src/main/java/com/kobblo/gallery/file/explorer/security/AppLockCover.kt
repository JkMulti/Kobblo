package com.kobblo.gallery.file.explorer.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.DialogWindowProvider
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.picker.PickerTheme

/**
 * An activity's content with App Lock's lock drawn over it while the app is locked. Every activity
 * routes its content through here, so there's no way round the lock. Cancelling the lock leaves the app.
 */
@Composable
fun AppLockCover(activity: Activity, keepContent: Boolean = false, content: @Composable () -> Unit) {
    val locked = AppLockGate.locked
    Box(Modifier.fillMaxSize()) {
        // Usually the content is dropped while locked, so no sheet or dialog it opened (each its own window)
        // can float above the lock; the view model remembers where the person was. [keepContent] keeps it
        // alive under the lock instead, for screens like the editor whose unsaved work would be lost.
        if (!locked || keepContent) {
            val hidden = if (locked) Modifier.clearAndSetSemantics {} else Modifier
            Box(Modifier.fillMaxSize().then(hidden)) { content() }
        }
        if (locked) {
            // The picker's theme reads the saved look, so the lock looks the same over every activity.
            PickerTheme {
                val name = activity.getString(R.string.app_name_brand)
                LockScreen(
                    flow = LockFlow.Unlock,
                    lockedName = name,
                    heading = activity.getString(R.string.lock_unlock_named, name),
                    onDone = AppLockGate::unlock,
                    onCancel = activity::finish
                )
            }
        }
    }
}

/**
 * While [active], marks the current window secure: Android then refuses screenshots and screen recording
 * of it, and recent apps shows it blank. Several parts of a screen can ask at once (the Vault under the
 * lock, say); the window stays secure until the last of them lets go.
 */
@Composable
fun BlockCapture(active: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: LocalContext.current.findActivity()?.window
        ?: return
    DisposableEffect(active, window) {
        val ticket = Any()
        if (active) CaptureBlocks.hold(window, ticket)
        onDispose { if (active) CaptureBlocks.release(window, ticket) }
    }
}

/** Who is holding each window secure. Touched only from the main thread. */
private object CaptureBlocks {
    private val holders = java.util.WeakHashMap<Window, MutableSet<Any>>()

    fun hold(window: Window, ticket: Any) {
        val set = holders.getOrPut(window) { mutableSetOf() }
        if (set.isEmpty()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        set += ticket
    }

    fun release(window: Window, ticket: Any) {
        val set = holders[window] ?: return
        set -= ticket
        if (set.isEmpty()) {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Whether screenshots of the Vault and locked items are allowed. They aren't, unless turned on. */
object PrivacyPrefs {
    private const val PREFS = "kobblo_prefs"
    private const val KEY_SCREENSHOTS = "allow_private_screenshots"

    fun screenshotsAllowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREENSHOTS, false)

    fun setScreenshotsAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREENSHOTS, allowed).apply()
    }
}

/** The activity behind a context, unwrapping any ContextWrappers in between. */
internal fun Context.findActivity(): Activity? =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity
