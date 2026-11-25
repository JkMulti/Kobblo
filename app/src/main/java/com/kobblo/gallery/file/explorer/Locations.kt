package com.kobblo.gallery.file.explorer

import androidx.compose.runtime.Immutable
import com.kobblo.gallery.file.explorer.data.SdCardPaths
import com.kobblo.gallery.file.explorer.session.CloudService
import com.kobblo.gallery.file.explorer.session.Places
import com.kobblo.gallery.file.explorer.session.ServerKind

// A location is a string that says where the app is: a path on the phone, `drive:me@example.com` or
// `sftp_41c7:/home` for somewhere else, or the name of one of the app's own tabs. They are kept in saved
// shortcuts and recents, so what each one means can't change.

enum class NavTab { HOME, RECENT, PINNED, CLOUD }

@Immutable
data class NavBarState(val selected: NavTab = NavTab.HOME)

/** The screens that have a location of their own. While one is open, the phone counts as the storage. */
private val ScreenLocations =
    setOf("home", "gallery", "locations", "send", "cleaner", "trash", "settings", "about")

/** Whether [path] is something kept in the cloud or on a server, rather than a file that can be opened as it is. */
fun isCloudPath(path: String): Boolean = Places.isInsideCloud(path)

/**
 * The id of the storage [location] is in, which is what navigation remembers each storage under:
 * `internal` for the phone, `sd` for a card, and for the rest the service with its account, or the server.
 *
 * @param activeDriveEmail the account meant by a Drive location that doesn't name one, and
 * [activeMegaEmail] the same for MEGA.
 */
fun getStorageIdForLocation(
    location: String,
    sdCardPath: String? = null,
    activeDriveEmail: String? = null,
    activeMegaEmail: String? = null
): String {
    if (location in ScreenLocations) return "internal"
    if (SdCardPaths.isSdCardLocation(location, sdCardPath)) return "sd"
    return when (val cloud = CloudService.of(location)) {
        CloudService.Drive -> cloud.storageId(location, activeDriveEmail)
        CloudService.Mega -> cloud.storageId(location, activeMegaEmail)
        CloudService.Dropbox -> cloud.key
        null -> when {
            ServerKind.of(location) != null -> location.substringBefore(':')
            // However it's written, Dropbox is the one account.
            location.startsWith(CloudService.Dropbox.key) -> CloudService.Dropbox.key
            else -> "internal"
        }
    }
}
