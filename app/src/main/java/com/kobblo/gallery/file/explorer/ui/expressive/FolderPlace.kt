package com.kobblo.gallery.file.explorer.ui.expressive

import java.io.File

// Where a folder is: which storage holds it, how far down it sits, what it and the way back are
// called, and where Back leads. Nothing here touches Android, so all of it can be tested as it is.

/** The Browse tab's root: the list of every location. */
internal const val LOCATIONS = "locations"

/** Places the app shows that are not folders, so a folder's page must never try to draw them. */
private val OtherScreens = setOf(LOCATIONS, "home", "gallery", "send")

/** Whether [location] is somewhere a folder's page lists, rather than a tab or a category. */
internal fun isFolderLocation(location: String): Boolean = location !in OtherScreens && !location.startsWith("category/")

/**
 * How deep a location sits, so opening a folder and going back can move opposite ways. Paths on the
 * phone are compared by their slashes; anywhere else by how many folders were opened to get there.
 */
internal fun pageDepth(location: String, foldersOpened: Int): Int =
    if (location.startsWith("/")) location.count { it == '/' } else foldersOpened

/** The cloud services that can be signed in to with more than one account, by the start of their ids. */
private val ManyAccountServices = listOf("drive", "mega")

/**
 * Whether a listed storage's id and an id from navigation mean the same storage. They do when they
 * are equal. For a service with several accounts they also do when both belong to it and name the
 * same account, whatever the letter case, or when either one doesn't say which account it means.
 */
internal fun sameStorage(listedId: String, wantedId: String): Boolean {
    if (listedId == wantedId) return true
    val service = ManyAccountServices.firstOrNull { listedId.startsWith(it) && wantedId.startsWith(it) } ?: return false
    fun accountOf(id: String) = if (id.startsWith(service + "_")) id.removePrefix(service + "_") else null
    val listed = accountOf(listedId) ?: return true
    val wanted = accountOf(wantedId) ?: return true
    return listed.equals(wanted, ignoreCase = true)
}

/**
 * The storage a location belongs to. A card or drive is recognised by the path itself, since several
 * of them share one id; anything else by the [storageId] navigation worked out. When neither finds
 * one, the first in the list stands in, which is the phone.
 */
internal fun storageHolding(location: String, storageId: String, storages: List<ConnectedStorage>): ConnectedStorage? =
    storages.firstOrNull { it.id.startsWith(CardStorageId) && (location == it.location || location.startsWith(it.location + "/")) }
        ?: storages.firstOrNull { sameStorage(it.id, storageId) }
        ?: storages.firstOrNull()

/** A folder as its page describes it. */
internal class FolderPlace(
    val storageId: String,
    val storage: ConnectedStorage?,
    /** Where the storage begins: a path on the phone, or the storage's own location anywhere else. */
    val root: String,
    val storageName: String,
    /** How many folders below the storage's root this one is. The root itself is 0. */
    val depth: Int,
    val title: String,
    /** What the way back leads to, or null at the storage's root, where it leads out to Browse. */
    val backTitle: String?,
    /** The folder above this one, where both are on the phone and something could be moved up into it. */
    val parentPath: String?
) {
    /** On the phone or a card in it, where the app can search, drag and use the keyboard. */
    val isOnDevice: Boolean get() = storageId == PhoneStorageId || storageId == CardStorageId

    /**
     * The free space to mention under the list, in gigabytes, or null where none is known. A card
     * reports its own, and [cardFreeGb] is the fallback for one that isn't in the list.
     */
    fun freeGb(phoneFreeGb: Float, cardFreeGb: Float): Float? = when (storageId) {
        PhoneStorageId -> phoneFreeGb
        CardStorageId -> storage?.space?.freeGb ?: cardFreeGb
        else -> null
    }
}

/**
 * Works out a [FolderPlace].
 *
 * @param folderStack the folders opened on the way here on a storage with no paths, as location and name.
 * @param folderName the name of the folder itself on such a storage, null at its root.
 * @param cardRoot the root of the card the location is on, when it is known.
 * @param unnamedStorage what to call the storage when the list has none to name.
 */
