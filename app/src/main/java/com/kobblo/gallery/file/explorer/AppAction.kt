package com.kobblo.gallery.file.explorer

import com.kobblo.gallery.file.explorer.data.core.ConflictResolution
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent

/**
 * What a screen can ask the app to do. Screens only send these, and [ActionDispatcher] has each one done.
 *
 * Every action belongs to a group, the interface above it, named for the part of the app that answers it.
 * The dispatcher takes a group at a time, so an action added to one here has a single place left to handle
 * it, and the compiler says which.
 */
sealed interface AppAction {

    // ---- files and places ----

    /** Going from one place to another. A place is a folder, a cloud or server path, or a name such as "home". */
    sealed interface Navigation : AppAction

    data class SetLocation(val path: String, val folderName: String? = null) : Navigation

    /** Opens a storage, sorted and filtered the way it was last left. */
    data class SwitchToStorage(val storageId: String, val defaultLocation: String) : Navigation

    /** Up one folder in a cloud account or on a server, and out to the locations from its top. */
    data object NavigateBackInDrive : Navigation

    /** Opens a section by its tab. The cloud one goes to whichever account or server is connected. */
    data class SelectNavTab(val tab: NavTab) : Navigation

    /** Counts a tap on the tab called [id]. It goes nowhere by itself. */
    data class NavTabTapped(val id: String) : Navigation

    /** Reads the open place again, along with the recent files and the space that's left. */
    data object Reload : Navigation

    /** How the open list is searched and arranged. */
    sealed interface Listing : AppAction

    data class SetQuery(val query: String) : Listing
    data class SetFilter(val filter: String) : Listing
    data class SetSortMode(val mode: SortMode) : Listing

    /** Between a list and a grid. A folder remembers its own. */
    data object ToggleViewMode : Listing

    /** A setting, so every list follows it. */
    data class SetShowHiddenFiles(val show: Boolean) : Listing

    /** The files ticked in the open list. */
    sealed interface Selection : AppAction

    data class ToggleSelection(val id: Int) : Selection

    /** Ticks [ids], or the whole list if there are none given. Locked files are always left out. */
    data class SelectAll(val ids: Collection<Int>? = null) : Selection
    data object SelectNone : Selection
    data object ClearSelection : Selection

    /** Opening files, and looking into them. */
    sealed interface Opening : AppAction

    /** Opens the file with this id in the open list or among the recent files. */
    data class OpenFileViewer(val id: Int) : Opening

    /** Opens exactly this file, which needn't be in any list. */
    data class ViewFile(val file: FileItem) : Opening
    data object CloseFileViewer : Opening

    /** Brings a cloud or server file onto the phone first. A folder is simply gone into. */
    data class DownloadAndOpenFile(val file: FileItem) : Opening

    /** The screen has taken up the file that was downloaded. */
    data object ClearDownloadedCloudFileToOpen : Opening

    /** The screen has taken up the file that was behind a lock. */
    data object ClearUnlockedFileToOpen : Opening

    /** Gathers what a file's details show, which can mean reading it, and hands that to [onLoaded]. */
    data class LoadFileDetails(val path: String, val onLoaded: (FileDetails) -> Unit) : Opening

    /** Puts a file among the recent ones, with what was done to it. */
    data class LogRecentAction(val path: String, val action: String) : Opening

    /** Making, naming, starring and deleting files. */
    sealed interface Editing : AppAction

    data class CreateFolder(val name: String) : Editing
    data class CreateFile(val name: String) : Editing
    data class RenameFile(val path: String, val newName: String) : Editing

    /**
     * Gives the ticked files one [baseName] and a number each, counted from [startNumber] and filled up with
     * zeros to [padding] digits. [isPrefix] puts the number first, and [style] is how it's written.
     */
    data class BatchRenameFiles(
        val baseName: String, val extension: String,
        val padding: Int, val startNumber: Int, val isPrefix: Boolean, val style: String
    ) : Editing

    /** Stars a file, or takes its star off. */
    data class TogglePin(val path: String) : Editing
    data class SetFavorites(val paths: List<String>, val favorite: Boolean) : Editing
    data object DeleteSelectedFiles : Editing
    data class DeleteFiles(val files: List<FileItem>) : Editing

    /** Copying and moving. What the clipboard is for is "copy" or "cut". */
    sealed interface Transfer : AppAction

    /** Puts the ticked files on the clipboard. */
    data class SetClipboard(val action: String) : Transfer

    /** Puts one file on the clipboard, whatever is ticked. */
    data class ClipFile(val path: String, val action: String) : Transfer
    data object ClearClipboard : Transfer
    data class PasteClipboard(val location: String) : Transfer

    /** A stack of files let go over a folder. By path, as the list they were picked up from may not be open any more. */
    data class MoveDragged(val paths: List<String>, val location: String) : Transfer
    data object TogglePastePause : Transfer
    data object CancelPaste : Transfer

