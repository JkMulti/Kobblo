package com.kobblo.gallery.file.explorer.ui.root

import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTab

/** How far in a page lies. Going to a higher floor is going deeper, and to a lower one is coming back. */
internal enum class Floor {
    /** What the tab bar leads to. */
    Tabs,

    /** Screens that open over a tab and are closed again. */
    Covers,

    /** Every file of one kind, wherever it is. It opens from a tab, and from the Cleaner over one. */
    Category
}

/**
 * One of the pages the root shows. There's one at a time, but for the moment in which one slides over
 * another. [id] never changes, as what someone was doing on a page is saved under it. [place] is where
 * the page comes among the others on its floor, and two that share a place belong to one tab.
 */
internal sealed class RootPage(val id: String, val floor: Floor, val place: Int) {
    data object Gallery : RootPage("gallery", Floor.Tabs, 0)
    data object Recents : RootPage("home", Floor.Tabs, 1)
    data object Locations : RootPage("locations", Floor.Tabs, 2)
    data object Folder : RootPage("browse", Floor.Tabs, 2)
    data object Send : RootPage("send", Floor.Tabs, 3)
    data object Settings : RootPage("settings", Floor.Tabs, 4)

    data object Cleaner : RootPage("cleaner", Floor.Covers, 0)
    data object Trash : RootPage("trash", Floor.Covers, 1)
    data object About : RootPage("about", Floor.Covers, 2)
    data object Vault : RootPage("vault", Floor.Covers, 3)

    data class Category(val key: String) : RootPage(CategoryPrefix + key, Floor.Category, 0)

    /** Settings is a tab, and About a page of it, like a folder in Browse, so both keep the tab bar. */
    val keepsTabBar: Boolean get() = floor == Floor.Tabs || this == About

    /** Whether the page says itself what Back does on it. */
    val ownsBack: Boolean get() = floor == Floor.Covers || this == Settings
}

internal const val CategoryPrefix = "category/"

/** The page to show. The screens that open over the tabs come first, in the order they cover one another. */
internal fun AppState.page(aboutOpen: Boolean): RootPage = when {
    aboutOpen -> RootPage.About
    showSettingsScreen -> RootPage.Settings
    showTrashScreen -> RootPage.Trash
    showCleanerScreen -> RootPage.Cleaner
    showVaultScreen -> RootPage.Vault
    location == "gallery" -> RootPage.Gallery
    location == "home" -> RootPage.Recents
    location == "locations" -> RootPage.Locations
    location == "send" -> RootPage.Send
    location.startsWith(CategoryPrefix) -> RootPage.Category(location.removePrefix(CategoryPrefix))
    else -> RootPage.Folder
}

/** Going from one page to another, as it looks. */
internal enum class PageMove {
    /** To a tab further along the bar. */
    TabAfter,

    /** To a tab nearer its start. */
    TabBefore,

    Deeper,
    Back
}

internal fun pageMove(from: RootPage, to: RootPage): PageMove = when {
    from.floor != to.floor -> deeperIf(to.floor > from.floor)
    from.floor == Floor.Tabs -> when {
        to.place > from.place -> PageMove.TabAfter
        to.place < from.place -> PageMove.TabBefore
        // The list of locations is the parent of every folder.
        else -> deeperIf(from == RootPage.Locations && to == RootPage.Folder)
    }
    from.floor == Floor.Covers -> deeperIf(to.place >= from.place)
    // One category goes to another when it has kinds within it, and those have the longer names.
    else -> deeperIf(to.id.length > from.id.length)
}

private fun deeperIf(deeper: Boolean): PageMove = if (deeper) PageMove.Deeper else PageMove.Back

/** The tab the page at [location] belongs to. About opens from Settings, so it's still that tab. */
internal fun tabOf(state: AppState, aboutOpen: Boolean): ExpressiveTab = when {
    aboutOpen || state.showSettingsScreen -> ExpressiveTab.SETTINGS
    state.location == "gallery" -> ExpressiveTab.GALLERY
    state.location == "home" -> ExpressiveTab.RECENTS
    state.location == "send" -> ExpressiveTab.SEND
    else -> ExpressiveTab.BROWSE
}

/**
 * Where the Browse tab returns to when it's switched back to, having been at [location]. A cloud folder
 * also needs its folder stack to restore, so those return to the list of locations instead. The other
 * tabs leave it where it was.
 */
internal fun browsePlaceAfter(location: String, before: String): String = when {
    location == "gallery" || location == "home" || location == "send" -> before
    location.startsWith("/") || location.startsWith(CategoryPrefix) -> location
    else -> "locations"
}
