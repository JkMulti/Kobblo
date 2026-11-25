package com.kobblo.gallery.file.explorer

// Why a server couldn't be connected to. Each protocol tells different things apart, so each has its own set.

enum class SmbError {
    /** The server turned the name or the password down. */
    AUTH_FAILED,
    HOST_UNREACHABLE,
    TIMEOUT,

    /** Signed in, but the server has no share by that name. */
    SHARE_NOT_FOUND,
    UNKNOWN
}

enum class FtpError { AUTH_FAILED, HOST_UNREACHABLE, TIMEOUT, UNKNOWN }

enum class SftpError {
    AUTH_FAILED,
    HOST_UNREACHABLE,
    TIMEOUT,

    /** Its key isn't the one it had last time, so this may not be the same server. */
    HOST_KEY_MISMATCH,
    UNKNOWN
}

enum class WebDavError {
    AUTH_FAILED,

    /** A server answered, but not at that address. */
    NOT_FOUND,
    HOST_UNREACHABLE,
    UNKNOWN
}
