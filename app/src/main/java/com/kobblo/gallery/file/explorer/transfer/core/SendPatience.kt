package com.kobblo.gallery.file.explorer.transfer.core

/**
 * How long a dropped connection keeps being tried. The clock starts over whenever the transfer has got well past
 * where it ever had, so a long send can't use up its patience just by running, and a connection that falls over at
 * the same place every time does run out of it. "Well past" because what counts as sent includes what's sitting in
 * the network's buffers, which comes out a little different every try.
 */
class RetryWindow(private val windowMs: Long, private val minGainBytes: Long = 8L * 1024 * 1024) {
    private var furthest = -1L
    private var deadline = 0L

    fun mayRetry(now: Long, reached: Long): Boolean {
        if (furthest < 0 || reached >= furthest + minGainBytes) {
            furthest = reached
            deadline = now + windowMs
        }
        return now < deadline
    }
}

/**
 * How long one write to the network has been going. When a network dies quietly, or the other phone walks out of
 * range, nothing fails: a write just never finishes, and this is the only way to tell. Reading the file doesn't
 * count, since a file from a cloud provider can take as long as it likes to arrive.
 */
class WriteWatch {
    @Volatile private var since = 0L

    fun begin(now: Long) {
        since = now
    }

    fun end() {
        since = 0L
    }

    fun blockedFor(now: Long): Long = since.let { if (it == 0L) 0L else now - it }
}
