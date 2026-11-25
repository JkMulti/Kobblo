package com.kobblo.gallery.file.explorer.ui.expressive

import android.os.Environment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.getStorageIdForLocation
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * Every location in one sheet, opened from Browse's "..." button. Tapping one opens it, the one last
 * browsed is ticked, and a location that was added can be disconnected here, after being asked once
 * more. Each of those closes the sheet, as does going on to add a location.
 */
@Composable
internal fun ManageLocationsSheet(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onAddLocation: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val storages = remember(state, context) { connectedStorages(context, state) }
    val browsedId = remember(state.lastBrowseLocation, state.sdCardPath) {
        val browsed = state.lastBrowseLocation.ifEmpty { Environment.getExternalStorageDirectory().absolutePath }
        getStorageIdForLocation(browsed, state.sdCardPath, state.googleDriveAccountEmail, state.megaAccountEmail)
    }
    var leaving by remember { mutableStateOf<ConnectedStorage?>(null) }

    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(title = stringResource(R.string.storage_locations)) {
            KobbloGroup {
                storages.forEachIndexed { position, storage ->
                    KobbloRow(
                        title = storage.name,
                        subtitle = storage.summary,
                        leading = { Icon(storage.icon, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                        trailing = {
                            if (storage.canDisconnect) {
                                KobbloIconButton(
                                    icon = AppIcons.Logout,
                                    description = stringResource(R.string.screen_disconnect_named, storage.name),
                                    onClick = { leaving = storage },
                                    size = 32.dp,
                                    iconSize = 18.dp,
                                    tint = colors.danger,
                                    background = Color.Transparent
                                )
                            }
                            if (storage.id == browsedId) {
                                Icon(AppIcons.Check, contentDescription = stringResource(R.string.screen_active), tint = colors.action, modifier = Modifier.size(20.dp))
                            }
                        },
                        showDivider = position < storages.lastIndex,
                        onClick = {
                            onAction(AppAction.SwitchToStorage(storage.id, storage.location))
                            onDismiss()
                        }
                    )
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.add_location),
                    titleColor = colors.action,
                    leading = { Icon(AppIcons.Add, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp)) },
                    onClick = {
                        onDismiss()
                        onAddLocation()
                    }
                )
            }
        }
    }

    leaving?.let { storage ->
        ExpressiveConfirmationSheet(
            title = stringResource(if (storage.logsOut) R.string.logout_prompt else R.string.disconnect_prompt, storage.name),
            subtitle = storage.summary,
            confirmLabel = stringResource(if (storage.logsOut) R.string.logout_action else R.string.disconnect_action),
            onConfirm = {
                disconnectActions(storage.id, state.googleDriveAccountEmail, state.megaAccountEmail).forEach(onAction)
                leaving = null
                onDismiss()
            },
            onDismissRequest = { leaving = null }
        )
    }
}
