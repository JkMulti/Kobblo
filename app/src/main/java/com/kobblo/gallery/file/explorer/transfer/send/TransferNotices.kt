package com.kobblo.gallery.file.explorer.transfer.send

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kobblo.gallery.file.explorer.MainActivity
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.IncomingTransferRequest
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.PromptKind
import com.kobblo.gallery.file.explorer.transfer.TransferEntry
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.PromptRules
import com.kobblo.gallery.file.explorer.transfer.core.SenderTrust

/**
 * What the shade says about sending and receiving, beyond the running service's own quiet line: the question, for
 * when the in-app one can't be seen, and how something ended, for when the card that shows it can't be either.
 */
object TransferNotices {
    private const val TAG = "TransferNotices"
    private const val PROMPT_CHANNEL = "transfer_accept_reject"
    private const val RESULT_CHANNEL = "transfer_results"
    private const val PROMPT_ID = 2002
    private const val RESULT_ID = 2005
    // The app takes it down itself on time. This is for when the app has died and can't.
    private const val OUTLAST_MS = 5_000L

    const val ACTION_ACCEPT = "com.kobblo.gallery.file.explorer.transfer.ACCEPT"
    const val ACTION_REJECT = "com.kobblo.gallery.file.explorer.transfer.REJECT"
    const val EXTRA_PROMPT = "prompt"

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(context: Context) {
        val manager = manager(context)
        manager.createNotificationChannel(
            NotificationChannel(PROMPT_CHANNEL, context.getString(R.string.transfer_prompt_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.transfer_prompt_channel_note)
                enableVibration(true)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, context.getString(R.string.transfer_result_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.transfer_result_channel_note)
            }
        )
    }

