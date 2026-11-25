package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.explorer.ExplorerUi
import com.kobblo.gallery.file.explorer.explorer.FolderViewMemory
import com.kobblo.gallery.file.explorer.explorer.sortDirectionLabel
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.hub.Hub

/** The filter that shows everything, which is the same as having none. */
internal const val NoFilter = "all"

/** The kinds a folder's list can be narrowed to. A filter is saved as a kind's [key]. */
internal enum class KindFilter(val key: String, private val otherKey: String?, @StringRes val label: Int) {
    Images("image", "images", R.string.cat_images),
    Videos("video", "videos", R.string.cat_videos),
    Audio("audio", null, R.string.cat_audio),
    Documents("doc", "docs", R.string.cat_docs),
    Apps("apk", "apps", R.string.cat_apps),
    Archives("archive", "archives", R.string.cat_archives);

    companion object {
        /** The kind a saved filter means. Either spelling of a kind is understood. */
        fun of(filter: String): KindFilter? = entries.firstOrNull { it.key == filter || it.otherKey == filter }
    }
}

/** A filter in words: its kind's name, or the filter itself with a capital for one that isn't a known kind. */
@Composable
internal fun filterLabel(filter: String): String =
    KindFilter.of(filter)?.let { stringResource(it.label) } ?: filter.replaceFirstChar { it.uppercase() }

/** The four orders a folder can be listed in, as the sheet lists them. */
private val SortChoices = listOf(
    SortMode.ALPHABETICAL to R.string.sort_name,
    SortMode.DATE to R.string.sort_date_modified,
    SortMode.SIZE to R.string.sort_size_label,
    SortMode.KIND to R.string.tools_sort_kind
)

/**
 * Whether picking a sort closes the sheet. On the phone, picking the sort that is already on flips its
 * direction, and the sheet stays to show which way it now runs. Any other pick is done once made.
 */
internal fun closesAfterSort(picked: SortMode, current: SortMode, location: String): Boolean =
    picked != current || !location.startsWith("/")

/** The "..." menu of a folder: how it is shown, and what to do with everything in it. */
@Composable
internal fun FolderOptionsSheet(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit,
    onOpenFilter: () -> Unit
) {
    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(
            title = stringResource(R.string.view_options),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            KobbloSegmentedControl(
                options = listOf(stringResource(R.string.view_icons), stringResource(R.string.view_list)),
                selectedIndex = if (state.isListMode) 1 else 0,
                onSelect = { picked ->
                    val wantsList = picked == 1
                    if (wantsList != state.isListMode) onAction(AppAction.ToggleViewMode)
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(KobbloSpace.groupGap))
            SortGroup(state, onAction, onDismiss)
            Spacer(Modifier.height(KobbloSpace.groupGap))
            CommandGroup(state, onAction, onDismiss, onOpenFilter)
        }
    }
}

@Composable
private fun SortGroup(state: AppState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    val colors = KobbloDesign.colors
    val reversed = remember(FolderViewMemory.revision, state.location) { FolderViewMemory.isReversed(state.location) }
    KobbloGroup {
        SortChoices.forEachIndexed { position, (mode, label) ->
            KobbloRow(
                title = stringResource(label),
                trailing = {
                    if (mode == state.sortMode) {
                        // Which way it runs, in that sort's own words, where a folder keeps a direction at all.
                        sortDirectionLabel(mode, reversed, state.location)?.let { direction ->
                            Text(direction, style = KobbloType.callout, color = colors.smoke)
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(AppIcons.Check, contentDescription = stringResource(R.string.screen_current_sort), tint = colors.action, modifier = Modifier.size(20.dp))
                    }
                },
                showDivider = position < SortChoices.lastIndex,
                onClick = {
                    onAction(AppAction.SetSortMode(mode))
                    if (closesAfterSort(mode, state.sortMode, state.location)) onDismiss()
                }
            )
        }
    }
}

/**
 * What can be done with the folder as a whole. The last four need a real path, so they are only
 * there for a folder on the phone.
 */
@Composable
private fun CommandGroup(state: AppState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit, onOpenFilter: () -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val folder = state.location
    val hasPath = folder.startsWith("/")
    KobbloGroup {
        CommandRow(stringResource(R.string.select_all), AppIcons.SelectAll) {
            onAction(AppAction.SelectAll())
            onDismiss()
        }
        KobbloRow(
            title = stringResource(R.string.filter_label),
            trailing = {
                if (state.filter != NoFilter) Text(filterLabel(state.filter), style = KobbloType.callout, color = colors.smoke)
                Spacer(Modifier.width(6.dp))
                CommandGlyph(AppIcons.Filter)
            },
            showDivider = true,
            onClick = onOpenFilter
        )
        KobbloRow(
            title = stringResource(R.string.settings_hidden_files),
            trailing = {
                ExpressiveSwitch(checked = state.showHiddenFiles, onCheckedChange = { shown -> onAction(AppAction.SetShowHiddenFiles(shown)) })
            },
            showDivider = true,
            onClick = { onAction(AppAction.SetShowHiddenFiles(!state.showHiddenFiles)) }
        )
        CommandRow(stringResource(R.string.recently_deleted), AppIcons.Trash, showDivider = hasPath) {
            onDismiss()
            onAction(AppAction.SetTrashScreenVisible(true))
        }
        if (hasPath) {
            CommandRow(stringResource(R.string.tools_search_in_files), AppIcons.Search) {
                onDismiss()
                Hub.open(ToolsRoute.ContentSearch(folder))
            }
            CommandRow(stringResource(R.string.tools_inspect), AppIcons.Info) {
                onDismiss()
                Hub.open(ToolsRoute.Inspect(folder))
            }
            CommandRow(stringResource(R.string.explorer_copy_path), AppIcons.Copy) {
                ExplorerUi.copyPath(context, folder)
                onDismiss()
            }
            CommandRow(stringResource(R.string.explorer_go_to_path), AppIcons.FolderOpen, showDivider = false) {
                onDismiss()
                ExplorerUi.goToPath(folder)
            }
        }
    }
}

@Composable
private fun CommandGlyph(glyph: ImageVector) {
    Icon(glyph, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp))
}

/** A command that reads like a context menu's: its name, then its glyph at the far edge. */
@Composable
private fun CommandRow(title: String, glyph: ImageVector, showDivider: Boolean = true, onClick: () -> Unit) {
    KobbloRow(title = title, trailing = { CommandGlyph(glyph) }, showDivider = showDivider, onClick = onClick)
}

/** Picks what kinds of file a folder shows. The kind in use is ticked, and "All Files" clears it. */
@Composable
internal fun FilterSheet(currentFilter: String, onDismiss: () -> Unit, onSelectFilter: (String) -> Unit) {
    val choices = listOf(NoFilter to stringResource(R.string.all_files)) +
        KindFilter.entries.map { it.key to stringResource(it.label) }
    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(title = stringResource(R.string.filter_label)) {
            KobbloGroup {
                choices.forEachIndexed { position, (key, label) ->
                    KobbloRow(
                        title = label,
                        trailing = {
                            if (key == currentFilter) {
                                Icon(AppIcons.Check, contentDescription = stringResource(R.string.tools_selected), tint = KobbloDesign.colors.action, modifier = Modifier.size(20.dp))
                            }
                        },
                        showDivider = position < choices.lastIndex,
                        onClick = { onSelectFilter(key) }
                    )
                }
            }
        }
    }
}
