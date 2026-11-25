package com.kobblo.gallery.file.explorer.files

import android.content.Context
import com.kobblo.gallery.file.explorer.fileops.Locks

/** What's kept by a file's path, and so has to go where the file goes: its lock, and its star with it. */
internal interface PathFollowers {
    fun moved(from: String, to: String)

    /** A copy of something locked is as private as the original. */
    fun copied(from: String, to: String)

    /** It's gone for good. */
    fun forget(path: String)
}

internal class LocksAndFavorites(private val context: Context) : PathFollowers {
    override fun moved(from: String, to: String) = Locks.moved(context, from, to)

    override fun copied(from: String, to: String) = Locks.copied(context, from, to)

    override fun forget(path: String) = Locks.forget(context, path)
}
