package com.kobblo.gallery.file.explorer.transfer.send

import android.content.Context
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.TransferSession
import com.kobblo.gallery.file.explorer.localsend.TransferStatus
import com.kobblo.gallery.file.explorer.transfer.core.TransferMath

/** The words for a transfer's state, shared by the screen and the notification. */
object TransferFormat {
    fun size(context: Context, bytes: Long): String = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(bytes)

    fun eta(context: Context, seconds: Long): String = when {
        seconds < 60 -> context.getString(R.string.transfer_eta_seconds, seconds)
        seconds < 3600 -> context.getString(R.string.transfer_eta_minutes, (seconds + 59) / 60)
        else -> context.getString(R.string.transfer_eta_hours, seconds / 3600, (seconds % 3600) / 60)
    }

    fun title(context: Context, session: TransferSession): String {
        val count = session.files.size
        return when (session.status) {
            TransferStatus.COMPLETED ->
                if (!session.isIncoming && session.files.singleOrNull()?.preview != null) context.getString(R.string.transfer_note_sent, session.peer.alias)
                else if (session.isIncoming) context.resources.getQuantityString(R.plurals.transfer_received_title, count, count, session.peer.alias)
                else context.resources.getQuantityString(R.plurals.transfer_sent_title, count, count, session.peer.alias)
            TransferStatus.FAILED ->
                if (session.isIncoming) context.getString(R.string.transfer_receive_failed_title, session.peer.alias)
                else context.getString(R.string.transfer_send_failed_title, session.peer.alias)
            TransferStatus.REJECTED -> context.getString(R.string.transfer_declined_by, session.peer.alias)
            TransferStatus.CANCELLED -> context.getString(R.string.transfer_cancelled_title)
            else ->
                if (session.isIncoming) context.getString(R.string.transfer_receiving_from, session.peer.alias)
                else context.getString(R.string.transfer_sending_to, session.peer.alias)
        }
    }

    /** The line under the title: where it is, how fast, and how long it has left. */
    fun detail(context: Context, session: TransferSession): String = when (session.status) {
        TransferStatus.INITIALIZING -> context.getString(R.string.transfer_connecting)
        TransferStatus.WAITING_ACCEPT -> context.getString(R.string.transfer_waiting_accept, session.peer.alias)
        TransferStatus.IN_PROGRESS -> buildString {
            append(TransferMath.percent(session.bytesTransferred, session.totalBytes)).append('%')
            if (session.waitingOnNetwork) {
                append(" · ").append(context.getString(R.string.transfer_waiting_on, session.peer.alias))
            } else if (session.speedBytesPerSec > 0) {
                append(" · ").append(context.getString(R.string.transfer_speed, size(context, session.speedBytesPerSec)))
            }
            session.etaSeconds?.let { append(" · ").append(eta(context, it)) }
        }
        TransferStatus.RECONNECTING -> context.getString(R.string.transfer_reconnecting)
        TransferStatus.COMPLETED -> {
            val already = session.files.count { it.alreadyThere }
            when {
                session.files.singleOrNull()?.preview != null && !session.isIncoming -> context.getString(R.string.transfer_note_sent_detail)
                already > 0 -> context.resources.getQuantityString(R.plurals.transfer_already_there_detail, already, already, size(context, session.totalBytes))
                else -> size(context, session.totalBytes)
            }
        }
        TransferStatus.CANCELLED -> context.getString(R.string.transfer_cancelled_detail, session.finishedFiles, session.files.size)
        TransferStatus.REJECTED -> context.getString(R.string.transfer_nothing_sent)
        TransferStatus.FAILED -> session.errorMessage ?: context.getString(R.string.transfer_failed_unknown)
    }
}
