package com.kobblo.gallery.file.explorer.session

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FtpError
import com.kobblo.gallery.file.explorer.FtpState
import com.kobblo.gallery.file.explorer.SftpError
import com.kobblo.gallery.file.explorer.SftpState
import com.kobblo.gallery.file.explorer.SmbError
import com.kobblo.gallery.file.explorer.SmbState
import com.kobblo.gallery.file.explorer.WebDavError
import com.kobblo.gallery.file.explorer.WebDavState
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpStorageProvider
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

internal object SmbTraits : ServerTraits<SmbConnection, SmbError>(ServerKind.Smb, SmbSlot) {
    override fun idOf(connection: SmbConnection) = connection.id

    override fun errorOf(failure: Throwable?): SmbError = when {
        failure.isUnreachable() -> SmbError.HOST_UNREACHABLE
        failure.isTimeout() -> SmbError.TIMEOUT
        failure.mentions("LOGON_FAILURE", "Access denied", "Authentication") -> SmbError.AUTH_FAILED
        failure.mentions("BAD_NETWORK_NAME", "not found") -> SmbError.SHARE_NOT_FOUND
        else -> SmbError.UNKNOWN
    }
}

internal object FtpTraits : ServerTraits<FtpConnection, FtpError>(ServerKind.Ftp, FtpSlot) {
    override fun idOf(connection: FtpConnection) = connection.id

    override fun errorOf(failure: Throwable?): FtpError = when {
        failure.isUnreachable() -> FtpError.HOST_UNREACHABLE
        failure.isTimeout() -> FtpError.TIMEOUT
        // 530 is FTP's own code for not being logged in.
        failure.mentions("Authentication", "Login", "530") -> FtpError.AUTH_FAILED
        else -> FtpError.UNKNOWN
    }
}

/**
 * @param pinKey keeps the fingerprint of the key a server showed the first time, so it can be held to it
 * from then on.
 */
internal class SftpTraits(private val pinKey: (connectionId: String, fingerprint: String) -> Unit) :
    ServerTraits<SftpConnection, SftpError>(ServerKind.Sftp, SftpSlot) {

    override fun idOf(connection: SftpConnection) = connection.id

    override fun errorOf(failure: Throwable?): SftpError = when {
        failure.isUnreachable() -> SftpError.HOST_UNREACHABLE
        failure.isTimeout() -> SftpError.TIMEOUT
        failure.mentions("Auth", "password", "publickey") -> SftpError.AUTH_FAILED
        failure.mentions("HostKeyChanged", "fingerprint") -> SftpError.HOST_KEY_MISMATCH
        else -> SftpError.UNKNOWN
    }

    override fun opened(connection: SftpConnection, answer: Any?) {
        val shown = (answer as? SftpStorageProvider.ConnectResult)?.observedFingerprint
        if (connection.hostKeyFingerprint == null && !shown.isNullOrBlank()) pinKey(connection.id, shown)
    }
}

internal object WebDavTraits : ServerTraits<WebDavConnection, WebDavError>(ServerKind.WebDav, WebDavSlot) {
    override fun idOf(connection: WebDavConnection) = connection.id

    override fun schemeOf(connection: WebDavConnection) =
        if (connection.isNextcloud) NextcloudScheme else super.schemeOf(connection)

    override fun errorOf(failure: Throwable?): WebDavError = when {
        failure.isUnreachable() -> WebDavError.HOST_UNREACHABLE
        failure is WebDavException.AuthFailed -> WebDavError.AUTH_FAILED
        failure is WebDavException.NotFound -> WebDavError.NOT_FOUND
        else -> WebDavError.UNKNOWN
    }
}

private fun Throwable?.isUnreachable() = this is UnknownHostException || this is ConnectException

private fun Throwable?.isTimeout() = this is SocketTimeoutException || this is TimeoutException

/** The libraries mostly tell one failure from another by what it says. */
private fun Throwable?.mentions(vararg words: String): Boolean {
    val said = this?.message ?: return false
    return words.any { it in said }
}

private object SmbSlot : ServerSlot<SmbConnection, SmbError> {
    override fun of(state: AppState) = with(state.smbState) {
        ServerView(savedConnections, activeConnectionId, currentPath, connectionStatus, error)
    }

    override fun into(state: AppState, view: ServerView<SmbConnection, SmbError>) =
        state.copy(smbState = SmbState(view.saved, view.activeId, view.path, view.status, view.error))
}

private object FtpSlot : ServerSlot<FtpConnection, FtpError> {
    override fun of(state: AppState) = with(state.ftpState) {
        ServerView(savedConnections, activeConnectionId, currentPath, connectionStatus, error)
    }

    override fun into(state: AppState, view: ServerView<FtpConnection, FtpError>) =
        state.copy(ftpState = FtpState(view.saved, view.activeId, view.path, view.status, view.error))
}

private object SftpSlot : ServerSlot<SftpConnection, SftpError> {
    override fun of(state: AppState) = with(state.sftpState) {
        ServerView(savedConnections, activeConnectionId, currentPath, connectionStatus, error)
    }

    override fun into(state: AppState, view: ServerView<SftpConnection, SftpError>) =
        state.copy(sftpState = SftpState(view.saved, view.activeId, view.path, view.status, view.error))
}

private object WebDavSlot : ServerSlot<WebDavConnection, WebDavError> {
    override fun of(state: AppState) = with(state.webDavState) {
        ServerView(savedConnections, activeConnectionId, currentPath, connectionStatus, error)
    }

    override fun into(state: AppState, view: ServerView<WebDavConnection, WebDavError>) =
        state.copy(webDavState = WebDavState(view.saved, view.activeId, view.path, view.status, view.error))
}