internal fun folderPlace(
    location: String,
    folderStack: List<Pair<String, String>>,
    folderName: String?,
    storageId: String,
    storages: List<ConnectedStorage>,
    phoneRoot: String,
    cardRoot: String?,
    severalDriveAccounts: Boolean,
    severalMegaAccounts: Boolean,
    unnamedStorage: String
): FolderPlace {
    val storage = storageHolding(location, storageId, storages)
    val root = when (storageId) {
        PhoneStorageId -> phoneRoot
        CardStorageId -> cardRoot ?: CardStorageId
        else -> storage?.location ?: phoneRoot
    }
    // With one account the service's name says enough. With several, only the address tells them apart.
    val storageName = when {
        storage == null -> unnamedStorage
        severalDriveAccounts && storage.id.startsWith("drive_") -> storage.id.removePrefix("drive_")
        severalMegaAccounts && storage.id.startsWith("mega_") -> storage.id.removePrefix("mega_")
        else -> storage.name
    }
    val hasPath = location.startsWith("/")
    val depth = when {
        hasPath && location.startsWith(root) -> location.removePrefix(root).split("/").count { it.isNotEmpty() }
        else -> folderStack.size + if (folderName != null) 1 else 0
    }
    val title = when {
        depth == 0 -> storageName
        hasPath -> File(location).name
        else -> folderName ?: storageName
    }
    val backTitle = when {
        depth == 0 -> null
        depth == 1 -> storageName
        hasPath -> File(location).parentFile?.name ?: storageName
        else -> folderStack.lastOrNull()?.second ?: storageName
    }
    val onDevice = storageId == PhoneStorageId || storageId == CardStorageId
    return FolderPlace(
        storageId = storageId,
        storage = storage,
        root = root,
        storageName = storageName,
        depth = depth,
        title = title,
        backTitle = backTitle,
        parentPath = if (onDevice && depth > 0 && hasPath) File(location).parent else null
    )
}

/** Where Back goes from a folder. */
internal sealed interface FolderExit {
    /** To another location: the folder above, or out to the list of locations. */
    data class To(val location: String) : FolderExit

    /** One step back through the folders opened on a storage that has no paths to go up by. */
    data object StepBack : FolderExit
}

/**
 * Whether Back leaves the storage from here. This is asked of the location alone, so it also holds
 * for the names navigation uses before a storage's first folder has loaded ("drive", "sd", "home").
 */
internal fun isStorageTop(location: String, storageId: String, noFoldersOpened: Boolean, phoneRoot: String, cardRoot: String?): Boolean = when {
    storageId == PhoneStorageId -> location == phoneRoot || location == "home" || '/' !in location
    storageId == CardStorageId -> location == (cardRoot ?: CardStorageId) || location == CardStorageId
    noFoldersOpened -> true
    storageId.startsWith("drive") -> location == "drive" || location.startsWith("drive:")
    storageId.startsWith("mega") -> location == "mega" || location.startsWith("mega:")
    storageId == "dropbox" -> location == "dropbox"
    else -> location.endsWith(":/")
}

/**
 * Decides [FolderExit]. From the top of a storage Back leads out to the list of locations. From a path
 * it leads to the folder above, but never above the storage's own root. Anywhere else it steps back
 * through the folders that were opened, and leaves when there are none.
 */
internal fun folderExit(location: String, storageId: String, noFoldersOpened: Boolean, phoneRoot: String, cardRoot: String?): FolderExit = when {
    isStorageTop(location, storageId, noFoldersOpened, phoneRoot, cardRoot) -> FolderExit.To(LOCATIONS)
    location.startsWith("/") -> {
        val floor = if (storageId == CardStorageId) cardRoot ?: phoneRoot else phoneRoot
        val above = File(location).parent
        FolderExit.To(if (above != null && above.length >= floor.length) above else floor)
    }
    !noFoldersOpened -> FolderExit.StepBack
    else -> FolderExit.To(LOCATIONS)
}
