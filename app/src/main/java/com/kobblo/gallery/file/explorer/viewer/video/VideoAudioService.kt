package com.kobblo.gallery.file.explorer.viewer.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R

/**
 * What keeps a video's sound going with the screen off, when someone has asked for that. The player stays with the
 * viewer. All this does is say to the system that this process is in use, and to the person that something's playing.
 */
class VideoAudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(ExtraName).orEmpty()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NotificationId, notification(name), type)
        } catch (_: Exception) {
            // Android can refuse from the background. Playing then lasts as long as the system lets the process live.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(name: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, getString(R.string.viewer_video_background_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VideoAudioService::class.java).setAction(ActionStop),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(name.ifEmpty { getString(R.string.viewer_video_background_title) })
            .setContentText(getString(R.string.viewer_video_background_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.viewer_video_background_stop), stop)
            .build()
    }

    companion object {
        private const val ChannelId = "viewer_video_background"
        private const val NotificationId = 2011
        private const val ActionStop = "com.kobblo.gallery.file.explorer.viewer.video.STOP_BACKGROUND"
        private const val ExtraName = "name"

        /** What the notification's Stop does. Set by the session that's playing. */
        var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, name: String) {
            val intent = Intent(context, VideoAudioService::class.java).putExtra(ExtraName, name)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Same as above: it's only that this won't be protected from being cleared away.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VideoAudioService::class.java))
        }
    }
}
