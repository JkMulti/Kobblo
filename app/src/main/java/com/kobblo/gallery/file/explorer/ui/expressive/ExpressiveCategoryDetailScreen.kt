package com.kobblo.gallery.file.explorer.ui.expressive

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.DisplayDates
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers
import com.kobblo.gallery.file.explorer.explorer.drag.rememberCategoryDrag
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.formatSize
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The categories Browse can list, each with the keys it is asked for by, its title, and what its
 * screen says when it has nothing in it.
 */
internal enum class FileCategory(@StringRes val title: Int, @StringRes val emptyMessage: Int, vararg keys: String) {
    Images(R.string.cat_images, R.string.screen_empty_images, "image", "images"),
    Videos(R.string.cat_videos, R.string.screen_empty_videos, "video", "videos"),
    Audio(R.string.cat_audio, R.string.screen_empty_audio, "audio"),
    Documents(R.string.cat_docs, R.string.screen_empty_documents, "doc", "docs", "documents"),
    Apps(R.string.cat_apps, R.string.screen_empty_apps, "apk", "app", "apps"),
    Downloads(R.string.cat_downloads, R.string.screen_empty_downloads, "download", "downloads"),
    Archives(R.string.cat_archives, R.string.screen_empty_archives, "archive", "archives"),
    LargeFiles(R.string.cat_large, R.string.screen_empty_large, "large", "largefiles", "other files", "other"),
    Duplicates(R.string.screen_category_duplicates, R.string.screen_empty_duplicates, "duplicates", "duplicate"),
    EmptyFolders(R.string.screen_category_empty_folders, R.string.screen_empty_empty_folders, "empty folders", "empty_folders");

    private val keys: Set<String> = keys.toSet()

    companion object {
        /** The category a key names, in whatever letter case it arrives. Null for one that isn't known. */
        fun of(key: String): FileCategory? {
            val wanted = key.lowercase(Locale.getDefault())
            return entries.firstOrNull { wanted in it.keys }
        }
    }
}

/** A key shown as it is, with a capital, for a category that has no title of its own. */
internal fun titleFromKey(key: String): String =
    key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/** Up to this many files are sorted as the screen opens. More are sorted off the main thread. */
private const val QuickListingSize = 2_000

/** A category's files as the screen draws them: newest first and grouped by day. */
private class CategoryListing(
    val sorted: List<FileItem>,
    val groups: Map<String, List<FileItem>>,
    val subtitle: String
) {
    companion object {
        fun of(files: List<FileItem>, context: Context): CategoryListing {
            val sorted = files.sortedByDescending { it.lastModified }
            return CategoryListing(sorted, groupByDay(sorted, System.currentTimeMillis(), context), subtitleFor(sorted, context))
        }

        private fun subtitleFor(files: List<FileItem>, context: Context): String {
            val resources = context.resources
            if (files.isEmpty()) return resources.getQuantityString(R.plurals.transfer_file_count, 0, 0)
            // Downloads lists its folders too, which aren't files and have no size here.
            val wording = if (files.any { it.type == "folder" }) R.plurals.screen_items_with_size else R.plurals.transfer_incoming_summary
            return resources.getQuantityString(wording, files.size, files.size, formatSize(files.sumOf { it.sizeBytes }))
        }
    }
}

/**
 * The listing for [files], or null while a large one is still being worked out. A few thousand files
 * are quick to sort right here, so the list arrives with the screen. A large category is sorted on a
 * background thread, because sorting 100,000 rows would hold up a frame.
 */
@Composable
private fun categoryListing(files: List<FileItem>): CategoryListing? {
    val context = LocalContext.current
    val quick = files.size <= QuickListingSize
    val listing by produceState(initialValue = if (quick) CategoryListing.of(files, context) else null, files) {
        value = if (quick) CategoryListing.of(files, context) else withContext(Dispatchers.Default) { CategoryListing.of(files, context) }
    }
    return listing
}

