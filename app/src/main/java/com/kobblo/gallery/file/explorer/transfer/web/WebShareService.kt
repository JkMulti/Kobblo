package com.kobblo.gallery.file.explorer.transfer.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Web Share runs only while this service does, and this service always shows that it's running. The
 * notification says so and can stop it, and so can the screen. It stops itself after a stretch of
 * inactivity, so a forgotten link doesn't stay open.
 */
class WebShareService : Service() {

    companion object {
        private const val TAG = "WebShareService"
        private const val CHANNEL_ID = "web_share_channel"
        private const val NOTIFICATION_ID = 2003
        private const val ACTION_STOP = "com.kobblo.gallery.file.explorer.transfer.web.ACTION_STOP"
        private const val CHECK_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, WebShareService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't start Web Share", e)
                WebShareController.stopServer()
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebShareService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false
    private var stoppedForInactivity = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.web_share_title), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        started = try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.web_share_starting)), type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Can't enter foreground", e)
            false
        }
        if (!started) {
            stopSelf()
            return
        }
        NearbyShareRepository.attach(this)
        acquireLocks()

        scope.launch {
            val staged = NearbyShareRepository.stagedFiles.value
            if (!WebShareController.startServer(this@WebShareService, staged)) {
                stopSelf()
                return@launch
            }
            // Not the link itself: whoever has it can get the files, and notifications show on a locked screen.
            manager.notify(NOTIFICATION_ID, buildNotification(getString(R.string.web_share_notification_text)))
            while (isActive) {
                delay(CHECK_MS)
                WebShareController.publishClients()
                if (WebShareController.shouldStopForInactivity()) {
                    stoppedForInactivity = true
                    stopSelf()
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !started) stopSelf()
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kobblo:WebShare").apply {
                setReferenceCounted(false)
                // Bounded by the inactivity limit, and re-taken only by this service's own lifetime.
                acquire(2 * 60 * 60 * 1000L)
            }
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "Kobblo:WebShare").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't take locks", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebShareService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.web_share_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.transfer_notification_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        WebShareController.stopServer(forInactivity = stoppedForInactivity)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }
}
