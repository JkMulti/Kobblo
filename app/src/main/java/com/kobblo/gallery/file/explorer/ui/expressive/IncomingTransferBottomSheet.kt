package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.IncomingTransferRequest
import com.kobblo.gallery.file.explorer.localsend.PromptKind
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.PeerMerge
import com.kobblo.gallery.file.explorer.transfer.send.ReceiveFolder
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.send.TransferNotices
import com.kobblo.gallery.file.explorer.transfer.web.WebShareController
import com.kobblo.gallery.file.explorer.transfer.ui.NoteRow
import com.kobblo.gallery.file.explorer.transfer.ui.deviceIcon
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType

/**
 * Asks before anything arrives: who is sending, what, how much, and where it will go. Rejecting is
 * as easy as accepting, and swiping the sheet away counts as rejecting.
 *
 * A sender's name is whatever it says it is. Only a signature makes it "a device you know" here, and a
 * stranger that has borrowed a known device's name gets called out for it.
 */
@Composable
fun IncomingTransferBottomSheet(
    request: IncomingTransferRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val folder = remember { ReceiveFolder.resolve(context).name }
    val count = request.files.size
    val saved by PeerDirectory.saved.collectAsState()
    val browser = request.kind.isBrowser
    val known = !browser && request.proven && saved.any { it.fingerprint.equals(request.sender.fingerprint, ignoreCase = true) }
    val borrowedName = remember(saved) { !browser && PeerMerge.impersonates(request.sender.alias, request.sender.fingerprint, saved) }
    var alwaysAccept by remember { mutableStateOf(false) }
    var dontAskBrowser by remember { mutableStateOf(false) }

    KobbloSheet(onDismissRequest = onDecline) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = KobbloSpace.xs, bottom = KobbloSpace.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KobbloIconBadge(deviceIcon(request.sender.deviceType ?: "mobile"), KobbloTint.Blue, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = TransferNotices.title(context, request),
                        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            TransferNotices.summary(context, request).takeUnless { request.kind == PromptKind.NOTE },
                            if (browser) request.sender.alias else null,
                            if (known) stringResource(R.string.transfer_known_device) else stringResource(R.string.transfer_unverified)
                        ).joinToString(" · "),
                        style = KobbloType.footnote,
                        color = colors.smoke,
                        maxLines = 2
                    )
                }
            }

            if (borrowedName) {
                NoteRow(AppIcons.Warning, stringResource(R.string.transfer_borrowed_name, request.sender.alias), danger = true)
            }
            if (request.kind == PromptKind.FILES || request.kind == PromptKind.BROWSER_FILES) LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                itemsIndexed(request.files, key = { _, file -> file.fileId }) { index, file ->
                    val parent = file.fileName.substringBeforeLast('/', "")
                    val size = TransferFormat.size(context, file.size)
                    KobbloRow(
                        title = file.fileName.substringAfterLast('/'),
                        subtitle = if (parent.isNotEmpty()) "$parent · $size" else size,
                        modifier = Modifier
                            .padding(horizontal = KobbloSpace.screen)
                            .clip(KobbloShapes.groupItem(index, count))
                            .background(colors.snow),
                        showDivider = index < count - 1,
                        dividerInset = KobbloSpace.md
                    )
                }
            }

            KobbloGroupFooter(
                when (request.kind) {
                    PromptKind.BROWSER_OPEN -> stringResource(R.string.transfer_browser_open_detail)
                    PromptKind.NOTE, PromptKind.BROWSER_NOTE -> stringResource(R.string.transfer_note_accept_to_read)
                    else -> listOfNotNull(
                        if (request.alreadyHere > 0) pluralStringResource(R.plurals.transfer_some_already_here, request.alreadyHere, request.alreadyHere) else null,
                        stringResource(R.string.transfer_incoming_saved_to, folder),
                        if (known) null else stringResource(R.string.transfer_incoming_unverified)
                    ).joinToString(" ")
                }
            )
            // Offered only with a signature, because without one this couldn't be kept safely.
            if (request.proven && !browser) {
                KobbloGroup(modifier = Modifier.padding(top = 4.dp)) {
                    KobbloRow(
                        title = stringResource(R.string.transfer_always_accept),
                        subtitle = stringResource(R.string.transfer_always_accept_short),
                        trailing = { ExpressiveSwitch(checked = alwaysAccept, onCheckedChange = { alwaysAccept = it }) }
                    )
                }
            }
            if (browser) {
                KobbloGroup(modifier = Modifier.padding(top = 4.dp)) {
                    KobbloRow(
                        title = stringResource(R.string.transfer_dont_ask_browser),
                        subtitle = stringResource(R.string.transfer_dont_ask_browser_note),
                        trailing = { ExpressiveSwitch(checked = dontAskBrowser, onCheckedChange = { dontAskBrowser = it }) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            KobbloActionPair(
                confirmLabel = TransferNotices.acceptLabel(context, request),
                cancelLabel = stringResource(R.string.transfer_reject),
                onConfirm = {
                    if (alwaysAccept && request.proven) PeerDirectory.trustSender(request.sender, request.senderIp)
                    if (dontAskBrowser && browser) WebShareController.trustBrowser(request.senderIp)
                    onAccept()
                },
                onCancel = onDecline
            )
        }
    }
}
