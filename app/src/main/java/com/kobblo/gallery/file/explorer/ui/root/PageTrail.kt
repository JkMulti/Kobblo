package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.NavTab
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTab
import java.io.File

/** What getting from page to page needs remembered beyond where the app is. */
@Stable
internal class PageTrail {
    var aboutOpen by mutableStateOf(false)

    /** A category was opened from the Cleaner, which Back returns to. */
    var cleanerWaiting by mutableStateOf(false)

    /** How far a Back gesture has been pulled, from 0 to 1. */
    var pull by mutableFloatStateOf(0f)
}

/** Remembers where Browse was, through the app being closed and made again, for its tab to return to. */
@Composable
internal fun rememberBrowsePlace(location: String): State<String> {
    val place = rememberSaveable { mutableStateOf("locations") }
    LaunchedEffect(location) { place.value = browsePlaceAfter(location, place.value) }
    return place
}

/** Where Back goes from a page that leaves it to the root. */
internal sealed interface BackStep {
    data class To(val location: String) : BackStep

    /** To the Cleaner, with the list of locations under it. */
    data object ToCleaner : BackStep

    /** To the folder a cloud folder was opened from. It may not be the one above it, so the view model knows which. */
    data object UpInCloud : BackStep
}

private val CloudServices = listOf("drive", "mega", "dropbox")

/** Back from [location]. [phoneRoot] is the top of the phone's storage: the folders above it aren't the app's to show. */
internal fun backFrom(location: String, cleanerWaiting: Boolean, phoneRoot: String): BackStep = when {
    location.startsWith(CategoryPrefix) -> backFromCategory(cleanerWaiting)
    location.startsWith("/") -> {
        val above = File(location).parent?.takeIf { it.length >= phoneRoot.length }
        BackStep.To(above ?: "locations")
    }
    CloudServices.any { location == it || location.startsWith(it + "_id:") } -> BackStep.UpInCloud
    else -> BackStep.To("gallery")
}

/** Back from a category, whichever it is. */
internal fun backFromCategory(cleanerWaiting: Boolean): BackStep =
    if (cleanerWaiting) BackStep.ToCleaner else BackStep.To("locations")

/** Takes a [step] back. */
internal fun PageTrail.take(step: BackStep, onAction: (AppAction) -> Unit) = when (step) {
    is BackStep.To -> onAction(AppAction.SetLocation(step.location))
    BackStep.UpInCloud -> onAction(AppAction.NavigateBackInDrive)
    BackStep.ToCleaner -> {
        cleanerWaiting = false
        onAction(AppAction.SetLocation("locations"))
        onAction(AppAction.SetCleanerScreenVisible(true))
    }
}

/** What tapping a tab comes to: what to do, in order, and the location it leads to if it leads to one. */
internal data class TabMove(val actions: List<AppAction>, val closesAbout: Boolean = false, val destination: String? = null)

/**
 * Works out what tapping [tab] does. [browsePlace] is where Browse was left.
 *
 * Settings lies over whatever tab was showing, so it closes for another. The Vault, Recently Deleted and
 * the Cleaner open from Browse and cover it until closed, so going to another tab closes them too.
 * Settings leaves them under it, and Browse is still as it was when its tab is picked again. Tapping the
 * tab you are on returns to its root, as on iOS.
 */
internal fun tabMove(tab: ExpressiveTab, state: AppState, aboutOpen: Boolean, browsePlace: String): TabMove {
    val here = tab == tabOf(state, aboutOpen)
    if (tab == ExpressiveTab.SETTINGS) {
        return if (here) TabMove(emptyList(), closesAbout = aboutOpen) else TabMove(listOf(AppAction.SetShowSettingsScreen(true)))
    }

    val closing = buildList {
        if (state.showSettingsScreen) add(AppAction.SetShowSettingsScreen(false))
        if (tab != ExpressiveTab.BROWSE || here) {
            if (state.showVaultScreen) add(AppAction.SetShowVaultScreen(false))
            if (state.showTrashScreen) add(AppAction.SetTrashScreenVisible(false))
            if (state.showCleanerScreen) add(AppAction.SetCleanerScreenVisible(false))
        }
    }
    fun lead(location: String, vararg going: AppAction) = TabMove(closing + going, closesAbout = aboutOpen, destination = location)
    return when (tab) {
        ExpressiveTab.RECENTS -> lead("home", AppAction.SelectNavTab(NavTab.HOME))
        ExpressiveTab.SEND -> lead("send", *sendSelection(state).toTypedArray(), AppAction.SetLocation("send"))
        ExpressiveTab.BROWSE -> {
            val place = if (here) "locations" else browsePlace
            lead(place, AppAction.SetLocation(place))
        }
        else -> lead("gallery", AppAction.SetLocation("gallery"))
    }
}

/** Going to Send takes whatever is selected along, to be sent. */
private fun sendSelection(state: AppState): List<AppAction> {
    if (!state.isSelectionMode || state.selectedFiles.isEmpty()) return emptyList()
    return listOf(AppAction.NearbyShareAction.StageFiles(state.selectionAsPicked()), AppAction.ClearSelection)
}

/** The selected files, in the order they were picked. */
internal fun AppState.selectionAsPicked(): List<FileItem> = selectedFiles.mapNotNull { id -> files.find { it.id == id } }

/** The selected files, in the order they're listed. */
internal fun AppState.selectionAsListed(): List<FileItem> = files.filter { it.id in selectedFiles }
