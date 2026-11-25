package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.WebDavState
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The saved WebDAV servers, each shown with its address, and the form that adds one. */
@Composable
fun WebDavConnectionsDialog(
    state: WebDavState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    SavedConnectionsDialog(
        title = stringResource(R.string.polish_webdav_title),
        icon = AppIcons.CloudSync,
        connections = rememberWebDavConnections(state, nextcloud = false),
        activeId = state.activeConnectionId,
        status = state.connectionStatus,
        form = WebDavForm,
        commands = WebDavCommands,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

/**
 * Nextcloud servers are WebDAV servers with a mark on them, saved in the one list. This is the half of
 * that list one of the two dialogs shows: the marked ones for [nextcloud], or the rest.
 */
@Composable
internal fun rememberWebDavConnections(state: WebDavState, nextcloud: Boolean): List<SavedConnection> =
    remember(state.savedConnections, nextcloud) {
        state.savedConnections
            .filter { it.isNextcloud == nextcloud }
            .map { SavedConnection(id = it.id, name = it.displayName, detail = it.serverUrl) }
    }

/** Both halves are opened, closed and forgotten by the same actions. */
internal val WebDavCommands = ConnectionCommands<WebDavConnection>(
    add = AppAction.WebDavAction::AddConnection,
    connect = AppAction.WebDavAction::Connect,
    disconnect = AppAction.WebDavAction::Disconnect,
    delete = AppAction.WebDavAction::DeleteConnection
)
