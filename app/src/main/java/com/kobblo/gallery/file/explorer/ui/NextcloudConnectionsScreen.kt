package com.kobblo.gallery.file.explorer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.WebDavState
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** The saved Nextcloud servers, each shown with its address, and the form that adds one. */
@Composable
fun NextcloudConnectionsDialog(
    state: WebDavState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit
) {
    SavedConnectionsDialog(
        title = stringResource(R.string.polish_nextcloud_title),
        icon = AppIcons.CloudSync,
        connections = rememberWebDavConnections(state, nextcloud = true),
        activeId = state.activeConnectionId,
        status = state.connectionStatus,
        form = NextcloudForm,
        commands = WebDavCommands,
        onAction = onAction,
        onDismiss = onDismiss
    )
}
