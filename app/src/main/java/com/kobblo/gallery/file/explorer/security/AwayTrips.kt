package com.kobblo.gallery.file.explorer.security

import android.os.SystemClock

/**
 * A trip out of the app that the app itself started and expects back from: picking a file in another
 * app, a share sheet. Locking the moment it's left would lock on the person in the middle of that, and
 * drop what they picked. So such a trip gets a short grace, and anything longer still locks.
 */
object AwayTrips {
    /** How long a trip may take before it counts as leaving. */
    const val GraceMs = 60_000L

    @Volatile private var startedAt = 0L

    /** Call just before sending the person to another app on purpose. */
    fun begin() {
        startedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Whether a trip began just before the app was left at [leftAt]. App Lock and the Vault both ask about
     * the same leaving, so asking doesn't use it up: a trip only matches a leaving moments after it began.
     */
    fun startedJustBefore(leftAt: Long): Boolean {
        val began = startedAt
        return began != 0L && leftAt - began in 0..5_000L
    }

    /** The wait before locking: [lockAfterMs], or the grace for a trip the app started, whichever is longer. */
    fun delayFor(lockAfterMs: Long, onTrip: Boolean): Long = if (onTrip) maxOf(lockAfterMs, GraceMs) else lockAfterMs
}
