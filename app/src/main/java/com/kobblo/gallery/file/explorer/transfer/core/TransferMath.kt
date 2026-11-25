package com.kobblo.gallery.file.explorer.transfer.core

/**
 * A speed that doesn't jump around. Each reading blends into the last, so one stalled write or one
 * flushed buffer doesn't swing the time remaining by minutes.
 */
class SpeedMeter(private val smoothing: Double = 0.3, private val minIntervalMs: Long = 500) {
    private var lastTimeMs = -1L
    private var lastBytes = 0L
    private var smoothed = 0.0

    val bytesPerSecond: Long get() = smoothed.toLong()

    fun update(nowMs: Long, totalBytes: Long) {
        if (lastTimeMs < 0) {
            lastTimeMs = nowMs
            lastBytes = totalBytes
            return
        }
        val elapsed = nowMs - lastTimeMs
        if (elapsed < minIntervalMs) return
        val instant = (totalBytes - lastBytes).coerceAtLeast(0) * 1000.0 / elapsed
        smoothed = if (smoothed == 0.0) instant else smoothing * instant + (1 - smoothing) * smoothed
        lastTimeMs = nowMs
        lastBytes = totalBytes
    }
}

/** Limits how often progress reaches the screen: a fast link would otherwise redraw thousands of times a second. */
class ProgressGate(private val minIntervalMs: Long = 150) {
    private var lastMs = 0L

    fun allow(nowMs: Long, force: Boolean = false): Boolean {
        if (!force && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }
}

object TransferMath {
    /** Seconds left at this speed, or null while the speed isn't known yet. */
    fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long? {
        if (bytesPerSecond <= 0) return null
        if (remainingBytes <= 0) return 0
        return (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
    }

    /** Whole percent, which only reaches 100 when every byte has gone. */
    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        if (done >= total) return 100
        return (done * 100 / total).toInt().coerceIn(0, 99)
    }
}
