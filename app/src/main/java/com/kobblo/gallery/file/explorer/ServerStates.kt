package com.kobblo.gallery.file.explorer

import androidx.compose.runtime.Immutable
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

// The four kinds of server are described alike. They are four types because each saves a connection of
// its own kind and fails in its own ways, and the screens that draw them want to know which.

enum class ConnectionStatus {
    Idle,
    Connecting,
    Connected,
    Error
}

/** Windows shares. */
@Immutable
data class SmbState(
    val savedConnections: ImmutableList<SmbConnection> = persistentListOf(),
    /** The connected one, or the one that was when the app was last used. */
    val activeConnectionId: String? = null,
    val currentPath: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val error: SmbError? = null
)

/** FTP and FTPS servers. */
@Immutable
data class FtpState(
    val savedConnections: ImmutableList<FtpConnection> = persistentListOf(),
    /** The connected one, or the one that was when the app was last used. */
    val activeConnectionId: String? = null,
    val currentPath: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val error: FtpError? = null
)

/** Servers reached over SSH. */
@Immutable
data class SftpState(
    val savedConnections: ImmutableList<SftpConnection> = persistentListOf(),
    /** The connected one, or the one that was when the app was last used. */
    val activeConnectionId: String? = null,
    val currentPath: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val error: SftpError? = null
)

/** WebDAV servers and Nextcloud, which is listed apart but reached the same way. */
@Immutable
data class WebDavState(
    val savedConnections: ImmutableList<WebDavConnection> = persistentListOf(),
    /** The connected one, or the one that was when the app was last used. */
    val activeConnectionId: String? = null,
    val currentPath: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    val error: WebDavError? = null
)
