package com.kobblo.gallery.file.explorer.data.sftp

/** How an SFTP server is told who is signing in. The names are what gets saved. */
enum class SftpAuthType { PASSWORD, KEY }

/**
 * A saved SFTP server and how to reach it. [SftpStore] keeps these as JSON under the field names below, so
 * the names, their types and their order are fixed. The account's password is not among them: it is
 * stored apart, encrypted.
 */
data class SftpConnection(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SftpAuthType = SftpAuthType.PASSWORD,
    /** The private key itself, as the text of its file. Only read when signing in with [SftpAuthType.KEY]. */
    val privateKeyPem: String = "",
    /** What unlocks [privateKeyPem], or blank for a key that isn't locked. */
    val privateKeyPassphrase: String = "",
    val initialPath: String = "/",
    /**
     * The fingerprint of the server's host key, once one has been seen. From then on only that key is
     * accepted; while this is null the first key the server shows is trusted.
     */
    val hostKeyFingerprint: String? = null,
    val savedAt: Long
)
