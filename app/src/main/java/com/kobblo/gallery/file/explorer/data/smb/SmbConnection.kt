package com.kobblo.gallery.file.explorer.data.smb

/**
 * A saved Windows share (SMB) and how to reach it. [SmbStore] keeps these as JSON under the field names
 * below, so the names, their types and their order are fixed. The password is not among them: it is stored
 * apart, encrypted.
 */
data class SmbConnection(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    /** The share on the host to open, as in `\\host\shareName`. */
    val shareName: String,
    /** The Windows domain the account belongs to, or empty for an account on the host itself. */
    val domain: String = "",
    val username: String,
    val savedAt: Long
)