    /** Hiding files, which is giving them a name that starts with a dot. */
    sealed interface Hiding : AppAction

    /** Hides these, or shows them again if every one of them is hidden already. */
    data class ToggleHideFiles(val files: List<FileItem>) : Hiding

    /** What to do about a name that was already taken. */
    data class ResolveHideConflict(val resolution: ConflictResolution) : Hiding

    /** Leaves that file as it is and goes on with the rest. */
    data object DismissHideConflict : Hiding

    /** The explanation of what hiding does has been read, so it won't be shown again. */
    data class SetHasSeenHideWarning(val seen: Boolean) : Hiding

    /** Kobblo's lock, in front of one file or folder. */
    sealed interface Locking : AppAction

    data class RequestAuth(
        val reason: AuthReason, val path: String, val fileId: Int? = null, val folderName: String? = null
    ) : Locking

    /** The lock was entered, or newly set, for [reason]. The lock screen is what checks it, not the view model. */
    data class AuthSuccess(
        val reason: AuthReason, val path: String, val fileId: Int? = null, val folderName: String? = null
    ) : Locking
    data object CancelAuth : Locking

    // ---- screens of their own ----

    /** Screens that lie over the open place. */
    sealed interface Screens : AppAction

    data class SetShowSettingsScreen(val show: Boolean) : Screens
    data class SetShowVaultScreen(val show: Boolean) : Screens
    data class SetTrashScreenVisible(val visible: Boolean) : Screens
    data class SetCleanerScreenVisible(val visible: Boolean) : Screens
    data class SetShowBatchRenameDialog(val show: Boolean) : Screens

    /** The Bin. Its files go by the encoded names they have there. */
    sealed interface Trash : AppAction

    data object RefreshTrash : Trash
    data class RestoreTrashFiles(val files: List<String>) : Trash
    data class PermanentlyDeleteTrashFiles(val files: List<String>) : Trash

    /** The Cleaner, and the files ticked in one of its categories. */
    sealed interface Cleaner : AppAction

    data class SetCleanerCategory(val category: String?) : Cleaner
    data class SelectAllCleanerFiles(val ids: List<Int>) : Cleaner
    data object ClearCleanerSelection : Cleaner

    /** Organize Downloads. It says what it would move, and only a yes moves it. */
    sealed interface Organizer : AppAction

    data object OrganiseDownloads : Organizer
    data object ConfirmOrganiseDownloads : Organizer
    data object CancelOrganiseDownloads : Organizer

    /** Installing apps without Android's own questions, which takes Shizuku. */
    sealed interface Installing : AppAction

    /** With [forceUninstall], the installed copy goes first. */
    data class SilentInstallApk(
        val path: String, val downgrade: Boolean, val forceUninstall: Boolean = false
    ) : Installing

    data class BatchInstallApks(
        val paths: List<String>, val downgrade: Boolean, val silent: Boolean, val forceUninstall: Boolean = false
    ) : Installing

    /** The music player. */
    sealed interface Music : AppAction

    /** Plays a song, with the open list as what comes after it. */
    data class PlayAudio(val file: FileItem) : Music
    data object ToggleAudioPlayback : Music

    /** To [position], in milliseconds. */
    data class SeekAudio(val position: Long) : Music
    data object PlayNextAudio : Music
    data object PlayPreviousAudio : Music
    data object StopAudio : Music
    data class SetShowFullScreenPlayer(val show: Boolean) : Music

    // ---- elsewhere ----

    /** Signing in to cloud accounts, and out of them. */
    sealed interface CloudAccounts : AppAction

    data class SetGoogleDriveAuthStatus(val isAuthenticated: Boolean, val email: String?) : CloudAccounts

    /** The files chosen in Google's own picker, which are all that the top of Drive lists from then on. */
    data class SetDrivePickedIds(val ids: List<String>) : CloudAccounts

    /** Google asked for one more screen before it would go on, and that's been shown. */
    data object ClearRecoverableAuthIntent : CloudAccounts

    /** Signs in when there's a [password], since MEGA has no page of its own to do it on. */
    data class SetMegaAuthStatus(
        val isAuthenticated: Boolean, val email: String?, val password: String? = null
    ) : CloudAccounts

    /** The sheet that asks for a MEGA account. */
    data class SetShowMegaPopup(val show: Boolean) : CloudAccounts
    data class SetDropboxAuthStatus(val isAuthenticated: Boolean, val email: String?) : CloudAccounts

    /** The saved connections to servers. Each protocol has the same handful of actions, for its own kind of connection. */
    sealed interface Servers : AppAction

    sealed interface SmbAction : Servers {
        data class AddConnection(val connection: SmbConnection, val password: String) : SmbAction
        data class DeleteConnection(val connectionId: String) : SmbAction
        data class Connect(val connectionId: String) : SmbAction
        data class Disconnect(val connectionId: String) : SmbAction
        data class NavigateTo(val path: String) : SmbAction
        data object ConnectSucceeded : SmbAction
        data class ConnectFailed(val error: SmbError) : SmbAction
    }

