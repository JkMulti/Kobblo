package com.kobblo.gallery.file.explorer.startup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.kobblo.gallery.file.explorer.MainViewModel
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.tools.files.ToolsFiles
import com.kobblo.gallery.file.explorer.transfer.TransferEntry

/** Does what the intent that opened the app asked for. What that is comes from [requestIn]. */
internal class IncomingIntents(private val context: Context, private val model: () -> MainViewModel) {

    fun handle(intent: Intent) {
        when (val request = requestIn(AndroidIntentParts(intent))) {
            null -> Unit
            is IncomingRequest.SharedFiles -> model().handleIncomingShare(request.files, request.type)
            is IncomingRequest.SharedText -> {
                // The app offers itself for any share, so words and links must go somewhere too.
                NearbyShareRepository.stageNote(context, request.text)
                openSendTab()
            }
            is IncomingRequest.OpenFile -> model().handleOpenFileFromIntent(request.file, request.type)
            is IncomingRequest.OpenFolder ->
                ToolsFiles.resolveShortcutTarget(context, request.path)?.let { model().showFolderFromOutside(it) }
            IncomingRequest.ShowPlayer -> model().setShowFullScreenPlayer(true)
            IncomingRequest.GetReadyToReceive -> openSendTab()
            // The answer has to match what's being asked, and only the app's own notification knows its id.
            is IncomingRequest.AcceptFiles -> NearbyShareRepository.acceptIncomingRequest(request.promptId)
        }
    }

    private fun openSendTab() = model().setLocation("send")
}

private class AndroidIntentParts(private val intent: Intent) : IntentParts<Uri> {
    override val action: String? get() = intent.action
    override val type: String? get() = intent.type
    override val data: Uri? get() = intent.data

    override fun stream(): Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    override fun streams(): List<Uri>? = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

    override fun clip(): List<Uri?> = intent.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }.orEmpty()

    override fun sharedText(): String? = TransferEntry.sharedText(intent)

    override fun extra(name: String): String? = intent.getStringExtra(name)
}
