package com.kobblo.gallery.file.explorer

import android.content.Intent
import android.os.Environment
import androidx.compose.runtime.Immutable
import com.kobblo.gallery.file.explorer.data.share.IncomingSharePrompt
import com.kobblo.gallery.file.explorer.localsend.IncomingTransferRequest
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.localsend.TransferSession
import com.kobblo.gallery.file.explorer.ui.design.KobbloAccent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Everything the app's one main screen shows. [MainViewModel] holds the only live copy and every screen
 * draws from it, so a value belongs here when more than one screen reads it or when it has to outlive the
 * screen that set it. What ticks many times a second, such as the music's position, has a flow of its own
 * instead, since each new copy of this redraws whatever reads it.
 */
@Immutable
data class AppState(
    // Where the app is, and how it got there.

    val navBarState: NavBarState = NavBarState(),
    /** How often each tab has been tapped, by its id, so a screen can notice a tap on the tab it's already on. */
    val navTapCounters: ImmutableMap<String, Int> = persistentMapOf(),
    /** The open place: a path on the phone, a place in the cloud or on a server, or the name of a tab. */
    val location: String = "gallery",
    /** What to call [location] where it has no path to take a name from. */
    val currentFolderName: String? = null,
    /** The way back through a storage that has no paths to go up by, as locations and their names. */
    val driveFolderStack: List<Pair<String, String>> = emptyList(),
    /** The last folder on the phone that was open, which is the one Browse offers again. */
    val lastBrowseLocation: String = Environment.getExternalStorageDirectory().absolutePath,
    /** How each storage was last left, by its id. */
    val storageStates: PersistentMap<String, StorageNavState> = persistentMapOf(),

    // How the open folder is narrowed and laid out.

    val filter: String = "all",
    val query: String = "",
    val sortMode: SortMode = SortMode.ALPHABETICAL,
    val isListMode: Boolean = false,
    val showHiddenFiles: Boolean = false,

    // What's listed.

    val files: ImmutableList<FileItem> = persistentListOf(),
    /** The Home feed's own copy, so it keeps showing its pictures while leaving for another tab. */
    val galleryFiles: ImmutableList<FileItem> = persistentListOf(),
    val recentFiles: ImmutableList<FileItem> = persistentListOf(),
    val favoritePaths: ImmutableList<String> = persistentListOf(),
    /** Lists as they were last shown, by location, so going back somewhere shows it at once. */
    val folderCache: PersistentMap<String, ImmutableList<FileItem>> = persistentMapOf(),
    val isLoading: Boolean = false,
    /** Why the last thing that was tried didn't work, for the folder it was tried in. */
    val errorMessage: String? = null,

    // What's chosen in the list, by the ids of its rows, and what's been cut or copied.

    val isSelectionMode: Boolean = false,
    val selectedFiles: ImmutableSet<Int> = persistentSetOf(),
    val clipboardPaths: ImmutableList<String> = persistentListOf(),
    /** `copy` or `cut`, while there's something on the clipboard. */
    val clipboardAction: String? = null,

    // A file being opened.

    val viewingFile: FileItem? = null,
    /** A file whose copy on the phone has arrived and is waiting to be opened. */
    val downloadedCloudFileToOpen: FileItem? = null,
    val isDownloading: Boolean = false,
    /** A locked file the lock was entered for, waiting to be opened. */
    val unlockedFileToOpen: FileItem? = null,

    // What the lock screen is being asked for.

    val authRequestReason: AuthReason? = null,
    val authRequestPath: String? = null,
    val authRequestFileId: Int? = null,
    val authRequestFolderName: String? = null,

    // Hiding and renaming.

    val hasSeenHideWarning: Boolean = false,
    val pendingHideConflict: PendingHideConflict? = null,
    val showBatchRenameDialog: Boolean = false,

    // The phone's storage, and what the app may reach of it. Sizes are in decimal gigabytes.

    val hasStoragePermission: Boolean = true,
    val hasShizuku: Boolean = false,
    val storageFreeGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val sdCardPath: String? = null,
    val sdCardStorageFreeGb: Float = 0f,
    val sdCardStorageTotalGb: Float = 0f,

    // Cloud accounts. Each service names the account in use; Drive and MEGA can have several signed in.

    val isGoogleDriveAuthenticated: Boolean = false,
    val googleDriveAccountEmail: String? = null,
    val googleDriveAccounts: ImmutableList<String> = persistentListOf(),
    val driveStorageFreeBytes: Long = 0L,
    val driveStorageTotalBytes: Long = 0L,
    /** Google's own screen for granting access, when Drive has turned a request down for want of it. */
    val recoverableAuthIntent: Intent? = null,
    val isMegaAuthenticated: Boolean = false,
    val megaAccountEmail: String? = null,
    val megaAccounts: ImmutableList<MegaAccount> = persistentListOf(),
    val megaLoginError: String? = null,
    val showMegaPopup: Boolean = false,
    val isDropboxAuthenticated: Boolean = false,
    val dropboxAccountEmail: String? = null,

    // Saved servers, and the one of each kind that's connected.

    val smbState: SmbState = SmbState(),
    val ftpState: FtpState = FtpState(),
    val sftpState: SftpState = SftpState(),
    val webDavState: WebDavState = WebDavState(),

    // Screens that lie over the tabs.

    val showSettingsScreen: Boolean = false,
    val showThemeSheet: Boolean = false,
    val showVaultScreen: Boolean = false,
    val isVaultAlwaysShown: Boolean = true,

    // Recently Deleted.

    val showTrashScreen: Boolean = false,
    val trashFiles: ImmutableList<FileItem> = persistentListOf(),
    val trashIsLoading: Boolean = false,
    val isRecycleBinEnabled: Boolean = true,
    val recycleBinRetentionValue: Int = 7,
    val recycleBinRetentionUnit: String = "Days",

    // The Cleaner, and the totals it shares with Browse's categories.

    val showCleanerScreen: Boolean = false,
    val cleanerLoading: Boolean = false,
    val cleanerData: CleanerData? = null,
    val categoryTotals: CategoryTotals? = null,
    val currentCleanerCategory: String? = null,
    val cleanerSelectedFiles: ImmutableSet<Int> = persistentSetOf(),

    // Organize Downloads: where each kind goes, and the files waiting for a yes.

    val orgDestDocs: String = "",
    val orgDestImages: String = "",
    val orgDestApks: String = "",
    val orgDestMusic: String = "",
    val orgDestVideos: String = "",
    val organisePendingFiles: List<FileItem> = emptyList(),

    // The music player, mirrored from MusicPlayer, which outlives any screen.

    val currentAudioFile: FileItem? = null,
    val isAudioPlaying: Boolean = false,
    val audioPlaybackPosition: Long = 0L,
    val audioDuration: Long = 0L,
    val audioTitle: String = "",
    val audioArtist: String = "",
    val audioArtworkData: ByteArray? = null,
    val showFullScreenPlayer: Boolean = false,

    // Nearby Share, mirrored from NearbyShareRepository, which goes on sharing without a screen.

    val nearbyPeers: ImmutableList<NearbyPeer> = persistentListOf(),
    val isNearbyReceiving: Boolean = false,
    val activeNearbyTransfer: TransferSession? = null,
    val incomingNearbyRequest: IncomingTransferRequest? = null,
    val stagedNearbyFiles: ImmutableList<FileItem> = persistentListOf(),
    val nearbyDeviceName: String = "",
    val nearbyReceivePath: String = "",
    val nearbyAskBeforeReceiving: Boolean = true,
    val receivedFilesPrompt: ReceivedFilesPrompt? = null,
    /** Files another app has shared into this one, waiting for a folder. */
    val incomingSharePrompt: IncomingSharePrompt? = null,

    // How the app looks.

    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val darkSurfaceStyle: DarkSurfaceStyle = DarkSurfaceStyle.STANDARD,
    val tint: KobbloAccent = KobbloAccent.Default,
    val themeHue: Float = 14f,
    val themeLightnessOffset: Float = 0f,
    val useDynamicSystemTheme: Boolean = false,
    /** The shape that was chosen, and the one that choice comes to now. */
    val iconShapeSetting: IconShapeType = IconShapeType.COOKIE,
    val activeIconShape: IconShapeType = IconShapeType.COOKIE,
    val gridColumns: Int = 3,
    val cornerRoundness: Float = 0.5f,
    val fontStyle: String = "Outfit",
    val textDecorations: ImmutableSet<String> = persistentSetOf(),
    val mainTextScale: Float = 1.0f,
    val subTextScale: Float = 1.0f,
    val invertText: Boolean = false,

    // The rest of Settings. A viewer is named as Settings saves it.

    val currentLanguage: String = "device_default",
    val viewerTextPdf: String = "In-app",
    val viewerMusic: String = "In-app",
    val viewerImage: String = "In-app",
    val haptics: HapticsSettings = HapticsSettings()
)

/** How one storage was left: the last place open in it, how that was arranged, and the way back from there. */
@Immutable
data class StorageNavState(
    val lastLocation: String,
    val sortMode: SortMode = SortMode.ALPHABETICAL,
    val filter: String = "all",
    val folderStack: List<Pair<String, String>> = emptyList(),
    val folderName: String? = null
)

/** A hide or unhide that has stopped to ask, because the name one of its files needs is taken. */
@Immutable
data class PendingHideConflict(
    val file: FileItem,
    val targetName: String,
    val targetPath: String,
    val suggestedName: String,
    /** The files that come after this one. */
    val remainingFiles: List<FileItem>,
    val isUnhide: Boolean,
    /** How it had gone up to here. */
    val successCount: Int,
    val failedCount: Int = 0
)

/** Files that have just arrived from another device. */
data class ReceivedFilesPrompt(
    val peerAlias: String,
    val files: List<FileItem>,
    /** How many were meant to arrive, when it isn't the transfer on hand that knows. */
    val expected: Int? = null
)

/** A signed-in MEGA account. A list of these is saved as JSON under these names, so they can't change. */
@Immutable
data class MegaAccount(
    val email: String,
    val session: String
)
