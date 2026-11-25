package com.kobblo.gallery.file.explorer.ui

import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.ftp.FtpConnection
import com.kobblo.gallery.file.explorer.data.sftp.SftpConnection
import com.kobblo.gallery.file.explorer.data.smb.SmbConnection
import com.kobblo.gallery.file.explorer.data.webdav.WebDavConnection
import com.kobblo.gallery.file.explorer.data.webdav.buildNextcloudUrl
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Domain
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Host
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Name
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Password
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Port
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Server
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Share
import com.kobblo.gallery.file.explorer.ui.ConnectionField.Username
import java.util.UUID

// Adding a server is the same job whatever it speaks: ask for a few lines of text, check none that
// matter were left empty, and make a connection of them. What a kind of server asks for, and what it
// makes of the answers, is written down here once per kind. Nothing here draws anything, so all of it
// can be tested without a screen.

/** One line of text a form can ask for. */
internal enum class ConnectionField(@StringRes val label: Int) {
    /** What to call the connection in lists. */
    Name(R.string.polish_field_name),
    Host(R.string.host_ip_label),
    Port(R.string.port_label),
    /** The shared folder on a Windows or NAS server. */
    Share(R.string.share_name_label),
    /** The Windows domain or workgroup an account belongs to. */
    Domain(R.string.polish_field_domain),
    /** A whole address in one line, for servers reached by URL. */
    Server(R.string.polish_field_server),
    Username(R.string.username_label),
    Password(R.string.password_label)
}

/** What has been typed into a form so far, and where its switch stands if it has one. */
internal data class ConnectionEntries(
    private val typed: Map<ConnectionField, String> = emptyMap(),
    val switchOn: Boolean = false
) {
    /** What's in [field]: empty until something is typed, and exactly as typed after, spaces and all. */
    operator fun get(field: ConnectionField): String = typed[field].orEmpty()

    fun with(field: ConnectionField, text: String): ConnectionEntries = copy(typed = typed + (field to text))

    /** The port as a number, or [fallback] when what's there isn't one. */
    fun port(fallback: Int): Int = this[Port].toIntOrNull() ?: fallback
}

/**
 * The form that adds one kind of server.
 *
 * @param fields what it asks for, from top to bottom.
 * @param required the fields that can't be left blank.
 * @param incomplete what to say when one of them is.
 * @param confirm the word on its button.
 * @param footnote a hint beneath the fields.
 * @param switchLabel the one yes-or-no question it ends with, if it has one.
 * @param defaultPort the port the form starts filled in with.
 * @param serverExample an address of the right shape, shown in [ConnectionField.Server] until something is typed.
 * @param build makes the connection from a complete form, given an id for it and the time.
 */
internal class ConnectionForm<C : Any>(
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val fields: List<ConnectionField>,
    val required: Set<ConnectionField>,
    @StringRes val incomplete: Int,
    @StringRes val confirm: Int = R.string.save_action,
    @StringRes val footnote: Int? = null,
    @StringRes val switchLabel: Int? = null,
    val defaultPort: Int? = null,
    val serverExample: String = "",
    private val build: (entries: ConnectionEntries, id: String, savedAt: Long) -> C
) {
    /** The form as it first appears: empty, but for the usual port. */
    fun blank(): ConnectionEntries {
        val empty = ConnectionEntries()
        return if (defaultPort == null) empty else empty.with(Port, defaultPort.toString())
    }

    /** Whether every required field has something in it. Spaces alone are nothing. */
    fun isComplete(entries: ConnectionEntries): Boolean = required.none { entries[it].isBlank() }

    /** The connection [entries] describe, or null while a required field is still blank. */
    fun connection(
        entries: ConnectionEntries,
        id: String = UUID.randomUUID().toString(),
        savedAt: Long = System.currentTimeMillis()
    ): C? = if (isComplete(entries)) build(entries, id, savedAt) else null
}

private const val FtpPort = 21
private const val SftpPort = 22
private const val SmbPort = 445

