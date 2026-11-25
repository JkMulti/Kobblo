package com.kobblo.gallery.file.explorer.ui

import android.os.SystemClock
import android.util.Log
import com.kobblo.gallery.file.explorer.BuildConfig

/**
 * Times a tab switch in debug builds, for finding where one drags. Each stage a switch passes through
 * reports here, and is logged with how long after the tap it happened: the tap itself, the action it
 * sends, the view model taking it, the files being fetched, the screen composing, and the animation
 * starting and coming to rest. Release builds do nothing.
 *
 * Read it with `adb logcat -s TabSwitchLatency`.
 */
object TabSwitchLatencyTracker {
    private const val TAG = "TabSwitchLatency"

    /** When the last tab was tapped, on the clock that keeps running while the phone sleeps. Zero before any tap. */
    private var tappedAt = 0L

    fun onTabTap(tabName: String) {
        if (!BuildConfig.DEBUG) return
        tappedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "tap       $tabName")
    }

    fun onActionDispatched(location: String) = stage("action") { "location $location sent" }

    fun onViewModelSetLocation(location: String, hasCache: Boolean, cachedCount: Int) = stage("model") {
        if (hasCache) "$location taken, $cachedCount files already cached" else "$location taken, nothing cached"
    }

    fun onDataFetchStart(location: String) = stage("fetch") { "$location started" }

    fun onDataFetchEnd(location: String, itemCount: Int, durationMs: Long) = stage("fetch") {
        "$location done: $itemCount files in ${durationMs}ms"
    }

    fun onBrowseCompose(location: String, fileCount: Int, isLoading: Boolean) = stage("compose") {
        "Browse at $location with $fileCount files" + if (isLoading) ", still loading" else ""
    }

    fun onHomeCompose(recentCount: Int) = stage("compose") { "Recents with $recentCount files" }

    fun onTransitionStart(initialState: String, targetState: String) = stage("motion") {
        "$initialState to $targetState started"
    }

    fun onTransitionSettled(targetState: String) = stage("motion") { "$targetState at rest" }

    /** Logs one line for a stage, with the time since the tap that began the switch. */
    private inline fun stage(name: String, what: () -> String) {
        if (!BuildConfig.DEBUG) return
        val sinceTap = if (tappedAt > 0L) SystemClock.elapsedRealtime() - tappedAt else 0L
        Log.i(TAG, "${name.padEnd(9)} ${what()} (+${sinceTap}ms)")
    }
}