/**
 * The list's opacity, which drops to nothing and comes back each time the view changes. Each row is its
 * own item in the list, so switching views fades the whole list rather than each day's part of it.
 */
@Composable
private fun rememberViewFade(isListMode: Boolean): Animatable<Float, AnimationVector1D> {
    val still = ExpressiveMotion.isReducedMotion()
    val fade = remember { Animatable(1f) }
    val fadedIn = remember { booleanArrayOf(isListMode) }
    LaunchedEffect(isListMode) {
        if (fadedIn[0] == isListMode) return@LaunchedEffect
        fadedIn[0] = isListMode
        if (!still) {
            fade.snapTo(0f)
            fade.animateTo(1f, ExpressiveMotion.FastTween)
        }
    }
    return fade
}

/** How many lines of [columns] cards it takes to show [count] files. */
internal fun cardRowCount(count: Int, columns: Int): Int = (count + columns - 1) / columns

/** The files on line [row] of a group drawn [columns] to a line. The last line may be short. */
internal fun cardRow(files: List<FileItem>, row: Int, columns: Int): List<FileItem> =
    files.subList(row * columns, minOf((row + 1) * columns, files.size))

/** What a line of cards is keyed by in the list: its first file. Dragging finds the line's files by it. */
internal fun cardRowKey(firstFile: FileItem): String = "cards_${firstFile.id}"

/** Every line of cards in every group, by its key, for a drag to look up what is under the finger. */
internal fun cardRowsByKey(groups: Collection<List<FileItem>>, columns: Int): Map<Any, List<FileItem>> = buildMap {
    for (files in groups) {
        for (row in 0 until cardRowCount(files.size, columns)) {
            val cards = cardRow(files, row, columns)
            put(cardRowKey(cards.first()), cards)
        }
    }
}

/**
 * One category from Browse: every file of a kind wherever it is kept, newest first under the day it
 * changed, as rows or as lines of cards.
 */
