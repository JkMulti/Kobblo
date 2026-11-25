package com.kobblo.gallery.file.explorer.tools.crash

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The most recent crash, kept in the app's private storage. A stack trace can hold file names, so
 * it only leaves the phone when someone shares it from the crash screen.
 */
object CrashLog {
    /** Set on the intent that reopens the app after a crash. */
    const val ExtraShowCrash = "com.kobblo.gallery.file.explorer.SHOW_CRASH"

    private const val FileName = "crash_log.txt"

    private fun file(context: Context) = File(context.filesDir, FileName)

    fun write(context: Context, throwable: Throwable) {
        file(context).writeText(render(context, throwable))
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.isFile && it.length() > 0L }?.readText()
    } catch (_: Exception) {
        null
    }

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    fun delete(context: Context) {
        file(context).delete()
    }

    /** Earlier versions also left copies where other apps could read them. */
    fun removeOldPublicCopies(context: Context) {
        try {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "kobblo_crash_log.txt").delete()
        } catch (_: Exception) {
        }
        try {
            context.getExternalFilesDir(null)?.let { File(it, FileName).delete() }
        } catch (_: Exception) {
        }
    }

    private fun render(context: Context, throwable: Throwable): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return "Kobblo ${version.orEmpty()} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n$time\n\n" +
            android.util.Log.getStackTraceString(throwable)
    }
}
