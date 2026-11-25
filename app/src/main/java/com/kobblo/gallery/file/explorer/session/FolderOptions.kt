package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.StorageNavState
import com.kobblo.gallery.file.explorer.explorer.FolderViewMemory
import com.kobblo.gallery.file.explorer.getStorageIdForLocation
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/** How the open folder is narrowed and laid out, as its options sheet offers it. */
internal class FolderOptions(
    private val live: LiveState,
    private val settings: SettingsStore,
    private val contents: FolderContents,
    private val loader: FolderLoader,
    private val recents: Recents
) {
    fun filterBy(filter: String) {
        live.update {
            it.copy(filter = filter, selectedFiles = persistentSetOf())
                .leavingStorage(getStorageIdForLocation(it.location, it.sdCardPath, it.googleDriveAccountEmail, it.megaAccountEmail)) {
                    copy(filter = filter)
                }
        }
        contents.rearrange()
    }

    /** Choosing the sort a folder already has turns it around. */
    fun sortBy(mode: SortMode) {
        val state = live.now
        FolderViewMemory.chooseSort(state.location, mode, state.isListMode)
        live.update {
            it.copy(sortMode = mode).leavingStorage(getStorageIdForLocation(it.location, it.sdCardPath)) {
                copy(sortMode = mode)
            }
        }
        contents.rearrange()
    }

    /** Remembers how the open location's storage was last arranged. */
    private fun AppState.leavingStorage(storageId: String, change: StorageNavState.() -> StorageNavState): AppState {
        val left = storageStates[storageId] ?: StorageNavState(lastLocation = location, sortMode = sortMode, filter = filter)
        return copy(storageStates = storageStates.put(storageId, left.change()))
    }

    fun toggleList() = showAsList(!live.now.isListMode)

    /** A folder remembers its own. Everywhere else shares the one choice. */
    fun showAsList(isList: Boolean) {
        val location = live.now.location
        if (Places.isOnPhone(location)) FolderViewMemory.chooseList(location, isList) else settings.listMode = isList
        live.update { it.copy(isListMode = isList) }
    }

    fun showHidden(show: Boolean) {
        settings.showHiddenFiles = show
        // Every list that was remembered was made the other way.
        live.update { it.copy(showHiddenFiles = show, folderCache = persistentMapOf()) }
        contents.rearrange()
        recents.refresh()
        val location = live.now.location
        if (location != Places.Send) loader.load(location)
    }
}
