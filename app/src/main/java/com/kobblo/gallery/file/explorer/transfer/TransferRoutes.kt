package com.kobblo.gallery.file.explorer.transfer

import androidx.compose.runtime.Composable
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.transfer.connect.ConnectScreen
import com.kobblo.gallery.file.explorer.transfer.hotspot.CreateConnectionScreen
import com.kobblo.gallery.file.explorer.transfer.pick.PickerScreen
import com.kobblo.gallery.file.explorer.transfer.ui.CurrentTransferScreen
import com.kobblo.gallery.file.explorer.transfer.ui.HistoryDetailScreen
import com.kobblo.gallery.file.explorer.transfer.ui.HistoryScreen
import com.kobblo.gallery.file.explorer.transfer.web.WebShareScreen
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute

/** The full screens of this area. Open one with `Hub.open(...)`. */
sealed interface TransferRoute : HubRoute {
    /** Choosing what to send. With [sendTo], confirming sends straight to those devices. */
    data class Picker(val sendTo: List<NearbyPeer> = emptyList()) : TransferRoute
    data object Current : TransferRoute
    data object History : TransferRoute
    data class HistoryDetail(val id: String) : TransferRoute
    data object Connect : TransferRoute
    data object CreateConnection : TransferRoute
    data object WebShare : TransferRoute
}

@Composable
fun TransferRouteContent(route: TransferRoute, state: AppState, onAction: (AppAction) -> Unit) {
    when (route) {
        is TransferRoute.Picker -> PickerScreen(route.sendTo, state.hasStoragePermission) { onAction(AppAction.RequestStorageAccess) }
        TransferRoute.Current -> CurrentTransferScreen(state, onAction)
        TransferRoute.History -> HistoryScreen()
        is TransferRoute.HistoryDetail -> HistoryDetailScreen(route.id, state, onAction)
        TransferRoute.Connect -> ConnectScreen()
        TransferRoute.CreateConnection -> CreateConnectionScreen()
        TransferRoute.WebShare -> WebShareScreen()
    }
}