@Composable
fun ExpressiveCategoryDetailScreen(
    categoryKey: String,
    state: AppState,
    onAction: (AppAction) -> Unit,
    onBack: () -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val category = FileCategory.of(categoryKey)
    val listing = categoryListing(state.files)
    val files = listing?.sorted.orEmpty()
    val groups = listing?.groups.orEmpty()
    val blocked = files.isEmpty() && !state.hasStoragePermission
    val waiting = listing == null || (files.isEmpty() && state.isLoading)
    val allSelected = remember(files, state.selectedFiles) { files.isNotEmpty() && files.all { it.id in state.selectedFiles } }

    val listState = rememberLazyListState()
    val drag = rememberCategoryDrag(state, listState)
    val columns = state.gridColumns.coerceIn(2, 4)
    // Only ever worked out once a drag asks, since nothing else needs it.
    val cardRows = remember(groups, columns) { lazy { cardRowsByKey(groups.values, columns) } }
    drag.columns = columns
    drag.cardRows = { cardRows.value }
    val fade = rememberViewFade(state.isListMode)
    val taps = rememberFileTaps(onAction, onFileClick, onFileMenuClick)

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (category != null) stringResource(category.title) else titleFromKey(categoryKey),
            // Saying "0 files" here would be a claim about files the app hasn't looked at yet.
            subtitle = if (files.isEmpty() && (state.isLoading || blocked)) "" else listing?.subtitle.orEmpty(),
            onBack = onBack,
            backLabel = stringResource(R.string.nav_browse),
            actions = {
                if (files.isNotEmpty()) {
                    KobbloIconButton(
                        icon = if (allSelected) AppIcons.Deselect else AppIcons.SelectAll,
                        description = stringResource(if (allSelected) R.string.select_none else R.string.select_all),
                        onClick = { onAction(if (allSelected) AppAction.SelectNone else AppAction.SelectAll(files.map { it.id })) }
                    )
                }
                ViewModeButton(state.isListMode, onAction)
            }
        )
        Spacer(Modifier.height(8.dp))

        when {
            waiting -> Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
                KobbloLoader()
            }

            blocked -> KobbloMessage(
                icon = AppIcons.FolderShared,
                title = stringResource(R.string.permission_dialog_title),
                body = stringResource(R.string.permission_dialog_message),
                action = {
                    androidx.compose.material3.Button(onClick = { onAction(AppAction.RequestStorageAccess) }) {
                        Text(stringResource(R.string.permission_grant_access), fontWeight = FontWeight.SemiBold)
                    }
                }
            )

            files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                KobbloMessage(
                    icon = AppIcons.FolderOpen,
                    title = stringResource(category?.emptyMessage ?: R.string.no_files_found)
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = with(FileDragModifiers) {
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                        .list(drag.list, drag::place)
                },
                contentPadding = PaddingValues(top = 4.dp, bottom = listClearance(state, aboveTabBar = 120.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for ((day, dayFiles) in groups) {
                    item(key = "header_$day", contentType = "header") { KobbloGroupHeader(title = day) }
                    if (state.isListMode) {
                        // An id can't repeat in one list, and a path can for a moment during a move.
                        itemsIndexed(dayFiles, key = { _, file -> file.id }, contentType = { _, _ -> "row" }) { index, file ->
                            FileRow(
                                file = file,
                                isSelected = file.id in state.selectedFiles,
                                taps = taps,
                                groupIndex = index,
                                groupCount = dayFiles.size
                            )
                        }
                    } else {
                        val lines = cardRowCount(dayFiles.size, columns)
                        items(lines, key = { line -> cardRowKey(dayFiles[line * columns]) }, contentType = { "cards" }) { line ->
                            CardLine(
                                files = cardRow(dayFiles, line, columns),
                                columns = columns,
                                isLastLine = line == lines - 1,
                                state = state,
                                taps = taps
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line of a day's cards. A short last line is padded out, so its cards stay the width of the others. */
@Composable
private fun CardLine(files: List<FileItem>, columns: Int, isLastLine: Boolean, state: AppState, taps: FileTaps) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .padding(bottom = if (isLastLine) 0.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (file in files) {
            FileCard(
                file = file,
                isSelected = file.id in state.selectedFiles,
                taps = taps,
                columns = columns,
                modifier = Modifier.weight(1f)
            )
        }
        repeat(columns - files.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Groups files by the day they changed, keeping their order. Each day's label is made once, because
 * a calendar and a date format per file took seconds on the main thread at 100,000 rows.
 */
internal fun groupByDay(files: List<FileItem>, now: Long, context: Context): Map<String, List<FileItem>> =
    groupByDay(
        files, now,
        todayLabel = context.getString(R.string.recents_today),
        yesterdayLabel = context.getString(R.string.recents_yesterday),
        earlierLabel = context.getString(R.string.recents_earlier)
    )

/** The same, with the labels given: what the tests call. */
internal fun groupByDay(
    files: List<FileItem>,
    now: Long,
    todayLabel: String,
    yesterdayLabel: String,
    earlierLabel: String
): Map<String, List<FileItem>> {
    val zone = TimeZone.getDefault()
    val format = DisplayDates.date()
    val dayMillis = 86_400_000L
    fun dayOf(millis: Long) = Math.floorDiv(millis + zone.getOffset(millis), dayMillis)
    val today = dayOf(now)
    val labels = HashMap<Long, String>()
    val groups = linkedMapOf<String, MutableList<FileItem>>()
    for (file in files) {
        val moment = file.lastModified
        val label = if (moment <= 0L) {
            earlierLabel
        } else {
            labels.getOrPut(dayOf(moment)) {
                when (dayOf(moment)) {
                    today -> todayLabel
                    today - 1 -> yesterdayLabel
                    else -> format.format(Date(moment))
                }
            }
        }
        groups.getOrPut(label) { ArrayList() }.add(file)
    }
    return groups
}
