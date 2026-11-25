package com.kobblo.gallery.file.explorer.transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.ReceivedNote
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.core.IncomingRules
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.send.ReceivedActions
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * Everything of this area that appears over whatever is on screen, because it arrives on its own time.
 * One line in the app's root draws it.
 */
@Composable
fun TransferOverlays() {
    val context = LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        NearbyShareRepository.attach(context)
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Not started and stopped: a stop can wait ten seconds on whatever's now in front going idle, and a
            // video in its little window is started without anyone being able to see a sheet in it.
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> NearbyShareRepository.setAppShowing(true)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> NearbyShareRepository.setAppShowing(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            NearbyShareRepository.setAppShowing(false)
        }
    }
    val note by NearbyShareRepository.receivedNote.collectAsState()
    note?.let { current ->
        ReceivedNoteSheet(current, onDismiss = NearbyShareRepository::dismissReceivedNote)
    }
}

/** A note someone sent. It's only words until someone taps: a link opens from the button and nowhere else. */
@Composable
private fun ReceivedNoteSheet(note: ReceivedNote, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val link = remember(note.text) { IncomingRules.firstLink(note.text) }
    var copied by remember(note) { mutableStateOf(false) }

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(stringResource(R.string.transfer_note_from, note.fromAlias))
            KobbloGroup {
                SelectionContainer {
                    Text(
                        text = note.text,
                        style = KobbloType.body,
                        color = colors.ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = KobbloSpace.md, vertical = 12.dp)
                    )
                }
            }
            if (link != null) {
                KobbloGroupFooter(stringResource(R.string.transfer_note_link_warning))
            }
            Spacer(Modifier.height(12.dp))
            if (link != null) {
                WideButton(
                    text = stringResource(R.string.transfer_note_open, IncomingRules.linkHost(link)),
                    icon = AppIcons.OpenExternal,
                    onClick = {
                        ReceivedActions.openLink(context, link)
                        onDismiss()
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
            WideButton(
                text = stringResource(if (copied) R.string.transfer_note_copied else R.string.transfer_copy),
                icon = AppIcons.Copy,
                secondary = link != null,
                onClick = {
                    ReceivedActions.copy(context, note.text)
                    copied = true
                }
            )
        }
    }
}

/** Writing a note to send. It's added to what's chosen, like anything else. */
@Composable
fun NoteComposerSheet(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = KobbloDesign.colors
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(stringResource(R.string.transfer_note_compose_title))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KobbloSpace.screen)
                    .clip(KobbloShapes.group)
                    .background(colors.snow)
                    .padding(KobbloSpace.inset)
                    .heightIn(min = 96.dp, max = 240.dp)
                    .focusRequester(focus),
                textStyle = KobbloType.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.action),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(stringResource(R.string.transfer_note_placeholder), style = KobbloType.body, color = colors.smoke)
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(12.dp))
            WideButton(
                text = stringResource(R.string.transfer_add),
                enabled = text.isNotBlank(),
                onClick = { onAdd(text) }
            )
        }
    }
}

/**
 * One known device, from holding it in a list. Says plainly what always accepting means, and only offers
 * it for a device that can prove who it is, because that's the only kind it could safely apply to.
 */
@Composable
fun KnownDeviceSheet(fingerprint: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved by PeerDirectory.saved.collectAsState()
    val peer: SavedPeer = saved.firstOrNull { it.fingerprint.equals(fingerprint, ignoreCase = true) } ?: run {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(
                text = peer.alias,
                subtitle = listOfNotNull(
                    peer.deviceModel?.takeIf { it.isNotBlank() },
                    peer.lastUsedMs.takeIf { it > 0 }?.let { lastUsed(context, it) }
                ).joinToString(" · ")
            )
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.transfer_always_accept),
                    trailing = {
                        ExpressiveSwitch(
                            checked = peer.autoAccept && peer.canProve,
                            enabled = peer.canProve,
                            onCheckedChange = { PeerDirectory.setAutoAccept(peer.fingerprint, it) }
                        )
                    }
                )
            }
            KobbloGroupFooter(
                stringResource(if (peer.canProve) R.string.transfer_always_accept_risk else R.string.transfer_always_accept_unavailable, peer.alias)
            )
            Spacer(Modifier.height(16.dp))
            WideButton(
                text = stringResource(R.string.transfer_forget_device),
                danger = true,
                onClick = {
                    PeerDirectory.forget(peer)
                    onDismiss()
                }
            )
            KobbloGroupFooter(stringResource(R.string.transfer_forget_note))
        }
    }
}
