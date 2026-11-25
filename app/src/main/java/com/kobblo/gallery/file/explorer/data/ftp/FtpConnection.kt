package com.kobblo.gallery.file.explorer.data.ftp

/**
 * A saved FTP server and how to reach it. [FtpStore] keeps these as JSON under the field names below, so
 * the names, their types and their order are fixed. The password is not among them: it is stored apart,
 * encrypted.
 */
data class FtpConnection(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 21,
    val username: String,
    /** Start in the clear and switch to TLS once connected, which is what most FTPS servers expect. */
    val useFtps: Boolean = false,
    /** TLS from the first byte, the older arrangement usually found on port 990. Wins over [useFtps]. */
    val useImplicitFtps: Boolean = false,
    val initialPath: String = "/",
    /** The phone opens the data connections. Off, the server calls back, which few networks let through. */
    val passiveMode: Boolean = true,
    val savedAt: Long
)
