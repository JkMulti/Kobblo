package com.kobblo.gallery.file.explorer.transfer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.TransferSession
import com.kobblo.gallery.file.explorer.localsend.TransferStatus
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * What's moving right now, on the Send tab. It reads the repository directly: progress ticks several
 * times a second, and going through the app's state would recompose every screen each time.
 */
@Composable
fun TransferStatusCard(modifier: Modifier = Modifier) {
    val session by NearbyShareRepository.activeTransfer.collectAsState()
    val queue by NearbyShareRepository.sendQueue.collectAsState()
    val unfinished by NearbyShareRepository.unfinished.collectAsState()
    val current = session ?: return
    // The sheet announcing what arrived already covers this, and offers to open it.
    if (current.isIncoming && current.status == TransferStatus.COMPLETED) return

    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val moving = !current.status.isFinal
    val multi = queue?.takeIf { it.peers.size > 1 }
    val finishedMulti = multi?.takeIf { it.finished }
    // After several devices, the last one's result isn't the result. The whole list is.
    val title = finishedMulti?.let { state ->
        val sent = state.results.count { it.status == TransferStatus.COMPLETED }
        pluralStringResource(R.plurals.transfer_multi_done, state.peers.size, sent, state.peers.size)
    } ?: TransferFormat.title(context, current)
    val failedPeers = finishedMulti?.results?.filter { it.status != TransferStatus.COMPLETED }.orEmpty()
    val detail = when {
        finishedMulti == null -> TransferFormat.detail(context, current)
        failedPeers.isEmpty() -> TransferFormat.size(context, current.totalBytes)
        else -> stringResource(R.string.transfer_multi_failed, failedPeers.joinToString(", ") { it.peer.alias })
    }
    val allWentWell = if (finishedMulti != null) failedPeers.isEmpty() else current.status == TransferStatus.COMPLETED

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        KobbloGroup {
            Column(modifier = Modifier.padding(KobbloSpace.inset)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KobbloIconBadge(
                        icon = when {
                            moving && current.isIncoming -> AppIcons.Download
                            moving -> AppIcons.Upload
                            allWentWell -> AppIcons.Check
                            else -> AppIcons.Warning
                        },
                        color = when {
                            moving -> KobbloTint.Blue
                            allWentWell -> KobbloTint.Green
                            else -> KobbloTint.Gray
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = KobbloType.body.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = detail,
                            style = KobbloType.footnote,
                            color = if (!moving && !allWentWell && current.status != TransferStatus.CANCELLED) colors.danger else colors.smoke,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (moving) {
                        SmallCapsule(stringResource(R.string.cancel), onClick = NearbyShareRepository::cancelActiveTransfer)
                    } else {
                        SmallCapsule(stringResource(R.string.done_action), onClick = NearbyShareRepository::dismissTransferCard)
                    }
                }
                if (current.status == TransferStatus.IN_PROGRESS) {
                    Spacer(Modifier.height(12.dp))
                    ProgressLine(current.progress)
                }
                // Only for a transfer that stopped by itself. A refusal or a cancel was someone's decision.
                if (!moving && !current.isIncoming && unfinished.isNotEmpty()) {
                    val left = unfinished.sumOf { it.missing.size }
                    val partly = unfinished.any { it.done.isNotEmpty() }
                    Spacer(Modifier.height(12.dp))
                    WideRetry(
                        text = if (partly) pluralStringResource(R.plurals.transfer_send_the_rest, left, left) else stringResource(R.string.transfer_try_again),
                        onClick = { NearbyShareRepository.retryUnfinished(context) }
                    )
                }
            }
            // Once several devices are done, the file list would only describe the last of them.
            // A note is one thing, on the other screen by now, and has no files to look through.
            if (finishedMulti == null && (current.status == TransferStatus.IN_PROGRESS || current.status.isFinal) &&
                current.files.singleOrNull()?.preview == null
            ) {
                FilesRow(current)
            }
        }
        if (multi != null && finishedMulti == null) {
            Text(
                text = stringResource(R.string.transfer_multi_progress, multi.index + 1, multi.peers.size),
                style = KobbloType.footnote,
                color = colors.smoke,
                modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun WideRetry(text: String, onClick: () -> Unit) {
    SmallCapsule(text, onClick = onClick, filled = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FilesRow(session: TransferSession) {
    val total = session.files.size
    val done = session.finishedFiles
    val currentName = session.files.getOrNull(session.currentFileIndex)?.fileName?.substringAfterLast('/')
    KobbloRow(
        title = stringResource(R.string.transfer_files_progress, done, total),
        subtitle = if (!session.status.isFinal) currentName else null,
        trailing = { KobbloChevron() },
        onClick = { Hub.open(TransferRoute.Current) }
    )
}
