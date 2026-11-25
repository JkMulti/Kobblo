package com.kobblo.gallery.file.explorer.tools.storage

import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Real recursive folder sizes, computed off the main thread and remembered.
 *
 * A folder's modified time says nothing about changes deeper in it, so a remembered size can only
 * be trusted for a while: past that it is still shown at once, and replaced when a fresh walk ends.
 * Walks stop as soon as nobody is waiting for them, which is what keeps scrolling past a long list
 * of folders cheap.
 */
object FolderSizes {
    private const val FRESH_FOR_MS = 60_000L

    private class Entry(val size: DirectorySize, val measuredAt: Long)

    private val cache = LruCache<String, Entry>(8192)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val walkers = Dispatchers.IO.limitedParallelism(2)

    /** What's remembered for [path], fresh or not. */
    fun peek(path: String): DirectorySize? = cache.get(path)?.size

    private fun isFresh(path: String): Boolean {
        val entry = cache.get(path) ?: return false
        return SystemClock.elapsedRealtime() - entry.measuredAt < FRESH_FOR_MS
    }

    /** The size of [path]. Cancelling the caller stops the walk. */
    suspend fun measure(path: String, force: Boolean = false): DirectorySize {
        if (!force && isFresh(path)) cache.get(path)?.let { return it.size }
        return withContext(walkers) {
            val job = currentCoroutineContext().job
            val now = SystemClock.elapsedRealtime()
            DirectoryWalker.measure(
                root = File(path),
                checkCancelled = { job.ensureActive() },
                onDirectory = { dir, size -> cache.put(dir.path, Entry(size, now)) }
            )
        }
    }

    /** Forgets [path] and the folders above it, whose totals just changed too. */
    fun invalidate(path: String) {
        var current: File? = File(path)
        while (current != null) {
            cache.remove(current.path)
            current = current.parentFile
        }
    }
}

/** A folder's size for a screen to show: the remembered value right away, then the measured one. */
@Composable
fun rememberFolderSize(path: String): State<DirectorySize?> =
    produceState(initialValue = FolderSizes.peek(path), path) {
        value = FolderSizes.measure(path)
    }
