package com.kobblo.gallery.file.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.ConnectionStatus
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloColors
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloFormField
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch

// Every network location, whatever its protocol, is listed and added the same way. The sheets that do
// it live here; a kind of server brings only its title, its icon, its form and its actions.

/** One saved connection as a list shows it: its [name], with a [detail] such as its address beneath. */
internal class SavedConnection(val id: String, val name: String, val detail: String)

/** The actions that add, open, close and forget one kind of connection. */
internal class ConnectionCommands<C>(
    val add: (connection: C, password: String) -> AppAction,
    val connect: (id: String) -> AppAction,
    val disconnect: (id: String) -> AppAction,
    val delete: (id: String) -> AppAction
)

/**
 * The saved connections of one kind, and the way to add another. With none saved yet there's no list
 * to show, so it opens on the form, and leaving the form leaves altogether.
 */
@Composable
internal fun <C : Any> SavedConnectionsDialog(
    title: String,
    icon: ImageVector,
    connections: List<SavedConnection>,
    activeId: String?,
    status: ConnectionStatus,
    form: ConnectionForm<C>,
    commands: ConnectionCommands<C>,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    var adding by remember { mutableStateOf(connections.isEmpty()) }

    if (adding) {
        NewConnectionSheet(
            form = form,
            onSave = { connection, password ->
                onAction(commands.add(connection, password))
                adding = false
                onDismiss()
            },
            onDismiss = { if (connections.isEmpty()) onDismiss() else adding = false }
        )
    } else {
        ConnectionListSheet(title = title, count = connections.size, onNew = { adding = true }, onDismiss = onDismiss) {
            connections.forEachIndexed { index, connection ->
                ConnectionRow(
                    connection = connection,
                    icon = icon,
                    // The light beside the one in use shows how it's doing.
                    status = status.takeIf { connection.id == activeId },
                    showDivider = index < connections.lastIndex,
                    onConnect = {
                        onAction(commands.connect(connection.id))
                        onDismiss()
                    },
                    onStop = { onAction(commands.disconnect(connection.id)) },
                    onDelete = { onAction(commands.delete(connection.id)) }
                )
            }
        }
    }
}

/**
 * [form] as a sheet. Its button hands over the connection and its password once nothing required is
 * blank; until then it says what's missing, above the fields.
 */
@Composable
private fun <C : Any> NewConnectionSheet(
    form: ConnectionForm<C>,
    onSave: (connection: C, password: String) -> Unit,
    onDismiss: () -> Unit
) {
    var entries by remember { mutableStateOf(form.blank()) }
    var triedIncomplete by remember { mutableStateOf(false) }

    ConnectionFormSheet(
        title = stringResource(form.title),
        subtitle = stringResource(form.subtitle),
        error = if (triedIncomplete) stringResource(form.incomplete) else null,
        confirmLabel = stringResource(form.confirm),
        footnote = form.footnote?.let { stringResource(it) },
        onDismiss = onDismiss,
        onConfirm = {
            val connection = form.connection(entries)
            if (connection == null) triedIncomplete = true else onSave(connection, entries[ConnectionField.Password])
        }
    ) {
        form.fields.forEachIndexed { index, field ->
            KobbloFormField(
                label = stringResource(field.label),
                value = entries[field],
                onValueChange = { entries = entries.with(field, it) },
                placeholder = field.placeholder(form),
                secret = field == ConnectionField.Password,
                // The last row of a group has nothing beneath it to be divided from.
                showDivider = index < form.fields.lastIndex || form.switchLabel != null,
                keyboardOptions = field.keyboard
            )
        }
        if (form.switchLabel != null) {
            KobbloRow(
                title = stringResource(form.switchLabel),
                trailing = {
                    ExpressiveSwitch(checked = entries.switchOn, onCheckedChange = { entries = entries.copy(switchOn = it) })
                }
            )
        }
    }
}

/** What an empty field says about itself: whether it can stay empty, or what belongs in it. */
@Composable
private fun ConnectionField.placeholder(form: ConnectionForm<*>): String = when (this) {
    ConnectionField.Name, ConnectionField.Domain -> stringResource(R.string.polish_optional)
    ConnectionField.Host, ConnectionField.Share, ConnectionField.Username -> stringResource(R.string.polish_required)
    ConnectionField.Server -> form.serverExample
    ConnectionField.Port, ConnectionField.Password -> ""
}

/** The keyboard that suits what a field takes. */
private val ConnectionField.keyboard: KeyboardOptions
    get() = when (this) {
        ConnectionField.Port -> KeyboardOptions(keyboardType = KeyboardType.Number)
        ConnectionField.Server -> KeyboardOptions(keyboardType = KeyboardType.Uri)
        ConnectionField.Password -> KeyboardOptions(keyboardType = KeyboardType.Password)
        else -> KeyboardOptions.Default
    }

