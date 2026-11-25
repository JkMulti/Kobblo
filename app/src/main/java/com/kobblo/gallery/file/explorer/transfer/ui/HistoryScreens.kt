package com.kobblo.gallery.file.explorer.transfer.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.core.TransferDirection
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecord
import com.kobblo.gallery.file.explorer.transfer.core.TransferRecordFile
import com.kobblo.gallery.file.explorer.transfer.core.TransferResult
import com.kobblo.gallery.file.explorer.transfer.send.TransferFiles
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.send.TransferHistory
import com.kobblo.gallery.file.explorer.ui.handleFileOpen
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File
import java.util.Calendar
import java.util.Date

private val listPadding: PaddingValues
    @Composable get() = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

/** Everything sent and received, newest first, grouped by day. */
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val records by TransferHistory.records.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { TransferHistory.attach(context) }

    HubScreen(
        title = stringResource(R.string.transfer_history),
        backLabel = stringResource(R.string.transfer_send_tab),
        trailingIcon = if (records.isNotEmpty()) AppIcons.Trash else null,
        trailingDescription = stringResource(R.string.transfer_history_clear),
        onTrailingClick = { confirmClear = true }
    ) {
        if (records.isEmpty()) {
            KobbloMessage(
                icon = AppIcons.Clock,
                title = stringResource(R.string.transfer_history_empty_title),
                body = stringResource(R.string.transfer_history_empty_body),
                modifier = Modifier.padding(top = 64.dp)
            )
        } else {
            val days = remember(records) { records.groupBy { dayKey(it.timeMs) }.values.toList() }
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listPadding) {
                days.forEach { day ->
                    item(key = "day-${dayKey(day.first().timeMs)}") {
                        KobbloGroupHeader(title = dayTitle(context, day.first().timeMs))
                    }
                    itemsIndexed(day, key = { _, record -> record.id }) { index, record ->
                        RecordRow(
                            record = record,
                            showDivider = index < day.lastIndex,
                            modifier = Modifier
                                .padding(horizontal = KobbloSpace.screen)
                                .clip(KobbloShapes.groupItem(index, day.size))
                                .background(KobbloDesign.colors.snow)
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet(
            title = stringResource(R.string.transfer_history_clear_title),
            subtitle = stringResource(R.string.transfer_history_clear_body),
            confirmLabel = stringResource(R.string.transfer_history_clear),
            cancelLabel = stringResource(R.string.cancel),
            onConfirm = {
                confirmClear = false
                TransferHistory.clear()
            },
            onDismissRequest = { confirmClear = false }
        )
    }
}

@Composable
private fun RecordRow(record: TransferRecord, showDivider: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val time = DateFormat.getTimeFormat(context).format(Date(record.timeMs))
    val summary = buildString {
        if (record.note != null) {
            append(com.kobblo.gallery.file.explorer.transfer.send.Notes.label(record.note))
        } else {
            append(pluralStringResourceText(context, record.fileCount))
            append(" · ").append(TransferFormat.size(context, record.totalBytes))
        }
        append(" · ").append(time)
        resultLabel(context, record.result)?.let { append(" · ").append(it) }
    }
    KobbloRow(
        title = recordTitle(context, record),
        subtitle = summary,
        modifier = modifier,
        leading = {
            KobbloIconBadge(
                icon = if (record.direction == TransferDirection.SENT) AppIcons.Upload else AppIcons.Download,
                color = when (record.result) {
                    TransferResult.COMPLETED -> KobbloTint.Blue
                    TransferResult.PARTIAL, TransferResult.FAILED -> KobbloTint.Red
                    else -> KobbloTint.Gray
                }
            )
        },
        trailing = { KobbloChevron() },
        showDivider = showDivider,
        onClick = { Hub.open(TransferRoute.HistoryDetail(record.id)) }
    )
}

/** One transfer in full, with its files to open and a way to send them again. */
@Composable
fun HistoryDetailScreen(id: String, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val records by TransferHistory.records.collectAsState()
    val record = records.firstOrNull { it.id == id }
    LaunchedEffect(record == null) {
        if (record == null) Hub.back()
    }
    if (record == null) return

    val existing = remember(record) { record.files.filter { it.path != null && pathExists(it.path) } }
    // What's already over there doesn't need to go again, or it would arrive twice.
    val partlySent = record.result == TransferResult.PARTIAL && record.direction == TransferDirection.SENT
    val toSend = remember(existing, partlySent) {
        if (partlySent) existing.filter { !it.ok }.ifEmpty { existing } else existing
    }

    HubScreen(title = recordTitle(context, record), backLabel = stringResource(R.string.transfer_history)) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listPadding) {
            item(key = "facts") {
                Spacer(Modifier.height(8.dp))
                KobbloGroup {
                    FactRow(stringResource(R.string.transfer_result), resultLabel(context, record.result) ?: stringResource(R.string.transfer_result_completed), showDivider = true)
                    FactRow(
                        stringResource(R.string.transfer_when),
                        DateUtils.formatDateTime(context, record.timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
                        showDivider = true
                    )
                    if (record.note == null) {
                        FactRow(stringResource(R.string.transfer_size), TransferFormat.size(context, record.totalBytes), showDivider = record.peerAddress != null || record.message != null)
                    }
                    record.peerAddress?.let { FactRow(stringResource(R.string.transfer_address), it, showDivider = record.message != null) }
                    record.message?.let { message ->
                        KobbloRow(title = message, titleColor = KobbloDesign.colors.danger)
                    }
                }
            }
            record.note?.let { note ->
                item(key = "note") {
                    NoteBlock(note)
                }
            }
            if (record.note == null) item(key = "files-title") {
                KobbloGroupHeader(title = pluralStringResource(R.plurals.transfer_file_count, record.fileCount, record.fileCount))
            }
            itemsIndexed(record.files, key = { index, file -> "$index-${file.name}" }) { index, file ->
                RecordFileRow(
                    file = file,
                    showDivider = index < record.files.lastIndex,
                    modifier = Modifier
                        .padding(horizontal = KobbloSpace.screen)
                        .clip(KobbloShapes.groupItem(index, record.files.size))
                        .background(KobbloDesign.colors.snow),
                    onOpen = { path ->
                        // Viewers are drawn beneath these screens.
                        Hub.close()
                        handleFileOpen(TransferFiles.fromFile(context, File(path)), context, state, focus, onAction)
                    }
                )
            }
            if (record.moreFiles > 0) {
                item(key = "more") {
                    Text(
                        text = pluralStringResource(R.plurals.transfer_more_files_count, record.moreFiles, record.moreFiles),
                        style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.footnote,
                        color = KobbloDesign.colors.smoke,
                        modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = 8.dp)
                    )
                }
            }
            item(key = "actions") {
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    if (toSend.isNotEmpty()) {
                        WideButton(
                            text = if (partlySent && toSend.size < existing.size) {
                                pluralStringResource(R.plurals.transfer_send_the_rest, toSend.size, toSend.size)
                            } else {
                                stringResource(R.string.transfer_send_again)
                            },
                            icon = AppIcons.Send,
                            onClick = {
                                val items = toSend.map { file ->
                                    val path = file.path.orEmpty()
                                    if (path.startsWith("content://")) {
                                        TransferFiles.fromUri(context, android.net.Uri.parse(path), file.name, file.size)
                                    } else {
                                        TransferFiles.fromFile(context, File(path), file.name)
                                    }
                                }
                                NearbyShareRepository.stageFiles(items)
                                Hub.close()
                                onAction(AppAction.SetLocation("send"))
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    WideButton(
                        text = stringResource(R.string.transfer_remove_from_history),
                        danger = true,
                        onClick = { TransferHistory.remove(record.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteBlock(note: String) {
    val context = LocalContext.current
    val link = remember(note) { com.kobblo.gallery.file.explorer.transfer.core.IncomingRules.firstLink(note) }
    KobbloGroupHeader(title = stringResource(R.string.transfer_note_title))
    KobbloGroup {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                note,
                style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.body,
                color = KobbloDesign.colors.ink,
                modifier = Modifier.padding(horizontal = KobbloSpace.md, vertical = 12.dp)
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    if (link != null) {
        WideButton(
            text = stringResource(R.string.transfer_note_open, com.kobblo.gallery.file.explorer.transfer.core.IncomingRules.linkHost(link)),
            icon = AppIcons.OpenExternal,
            onClick = { com.kobblo.gallery.file.explorer.transfer.send.ReceivedActions.openLink(context, link) }
        )
        Spacer(Modifier.height(8.dp))
    }
    WideButton(
        text = stringResource(R.string.transfer_copy),
        icon = AppIcons.Copy,
        secondary = true,
        onClick = { com.kobblo.gallery.file.explorer.transfer.send.ReceivedActions.copy(context, note) }
    )
}

@Composable
private fun FactRow(title: String, value: String, showDivider: Boolean) {
    KobbloRow(
        title = title,
        showDivider = showDivider,
        trailing = {
            Text(
                value,
                style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.callout,
                color = KobbloDesign.colors.smoke,
                maxLines = 2
            )
        }
    )
}

@Composable
private fun RecordFileRow(file: TransferRecordFile, showDivider: Boolean, modifier: Modifier, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val path = file.path?.takeIf { pathExists(it) && file.ok && !it.startsWith("content://") }
    val folder = file.name.substringBeforeLast('/', "")
    val size = TransferFormat.size(context, file.size)
    KobbloRow(
        title = file.name.substringAfterLast('/'),
        subtitle = when {
            !file.ok -> stringResource(R.string.transfer_file_not_sent)
            folder.isNotEmpty() -> "$folder · $size"
            else -> size
        },
        modifier = modifier,
        showDivider = showDivider,
        dividerInset = KobbloSpace.md,
        trailing = when {
            !file.ok -> {
                { Icon(AppIcons.Alert, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp)) }
            }
            path != null -> {
                { KobbloChevron() }
            }
            else -> null
        },
        onClick = path?.let { { onOpen(it) } }
    )
}

private fun pathExists(path: String): Boolean =
    path.startsWith("content://") || File(path).exists()

private fun recordTitle(context: android.content.Context, record: TransferRecord): String =
    if (record.direction == TransferDirection.SENT) context.getString(R.string.transfer_to_peer, record.peerAlias)
    else context.getString(R.string.transfer_from_peer, record.peerAlias)

private fun pluralStringResourceText(context: android.content.Context, count: Int): String =
    context.resources.getQuantityString(R.plurals.transfer_file_count, count, count)

/** A word for the result, except for plain success, which needs none in a list. */
private fun resultLabel(context: android.content.Context, result: TransferResult): String? = when (result) {
    TransferResult.COMPLETED -> null
    TransferResult.PARTIAL -> context.getString(R.string.transfer_result_partial)
    TransferResult.FAILED -> context.getString(R.string.transfer_result_failed)
    TransferResult.CANCELLED -> context.getString(R.string.transfer_result_cancelled)
    TransferResult.DECLINED -> context.getString(R.string.transfer_result_declined)
}

private fun dayKey(timeMs: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = timeMs }
    return calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun dayTitle(context: android.content.Context, timeMs: Long): String = when {
    DateUtils.isToday(timeMs) -> context.getString(R.string.transfer_today)
    DateUtils.isToday(timeMs + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.transfer_yesterday)
    else -> DateUtils.formatDateTime(context, timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
}
