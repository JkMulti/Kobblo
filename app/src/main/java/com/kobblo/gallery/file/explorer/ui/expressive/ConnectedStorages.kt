package com.kobblo.gallery.file.explorer.ui.expressive

import android.content.Context
import android.os.Environment
import androidx.compose.ui.graphics.vector.ImageVector
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.tools.storage.VolumeInfo
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** How much room a volume on the phone has, in the gigabytes the system counts it in. */
internal class StorageSpace(val totalGb: Float, val freeGb: Float) {
    /** How full it is, from 0 to 1. A volume too small to measure is taken as a tenth of a gigabyte, so nothing divides by zero. */
    val usedFraction: Float get() = 1f - freeGb / totalGb.coerceAtLeast(0.1f)

    val freeText: String get() = oneDecimal(freeGb)

    /** A whole number is enough for a phone, but a small card would read as "of 0 GB". */
    val totalText: String get() = if (totalGb < 10f) oneDecimal(totalGb) else "%.0f".format(totalGb)
}

private fun oneDecimal(gigabytes: Float): String = "%.1f".format(gigabytes)

/**
 * Somewhere files are kept that Browse can open: the phone itself, a card or drive plugged into it,
 * a cloud account, or a server.
 *
 * [id] is what the rest of the app calls it and [location] is where opening it lands. Only storage on
 * the phone has a [space]: nobody is told how full a server is.
 */
internal class ConnectedStorage(
    val id: String,
    val name: String,
    val summary: String,
    val location: String,
    val icon: ImageVector,
    val space: StorageSpace? = null
) {
    val isOnDevice: Boolean get() = space != null

    /**
     * The id navigation knows this storage by. Every card and drive beyond the first has an id of its
     * own in the list, but they are all opened as "sd": the location says which one is meant.
     */
    val switchId: String get() = if (id.startsWith(CardStorageId + "_")) CardStorageId else id

    /** Whether the list of locations offers to disconnect it. The phone and its first card are never offered. */
    val canDisconnect: Boolean get() = id != PhoneStorageId && id != CardStorageId

    /**
     * Whether leaving it is worded as logging out. That is so for an account listed under its service's
     * bare id; an account listed by its address, like a server, is worded as disconnecting.
     */
    val logsOut: Boolean get() = id in BareAccountIds
}

/** What navigation calls the phone's own storage, and the first card or drive plugged into it. */
internal const val PhoneStorageId = "internal"
internal const val CardStorageId = "sd"
private val BareAccountIds = setOf("drive", "mega", "dropbox")

/** The few words the list takes from resources. */
internal class StorageWords(
    val phone: String,
    /** Stands in for an account's address when the service hasn't said whose it is. */
    val connected: String,
    /** "12.3 GB free", given the number. */
    val gigabytesFree: (String) -> String
)

/** Every storage there is right now, in the order they are always listed. */
internal fun connectedStorages(context: Context, state: AppState): List<ConnectedStorage> = connectedStorages(
    state = state,
    volumes = StorageVolumes.snapshot,
    phoneRoot = Environment.getExternalStorageDirectory().absolutePath,
    words = StorageWords(
        phone = context.getString(R.string.internal_storage),
        connected = context.getString(R.string.screen_connected),
        gigabytesFree = { context.getString(R.string.gb_free, it) }
    )
)

/**
 * The same, from what it is made of: the phone, then the cards and drives mounted on it, then cloud
 * accounts, then saved servers, each kind in the order it was added.
 */
