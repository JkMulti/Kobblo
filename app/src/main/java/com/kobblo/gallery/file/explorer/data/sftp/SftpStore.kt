package com.kobblo.gallery.file.explorer.data.sftp

import android.content.Context
import com.kobblo.gallery.file.explorer.data.remote.SavedConnections

/** The saved SFTP connections. */
class SftpStore(context: Context) :
    SavedConnections<SftpConnection>(context, "sftp", SftpConnection::class.java, SftpConnection::id) {

    /**
     * Pins the server's host key once the first connection has been trusted, so later connections
     * are checked against it.
     */
    fun updateHostKeyFingerprint(connectionId: String, fingerprint: String) {
        val saved = find(connectionId) ?: return
        val password = getPassword(connectionId) ?: return
        saveConnection(saved.copy(hostKeyFingerprint = fingerprint), password)
    }
}
