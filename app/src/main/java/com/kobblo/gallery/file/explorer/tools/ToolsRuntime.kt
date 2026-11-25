package com.kobblo.gallery.file.explorer.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.tools.storage.VolumeInfo

/**
 * What the tools need running for as long as the app is open. Today that is watching the storage
 * volumes, so removing a card while browsing it leads back to Locations instead of to an error.
 */
@Composable
fun ToolsRuntime(state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { StorageVolumes.start(context) }

    val volumes by StorageVolumes.volumes.collectAsState()
    val previous = remember { arrayOf<List<VolumeInfo>>(emptyList()) }
    val removedMessage = stringResource(R.string.tools_volume_removed)
    LaunchedEffect(volumes) {
        val gone = previous[0].filter { old -> volumes.none { it.path == old.path } }
        previous[0] = volumes
        val location = state.location
        val lostCurrent = gone.firstOrNull { location == it.path || location.startsWith(it.path + "/") }
        if (lostCurrent != null) {
            onAction(AppAction.SetLocation("locations"))
            onAction(AppAction.ShowToast(removedMessage.format(lostCurrent.name)))
        }
    }
}
