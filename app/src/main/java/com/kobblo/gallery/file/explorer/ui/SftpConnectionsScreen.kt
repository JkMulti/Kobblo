package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.SftpState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The saved SFTP servers, each shown with its host and port, and the form that adds one. */
@Composable
fun SftpConnectionsDialog(
    state: SftpState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    SavedConnectionsDialog(
        title = stringResource(R.string.sftp_connections_title),
        icon = AppIcons.Shield,
        connections = state.savedConnections.map { server ->
            SavedConnection(
                id = server.id,
                name = server.displayName,
                detail = stringResource(R.string.sftp_connection_details, server.host, server.port)
            )
        },
        activeId = state.activeConnectionId,
        status = state.connectionStatus,
        form = SftpForm,
        commands = SftpCommands,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

private val SftpCommands = ConnectionCommands(
    add = AppAction.SftpAction::AddConnection,
    connect = AppAction.SftpAction::Connect,
    disconnect = AppAction.SftpAction::Disconnect,
    delete = AppAction.SftpAction::DeleteConnection
)
