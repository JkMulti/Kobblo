package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.search.SearchBackHandler
import com.kobblo.gallery.file.explorer.explorer.search.SearchResults
import com.kobblo.gallery.file.explorer.ui.formatSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import java.io.File

private data class CategoryEntry(
    val titleRes: Int,
    val icon: ImageVector,
    val tint: Color,
    val filterKey: String
)

private val CategoryEntries = listOf(
    CategoryEntry(R.string.cat_images, AppIcons.Photo, KobbloTint.Indigo, "image"),
    CategoryEntry(R.string.cat_videos, AppIcons.PlayRect, KobbloTint.Pink, "video"),
    CategoryEntry(R.string.cat_audio, AppIcons.Music, KobbloTint.Orange, "audio"),
    CategoryEntry(R.string.cat_docs, AppIcons.FileText, KobbloTint.Blue, "doc"),
    CategoryEntry(R.string.cat_apps, AppIcons.Android, KobbloTint.Green, "apk"),
    CategoryEntry(R.string.cat_archives, AppIcons.Archive, KobbloTint.Brown, "archive"),
    CategoryEntry(R.string.cat_large, AppIcons.Drive, KobbloTint.Gray, "large")
)

/**
 * The root of the Browse tab: everywhere a file can live, the way the Files app lists them. Each
 * section folds away, and everything the old drawer held now has a row here.
 */
@Composable
fun ExpressiveLocationsScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    onAddLocation: () -> Unit,
    onOpenVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val search = rememberFileSearch(state)
    SearchBackHandler(state, onAction)
    var showManage by remember { mutableStateOf(false) }
    var locationsOpen by rememberSaveable { mutableStateOf(true) }
    var favoritesOpen by rememberSaveable { mutableStateOf(true) }
    var categoriesOpen by rememberSaveable { mutableStateOf(true) }
    var toolsOpen by rememberSaveable { mutableStateOf(true) }
    // Nothing listed here can be selected except search results, which keep to the tab bar's clearance.
    val bottomPadding = listClearance(state, withSelectionBar = false)

    CompositionLocalProvider(LocalSelectionActive provides state.isSelectionMode) {
        Column(modifier = modifier.kobbloReadableWidth().nestedScroll(largeTitle.connection)) {
            FileScreenHeader(stringResource(R.string.nav_browse), state, onAction, largeTitle) {
                KobbloIconButton(
                    icon = AppIcons.MoreHoriz,
                    description = stringResource(R.string.manage_locations),
                    onClick = { showManage = true },
                    onLongClick = onOpenVault
                )
            }

            when (search.pane) {
                SearchPane.Results -> {
                    SearchResults(state, onAction, onFileClick, onFileMenuClick, bottomPadding)
                    return@Column
                }
                SearchPane.RecentSearches -> {
                    RecentSearchesPane(search, onAction, bottomPadding)
                    return@Column
                }
                SearchPane.None -> Unit
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding)
            ) {
                item(key = "locations") {
                    KobbloGroupHeader(
                        title = stringResource(R.string.locations_title),
                        collapsed = !locationsOpen,
                        onToggle = { locationsOpen = !locationsOpen },
                        modifier = Modifier.padding(top = 0.dp)
                    )
                    AnimatedVisibility(visible = locationsOpen) {
                        LocationsGroup(state, onAction, onAddLocation)
                    }
                }

                item(key = "favorites") {
                    KobbloGroupHeader(
                        title = stringResource(R.string.favorites_title),
                        collapsed = !favoritesOpen,
                        onToggle = { favoritesOpen = !favoritesOpen }
                    )
                    AnimatedVisibility(visible = favoritesOpen) {
                        FavoritesGroup(state, onAction)
                    }
                }

                item(key = "categories") {
                    KobbloGroupHeader(
                        title = stringResource(R.string.categories_title),
                        collapsed = !categoriesOpen,
                        onToggle = { categoriesOpen = !categoriesOpen }
                    )
                    AnimatedVisibility(visible = categoriesOpen) {
                        CategoriesGroup(state, onAction)
                    }
                }

                item(key = "tools") {
                    KobbloGroupHeader(
                        title = stringResource(R.string.tools_title),
                        collapsed = !toolsOpen,
                        onToggle = { toolsOpen = !toolsOpen }
                    )
                    AnimatedVisibility(visible = toolsOpen) {
                        ToolsGroup(state, onAction)
                    }
                }
            }
        }
    }

    if (showManage) {
        ManageLocationsSheet(state, onAction, onAddLocation, onDismiss = { showManage = false })
    }
}

