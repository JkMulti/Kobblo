package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FtpState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The saved FTP servers, each shown with its host and port, and the form that adds one. */
@Composable
fun FtpConnectionsDialog(
    state: FtpState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    SavedConnectionsDialog(
        title = stringResource(R.string.ftp_connections_title),
        icon = AppIcons.FolderShared,
        connections = state.savedConnections.map { server ->
            SavedConnection(
                id = server.id,
                name = server.displayName,
                detail = stringResource(R.string.ftp_connection_details, server.host, server.port)
            )
        },
        activeId = state.activeConnectionId,
        status = state.connectionStatus,
        form = FtpForm,
        commands = FtpCommands,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

private val FtpCommands = ConnectionCommands(
    add = AppAction.FtpAction::AddConnection,
    connect = AppAction.FtpAction::Connect,
    disconnect = AppAction.FtpAction::Disconnect,
    delete = AppAction.FtpAction::DeleteConnection
)
