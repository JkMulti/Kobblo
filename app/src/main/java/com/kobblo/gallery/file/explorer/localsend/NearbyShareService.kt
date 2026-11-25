package com.kobblo.gallery.file.explorer.localsend

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kobblo.gallery.file.explorer.AppBrand
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.TransferMath
import com.kobblo.gallery.file.explorer.transfer.hotspot.LocalHotspot
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps sharing alive while the app isn't on the screen. It runs for one or both of two reasons:
 *
 * - Receive is on. Then this phone announces itself and listens, for as long as someone might send to it.
 * - A transfer is moving, in either direction. Then it shows how far it has got, and keeps the phone and its
 *   Wi-Fi awake until it's over.
 *
 * It stops itself after a quarter of an hour in which nothing happened, and it never comes back by itself.
 */
class NearbyShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val notifications by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private var inForeground = false

    /** It's only here for a transfer, and goes when that does. */
    @Volatile
    private var stopAfterTransfer = false

    /** The one that's starting or up. Only touched on the main thread. */
    private var engine: ShareEngine? = null
    private var engineIsLive = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastProgressNoticeMs = 0L

    private val idleCheck = Runnable {
        if (NearbyShareRepository.isTransferActive()) noteActivity() else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Before anything else: a service started this way has only moments to show its notification.
        inForeground = enterForeground()
        if (!inForeground) {
            stopSelf()
            return
        }
        NearbyShareRepository.attach(this)
        wakeLock = newWakeLock()
        noteActivity()
        scope.launch { NearbyShareRepository.activeTransfer.collect(::onTransferChanged) }
        scope.launch { NearbyShareRepository.incomingRequest.collect { question -> if (question != null) noteActivity() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!inForeground || intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> becomeVisible()
            ACTION_STOP -> stopBeingVisible()
            ACTION_HOLD -> {
                noteActivity()
                // Not visible, and not about to be: this is only for the send, and has done its job when that has.
                if (engine == null) stopAfterTransfer = true
            }
            ACTION_TOUCH -> noteActivity()
            ACTION_CANCEL_TRANSFER -> NearbyShareRepository.cancelActiveTransfer()
        }
        return START_NOT_STICKY
    }

    // On Android 15 and later the system puts a time limit on some kinds of foreground service, and gives a
    // few seconds to stop once it's reached.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "The system's time limit was reached, type=$fgsType")
        NearbyShareRepository.emitError(getString(R.string.transfer_system_time_limit))
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(idleCheck)
        scope.cancel()
        stopEngine()
        NearbyShareRepository.setReceiving(false)
        holdWifi(false)
        // A network made for transfers shouldn't outlive the thing that receives them.
        LocalHotspot.stop()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
        wakeLock = null
    }

    // ---- being visible ----

    private fun becomeVisible() {
        stopAfterTransfer = false
        noteActivity()
        val current = engine
        when {
            // The protocol's own port is for the multicast socket and for other LocalSend apps on this phone.
            // A server that was handed it, because its usual one was taken, starts over to get off it.
            current?.port == LocalSendWire.GROUP_PORT -> {
                Log.i(TAG, "Moving the server off port ${LocalSendWire.GROUP_PORT}")
                stopEngine()
                startEngine()
            }
            engineIsLive -> NearbyShareRepository.setReceiving(true)
            // Also when Receive was turned off during a send, and on again before the send finished.
            current == null -> startEngine()
            // Otherwise it's already starting, and a second one would find the port taken.
        }
    }

    /** Turning Receive off shouldn't end a transfer that's under way, so the service itself may stay a while. */
    private fun stopBeingVisible() {
        stopEngine()
        NearbyShareRepository.setReceiving(false)
        if (NearbyShareRepository.isTransferActive()) stopAfterTransfer = true else stopSelf()
    }

    private fun startEngine() {
        val starting = ShareEngine(applicationContext, onActivity = ::noteActivity)
        engine = starting
        starting.start { listening -> main.post { onEngineSettled(starting, listening) } }
    }

    private fun onEngineSettled(settled: ShareEngine, listening: Boolean) {
        // Stopped, or replaced, while it was starting. It has been taken down again, and there's nothing to say.
        if (engine !== settled) return
        if (listening) {
            engineIsLive = true
            settled.goLive()
        } else {
            Log.e(TAG, "Couldn't listen on the usual port or on any other")
            stopEngine()
            NearbyShareRepository.emitError(getString(R.string.transfer_receive_start_failed))
            NearbyShareRepository.setReceiving(false)
            stopSelf()
        }
    }

    private fun stopEngine() {
        engineIsLive = false
        engine?.stop()
        engine = null
        PeerDirectory.stopKeepAlive()
    }

    // ---- staying awake ----

    /**
     * Something happened, so the quarter of an hour starts again. The wake lock is taken for the same length
     * each time, which both extends it and makes sure it can never outlast the service by more than that.
     */
    private fun noteActivity() {
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_TIMEOUT_MS)
        try {
            wakeLock?.acquire(IDLE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extend wake lock", e)
        }
    }

    private fun newWakeLock(): PowerManager.WakeLock? = try {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Not counted, so taking it again replaces the time it had left and one release lets it go.
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create wake lock", e)
        null
    }

    /** With the screen off, a phone otherwise drops its Wi-Fi into a power-saving mode that crawls. */
    private fun holdWifi(wanted: Boolean) {
        try {
            if (!wanted) {
                wifiLock?.takeIf { it.isHeld }?.release()
                return
            }
            val lock = wifiLock ?: newWifiLock().also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock failed", e)
        }
    }

    private fun newWifiLock(): WifiManager.WifiLock {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    // ---- the transfer under way ----

    private fun onTransferChanged(session: TransferSession?) {
        if (session == null) {
            show(idleNotice())
            return
        }
        val moving = !session.status.isFinal
        holdWifi(moving)
        noteActivity()
        if (moving) {
            // Progress ticks many times a second. A change of state is always worth showing at once.
            show(progressNotice(session), evenIfRecent = session.status != TransferStatus.IN_PROGRESS)
            return
        }
        // How it ended is a notification of its own, which outlasts this one if the service is about to stop.
        show(idleNotice())
        if (stopAfterTransfer && !NearbyShareRepository.isTransferActive()) stopSelf()
    }

    private fun show(notice: Notification, evenIfRecent: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!evenIfRecent && now - lastProgressNoticeMs < PROGRESS_NOTICE_GAP_MS) return
        lastProgressNoticeMs = now
        try {
            notifications.notify(NOTIFICATION_ID, notice)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    // ---- the notification ----

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, idleNotice(), type)
        true
    } catch (e: Exception) {
        // The system doesn't allow it from the background, or the permission for this kind of service is missing.
        Log.w(TAG, "Can't enter foreground", e)
        false
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.share_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.share_channel_description)
        channel.setShowBadge(false)
        notifications.createNotificationChannel(channel)
    }

    /** Nothing is moving. The one thing to do from here is stop being visible. */
    private fun idleNotice(): Notification =
        notice(title = getString(R.string.transfer_notification_title, appName()), text = getString(R.string.transfer_notification_ready))
            .setOngoing(false)
            .addAction(0, getString(R.string.transfer_notification_stop), command(ACTION_STOP, requestCode = 1))
            .build()

    private fun progressNotice(session: TransferSession): Notification {
        val notice = notice(title = TransferFormat.title(this, session), text = TransferFormat.detail(this, session))
            .setOngoing(true)
            .addAction(0, getString(R.string.cancel), command(ACTION_CANCEL_TRANSFER, requestCode = 2))
        if (session.status == TransferStatus.IN_PROGRESS) {
            notice.setProgress(100, TransferMath.percent(session.bytesTransferred, session.totalBytes), false)
        }
        return notice.build()
    }

    private fun notice(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_LOW)

    /** The name the person gave the app, if they gave it one. */
    private fun appName(): String =
        getSharedPreferences("kobblo_prefs", MODE_PRIVATE).getString("custom_app_name", AppBrand.NAME)
            ?.takeIf { it.isNotBlank() }
            ?: AppBrand.NAME

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun command(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, NearbyShareService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val TAG = "NearbyShareService"
        const val CHANNEL_ID = "nearby_share_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.kobblo.gallery.file.explorer.localsend.ACTION_START"
        const val ACTION_STOP = "com.kobblo.gallery.file.explorer.localsend.ACTION_STOP"
        private const val ACTION_HOLD = "com.kobblo.gallery.file.explorer.localsend.ACTION_HOLD"
        private const val ACTION_TOUCH = "com.kobblo.gallery.file.explorer.localsend.ACTION_TOUCH"
        private const val ACTION_CANCEL_TRANSFER = "com.kobblo.gallery.file.explorer.localsend.ACTION_CANCEL_TRANSFER"

        private const val WAKE_LOCK_TAG = "Kobblo:NearbyShareWakeLock"
        private const val WIFI_LOCK_TAG = "Kobblo:Transfer"
        private const val IDLE_TIMEOUT_MS = 15 * 60 * 1000L
        private const val PROGRESS_NOTICE_GAP_MS = 1_000L

        private fun order(context: Context, action: String) = Intent(context, NearbyShareService::class.java).setAction(action)

        /**
         * The system refuses to start or reach a service from some places, the background above all, and says
         * so by throwing. None of the callers can do anything about that except know it happened.
         */
        private inline fun allowed(what: String, ask: () -> Unit): Boolean = try {
            ask()
            true
        } catch (e: Exception) {
            Log.w(TAG, "The system wouldn't $what the service", e)
            false
        }

        /** Makes this phone visible. False if the system wouldn't let the service start from where this was called. */
        fun start(context: Context): Boolean = allowed("start") { context.startForegroundService(order(context, ACTION_START)) }

        /** Keeps the app alive through a send without changing whether this phone is visible. */
        fun hold(context: Context) {
            allowed("hold") { context.startForegroundService(order(context, ACTION_HOLD)) }
        }

        /** Someone is looking at the Send tab, so being visible isn't idle. Starts nothing if it isn't running. */
        fun touch(context: Context) {
            if (!NearbyShareRepository.isReceiving.value) return
            allowed("reach") { context.startService(order(context, ACTION_TOUCH)) }
        }

        /** Stops being visible. A transfer that's under way carries on, and the service goes when it's done. */
        fun stop(context: Context) {
            allowed("stop") {
                if (NearbyShareRepository.isTransferActive()) {
                    // With something moving the service is already running, so this can't start one just to stop it.
                    context.startService(order(context, ACTION_STOP))
                } else {
                    context.stopService(Intent(context, NearbyShareService::class.java))
                }
            }
        }
    }
}