@Composable
private fun LocationsGroup(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onAddLocation: () -> Unit
) {
    val colors = KobbloDesign.colors
    // Listed so a card or drive appears and disappears here the moment it does on the device.
    val volumes by com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes.volumes.collectAsState()
    val context = LocalContext.current
    val storages = remember(state, volumes, context) { connectedStorages(context, state) }

    KobbloGroup {
        storages.forEach { storage ->
            // Storage on the phone says how full it is, in words and as a meter. Anywhere else says whose it is.
            val space = storage.space
            KobbloRow(
                title = storage.name,
                subtitle = if (space == null) storage.summary else stringResource(R.string.gb_free_of_total, space.freeText, space.totalText),
                leading = {
                    Icon(storage.icon, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
                },
                trailing = { KobbloChevron() },
                below = if (space == null) null else {
                    { StorageMeter(fraction = space.usedFraction, height = 4.dp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
                },
                showDivider = true,
                onClick = { onAction(AppAction.SwitchToStorage(storage.switchId, storage.location)) }
            )
        }
        KobbloRow(
            title = stringResource(R.string.recently_deleted),
            leading = {
                Icon(AppIcons.Trash, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
            },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { onAction(AppAction.SetTrashScreenVisible(true)) }
        )
        KobbloRow(
            title = stringResource(R.string.add_location),
            titleColor = colors.action,
            leading = {
                Icon(AppIcons.Add, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
            },
            onClick = onAddLocation
        )
    }
}

@Composable
private fun FavoritesGroup(state: AppState, onAction: (AppAction) -> Unit) {
    val colors = KobbloDesign.colors
    val downloadsCount = state.categoryTotals?.downloadsCount

    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.cat_downloads),
            leading = {
                Icon(AppIcons.Download, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
            },
            trailing = {
                if (downloadsCount != null) {
                    TrailingValue(pluralStringResource(R.plurals.items_count, downloadsCount, downloadsCount))
                }
                KobbloChevron()
            },
            showDivider = state.favoritePaths.isNotEmpty(),
            onClick = { onAction(AppAction.SetLocation("category/download")) }
        )
        state.favoritePaths.forEachIndexed { index, path ->
            val file = remember(path) { File(path) }
            KobbloRow(
                title = file.name,
                leading = {
                    Icon(
                        imageVector = if (file.isDirectory) AppIcons.Folder else AppIcons.File,
                        contentDescription = null,
                        tint = colors.action,
                        modifier = Modifier.size(26.dp)
                    )
                },
                trailing = { KobbloChevron() },
                showDivider = index < state.favoritePaths.lastIndex,
                onClick = {
                    // A pinned file opens where it lives, so its neighbours stay one tap away.
                    val folder = if (file.isDirectory) file else file.parentFile
                    folder?.let { onAction(AppAction.SetLocation(it.absolutePath)) }
                }
            )
        }
    }
}

@Composable
private fun CategoriesGroup(state: AppState, onAction: (AppAction) -> Unit) {
    val totals = state.categoryTotals

    fun sizeFor(filterKey: String): String {
        if (totals == null) return ""
        val bytes = when (filterKey) {
            "image" -> totals.imageBytes
            "video" -> totals.videoBytes
            "audio" -> totals.audioBytes
            "doc" -> totals.docBytes
            "apk" -> totals.appBytes
            "archive" -> totals.archiveBytes
            "large" -> totals.largeBytes
            else -> 0L
        }
        return formatSize(bytes)
    }

    KobbloGroup {
        CategoryEntries.forEachIndexed { index, category ->
            KobbloRow(
                title = stringResource(category.titleRes),
                leading = { KobbloIconBadge(category.icon, category.tint) },
                trailing = {
                    TrailingValue(sizeFor(category.filterKey))
                    KobbloChevron()
                },
                showDivider = index < CategoryEntries.lastIndex,
                onClick = { onAction(AppAction.SetLocation("category/${category.filterKey}")) }
            )
        }
    }
}

@Composable
private fun ToolsGroup(
    state: AppState,
    onAction: (AppAction) -> Unit
) {
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.storage_cleaner_nav),
            leading = { KobbloIconBadge(AppIcons.Broom, KobbloTint.Teal) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { onAction(AppAction.SetCleanerScreenVisible(true)) }
        )
        KobbloRow(
            title = stringResource(R.string.gallery_similar_title),
            leading = { KobbloIconBadge(AppIcons.Similar, KobbloTint.Indigo) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { com.kobblo.gallery.file.explorer.ui.hub.Hub.open(com.kobblo.gallery.file.explorer.gallery.GalleryRoute.SimilarPhotos) }
        )
        KobbloRow(
            title = stringResource(R.string.tools_storage_analyzer),
            leading = { KobbloIconBadge(AppIcons.PieChart, KobbloTint.Orange) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { com.kobblo.gallery.file.explorer.ui.hub.Hub.open(com.kobblo.gallery.file.explorer.tools.ToolsRoute.Analyzer()) }
        )
        KobbloRow(
            title = stringResource(R.string.tools_apps_title),
            leading = { KobbloIconBadge(AppIcons.Apps, KobbloTint.Green) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { com.kobblo.gallery.file.explorer.ui.hub.Hub.open(com.kobblo.gallery.file.explorer.tools.ToolsRoute.Apps) }
        )
        KobbloRow(
            title = stringResource(R.string.organize_downloads),
            leading = { KobbloIconBadge(AppIcons.Sparkles, KobbloTint.Purple) },
            trailing = { KobbloChevron() },
            showDivider = state.isVaultAlwaysShown,
            onClick = { onAction(AppAction.OrganiseDownloads) }
        )
        // Settings and About are a tab of their own. The Vault lives here; with it hidden, holding the
        // top bar's button opens it instead.
        if (state.isVaultAlwaysShown) {
            KobbloRow(
                title = stringResource(R.string.vault_title),
                leading = { KobbloIconBadge(AppIcons.LockFill, KobbloTint.Indigo) },
                trailing = { KobbloChevron() },
                onClick = { onAction(AppAction.SetShowVaultScreen(true)) }
            )
        }
    }
}

@Composable
private fun TrailingValue(text: String) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = KobbloType.callout,
        color = KobbloDesign.colors.smoke,
        maxLines = 1,
        modifier = Modifier.padding(end = 6.dp)
    )
}
