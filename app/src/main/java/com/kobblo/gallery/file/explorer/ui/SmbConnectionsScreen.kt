package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.SmbState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The saved Windows and NAS shares, each shown with its host, port and share, and the form that adds one. */
@Composable
fun SmbConnectionsDialog(
    state: SmbState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    SavedConnectionsDialog(
        title = stringResource(R.string.smb_connections_title),
        icon = AppIcons.Server,
        connections = state.savedConnections.map { share ->
            SavedConnection(
                id = share.id,
                name = share.displayName,
                detail = stringResource(R.string.smb_connection_details, share.host, share.port, share.shareName)
            )
        },
        activeId = state.activeConnectionId,
        status = state.connectionStatus,
        form = SmbForm,
        commands = SmbCommands,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

private val SmbCommands = ConnectionCommands(
    add = AppAction.SmbAction::AddConnection,
    connect = AppAction.SmbAction::Connect,
    disconnect = AppAction.SmbAction::Disconnect,
    delete = AppAction.SmbAction::DeleteConnection
)
