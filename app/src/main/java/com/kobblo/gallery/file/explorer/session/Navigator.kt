package com.kobblo.gallery.file.explorer.session

import android.app.Application
import android.os.Environment
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.AuthReason
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.NavBarState
import com.kobblo.gallery.file.explorer.NavTab
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.RecentActions
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.data.StorageUtils
import com.kobblo.gallery.file.explorer.explorer.FolderViewMemory
import com.kobblo.gallery.file.explorer.explorer.drag.FileDrag
import com.kobblo.gallery.file.explorer.explorer.search.ExplorerSearch
import com.kobblo.gallery.file.explorer.fileops.Locks
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Moving between locations: into folders and back, from one storage to another, and to the tabs. */
internal class Navigator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val live: LiveState,
    private val words: Words,
    private val settings: SettingsStore,
    private val repository: FileRepository,
    private val loader: FolderLoader,
    private val search: FolderSearch,
    private val recents: Recents,
    private val drive: DriveAccounts,
    private val mega: MegaAccounts,
    private val servers: Servers
) {
    /** Opens a location. One that's behind a lock asks for it instead, and opens once it's been entered. */
    fun goTo(location: String, folderName: String? = null) {
        val card = live.now.sdCardPath ?: StorageUtils.getSdCardPath(app)
        val target = if (card != null && Places.isCardShorthand(location)) card else location
        // A tap on a locked folder has already asked. A typed path, a shortcut or a link to what's inside hasn't.
        if (Places.isOnPhone(target) && Locks.inTheWay(app, target) != null) {
            val name = folderName ?: target.substringAfterLast('/')
            live.update { it.asking(LockQuestion(AuthReason.OPEN_FILE, target, row = null, name)) }
            return
        }
        Locks.closeAllBut(target)
        arrive(target, folderName, card)
        search.stop()
        ExplorerSearch.onLocationChanged()
        if (target == Places.Send && !live.now.isNearbyReceiving) NearbyShareRepository.startService(app)
        if (Places.isOnPhone(target)) {
            scope.launch(Dispatchers.IO) { repository.logRecentAction(target, RecentActions.Visited) }
        }
        loader.load(target)
        if (target == Places.Home) recents.refresh()
    }

    private fun arrive(target: String, folderName: String?, card: String?) {
        val listElsewhere = settings.listMode
        val arrival = Arrival(
            location = target,
            folderName = folderName,
            cardPath = card,
            look = FolderViewMemory.viewFor(target, listElsewhere),
            listElsewhere = listElsewhere,
            carriesSelection = FileDrag.carriesSelection,
            unnamedCloud = words.text(R.string.app_cloud)
        )
        val remembered = arrival.remembered(live.now)
        TabSwitchLatencyTracker.onViewModelSetLocation(target, hasCache = remembered != null, cachedCount = remembered?.size ?: 0)
        live.update(arrival::from)
    }

    /** One step back along the way here, on a storage that has no paths to go up by. */
    fun stepBack() {
        val wayBack = live.now.driveFolderStack
        val (location, name) = wayBack.lastOrNull() ?: return goTo(Places.Locations)
        live.update {
            it.copy(
                location = location,
                currentFolderName = name,
                driveFolderStack = wayBack.dropLast(1),
                files = it.folderCache[location] ?: persistentListOf(),
                isLoading = true
            )
        }
        loader.load(location)
    }

    /** Opens a storage at its top, wherever it was last left, but arranged as it was then. */
    fun switchTo(storageId: String, top: String) {
        val driveAccount = CloudService.Drive.accountOfStorage(storageId)
        val megaAccount = CloudService.Mega.accountOfStorage(storageId)
        driveAccount?.let(drive::use)
        megaAccount?.let(mega::use)
        live.update {
            val left = it.storageStates[storageId]
            it.copy(
                sortMode = left?.sortMode ?: SortMode.ALPHABETICAL,
                filter = left?.filter ?: "all",
                driveFolderStack = emptyList(),
                currentFolderName = null
            )
        }
        // With several accounts, a bare service name would open whichever is in use by the time it's read.
        goTo(
            when {
                driveAccount != null && top == CloudService.Drive.key -> CloudService.Drive.topOf(driveAccount)
                megaAccount != null && top == CloudService.Mega.key -> CloudService.Mega.topOf(megaAccount)
                else -> top
            }
        )
    }

    fun tabTapped(id: String) = live.update {
        val taps = it.navTapCounters.toPersistentMap()
        it.copy(navTapCounters = taps.put(id, (taps[id] ?: 0) + 1))
    }

    fun selectTab(tab: NavTab) {
        live.update { it.copy(navBarState = NavBarState(tab)) }
        goTo(
            when (tab) {
                NavTab.HOME -> Places.Home
                NavTab.RECENT -> Places.Recent
                NavTab.PINNED -> Places.Pinned
                NavTab.CLOUD -> servers.connectedTop(live.now) ?: signedInCloudOrPhone()
            }
        )
    }

    /** Where to go from a server that's been let go of: the first cloud that's signed in to, or the phone. */
    fun signedInCloudOrPhone(): String = live.now.signedInCloud()?.key ?: phoneRoot()

    private fun phoneRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /**
     * A notification or a shortcut saying where to go. Whatever was left open in the app would otherwise
     * sit on top of the folder, and the tap would seem to do nothing.
     */
    fun showFromOutside(path: String) {
        Hub.close()
        live.update {
            it.copy(
                showSettingsScreen = false,
                showVaultScreen = false,
                showTrashScreen = false,
                showCleanerScreen = false,
                showFullScreenPlayer = false
            )
        }
        goTo(path)
    }

    /** The phone has been put down, and any password given was for whoever gave it. */
    fun phoneLocked() {
        live.update { it.copy(showVaultScreen = false) }
        Locks.closeAll()
        val location = live.now.location
        if (!Places.isOnPhone(location)) return
        val lock = Locks.inTheWay(app, location) ?: return
        goTo(lock.substringBeforeLast('/', "").ifEmpty { Places.Locations })
    }
}

/** The first cloud service that's signed in to. */
internal fun AppState.signedInCloud(): CloudService? = when {
    isGoogleDriveAuthenticated -> CloudService.Drive
    isMegaAuthenticated -> CloudService.Mega
    isDropboxAuthenticated -> CloudService.Dropbox
    else -> null
}
