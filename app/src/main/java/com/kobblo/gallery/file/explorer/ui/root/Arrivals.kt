package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppAction.NearbyShareAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.transfer.send.TransferFiles
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.expressive.IncomingTransferBottomSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ReceivedFilesBottomSheet
import com.kobblo.gallery.file.explorer.ui.expressive.SaveToKobbloSheet
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import java.io.File

/** Covers everything, and takes every touch, while a cloud file is on its way to being opened. */
@Composable
internal fun DownloadCover(state: AppState) {
    if (!state.isDownloading) return
    val colors = KobbloDesign.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(enabled = false) {}
    ) {
        Surface(color = colors.snow, shape = KobbloShapes.xl) {
            Column(Modifier.padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                KobbloLoader()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.downloading_file), style = KobbloType.callout, color = colors.ink)
            }
        }
    }
}

/** Asks whether to take what a device nearby wants to send. */
@Composable
internal fun NearbyRequest(state: AppState, onAction: (AppAction) -> Unit) {
    val request = state.incomingNearbyRequest ?: return
    // A different question is a different sheet, whatever was switched on for the last one.
    key(request.sessionId) {
        IncomingTransferBottomSheet(
            request = request,
            onAccept = { onAction(NearbyShareAction.AcceptIncoming(request.sessionId)) },
            onDecline = { onAction(NearbyShareAction.DeclineIncoming(request.sessionId)) }
        )
    }
}

/** What has just been received, to open or to find. Files can arrive while one of the Hub's screens is up, so it goes first. */
@Composable
internal fun ReceivedFiles(state: AppState, onAction: (AppAction) -> Unit) {
    val prompt = state.receivedFilesPrompt ?: return
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    fun leaveFor(next: () -> Unit) {
        onAction(NearbyShareAction.DismissReceivedFilesPrompt)
        Hub.close()
        next()
    }
    ReceivedFilesBottomSheet(
        prompt = prompt,
        onOpen = { file -> leaveFor { FileOpener(context, state, focus, onAction).open(file) } },
        onOpenFolder = { folder ->
            leaveFor {
                onAction(AppAction.NavTabTapped("browse"))
                onAction(AppAction.SetLocation(folder, File(folder).name))
            }
        },
        onDismiss = { onAction(NearbyShareAction.DismissReceivedFilesPrompt) }
    )
}

/** What another app has shared into this one, to save or to send on to a device nearby. */
@Composable
internal fun SharedIn(state: AppState, onAction: (AppAction) -> Unit) {
    val prompt = state.incomingSharePrompt ?: return
    val context = LocalContext.current
    SaveToKobbloSheet(
        prompt = prompt,
        onSave = { folder -> onAction(AppAction.SaveIncomingShare(folder)) },
        onDismiss = { onAction(AppAction.DismissIncomingShare) },
        onSendNearby = {
            val toSend = prompt.files.map { TransferFiles.fromUri(context, it.uri, it.name, it.sizeBytes) }
            onAction(NearbyShareAction.StageFiles(toSend))
            onAction(AppAction.DismissIncomingShare)
            Hub.close()
            onAction(AppAction.SetLocation("send"))
        }
    )
}
