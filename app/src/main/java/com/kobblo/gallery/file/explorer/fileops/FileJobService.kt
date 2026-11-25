package com.kobblo.gallery.file.explorer.fileops

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds the process in the foreground while [FileJobs] has work. It does none of the work itself and owns none of
 * the state, so it can come and go without anything noticing. It leaves as soon as the work runs out, and it never
 * asks to be restarted: a kill is settled from the jobs' own records on the next launch.
 */
class FileJobService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockExtendedAt = 0L
    private var watcher: Job? = null
    private var lastStartId = 0
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> FileJobs.cancelRunning()
            ACTION_CANCEL_ALL -> FileJobs.cancelAll()
            else -> enterForeground()
        }
        // A cancel can arrive with no service running, when the notification is a plain one.
        if (!inForeground && (intent?.action == ACTION_CANCEL || intent?.action == ACTION_CANCEL_ALL)) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                FileJobs.PROGRESS_ID,
                FileJobs.progressNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException, or the day's limit already used up.
            Log.w(TAG, "Can't enter the foreground", e)
            FileJobs.onForegroundChanged(false)
            stopSelf(lastStartId)
            return
        }
        inForeground = true
        FileJobs.onForegroundChanged(true)
        holdWakeLock()
        if (watcher == null) {
            watcher = scope.launch {
                // A screen that's off stops the CPU, and a copy with it, unless something says otherwise.
                // The lock is always on a timer, so a job that's stuck or paused doesn't hold it for long.
                FileJobs.state.first { jobs ->
                    if (jobs.running?.paused == false && SystemClock.elapsedRealtime() - wakeLockExtendedAt > WAKE_EXTEND_MILLIS) holdWakeLock()
                    !jobs.busy
                }
                leave()
            }
        }
    }

    private fun holdWakeLock() {
        try {
            val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kobblo:FileJob")
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            lock.acquire(WAKE_HOLD_MILLIS)
            wakeLockExtendedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't hold a wake lock", e)
        }
    }

    private fun leave() {
        watcher?.cancel()
        watcher = null
        inForeground = false
        FileJobs.onForegroundChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only the latest request stops it, so a job asked for in this same moment still gets its service.
        stopSelf(lastStartId)
    }

    /** The system's daily limit on this type, on Android 15 and up. It has to stop within seconds or the app crashes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "The system's time limit for file work was reached")
        watcher?.cancel()
        watcher = null
        FileJobs.onSystemLimit()
        inForeground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (inForeground) FileJobs.onForegroundChanged(false)
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't release the wake lock", e)
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "FileJobService"
        private const val ACTION_CANCEL = "com.kobblo.gallery.file.explorer.fileops.CANCEL"
        private const val ACTION_CANCEL_ALL = "com.kobblo.gallery.file.explorer.fileops.CANCEL_ALL"
        private const val WAKE_HOLD_MILLIS = 10L * 60 * 1000
        private const val WAKE_EXTEND_MILLIS = 60L * 1000

        internal fun cancelIntent(context: Context, all: Boolean): PendingIntent = PendingIntent.getService(
            context,
            if (all) 2 else 1,
            Intent(context, FileJobService::class.java).setAction(if (all) ACTION_CANCEL_ALL else ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