/** A connection with no name of its own goes by its host. */
private fun ConnectionEntries.nameOrHost(): String = this[Name].ifBlank { this[Host] }

internal val FtpForm = ConnectionForm(
    title = R.string.add_ftp_connection_title,
    subtitle = R.string.polish_ftp_subtitle,
    fields = listOf(Name, Host, Port, Username, Password),
    required = setOf(Host, Username),
    incomplete = R.string.polish_server_missing,
    // Whether to wrap the session in TLS.
    switchLabel = R.string.use_ftps_label,
    defaultPort = FtpPort
) { entries, id, savedAt ->
    FtpConnection(
        id = id,
        displayName = entries.nameOrHost(),
        host = entries[Host],
        port = entries.port(FtpPort),
        username = entries[Username],
        useFtps = entries.switchOn,
        savedAt = savedAt
    )
}

internal val SftpForm = ConnectionForm(
    title = R.string.add_sftp_connection_title,
    subtitle = R.string.polish_sftp_subtitle,
    fields = listOf(Name, Host, Port, Username, Password),
    required = setOf(Host, Username),
    incomplete = R.string.polish_server_missing,
    defaultPort = SftpPort
) { entries, id, savedAt ->
    SftpConnection(
        id = id,
        displayName = entries.nameOrHost(),
        host = entries[Host],
        port = entries.port(SftpPort),
        username = entries[Username],
        savedAt = savedAt
    )
}

internal val SmbForm = ConnectionForm(
    title = R.string.add_smb_connection_title,
    subtitle = R.string.polish_smb_subtitle,
    fields = listOf(Name, Host, Port, Share, Domain, Username, Password),
    required = setOf(Host, Share, Username),
    incomplete = R.string.polish_smb_missing,
    defaultPort = SmbPort
) { entries, id, savedAt ->
    SmbConnection(
        id = id,
        displayName = entries.nameOrHost(),
        host = entries[Host],
        port = entries.port(SmbPort),
        shareName = entries[Share],
        domain = entries[Domain],
        username = entries[Username],
        savedAt = savedAt
    )
}

internal val WebDavForm = ConnectionForm(
    title = R.string.polish_webdav_add,
    subtitle = R.string.polish_webdav_subtitle,
    fields = listOf(Name, Server, Username, Password),
    required = setOf(Server, Username, Password),
    incomplete = R.string.polish_all_required,
    confirm = R.string.connect_action,
    // Whether to trust a certificate the server signed for itself.
    switchLabel = R.string.polish_allow_self_signed,
    serverExample = "https://example.com/dav/"
) { entries, id, _ ->
    val url = webDavUrl(entries[Server])
    WebDavConnection(
        id = id,
        displayName = entries[Name].ifBlank { url.substringAfter("://").trimEnd('/') },
        serverUrl = url,
        username = entries[Username],
        isNextcloud = false,
        allowSelfSignedCert = entries.switchOn
    )
}

/**
 * Nextcloud is WebDAV at an address every Nextcloud shares, so its form asks only where the server is
 * and works the rest out. The connection is named after the server, as there's no line to name it on.
 */
internal val NextcloudForm = ConnectionForm(
    title = R.string.polish_nextcloud_add,
    subtitle = R.string.polish_nextcloud_subtitle,
    fields = listOf(Server, Username, Password),
    required = setOf(Server, Username, Password),
    incomplete = R.string.polish_all_required,
    confirm = R.string.connect_action,
    footnote = R.string.polish_nextcloud_tip,
    serverExample = "cloud.example.com"
) { entries, id, _ ->
    val server = entries[Server]
    WebDavConnection(
        id = id,
        displayName = server.removePrefix("https://").removePrefix("http://").trimEnd('/'),
        serverUrl = buildNextcloudUrl(server, entries[Username]),
        username = entries[Username],
        isNextcloud = true,
        allowSelfSignedCert = false
    )
}

/** A WebDAV address as typed, given `http://` when it was typed without a scheme. */
internal fun webDavUrl(typed: String): String = if (typed.startsWith("http")) typed else "http://$typed"
