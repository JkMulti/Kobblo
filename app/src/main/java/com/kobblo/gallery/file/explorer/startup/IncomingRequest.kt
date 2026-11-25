package com.kobblo.gallery.file.explorer.startup

import android.content.Intent
import com.kobblo.gallery.file.explorer.music.MusicService
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.transfer.TransferEntry
import com.kobblo.gallery.file.explorer.transfer.send.TransferNotices

/** What another app, a shortcut or a notification wants of Kobblo. [U] is Android's Uri, left open so plain tests can stand in for it. */
internal sealed interface IncomingRequest<out U> {
    /** Files shared into the app, of the [type] the sender says they are. */
    data class SharedFiles<out U>(val files: List<U>, val type: String?) : IncomingRequest<U>

    /** Words or a link shared into the app, with no file. */
    data class SharedText(val text: String) : IncomingRequest<Nothing>

    /** A file to look at, of the [type] the sender says it is. */
    data class OpenFile<out U>(val file: U, val type: String?) : IncomingRequest<U>

    /** A folder's own shortcut. Any app can send one, so the path isn't to be trusted as it is. */
    data class OpenFolder(val path: String) : IncomingRequest<Nothing>

    data object ShowPlayer : IncomingRequest<Nothing>

    /** The Send tab, which is what makes a phone ready to receive. */
    data object GetReadyToReceive : IncomingRequest<Nothing>

    /** Yes to the incoming files that the prompt with this id asks about. */
    data class AcceptFiles(val promptId: String) : IncomingRequest<Nothing>
}

/**
 * The parts of an intent that say what it wants. What the sender packed in is behind functions, so only
 * what a request of that kind calls for is ever unpacked.
 */
internal interface IntentParts<U : Any> {
    val action: String?
    val type: String?
    val data: U?

    /** The one file that a share of one file carries. */
    fun stream(): U?

    /** The files that a share of several carries. */
    fun streams(): List<U>?

    /** What's on the intent's clip, in order, with null for anything that isn't a file. */
    fun clip(): List<U?>
    fun sharedText(): String?
    fun extra(name: String): String?
}

/** What [parts] asks for, or null if that's nothing more than opening the app. */
internal fun <U : Any> requestIn(parts: IntentParts<U>): IncomingRequest<U>? = when (parts.action) {
    Intent.ACTION_SEND -> sharedFile(parts) ?: parts.sharedText()?.let { IncomingRequest.SharedText(it) }
    Intent.ACTION_SEND_MULTIPLE -> sharedFiles(parts)
    Intent.ACTION_VIEW -> parts.data?.let { IncomingRequest.OpenFile(it, parts.type) }
    ToolsFiles.ACTION_OPEN_FOLDER -> parts.extra(ToolsFiles.EXTRA_FOLDER_PATH)?.let { IncomingRequest.OpenFolder(it) }
    MusicService.ActionOpenPlayer -> IncomingRequest.ShowPlayer
    TransferEntry.ACTION_RECEIVE -> IncomingRequest.GetReadyToReceive
    TransferEntry.ACTION_ACCEPT_PROMPT -> parts.extra(TransferNotices.EXTRA_PROMPT)?.let { IncomingRequest.AcceptFiles(it) }
    else -> null
}

/** Not every app puts the file where it belongs, so the clip and then the intent's own address are looked at too. */
private fun <U : Any> sharedFile(parts: IntentParts<U>): IncomingRequest<U>? {
    val file = parts.stream() ?: parts.clip().firstOrNull() ?: parts.data ?: return null
    return IncomingRequest.SharedFiles(listOf(file), parts.type)
}

private fun <U : Any> sharedFiles(parts: IntentParts<U>): IncomingRequest<U>? {
    val files = parts.streams() ?: parts.clip().filterNotNull()
    return if (files.isEmpty()) null else IncomingRequest.SharedFiles(files, parts.type)
}
