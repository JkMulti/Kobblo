package com.kobblo.gallery.file.explorer.transfer.send

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.ReceivedNote

/** Says a note has come, for when nobody is looking at the app. In the app, the sheet says it. */
object NoteNotification {
    private const val CHANNEL_ID = "nearby_share_incoming"
    private const val ID = 2004

    fun show(context: Context, note: ReceivedNote) {
        if (NearbyShareRepository.isAppShowing()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.transfer_notes_channel), NotificationManager.IMPORTANCE_HIGH)
            )
            manager.notify(
                ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.transfer_note_from, note.fromAlias))
                    // The words may be private and the phone may be locked, and a tap shows them anyway.
                    .setContentText(context.getString(R.string.transfer_note_tap))
                    .setSmallIcon(R.drawable.ic_stat_send)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            // Refused notifications: the sheet is still waiting for when the app opens.
        }
    }

    fun clear(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID)
    }
}
