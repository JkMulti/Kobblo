package com.kobblo.gallery.file.explorer.ui

import android.util.Log
import com.kobblo.gallery.file.explorer.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * A debug build's check that screens aren't piling up. Moving between two screens composes both for
 * the length of the animation, the one leaving and the one arriving, and one once it settles. A third
 * alive at the same time means a screen was never let go, so a debug build stops right there, while
 * the cause is still on the stack. Release builds do nothing.
 */
object ScreenCompositionTracker {
    private const val TAG = "ScreenComposition"

    /** The most that may be composed at once: the screen leaving and the screen arriving. */
    private const val MOST_AT_ONCE = 2

    private val composed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun onScreenEnter(screen: String) {
        if (!BuildConfig.DEBUG) return
        composed += screen
        Log.d(TAG, "+ $screen, now $composed")
        if (composed.size > MOST_AT_ONCE) {
            val problem = "${composed.size} screens composed at once, when at most $MOST_AT_ONCE should be: $composed"
            Log.e(TAG, problem)
            error(problem)
        }
    }

    fun onScreenExit(screen: String) {
        if (!BuildConfig.DEBUG) return
        composed -= screen
        Log.d(TAG, "- $screen, now $composed")
    }
}
