package com.kobblo.gallery.file.explorer.startup

import android.app.Activity
import android.content.Intent
import android.os.Process
import android.util.Log
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.tools.crash.CrashLog
import kotlin.system.exitProcess

/** After a crash the app starts over on a page that says so, with the log on it, rather than just vanishing. */
internal object CrashGuard {

    /** Whether [intent] is the one that starts the app over. */
    fun startsOver(intent: Intent): Boolean = intent.getBooleanExtra(CrashLog.ExtraShowCrash, false)

    /** From here on a crash starts the app over from [activity], since Android lets what's on screen open more. */
    fun watch(activity: Activity) {
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            try {
                startOver(activity, error)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startOver(activity: Activity, error: Throwable) {
        Log.e("KobbloCrash", "Kobblo crashed", error)
        CrashLog.write(activity, error)
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(CrashLog.ExtraShowCrash, true)
        )
        // Whatever threw may have left anything half done, so the new screen gets a new process.
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
