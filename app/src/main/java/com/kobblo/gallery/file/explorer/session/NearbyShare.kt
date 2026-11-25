package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.util.Log
import com.kobblo.gallery.file.explorer.AppAction.NearbyShareAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ReceivedFilesPrompt
import com.kobblo.gallery.file.explorer.localsend.NearbyShareEvent
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.NearbyShareService
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The main screen's side of Nearby Share. The sharing itself belongs to the process and goes on without this,
 * so what it's doing is copied into the app's state and what the screens ask for is passed on.
 */
internal class NearbyShare(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val settings: SettingsStore,
    private val messages: Messages,
    private val recents: Recents
) {
    fun observe() {
        mirror(NearbyShareRepository.peers) { copy(nearbyPeers = it.toImmutableList()) }
        mirror(NearbyShareRepository.isReceiving) { copy(isNearbyReceiving = it) }
        // Progress ticks several times a second, and the Send tab reads it directly. Copying every tick into
        // the app's state would recompose every screen.
        mirror(NearbyShareRepository.activeTransfer.distinctUntilChangedBy { it?.sessionId to it?.status }) {
            copy(activeNearbyTransfer = it)
        }
        mirror(NearbyShareRepository.incomingRequest) { copy(incomingNearbyRequest = it) }
        mirror(NearbyShareRepository.stagedFiles) { copy(stagedNearbyFiles = it.toImmutableList()) }
        scope.launch { NearbyShareRepository.events.collect(::report) }
    }

    private fun <T> mirror(values: Flow<T>, show: AppState.(T) -> AppState) {
        scope.launch {
            values.collect { value -> live.update { it.show(value) } }
        }
    }

    private fun report(event: NearbyShareEvent) = when (event) {
        is NearbyShareEvent.Success -> messages.post(event.message)
        is NearbyShareEvent.Error -> messages.post(event.message)
        is NearbyShareEvent.FilesReceived -> {
            val prompt = ReceivedFilesPrompt(event.peerAlias, event.files.map(::localFileItem), event.expected)
            recents.refresh()
            live.update { it.copy(receivedFilesPrompt = prompt) }
        }
    }

    /** Stops, once, a service that an older build may have left listening on the port it used. */
    fun leaveOldPort() {
        try {
            if (settings.leftOldNearbyPort) return
            settings.leftOldNearbyPort = true
            NearbyShareService.stop(app)
        } catch (e: Exception) {
            Log.w(Tag, "Couldn't stop the old service", e)
        }
    }

    fun handle(action: NearbyShareAction) {
        when (action) {
            is NearbyShareAction.ToggleReceive -> NearbyShareRepository.toggleReceiving(app, action.enabled)
            is NearbyShareAction.StageFiles -> NearbyShareRepository.stageFiles(action.files)
            is NearbyShareAction.RemoveStagedFile -> NearbyShareRepository.removeStagedFile(action.file)
            is NearbyShareAction.ClearStagedFiles -> NearbyShareRepository.clearStagedFiles()
            is NearbyShareAction.SendToPeer -> NearbyShareRepository.sendStagedFiles(app, action.peer)
            is NearbyShareAction.AcceptIncoming -> NearbyShareRepository.acceptIncomingRequest(action.sessionId)
            is NearbyShareAction.DeclineIncoming -> NearbyShareRepository.declineIncomingRequest(action.sessionId)
            is NearbyShareAction.CancelTransfer -> NearbyShareRepository.cancelActiveTransfer()
            is NearbyShareAction.DismissTransfer -> NearbyShareRepository.dismissTransferCard()
            is NearbyShareAction.DismissReceivedFilesPrompt -> live.update { it.copy(receivedFilesPrompt = null) }
            is NearbyShareAction.SetDeviceName -> rename(action.name)
            is NearbyShareAction.SetReceivePath -> {
                settings.receivePath = action.path
                live.update { it.copy(nearbyReceivePath = action.path) }
            }
            is NearbyShareAction.SetAskBeforeReceiving -> {
                settings.askBeforeReceiving = action.ask
                live.update { it.copy(nearbyAskBeforeReceiving = action.ask) }
            }
        }
    }

    private fun rename(name: String) {
        settings.deviceName = name
        live.update { it.copy(nearbyDeviceName = name) }
        // Other devices are showing the old one.
        NearbyShareRepository.broadcastAnnouncement()
    }

    private companion object {
        const val Tag = "NearbyShare"
    }
}
