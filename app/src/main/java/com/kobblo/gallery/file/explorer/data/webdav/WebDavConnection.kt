package com.kobblo.gallery.file.explorer.data.webdav

/**
 * A saved WebDAV server and how to reach it. A Nextcloud account is one of these as well: Nextcloud serves
 * a person's files over WebDAV at an address that follows from the server and the user name, which
 * [buildNextcloudUrl] works out.
 *
 * [WebDavStore] keeps these as JSON under the field names below, so the names, their types and their
 * order are fixed. The password is not among them: it is stored apart, encrypted.
 */
data class WebDavConnection(
    val id: String,
    val displayName: String,
    /** The address of the folder the connection opens at, scheme included: `https://example.com/dav/files/`. */
    val serverUrl: String,
    val username: String,
    /** Only changes how the connection is labelled and listed; the server is spoken to the same way. */
    val isNextcloud: Boolean = false,
    /** Accept whatever certificate the server shows, for servers at home that signed their own. */
    val allowSelfSignedCert: Boolean = false
) {
    init {
        val hasScheme = serverUrl.startsWith(PlainHttp) || serverUrl.startsWith(SecureHttp)
        require(hasScheme) { "serverUrl must include a scheme" }
    }

    /** [serverUrl] ending in exactly one slash, ready to have a path added to it. */
    val normalizedUrl: String
        get() = serverUrl.trimEnd('/') + "/"
}

/**
 * The WebDAV address of [username]'s files on the Nextcloud server at [domain]. The server may be given
 * with or without a scheme and with or without a slash at the end; HTTPS is used unless it says `http://`.
 */
fun buildNextcloudUrl(domain: String, username: String): String {
    val scheme = if (domain.startsWith(PlainHttp)) PlainHttp else SecureHttp
    val host = domain.removePrefix(SecureHttp).removePrefix(PlainHttp).trimEnd('/')
    return "$scheme$host/remote.php/dav/files/$username/"
}

private const val PlainHttp = "http://"
private const val SecureHttp = "https://"
