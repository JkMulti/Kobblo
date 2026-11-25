package com.kobblo.gallery.file.explorer.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App Lock. When it's on, Kobblo asks for its lock on the way in, and again whenever it comes back after
 * being away for at least the chosen number of minutes. It covers every screen the app shows: the main
 * app, the file picker other apps open, the photo editor, and files opened from other apps.
 */
object AppLockGate {

    /** True while the app should show its lock in place of its content. */
    var locked by mutableStateOf(false)
        private set

    /** What Lock After offers, in minutes. */
    val MinuteChoices = listOf(0, 1, 5, 15)

    private const val PREFS = "kobblo_prefs"
    private const val KEY_ON = "app_lock"
    private const val KEY_MINUTES = "app_lock_minutes"

    /** The app's activities that are on screen right now, by identity. */
    private val onScreen = HashSet<Int>()

    /** An activity is being recreated, say for a rotation, so its stop isn't the app being left. */
    private var recreating = false

    /** When the last activity left the screen, on the elapsed-realtime clock. 0 means never. */
    private var leftAt = 0L

    /** Whether that departure was to another app the app itself sent the person to, which earns a grace. */
    private var leftOnPurpose = false

    fun install(app: Application) {
        locked = enabled(app)
        app.registerActivityLifecycleCallbacks(Watcher)
    }

    fun unlock() {
        locked = false
    }

    /** App Lock was turned on or off. Turning it on doesn't lock the open app, only the next return to it. */
    fun settingsChanged(activity: Activity) {
        if (!enabled(activity)) locked = false
        updateRecentsCard(activity)
    }

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** Minutes away before it locks again; 0 locks as soon as the app is left. */
    fun minutes(context: Context): Int = prefs(context).getInt(KEY_MINUTES, 1)

    fun setMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_MINUTES, minutes).apply()
    }

    /** On, with a lock to ask for. A lock cleared by a reset leaves App Lock with nothing to ask, so it's off. */
    fun enabled(context: Context): Boolean = isOn(context) && LockStore.get(context).isSet

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cameBack(activity: Activity) {
        if (leftAt == 0L || !enabled(activity)) return
        val away = SystemClock.elapsedRealtime() - leftAt
        val limit = AwayTrips.delayFor(minutes(activity) * 60_000L, leftOnPurpose)
        if (away >= limit) locked = true
    }

    /** From Android 13, with App Lock on, recent apps shows a blank card rather than the app's content. */
    private fun updateRecentsCard(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!enabled(activity))
        }
    }

    private object Watcher : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            val returning = onScreen.isEmpty() && !recreating
            recreating = false
            onScreen += System.identityHashCode(activity)
            if (returning) cameBack(activity)
            updateRecentsCard(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            onScreen -= System.identityHashCode(activity)
            if (activity.isChangingConfigurations) {
                recreating = true
                return
            }
            if (onScreen.isEmpty()) {
                leftAt = SystemClock.elapsedRealtime()
                leftOnPurpose = AwayTrips.startedJustBefore(leftAt)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