/** Where a form asks for its details: a title, an inset group of fields, and the answer at its foot. */
@Composable
internal fun ConnectionFormSheet(
    title: String,
    subtitle: String?,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.save_action),
    confirmEnabled: Boolean = true,
    footnote: String? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    fields: @Composable ColumnScope.() -> Unit
) {
    val haptics = LocalHaptics.current
    val focusManager = LocalFocusManager.current
    KobbloSheet(onDismissRequest = onDismiss) {
        // Lifted with the keyboard, so the buttons stay in reach while typing.
        Column(modifier = Modifier.sheetContent().imePadding().padding(bottom = KobbloSpace.lg)) {
            KobbloSheetTitle(title, subtitle = subtitle)
            SheetScroll {
                if (error != null) {
                    Text(
                        text = error,
                        style = KobbloType.footnote,
                        color = KobbloDesign.colors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = KobbloSpace.content, end = KobbloSpace.content, bottom = KobbloSpace.xs)
                    )
                }
                KobbloGroup(content = fields)
                if (footnote != null) KobbloGroupFooter(footnote)
                below?.invoke(this)
            }
            Spacer(Modifier.height(KobbloSpace.md))
            KobbloActionPair(
                confirmLabel = confirmLabel,
                enabled = confirmEnabled,
                onConfirm = {
                    haptics.tap()
                    focusManager.clearFocus()
                    onConfirm()
                },
                onCancel = {
                    haptics.tap()
                    onDismiss()
                }
            )
        }
    }
}

/** A sheet listing what's saved, [rows], above the row that adds another. */
@Composable
private fun ConnectionListSheet(
    title: String,
    count: Int,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
    rows: @Composable ColumnScope.() -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    // Deleting the last one leaves nothing to list.
    LaunchedEffect(count) { if (count == 0) onDismiss() }
    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.sheetContent().padding(bottom = KobbloSpace.lg)) {
            KobbloSheetTitle(
                title,
                subtitle = if (count == 1) stringResource(R.string.polish_one_saved) else stringResource(R.string.polish_many_saved, count)
            )
            SheetScroll {
                KobbloGroup(content = rows)
                Spacer(Modifier.height(KobbloSpace.groupGap))
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.new_connection),
                        titleColor = colors.action,
                        leading = {
                            Icon(AppIcons.Add, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
                        },
                        onClick = {
                            haptics.tap()
                            onNew()
                        }
                    )
                }
            }
        }
    }
}

/**
 * One saved connection. Tapping it connects, and `...` offers what else can be done: a menu like any
 * other in the app, which asks before forgetting the connection. [status] is how the connection is
 * doing when this is the one in use, and null for the rest.
 */
@Composable
private fun ConnectionRow(
    connection: SavedConnection,
    icon: ImageVector,
    status: ConnectionStatus?,
    showDivider: Boolean,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val inUse = status != null
    var menuOpen by remember { mutableStateOf(false) }
    var askingToDelete by remember { mutableStateOf(false) }

    KobbloRow(
        title = connection.name,
        subtitle = connection.detail,
        leading = {
            Icon(icon, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
        },
        trailing = {
            if (status != null) {
                Box(modifier = Modifier.size(8.dp).clip(CapsuleShape).background(status.light(colors)))
                Spacer(Modifier.width(KobbloSpace.xxs))
            }
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.more_options),
                onClick = {
                    haptics.tap()
                    menuOpen = true
                },
                size = 32.dp,
                iconSize = 18.dp,
                tint = colors.smoke,
                background = Color.Transparent
            )
        },
        showDivider = showDivider,
        onClick = onConnect
    )

    if (menuOpen) {
        ItemMenuSheet(
            title = connection.name,
            subtitle = connection.detail,
            choice = MenuChoice(
                label = stringResource(if (inUse) R.string.stop_connection else R.string.connect_action),
                icon = if (inUse) AppIcons.Close else icon,
                onPick = {
                    menuOpen = false
                    if (inUse) onStop() else onConnect()
                }
            ),
            removal = MenuChoice(
                label = stringResource(R.string.delete_label),
                icon = AppIcons.Trash,
                onPick = {
                    menuOpen = false
                    askingToDelete = true
                }
            ),
            onDismiss = { menuOpen = false }
        )
    }

    if (askingToDelete) {
        ExpressiveConfirmationSheet(
            title = stringResource(R.string.polish_delete_connection, connection.name),
            subtitle = stringResource(R.string.polish_delete_connection_note),
            onConfirm = {
                haptics.delete()
                askingToDelete = false
                onDelete()
            },
            onDismissRequest = { askingToDelete = false }
        )
    }
}

/** The color of the light beside the connection in use: green once connected, red if it failed, gray meanwhile. */
private fun ConnectionStatus.light(colors: KobbloColors): Color = when (this) {
    ConnectionStatus.Connected -> colors.success
    ConnectionStatus.Error -> colors.danger
    ConnectionStatus.Idle, ConnectionStatus.Connecting -> colors.pewter
}

/** A sheet's own column: as wide as the sheet, and clear of the navigation bar. */
private fun Modifier.sheetContent(): Modifier = fillMaxWidth().navigationBarsPadding()

/**
 * The middle of a sheet, which scrolls once it's taller than the room it has. What's above and below
 * it in the sheet, the title and any buttons, stays where it is.
 */
@Composable
private fun ColumnScope.SheetScroll(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
        content = content
    )
}
