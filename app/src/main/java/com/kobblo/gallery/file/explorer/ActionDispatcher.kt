package com.kobblo.gallery.file.explorer

import com.kobblo.gallery.file.explorer.AppAction.*
import com.kobblo.gallery.file.explorer.startup.ShizukuAccess
import com.kobblo.gallery.file.explorer.startup.StorageAccessFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Has each [AppAction] done. Nearly all of it is the view model's work; permissions and a change of language
 * need the activity, so those go to the parts of it that were handed in.
 *
 * It is itself the `onAction` that screens call, and one object for as long as the activity lasts, so passing
 * it down never redraws anything.
 */
internal class ActionDispatcher(
    private val model: MainViewModel,
    private val storage: StorageAccessFlow,
    private val shizuku: ShizukuAccess,
    /** Ends with the activity, for work whose answer goes back to one of its screens. */
    private val scope: CoroutineScope,
    private val onLanguageChanged: () -> Unit
) : (AppAction) -> Unit {

    override fun invoke(action: AppAction) {
        when (action) {
            is Navigation -> navigate(action)
            is Listing -> arrange(action)
            is Selection -> select(action)
            is Opening -> open(action)
            is Editing -> edit(action)
            is Transfer -> transfer(action)
            is Hiding -> hide(action)
            is Locking -> lock(action)
            is Screens -> show(action)
            is Trash -> sortTrash(action)
            is Cleaner -> clean(action)
            is Organizer -> organize(action)
            is Installing -> install(action)
            is Music -> play(action)
            is CloudAccounts -> signIn(action)
            is Servers -> connect(action)
            is NearbyShareAction -> model.handleNearbyShareAction(action)
            is IncomingShare -> receive(action)
            is Access -> gainAccess(action)
            is Preferences -> prefer(action)
            is Messages -> tell(action)
        }
    }

    // ---- files and places ----

    private fun navigate(action: Navigation) {
        when (action) {
            is SetLocation -> model.setLocation(action.path, action.folderName)
            is SwitchToStorage -> model.switchToStorage(action.storageId, action.defaultLocation)
            NavigateBackInDrive -> model.navigateBackInDrive()
            is SelectNavTab -> model.selectNavTab(action.tab)
            is NavTabTapped -> model.navTabTapped(action.id)
            Reload -> model.reload()
        }
    }

    private fun arrange(action: Listing) {
        when (action) {
            is SetQuery -> model.setQuery(action.query)
            is SetFilter -> model.setFilter(action.filter)
            is SetSortMode -> model.setSortMode(action.mode)
            ToggleViewMode -> model.toggleViewMode()
            is SetShowHiddenFiles -> model.setShowHiddenFiles(action.show)
        }
    }

    private fun select(action: Selection) {
        when (action) {
            is ToggleSelection -> model.toggleSelection(action.id)
            is SelectAll -> model.selectAll(action.ids)
            SelectNone -> model.selectNone()
            ClearSelection -> model.clearSelection()
        }
    }

    private fun open(action: Opening) {
        when (action) {
            is OpenFileViewer -> model.openFileViewer(action.id)
            is ViewFile -> model.viewFile(action.file)
            CloseFileViewer -> model.closeFileViewer()
            is DownloadAndOpenFile -> model.downloadAndOpenFile(action.file)
            ClearDownloadedCloudFileToOpen -> model.clearDownloadedCloudFileToOpen()
            ClearUnlockedFileToOpen -> model.clearUnlockedFileToOpen()
            is LoadFileDetails -> scope.launch { action.onLoaded(model.getFileDetails(action.path)) }
            is LogRecentAction -> model.logRecentAction(action.path, action.action)
        }
    }

    private fun edit(action: Editing) {
        when (action) {
            is CreateFolder -> model.createFolder(action.name)
            is CreateFile -> model.createFile(action.name)
            is RenameFile -> model.renameFile(action.path, action.newName)
            is BatchRenameFiles -> with(action) {
                model.batchRenameFiles(baseName, extension, padding, startNumber, isPrefix, style)
            }
            is TogglePin -> model.togglePin(action.path)
            is SetFavorites -> model.setFavorites(action.paths, action.favorite)
            DeleteSelectedFiles -> model.deleteSelectedFiles()
            is DeleteFiles -> model.deleteFiles(action.files)
        }
    }

    private fun transfer(action: Transfer) {
        when (action) {
            is SetClipboard -> model.setClipboard(action.action)
            is ClipFile -> model.clipFile(action.path, action.action)
            ClearClipboard -> model.clearClipboard()
            is PasteClipboard -> model.pasteClipboard(action.location)
            is MoveDragged -> model.moveDragged(action.paths, action.location)
            TogglePastePause -> model.togglePastePause()
            CancelPaste -> model.cancelPaste()
        }
    }

    private fun hide(action: Hiding) {
        when (action) {
            is ToggleHideFiles -> model.toggleHideFiles(action.files)
            is ResolveHideConflict -> model.resolveHideConflict(action.resolution)
            DismissHideConflict -> model.dismissHideConflict()
            is SetHasSeenHideWarning -> model.setHasSeenHideWarning(action.seen)
        }
    }

    private fun lock(action: Locking) {
        when (action) {
            is RequestAuth -> with(action) { model.requestAuth(reason, path, fileId, folderName) }
            is AuthSuccess -> with(action) { model.authSuccess(reason, path, fileId, folderName) }
            CancelAuth -> model.cancelAuth()
        }
    }

    // ---- screens of their own ----

    private fun show(action: Screens) {
        when (action) {
            is SetShowSettingsScreen -> model.setShowSettingsScreen(action.show)
            is SetShowVaultScreen -> model.setShowVaultScreen(action.show)
            is SetTrashScreenVisible -> model.setTrashScreenVisible(action.visible)
            is SetCleanerScreenVisible -> model.setCleanerScreenVisible(action.visible)
            is SetShowBatchRenameDialog -> model.setShowBatchRenameDialog(action.show)
        }
    }

    private fun sortTrash(action: Trash) {
        when (action) {
            RefreshTrash -> model.loadTrashFiles()
            is RestoreTrashFiles -> model.restoreTrashFiles(action.files)
            is PermanentlyDeleteTrashFiles -> model.permanentlyDeleteTrashFiles(action.files)
        }
    }

    private fun clean(action: Cleaner) {
        when (action) {
            is SetCleanerCategory -> model.setCleanerCategory(action.category)
            is SelectAllCleanerFiles -> model.selectAllCleanerFiles(action.ids)
            ClearCleanerSelection -> model.clearCleanerSelection()
        }
    }

    private fun organize(action: Organizer) {
        when (action) {
            OrganiseDownloads -> model.organiseDownloads()
            ConfirmOrganiseDownloads -> model.confirmOrganiseDownloads()
            CancelOrganiseDownloads -> model.cancelOrganiseDownloads()
        }
    }

    private fun install(action: Installing) {
        when (action) {
            is SilentInstallApk -> with(action) { model.silentInstallApk(path, downgrade, forceUninstall) }
            is BatchInstallApks -> with(action) { model.batchInstallApks(paths, downgrade, silent, forceUninstall) }
        }
    }

    private fun play(action: Music) {
        when (action) {
            is PlayAudio -> model.playAudio(action.file)
            ToggleAudioPlayback -> model.toggleAudioPlayback()
            is SeekAudio -> model.seekAudio(action.position)
            PlayNextAudio -> model.playNextAudio()
            PlayPreviousAudio -> model.playPreviousAudio()
            StopAudio -> model.stopAudio()
            is SetShowFullScreenPlayer -> model.setShowFullScreenPlayer(action.show)
        }
    }

    // ---- elsewhere ----

    private fun signIn(action: CloudAccounts) {
        when (action) {
            is SetGoogleDriveAuthStatus -> model.updateGoogleDriveAuthStatus(action.isAuthenticated, action.email)
            is SetDrivePickedIds -> model.setDrivePickedIds(action.ids)
            ClearRecoverableAuthIntent -> model.clearRecoverableAuthIntent()
            is SetMegaAuthStatus -> model.setMegaAuthStatus(action.isAuthenticated, action.email, action.password)
            is SetShowMegaPopup -> model.setShowMegaPopup(action.show)
            is SetDropboxAuthStatus -> model.setDropboxAuthStatus(action.isAuthenticated, action.email)
        }
    }

    private fun connect(action: Servers) {
        when (action) {
            is SmbAction -> model.handleSmbAction(action)
            is FtpAction -> model.handleFtpAction(action)
            is SftpAction -> model.handleSftpAction(action)
            is WebDavAction -> model.handleWebDavAction(action)
        }
    }

    private fun receive(action: IncomingShare) {
        when (action) {
            is SaveIncomingShare -> model.saveIncomingShare(action.destinationPath)
            DismissIncomingShare -> model.dismissIncomingShare()
        }
    }

    // ---- the app itself ----

    private fun gainAccess(action: Access) {
        when (action) {
            RequestStorageAccess -> storage.request()
            RequestShizukuAccess -> shizuku.request()
            is AutoRequestAccess -> model.autoRequestAccess(action.path)
        }
    }

    private fun prefer(action: Preferences) {
        when (action) {
            is SetThemeMode -> model.setThemeMode(action.mode)
            is SetDarkSurfaceStyle -> model.setDarkSurfaceStyle(action.style)
            is SetTint -> model.setTint(action.tint)
            is SetLanguage -> {
                model.setLanguage(action.tag)
                onLanguageChanged()
            }
            is SetGridColumns -> model.setGridColumns(action.columns)
            is ToggleHapticsMaster -> model.toggleHapticsMaster(action.enabled)
            is ToggleHapticsOption -> model.toggleHapticsOption(action.key, action.enabled)
            is SetVaultAlwaysShown -> model.setVaultAlwaysShown(action.alwaysShown)
            is SetViewerPreference -> model.setViewerPreference(action.category, action.preference)
            is SetOrganiserPath -> model.setOrganiserPath(action.category, action.path)
            is SetRecycleBinSettings -> with(action) { model.setRecycleBinSettings(enabled, retentionValue, retentionUnit) }
        }
    }

    private fun tell(action: Messages) {
        when (action) {
            is ShowToast -> model.showToast(action.message)
            is SetErrorMessage -> model.setErrorMessage(action.message)
        }
    }
}