    /** False when a question couldn't reach anyone who isn't in the app. */
    fun canAskOutsideApp(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return manager(context).getNotificationChannel(PROMPT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun showPrompt(context: Context, request: IncomingTransferRequest, remainingMs: Long) {
        val title = title(context, request)
        // In the text, not the header, where it would squeeze the title down to "Mac Peer wants to se...". A
        // stranger's comes first, as that's what to know before anything else. A browser's title has said it already.
        val trust = trustLine(context, request)
        val summary = summary(context, request)
        val line = if (request.kind.isBrowser) "$summary · $trust" else "$trust · $summary"
        val body = listOfNotNull(warning(context, request), line, details(context, request)).joinToString("\n")
        // Who's asking and how much is fine on a locked screen. What the files are called can wait for an unlock.
        val locked = NotificationCompat.Builder(context, PROMPT_CHANNEL)
            .setContentTitle(title)
            .setContentText(line)
            .setSmallIcon(R.drawable.ic_stat_send)
            .build()
        val accept = NotificationCompat.Action.Builder(0, acceptLabel(context, request), acceptIntent(context, request.sessionId))
            // Nothing lands on a locked phone. Rejecting can't do any harm, so that's left free.
            .setAuthenticationRequired(true)
            .build()
        val notification = NotificationCompat.Builder(context, PROMPT_CHANNEL)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_send)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setContentIntent(openApp(context))
            // Swiping it away is an answer, the same as swiping the sheet away, and saves the sender waiting.
            .setDeleteIntent(rejectIntent(context, request.sessionId))
            .setTimeoutAfter(remainingMs.coerceAtLeast(1_000L) + OUTLAST_MS)
            .addAction(0, context.getString(R.string.transfer_reject), rejectIntent(context, request.sessionId))
            .addAction(accept)
            .build()
        try {
            manager(context).notify(PROMPT_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show the question: ${e.javaClass.simpleName}")
        }
    }

    fun clearPrompt(context: Context) {
        manager(context).cancel(PROMPT_ID)
    }

    /** How something ended. Only for when the app isn't on screen, where the card says it instead. */
    fun showResult(context: Context, title: String, text: String?) {
        val builder = NotificationCompat.Builder(context, RESULT_CHANNEL)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_stat_send)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (!text.isNullOrBlank()) {
            builder.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        try {
            manager(context).notify(RESULT_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show a result: ${e.javaClass.simpleName}")
        }
    }

    fun clearResult(context: Context) {
        manager(context).cancel(RESULT_ID)
    }

    fun title(context: Context, request: IncomingTransferRequest): String = when (request.kind) {
        PromptKind.FILES -> context.getString(R.string.transfer_incoming_from, request.sender.alias)
        PromptKind.NOTE -> context.getString(R.string.transfer_incoming_note_from, request.sender.alias)
        PromptKind.BROWSER_FILES, PromptKind.BROWSER_NOTE, PromptKind.BROWSER_OPEN -> context.getString(R.string.transfer_browser_at, request.senderIp)
    }

    /** What's coming, in a line. */
    fun summary(context: Context, request: IncomingTransferRequest): String {
        val count = request.files.size
        val size = TransferFormat.size(context, request.totalBytes)
        return when (request.kind) {
            PromptKind.FILES -> context.resources.getQuantityString(R.plurals.transfer_incoming_summary, count, count, size)
            PromptKind.NOTE -> context.getString(R.string.transfer_note_accept_to_read)
            PromptKind.BROWSER_FILES -> context.resources.getQuantityString(R.plurals.transfer_browser_wants_files, count, count, size)
            PromptKind.BROWSER_NOTE -> context.getString(R.string.transfer_browser_wants_note)
            PromptKind.BROWSER_OPEN -> context.getString(R.string.transfer_browser_wants_open)
        }
    }

    /** The first name or two, or who a browser says it is. Null when there's nothing to add. */
    private fun details(context: Context, request: IncomingTransferRequest): String? {
        val names = names(context, request)
        return when (request.kind) {
            PromptKind.FILES -> names
            PromptKind.NOTE -> null
            PromptKind.BROWSER_FILES -> listOfNotNull(request.sender.alias, names).joinToString(" · ")
            PromptKind.BROWSER_NOTE, PromptKind.BROWSER_OPEN -> request.sender.alias
        }
    }

    fun names(context: Context, request: IncomingTransferRequest): String? {
        val all = request.files.map { it.fileName }
        val first = PromptRules.firstNames(all, context.getString(R.string.share_unnamed_file)).joinToString(", ").ifBlank { return null }
        val more = PromptRules.moreThanShown(all.size)
        return if (more > 0) context.getString(R.string.transfer_names_more, first, more) else first
    }

    fun trust(request: IncomingTransferRequest): SenderTrust =
        if (request.kind.isBrowser) SenderTrust.UNVERIFIED
        else PromptRules.trust(request.proven, request.sender.alias, request.sender.fingerprint, PeerDirectory.saved.value)

    /** Always one of these two, so a stranger can't look like anything but a stranger. */
    fun trustLine(context: Context, request: IncomingTransferRequest): String =
        if (trust(request) == SenderTrust.KNOWN) context.getString(R.string.transfer_known_device_title)
        else context.getString(R.string.transfer_unverified)

    fun warning(context: Context, request: IncomingTransferRequest): String? =
        if (trust(request) == SenderTrust.BORROWED_NAME) context.getString(R.string.transfer_borrowed_name, request.sender.alias) else null

    fun acceptLabel(context: Context, request: IncomingTransferRequest): String =
        context.getString(if (request.kind == PromptKind.BROWSER_OPEN) R.string.transfer_allow else R.string.transfer_accept)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        PROMPT_ID,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun rejectIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        PROMPT_ID + 1,
        Intent(context, TransferAnswerReceiver::class.java).setAction(ACTION_REJECT).putExtra(EXTRA_PROMPT, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Below Android 12 a button can't ask for an unlock by itself, but the system won't open an activity from a
     * locked screen without one. So there it goes through the app, which is the one thing a button can do that way.
     */
    private fun acceptIntent(context: Context, id: String): PendingIntent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getBroadcast(
                context,
                PROMPT_ID + 2,
                Intent(context, TransferAnswerReceiver::class.java).setAction(ACTION_ACCEPT).putExtra(EXTRA_PROMPT, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context,
                PROMPT_ID + 2,
                Intent(context, MainActivity::class.java)
                    .setAction(TransferEntry.ACTION_ACCEPT_PROMPT)
                    .putExtra(EXTRA_PROMPT, id)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
}

/** The buttons on the question. The id has to match what's being asked, so an old notification can't answer a new one. */
class TransferAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(TransferNotices.EXTRA_PROMPT) ?: return
        NearbyShareRepository.attach(context)
        when (intent.action) {
            TransferNotices.ACTION_ACCEPT -> NearbyShareRepository.acceptIncomingRequest(id)
            TransferNotices.ACTION_REJECT -> NearbyShareRepository.declineIncomingRequest(id)
        }
        // With the process gone and back, nothing here remembers the question. It mustn't sit there unanswerable.
        if (!NearbyShareRepository.isAsking()) TransferNotices.clearPrompt(context)
    }
}