    sealed interface FtpAction : Servers {
        data class AddConnection(val connection: FtpConnection, val password: String) : FtpAction
        data class DeleteConnection(val connectionId: String) : FtpAction
        data class Connect(val connectionId: String) : FtpAction
        data class Disconnect(val connectionId: String) : FtpAction
        data class NavigateTo(val path: String) : FtpAction
    }

    sealed interface SftpAction : Servers {
        data class AddConnection(val connection: SftpConnection, val password: String) : SftpAction
        data class DeleteConnection(val connectionId: String) : SftpAction
        data class Connect(val connectionId: String) : SftpAction
        data class Disconnect(val connectionId: String) : SftpAction
        data class NavigateTo(val path: String) : SftpAction
    }

    /** Nextcloud speaks WebDAV, so its servers are here too. */
    sealed interface WebDavAction : Servers {
        data class AddConnection(val connection: WebDavConnection, val password: String) : WebDavAction
        data class DeleteConnection(val connectionId: String) : WebDavAction
        data class Connect(val connectionId: String) : WebDavAction
        data class Disconnect(val connectionId: String) : WebDavAction
        data class NavigateTo(val path: String) : WebDavAction
    }

    /** Sending to devices nearby, and receiving from them, by the LocalSend protocol. */
    sealed interface NearbyShareAction : AppAction {
        data class ToggleReceive(val enabled: Boolean) : NearbyShareAction

        /** Adds to what's waiting to be sent. */
        data class StageFiles(val files: List<FileItem>) : NearbyShareAction
        data class RemoveStagedFile(val file: FileItem) : NearbyShareAction
        data object ClearStagedFiles : NearbyShareAction
        data class SendToPeer(val peer: NearbyPeer) : NearbyShareAction

        data class AcceptIncoming(val sessionId: String) : NearbyShareAction
        data class DeclineIncoming(val sessionId: String) : NearbyShareAction
        data object CancelTransfer : NearbyShareAction

        /** Puts away the card about a transfer that's over. */
        data object DismissTransfer : NearbyShareAction
        data object DismissReceivedFilesPrompt : NearbyShareAction

        data class SetDeviceName(val name: String) : NearbyShareAction
        data class SetReceivePath(val path: String) : NearbyShareAction
        data class SetAskBeforeReceiving(val ask: Boolean) : NearbyShareAction
    }

    /** Files another app shared into Kobblo, waiting for someone to say where they go. */
    sealed interface IncomingShare : AppAction

    data class SaveIncomingShare(val destinationPath: String) : IncomingShare
    data object DismissIncomingShare : IncomingShare

    // ---- the app itself ----

    /** Permissions that only Android, or Shizuku, can give. */
    sealed interface Access : AppAction

    /** Access to files, asked for in whichever way this phone still allows. */
    data object RequestStorageAccess : Access
    data object RequestShizukuAccess : Access

    /** Asks Shizuku for what reading [path] takes, if it's running, and says nothing if it isn't. */
    data class AutoRequestAccess(val path: String) : Access

    /** Choices made in Settings that only need remembering. */
    sealed interface Preferences : AppAction

    data class SetThemeMode(val mode: ThemeMode) : Preferences
    data class SetDarkSurfaceStyle(val style: DarkSurfaceStyle) : Preferences
    data class SetTint(val tint: KobbloAccent) : Preferences

    /** Takes effect at once, so the whole screen is built again. */
    data class SetLanguage(val tag: String) : Preferences
    data class SetGridColumns(val columns: Int) : Preferences
    data class ToggleHapticsMaster(val enabled: Boolean) : Preferences

    /** One kind of haptic, by the key it's saved under. */
    data class ToggleHapticsOption(val key: String, val enabled: Boolean) : Preferences
    data class SetVaultAlwaysShown(val alwaysShown: Boolean) : Preferences

    /** Which viewer a kind of file opens in. */
    data class SetViewerPreference(val category: String, val preference: String) : Preferences

    /** The folder Organize Downloads moves a kind of file to. */
    data class SetOrganiserPath(val category: String, val path: String) : Preferences

    /** Whether what's deleted goes to the Bin, and how long it's kept there. */
    data class SetRecycleBinSettings(
        val enabled: Boolean, val retentionValue: Int, val retentionUnit: String
    ) : Preferences

    /** Telling the person something. */
    sealed interface Messages : AppAction

    data class ShowToast(val message: String) : Messages

    /** What went wrong with the open place, or null once that's over. */
    data class SetErrorMessage(val message: String?) : Messages
}

/** What Kobblo's lock is being asked for. */
enum class AuthReason {
    /** To open a locked file or folder. */
    OPEN_FILE,

    /** To put a lock on one. */
    LOCK_FILE,

    /** To take a lock off one. */
    UNLOCK_FILE,

    /** To go ahead with something that involves locked files, such as moving or deleting them. */
    CONFIRM_ACTION
}
