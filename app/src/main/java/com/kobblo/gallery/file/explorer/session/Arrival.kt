package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.StorageNavState
import com.kobblo.gallery.file.explorer.explorer.ResolvedFolderView
import com.kobblo.gallery.file.explorer.getStorageIdForLocation
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentMap

/**
 * Going to [location], as a change to the app's state. What's read there comes later; this is what shows
 * meanwhile. It is worked out from the state alone, so it can be tried without a phone.
 *
 * @param folderName what to call it, where its location has no path to take a name from.
 * @param cardPath where the memory card is, when that's known.
 * @param look how a folder on the phone remembers being laid out, or null anywhere else.
 * @param listElsewhere whether places that remember nothing of their own are shown as a list.
 * @param carriesSelection whether a drag is taking what's chosen along.
 * @param unnamedCloud what to call a cloud folder that was never given a name, on the way back to it.
 */
internal class Arrival(
    val location: String,
    private val folderName: String?,
    private val cardPath: String?,
    private val look: ResolvedFolderView?,
    private val listElsewhere: Boolean,
    private val carriesSelection: Boolean,
    private val unnamedCloud: String
) {
    fun from(state: AppState): AppState {
        val wayBack = wayBack(state)
        val remembered = remembered(state)
        // Ids only mean something within one folder's list, so a selection can't come along, unless a drag is
        // carrying it: that either moves it, by path, or brings it back here.
        val keepsSelection = carriesSelection || state.location == location
        return (if (keepsSelection) state else state.choosingNothing()).copy(
            location = location,
            currentFolderName = folderName,
            driveFolderStack = wayBack,
            lastBrowseLocation = if (Places.isOnPhone(location)) location else state.lastBrowseLocation,
            storageStates = storageStates(state, wayBack),
            sdCardPath = state.sdCardPath ?: cardPath,
            query = "",
            filter = if (startsUnfiltered()) "all" else state.filter,
            sortMode = look?.sort ?: state.sortMode,
            isListMode = look?.list ?: listElsewhere,
            files = remembered ?: state.files,
            isLoading = remembered == null,
            errorMessage = null
        )
    }

    /** What was last shown here, which stands in until it's been read again. */
    fun remembered(state: AppState): ImmutableList<FileItem>? = state.folderCache[location]?.takeIf { it.isNotEmpty() }

    private fun startsUnfiltered() =
        Places.isOnPhone(location) || location == Places.Home || location == Places.Gallery

    /** Going further in adds a step back to where this was. Going to a storage's top starts again. */
    private fun wayBack(state: AppState): List<Pair<String, String>> = when {
        Places.isInsideCloud(location) && Places.isCloud(state.location) ->
            state.driveFolderStack + (state.location to (state.currentFolderName ?: unnamedCloud))
        Places.isCloudTop(location) -> emptyList()
        else -> state.driveFolderStack
    }

    private fun storageStates(state: AppState, wayBack: List<Pair<String, String>>): PersistentMap<String, StorageNavState> {
        if (!Places.isStorage(location)) return state.storageStates
        val id = storageId(state)
        // A storage that's new here starts out arranged the way the last one was.
        val left = state.storageStates[id] ?: StorageNavState(location, state.sortMode, state.filter)
        return state.storageStates.put(id, left.copy(lastLocation = location, folderStack = wayBack, folderName = folderName))
    }

    private fun storageId(state: AppState): String = getStorageIdForLocation(
        location,
        state.sdCardPath ?: cardPath,
        state.googleDriveAccountEmail,
        state.megaAccountEmail
    )
}
