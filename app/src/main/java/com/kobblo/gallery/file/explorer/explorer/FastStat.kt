package com.kobblo.gallery.file.explorer.explorer

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File

/** What a list row needs to know about a file, from one call to the system. */
class FastStat(val isDirectory: Boolean, val size: Long, val modifiedMillis: Long) {
    companion object {
        /** Null if the system can't say, in which case the caller should ask `File`, which copes with more. */
        fun of(file: File): FastStat? = try {
            // stat rather than lstat, so a link to a folder lists as the folder it leads to, as `File` does.
            val stat = Os.stat(file.path)
            val modified = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                stat.st_mtim.tv_sec * 1000L + stat.st_mtim.tv_nsec / 1_000_000L
            } else {
                stat.st_mtime * 1000L
            }
            FastStat(OsConstants.S_ISDIR(stat.st_mode), stat.st_size, modified)
        } catch (_: Exception) {
            null
        }
    }
}