internal fun connectedStorages(
    state: AppState,
    volumes: List<VolumeInfo>,
    phoneRoot: String,
    words: StorageWords
): List<ConnectedStorage> = buildList {
    add(
        ConnectedStorage(
            id = PhoneStorageId,
            name = words.phone,
            summary = words.gigabytesFree(oneDecimal(state.storageFreeGb)),
            location = phoneRoot,
            icon = AppIcons.Phone,
            space = StorageSpace(totalGb = state.storageTotalGb.coerceAtLeast(1f), freeGb = state.storageFreeGb.coerceAtLeast(0f))
        )
    )
    volumes.filterNot { it.isPrimary }.forEachIndexed { position, volume ->
        add(
            ConnectedStorage(
                // The first keeps the id navigation has always used for a card. Each further one
                // carries its volume's id, and its path says where its root is.
                id = if (position == 0) CardStorageId else CardStorageId + "_" + volume.id,
                name = volume.name,
                summary = words.gigabytesFree(oneDecimal(volume.freeGb)),
                location = volume.path,
                icon = if (volume.isUsb) AppIcons.Usb else AppIcons.SdCard,
                space = StorageSpace(totalGb = volume.totalGb.coerceAtLeast(0.1f), freeGb = volume.freeGb.coerceAtLeast(0f))
            )
        )
    }

    addAll(cloudAccounts("drive", "Google Drive", state.googleDriveAccounts, state.isGoogleDriveAuthenticated, state.googleDriveAccountEmail, words))
    addAll(cloudAccounts("mega", "Mega", state.megaAccounts.map { it.email }, state.isMegaAuthenticated, state.megaAccountEmail, words))
    addAll(cloudAccounts("dropbox", "Dropbox", emptyList(), state.isDropboxAuthenticated, state.dropboxAccountEmail, words))

    state.smbState.savedConnections.mapTo(this) {
        server("smb", it.id, name = it.displayName.ifBlank { it.host }, protocol = "SMB", address = it.host, icon = AppIcons.Server)
    }
    state.ftpState.savedConnections.mapTo(this) {
        server("ftp", it.id, name = it.displayName.ifBlank { it.host }, protocol = "FTP", address = it.host, icon = AppIcons.FolderShared)
    }
    state.sftpState.savedConnections.mapTo(this) {
        server("sftp", it.id, name = it.displayName.ifBlank { it.host }, protocol = "SFTP", address = it.host, icon = AppIcons.Shield)
    }
    state.webDavState.savedConnections.mapTo(this) {
        // A Nextcloud server is a WebDAV server kept under its own name, so the two are told apart everywhere.
        val protocol = if (it.isNextcloud) "Nextcloud" else "WebDAV"
        val key = if (it.isNextcloud) "nextcloud" else "webdav"
        server(key, it.id, name = it.displayName.ifBlank { protocol }, protocol = protocol, address = it.serverUrl, icon = AppIcons.CloudSync)
    }
}

/**
 * One cloud service's entries. A service that keeps several accounts lists each under its address,
 * with an id and a location that name it. One that is only known to be signed in gets a single entry
 * under the service's bare [key], and none at all when nobody is signed in.
 */
private fun cloudAccounts(
    key: String,
    service: String,
    accounts: List<String>,
    signedIn: Boolean,
    activeAccount: String?,
    words: StorageWords
): List<ConnectedStorage> = when {
    accounts.isNotEmpty() -> accounts.map { address ->
        ConnectedStorage(id = key + "_" + address, name = service, summary = address, location = "$key:$address", icon = AppIcons.Cloud)
    }
    signedIn -> listOf(
        ConnectedStorage(id = key, name = service, summary = activeAccount ?: words.connected, location = key, icon = AppIcons.Cloud)
    )
    else -> emptyList()
}

/** A saved server. Its id is its protocol's [key] and the connection's own id, and it opens at its root. */
private fun server(key: String, connectionId: String, name: String, protocol: String, address: String, icon: ImageVector): ConnectedStorage {
    val id = key + "_" + connectionId
    return ConnectedStorage(id = id, name = name, summary = "$protocol · $address", location = "$id:/", icon = icon)
}

/**
 * What has to be sent to leave the storage called [storageId]. A cloud account is signed out, by the
 * address in its id or, for a bare id, the account that is active. A server is forgotten as well as
 * disconnected, since its row is only ever there because it was saved. Storage on the phone can't be left,
 * so there is nothing to send for it.
 */
internal fun disconnectActions(storageId: String, activeDriveAccount: String?, activeMegaAccount: String?): List<AppAction> {
    fun accountOf(key: String, active: String?) = if (storageId.startsWith(key + "_")) storageId.removePrefix(key + "_") else active
    fun connectionOf(key: String) = storageId.removePrefix(key + "_")
    fun isFor(key: String) = storageId.startsWith(key + "_")
    return when {
        storageId == "drive" || isFor("drive") -> listOf(AppAction.SetGoogleDriveAuthStatus(false, accountOf("drive", activeDriveAccount)))
        storageId == "mega" || isFor("mega") -> listOf(AppAction.SetMegaAuthStatus(false, accountOf("mega", activeMegaAccount), null))
        storageId == "dropbox" -> listOf(AppAction.SetDropboxAuthStatus(false, null))
        isFor("smb") -> connectionOf("smb").let { listOf(AppAction.SmbAction.DeleteConnection(it), AppAction.SmbAction.Disconnect(it)) }
        isFor("ftp") -> connectionOf("ftp").let { listOf(AppAction.FtpAction.DeleteConnection(it), AppAction.FtpAction.Disconnect(it)) }
        isFor("sftp") -> connectionOf("sftp").let { listOf(AppAction.SftpAction.DeleteConnection(it), AppAction.SftpAction.Disconnect(it)) }
        isFor("webdav") || isFor("nextcloud") -> storageId.substringAfter("_").let {
            listOf(AppAction.WebDavAction.DeleteConnection(it), AppAction.WebDavAction.Disconnect(it))
        }
        else -> emptyList()
    }
}
