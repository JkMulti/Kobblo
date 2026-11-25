package com.kobblo.gallery.file.explorer.transfer.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.connect.QrImage
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.ui.NoteRow
import com.kobblo.gallery.file.explorer.transfer.ui.SmallCapsule
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

private fun pluralOutcome(context: Context, outcome: WebOutcome): String = context.resources.getQuantityString(
    when (outcome.kind) {
        WebOutcome.Kind.ACCEPTED -> R.plurals.web_share_outcome_accepted
        WebOutcome.Kind.REJECTED -> R.plurals.web_share_outcome_rejected
        WebOutcome.Kind.DOWNLOADED -> R.plurals.web_share_outcome_downloaded
    },
    outcome.count,
    outcome.count
)

/**
 * Sending to a computer with no app on it: a link any browser can open. It's off until switched on,
 * says plainly that it's unencrypted, shows who has connected, and stops itself when left idle.
 */
@Composable
fun WebShareScreen() {
    val context = LocalContext.current
    val state by WebShareController.state.collectAsState()
    val staged by NearbyShareRepository.stagedFiles.collectAsState()

    // Whatever's chosen while it's on is what's on offer.
    LaunchedEffect(staged, state.running) {
        if (state.running) WebShareController.setFiles(context, staged)
    }

    HubScreen(title = stringResource(R.string.web_share_title), backLabel = stringResource(R.string.transfer_send_tab)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.web_share_switch),
                    subtitle = stringResource(if (state.running) R.string.web_share_on else R.string.web_share_off),
                    trailing = {
                        if (state.starting) {
                            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            ExpressiveSwitch(
                                checked = state.running,
                                onCheckedChange = { on ->
                                    if (on) WebShareService.start(context) else WebShareService.stop(context)
                                }
                            )
                        }
                    }
                )
            }

            if (state.stoppedForInactivity) {
                NoteRow(AppIcons.Clock, stringResource(R.string.web_share_stopped_idle), modifier = Modifier.padding(top = 4.dp))
            }
            state.failure?.let {
                NoteRow(AppIcons.Alert, stringResource(R.string.web_share_failed), danger = true, modifier = Modifier.padding(top = 4.dp))
            }

            if (!state.running) {
                KobbloGroupFooter(stringResource(R.string.web_share_intro))
            }

            val url = state.url
            if (state.running && url == null) {
                NoteRow(AppIcons.Alert, stringResource(R.string.transfer_no_address), danger = true, modifier = Modifier.padding(top = 8.dp))
            }
            if (state.running && url != null) {
                Spacer(Modifier.height(16.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrImage(
                        text = url,
                        description = stringResource(R.string.web_share_qr_description),
                        modifier = Modifier.padding(horizontal = 64.dp).fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = url,
                        style = KobbloType.footnote.copy(fontFamily = FontFamily.Monospace),
                        color = KobbloDesign.colors.ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = KobbloSpace.screen)
                    )
                    Spacer(Modifier.height(8.dp))
                    SmallCapsule(
                        stringResource(R.string.transfer_copy),
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.web_share_switch), url))
                        }
                    )
                }
                NoteRow(AppIcons.Warning, stringResource(R.string.web_share_warning), modifier = Modifier.padding(top = 12.dp))
            }

            KobbloGroupHeader(title = stringResource(R.string.web_share_files))
            KobbloGroup {
                KobbloRow(
                    title = when {
                        state.running && state.fileCount > 0 -> pluralStringResource(R.plurals.transfer_file_count, state.fileCount, state.fileCount)
                        staged.isNotEmpty() -> pluralStringResource(R.plurals.transfer_item_count, staged.size, staged.size)
                        else -> stringResource(R.string.web_share_no_files)
                    },
                    subtitle = if (state.running && state.fileCount > 0) TransferFormat.size(context, state.totalBytes) else stringResource(R.string.web_share_upload_note),
                    leading = { Icon(AppIcons.Folder, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp)) },
                    trailing = { KobbloChevron() },
                    onClick = { Hub.open(TransferRoute.Picker()) }
                )
            }

            if (state.running) {
                KobbloGroupHeader(title = stringResource(R.string.web_share_clients))
                KobbloGroup {
                    if (state.clients.isEmpty()) {
                        KobbloRow(title = stringResource(R.string.web_share_no_clients), titleColor = KobbloDesign.colors.smoke)
                    }
                    state.clients.forEachIndexed { index, client ->
                        KobbloRow(
                            title = client.userAgent,
                            subtitle = buildString {
                                append(client.address)
                                append(" · ")
                                append(DateUtils.getRelativeTimeSpanString(client.lastSeenMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS))
                                if (client.bytesSent > 0) {
                                    append(" · ").append(context.getString(R.string.web_share_client_sent, TransferFormat.size(context, client.bytesSent)))
                                }
                                if (client.bytesReceived > 0) {
                                    append(" · ").append(context.getString(R.string.web_share_client_received, TransferFormat.size(context, client.bytesReceived)))
                                }
                                client.outcome?.let { outcome ->
                                    append(" · ").append(
                                        pluralOutcome(context, outcome)
                                    )
                                }
                                if (!client.allowed) append(" · ").append(context.getString(R.string.web_share_client_waiting))
                            },
                            leading = { Icon(AppIcons.Laptop, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp)) },
                            showDivider = index < state.clients.lastIndex
                        )
                    }
                }
                KobbloGroupFooter(stringResource(R.string.web_share_idle_note, WebShareController.IDLE_TIMEOUT_MS / 60_000))
            }
        }
    }
}
