package com.kobblo.gallery.file.explorer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kobblo.gallery.file.explorer.data.core.ConflictResolution
import com.kobblo.gallery.file.explorer.explorer.drag.DragMoved
import com.kobblo.gallery.file.explorer.session.LockQuestion
import com.kobblo.gallery.file.explorer.session.MainSession
import com.kobblo.gallery.file.explorer.session.NumberedNames
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The main screen's view model: the state it draws, and one function for each thing it can ask for. The work
 * is done by the parts of [MainSession]; this only says which part does what.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val session = MainSession(application, viewModelScope)

    val state: StateFlow<AppState> = session.live.flow

    /** The music's position, which ticks while it plays. */
    val audioPosition: StateFlow<Long> = session.music.position

    /** Lines for the bar at the foot of the screen. */
    val snackbarMessage: SharedFlow<String> = session.messages.flow

    /** Moves made by a drag, which the bar offers to undo. */
    val dragMoveResults: SharedFlow<DragMoved> = session.transfers.undoable

    init {
        session.start()
    }

    override fun onCleared() {
        super.onCleared()
        session.stop()
    }

    private fun change(change: AppState.() -> AppState) = session.live.update(change)

    // Messages.

    fun showToast(message: String) = session.messages.post(message)

    fun setErrorMessage(msg: String?) = change { copy(errorMessage = msg) }

    // Where the app is.

    fun setLocation(location: String, folderName: String? = null) = session.navigator.goTo(location, folderName)

    fun navigateBackInDrive() = session.navigator.stepBack()

    fun switchToStorage(storageId: String, defaultLocation: String) = session.navigator.switchTo(storageId, defaultLocation)

    fun selectNavTab(tab: NavTab) = session.navigator.selectTab(tab)

    fun navTabTapped(id: String) = session.navigator.tabTapped(id)

    fun showFolderFromOutside(path: String) = session.navigator.showFromOutside(path)

    fun onPhoneLocked() = session.navigator.phoneLocked()

    fun reload() = session.reload()

    // How the open folder is shown.

    /** @param keepSelection for asking the same question again, where what's chosen should stay chosen. */
    fun setQuery(query: String, keepSelection: Boolean = false) = session.search.ask(query, keepSelection)

    fun setFilter(filter: String) = session.folderOptions.filterBy(filter)

    fun setSortMode(mode: SortMode) = session.folderOptions.sortBy(mode)

    fun toggleViewMode() = session.folderOptions.toggleList()

    fun setListMode(isList: Boolean) = session.folderOptions.showAsList(isList)

    fun setShowHiddenFiles(show: Boolean) = session.folderOptions.showHidden(show)

    // Recents and favorites.

    fun logRecentAction(path: String, action: String) = session.recents.log(path, action)

    fun togglePin(path: String) = session.favorites.toggle(path)

    fun setFavorites(paths: List<String>, favorite: Boolean) = session.favorites.set(paths, favorite)

    // What's chosen.

    fun toggleSelection(fileId: Int) = session.selection.toggle(fileId)

    fun selectAll(ids: Collection<Int>? = null) = session.selection.all(ids)

    fun selectNone() = session.selection.none()

    fun clearSelection() = session.selection.none()

    // Opening files.

    fun openFileViewer(id: Int) = session.opening.view(id)

    fun viewFile(file: FileItem) = session.opening.view(file)

    fun closeFileViewer() = session.opening.close()

    fun downloadAndOpenFile(file: FileItem) = session.opening.download(file)

    fun clearDownloadedCloudFileToOpen() = session.opening.offerTaken()

    fun handleOpenFileFromIntent(uri: Uri, intentMimeType: String?) = session.outside.open(uri, intentMimeType)

    suspend fun getFileDetails(path: String): FileDetails = session.repository.getFileDetails(path)

    // Locked files and folders.

    fun requestAuth(reason: AuthReason, path: String, fileId: Int?, folderName: String?) =
        session.locks.ask(LockQuestion(reason, path, fileId, folderName))

    fun cancelAuth() = session.locks.cancel()

    fun authSuccess(reason: AuthReason, path: String, fileId: Int?, folderName: String?) =
        session.locks.entered(LockQuestion(reason, path, fileId, folderName))

    fun clearUnlockedFileToOpen() = session.locks.opened()

    // Copying and moving.

    fun setClipboard(action: String) = session.transfers.clipSelection(action)

    fun clipFile(path: String, action: String) = session.transfers.clipFile(path, action)

    fun clearClipboard() = session.transfers.clearClipboard()

    fun pasteClipboard(destLocation: String) = session.transfers.paste(destLocation)

    fun moveDragged(paths: List<String>, destLocation: String) = session.transfers.moveDragged(paths, destLocation)

    fun undoDragMove(moved: DragMoved) = session.transfers.undo(moved)

    fun togglePastePause() = session.transfers.togglePause()

    fun cancelPaste() = session.transfers.cancelRunning()

    // Making, renaming, hiding and deleting.

    fun createFolder(name: String) = session.names.createFolder(name)

    fun createFile(name: String) = session.names.createFile(name)

    fun renameFile(path: String, newName: String) = session.names.rename(path, newName)

    fun setShowBatchRenameDialog(show: Boolean) = session.names.showRenaming(show)

    fun batchRenameFiles(
        baseName: String,
        extension: String,
        padding: Int,
        startNumber: Int,
        isPrefix: Boolean,
        numberingStyle: String
    ) = session.names.renameChosen(NumberedNames(baseName, extension, padding, startNumber, isPrefix, numberingStyle))

    fun toggleHideFiles(files: List<FileItem>) = session.hiding.toggle(files)

    fun resolveHideConflict(resolution: ConflictResolution) = session.hiding.resolve(resolution)

    fun dismissHideConflict(skipCurrent: Boolean = true) = session.hiding.dismiss(skipCurrent)

    fun deleteSelectedFiles() = session.deletion.deleteChosen()

    fun deleteFiles(items: List<FileItem>) = session.deletion.delete(items)

    // Recently Deleted.

    fun setTrashScreenVisible(visible: Boolean) = session.bin.show(visible)

    fun loadTrashFiles() = session.bin.read()

    fun restoreTrashFiles(encodedNames: List<String>) = session.bin.restore(encodedNames)

    fun permanentlyDeleteTrashFiles(encodedNames: List<String>) = session.bin.deleteForGood(encodedNames)

    // The Cleaner.

    fun setCleanerScreenVisible(visible: Boolean) = session.cleaner.show(visible)

    fun setCleanerCategory(category: String?) = session.cleaner.chooseCategory(category)

    fun selectAllCleanerFiles(fileIds: List<Int>) = session.cleaner.selectAll(fileIds)

    fun clearCleanerSelection() = session.cleaner.selectNone()

    fun toggleCleanerSelection(fileId: Int) = session.cleaner.toggle(fileId)

    // Downloads and apps.

    fun organiseDownloads() = session.downloads.propose()

    fun cancelOrganiseDownloads() = session.downloads.cancel()

    fun confirmOrganiseDownloads() = session.downloads.confirm()

    fun silentInstallApk(path: String, downgrade: Boolean, forceUninstall: Boolean = false) =
        session.installs.install(path, downgrade, forceUninstall)

    fun batchInstallApks(paths: List<String>, downgrade: Boolean, silent: Boolean, forceUninstall: Boolean = false) =
        session.installs.installAll(paths, downgrade, silent, forceUninstall)

    // Cloud accounts. Being told that an account is signed in without saying which leaves things as they are.

    fun updateGoogleDriveAuthStatus(isAuthenticated: Boolean, email: String?) {
        if (!isAuthenticated) session.drive.signOut(email) else if (email != null) session.drive.signIn(email)
    }

    fun setDrivePickedIds(ids: List<String>) = session.drive.allow(ids)

    fun clearRecoverableAuthIntent() = change { copy(recoverableAuthIntent = null) }

    fun setMegaAuthStatus(isAuthenticated: Boolean, email: String?, password: String? = null) {
        if (!isAuthenticated) {
            session.mega.signOut(email)
        } else if (email != null && password != null) {
            session.mega.signIn(email, password)
        }
    }

    fun setShowMegaPopup(show: Boolean) = session.mega.showPopup(show)

    fun setDropboxAuthStatus(isAuthenticated: Boolean, email: String?) =
        change { copy(isDropboxAuthenticated = isAuthenticated, dropboxAccountEmail = email, errorMessage = null) }

    // Servers.

    fun handleSmbAction(action: AppAction.SmbAction) = session.servers.handle(action)

    fun handleFtpAction(action: AppAction.FtpAction) = session.servers.handle(action)

    fun handleSftpAction(action: AppAction.SftpAction) = session.servers.handle(action)

    fun handleWebDavAction(action: AppAction.WebDavAction) = session.servers.handle(action)

    // Nearby Share, and files other apps share into this one.

    fun handleNearbyShareAction(action: AppAction.NearbyShareAction) = session.nearby.handle(action)

    fun handleIncomingShare(uris: List<Uri>, mimeType: String?) = session.shares.offer(uris, mimeType)

    fun updateIncomingShareDestination(destinationPath: String) = session.shares.chooseFolder(destinationPath)

    fun dismissIncomingShare() = session.shares.dismiss()

    fun saveIncomingShare(destinationPath: String) = session.shares.save(destinationPath)

    // Music.

    fun playAudio(file: FileItem) = session.music.play(file)

    fun toggleAudioPlayback() = session.music.toggle()

    fun seekAudio(position: Long) = session.music.seekTo(position)

    fun stopAudio() = session.music.stop()

    fun playNextAudio() = session.music.next()

    fun playPreviousAudio() = session.music.previous()

    fun setShowFullScreenPlayer(show: Boolean) = session.music.showFullScreen(show)

    // What the app may reach.

    fun updateStorageAccessState(hasAccess: Boolean) = session.storage.setAccess(hasAccess)

    fun refreshShizukuStatus() = session.storage.recheckShizuku()

    fun autoRequestAccess(path: String) = session.storage.askShizuku()

    // Screens that lie over the tabs.

    fun setShowSettingsScreen(show: Boolean) = change { copy(showSettingsScreen = show) }

    fun setShowThemeSheet(show: Boolean) = change { copy(showThemeSheet = show) }

    fun setShowVaultScreen(show: Boolean) = change { copy(showVaultScreen = show) }

    // Settings.

    fun onAppLaunch() = session.settings.refreshIconShape()

    fun setThemeMode(mode: ThemeMode) = session.settings.chooseThemeMode(mode)

    fun setDarkSurfaceStyle(style: DarkSurfaceStyle) = session.settings.chooseDarkSurface(style)

    fun setTint(tint: KobbloAccent) = session.settings.chooseTint(tint)

    fun setThemeHue(hue: Float) = session.settings.chooseHue(hue)

    fun setThemeLightnessOffset(offset: Float) = session.settings.chooseLightness(offset)

    fun setDynamicSystemTheme(useDynamic: Boolean) = session.settings.useDynamicTheme(useDynamic)

    fun setIconShape(shape: IconShapeType) = session.settings.chooseIconShape(shape)

    fun setFontStyle(style: String) = session.settings.chooseFont(style)

    fun toggleTextDecoration(decoration: String) = session.settings.toggleDecoration(decoration)

    fun setMainTextScale(scale: Float) = session.settings.scaleMainText(scale)

    fun setSubTextScale(scale: Float) = session.settings.scaleSubText(scale)

    fun setInvertText(invert: Boolean) = session.settings.invertText(invert)

    fun setGridColumns(columns: Int) = session.settings.chooseColumns(columns)

    fun setCornerRoundness(value: Float) = session.settings.chooseRoundness(value)

    fun setLanguage(tag: String) = session.settings.chooseLanguage(tag)

    fun setHasSeenHideWarning(seen: Boolean) = session.settings.hideWarningSeen(seen)

    fun setVaultAlwaysShown(alwaysShown: Boolean) = session.settings.alwaysShowVault(alwaysShown)

    fun setRecycleBinSettings(enabled: Boolean, retentionValue: Int, retentionUnit: String) =
        session.settings.setBin(enabled, retentionValue, retentionUnit)

    fun setOrganiserPath(category: String, path: String) = session.settings.setOrganizerFolder(category, path)

    fun setViewerPreference(category: String, preference: String) = session.settings.setViewer(category, preference)

    fun toggleHapticsMaster(enabled: Boolean) = session.settings.enableHaptics(enabled)

    fun toggleHapticsOption(key: String, enabled: Boolean) = session.settings.setHaptic(key, enabled)
}
