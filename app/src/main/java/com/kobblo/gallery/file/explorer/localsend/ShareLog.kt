package com.kobblo.gallery.file.explorer.localsend

import android.util.Log

/**
 * Where sharing notes what it's doing. On a phone that's Android's log. The parts that speak the protocol are
 * handed one of these instead of calling the log themselves, which is what lets them run in a test with no
 * Android under them.
 *
 * What's written must never hold a file's name or a note's words: only what kind of thing happened.
 */
internal interface ShareLog {
    fun debug(message: String) {}

    fun info(message: String) {}

    fun warn(message: String, cause: Throwable? = null) {}

    /** Says nothing, for tests. */
    object Silent : ShareLog
}

/** Android's log, under one [tag] for each part of sharing. */
internal class PhoneLog(private val tag: String) : ShareLog {
    override fun debug(message: String) {
        Log.d(tag, message)
    }

    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun warn(message: String, cause: Throwable?) {
        if (cause == null) Log.w(tag, message) else Log.w(tag, message, cause)
    }
}
