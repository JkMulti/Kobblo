package com.kobblo.gallery.file.explorer.gallery.search

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GalleryFeed
import com.kobblo.gallery.file.explorer.gallery.GalleryGrid
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.gallery.GalleryScope
import com.kobblo.gallery.file.explorer.gallery.GalleryStore
import com.kobblo.gallery.file.explorer.gallery.MediaIndex
import com.kobblo.gallery.file.explorer.gallery.SectionLabels
import com.kobblo.gallery.file.explorer.gallery.buildGalleryFeed
import com.kobblo.gallery.file.explorer.gallery.galleryCountLabel
import com.kobblo.gallery.file.explorer.gallery.places.PlaceTable
import com.kobblo.gallery.file.explorer.gallery.places.countryName
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Long enough for a word to be finished, short enough that nobody waits. */
private const val TypingPauseMillis = 120L

/**
 * Finding a photo. One field, and it reads everything: names, folders, dates, cameras, places, tags and kinds. Until
 * something's typed it offers what's really in this library, each as a word that could have been typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GallerySearchScreen(
    isCurrent: Boolean,
    state: AppState,
    columns: Int,
    labels: SectionLabels,
    onColumnsChange: (Int) -> Unit,
    onAction: (AppAction) -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileMenuClick: (FileItem) -> Unit,
    bottomPadding: Dp
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val store = remember { GalleryStore.get(context) }
    val index = remember { MediaIndex.get(context) }
    val facts by index.facts.collectAsState()
    val flags by index.flags.collectAsState()
    val details by index.details.collectAsState()
    val progress by index.progress.collectAsState()
    val recent by store.recentSearches.collectAsState()
    val largeTitle = rememberLargeTitleState(height = 64.dp)
    val gridState = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    val root = remember { Environment.getExternalStorageDirectory().path }
    // What the app's language calls photos, videos and dates, beside the English words that always work.
    val searchWords = remember(context) { SearchWords.from(context) }

    val kindNames = mapOf(
        MediaWord.FAVORITE to stringResource(R.string.gallery_search_word_favorites),
        MediaWord.VIDEO to stringResource(R.string.gallery_search_word_videos),
        MediaWord.SCREENSHOT to stringResource(R.string.gallery_search_word_screenshots),
        MediaWord.GIF to stringResource(R.string.gallery_search_word_gifs),
        MediaWord.RAW to stringResource(R.string.gallery_search_word_raw)
    )

    fun library(files: List<FileItem>) = SearchLibrary(
        files = files,
        facts = facts,
        flags = flags,
        details = details,
        root = root,
        placeNames = { place ->
            val table = PlaceTable.get(context)
            listOf(table.name(place), countryName(table.countryCode(place)))
        }
    )

    // A new keystroke or a new batch of details starts this again, and what's on screen stays until the new answer lands.
    // Exact matches come first. Smart Search adds what's in the pictures after them, best match first.
    val smartTitle = stringResource(R.string.smart_search_section)
    val results by produceState<GalleryFeed?>(null, query, state.galleryFiles, facts, flags, details) {
        val typed = query
        if (typed.isBlank()) {
            value = null
            return@produceState
        }
        delay(TypingPauseMillis)
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) {
            val job = coroutineContext
            val found = searchMedia(MediaQuery.parse(typed, local = searchWords), library(files)) { job.ensureActive() }
            val exact = buildGalleryFeed(found, labels)
            val smart = com.kobblo.gallery.file.explorer.smart.smartSearchIds(context, typed)
            job.ensureActive()
            com.kobblo.gallery.file.explorer.smart.withSmartMatches(exact, smart, files, smartTitle)
        }
    }

    val hints by produceState<SearchHints?>(null, state.galleryFiles, details) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) {
            val table = if (details.isEmpty()) null else PlaceTable.get(context)
            buildSearchHints(library(files), kindNames, firstPlaceName = { table?.name(it) })
        }
    }

    // Selecting and swiping stay within what was found.
    val feed = results
    LaunchedEffect(feed, isCurrent) {
        if (isCurrent && feed != null) GalleryScope.setVisible(feed.files)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Going through the results is what the keyboard was covering.
    val dropKeyboard = remember(focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    fun search(text: String) {
        query = text
        store.rememberSearch(text)
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection).nestedScroll(dropKeyboard)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.gallery_search),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = stringResource(R.string.nav_home),
            showSearch = true,
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(R.string.gallery_search_hint),
            onSearchSubmit = {
                store.rememberSearch(query)
                focusManager.clearFocus()
            },
            searchFocusRequester = focus,
            largeTitle = largeTitle
        )

        val still = progress
        when {
            query.isBlank() -> SearchSuggestions(
                hints = hints,
                recent = recent,
                remaining = still?.let { it.total - it.done },
                onPick = ::search,
                onClearRecent = { store.clearRecentSearches() },
                bottomPadding = bottomPadding
            )

            feed == null -> Unit

            feed.files.isEmpty() -> KobbloMessage(
                icon = AppIcons.Search,
                title = stringResource(R.string.gallery_search_none),
                body = if (still != null) {
                    stringResource(R.string.gallery_search_still_reading, "%,d".format(still.total - still.done))
                } else {
                    stringResource(R.string.gallery_search_none_body)
                }
            )

            else -> GalleryGrid(
                feed = feed,
                gridState = gridState,
                columns = columns,
                onColumnsChange = onColumnsChange,
                selected = state.selectedFiles,
                selecting = state.isSelectionMode,
                onFileClick = { file ->
                    store.rememberSearch(query)
                    focusManager.clearFocus()
                    onFileClick(file)
                },
                onFileMenuClick = onFileMenuClick,
                onSelectionChange = { onAction(AppAction.SelectAll(it)) },
                bottomPadding = bottomPadding,
                footer = galleryCountLabel(feed.photoCount, feed.videoCount) +
                    if (still != null) "\n" + stringResource(R.string.gallery_search_still_reading, "%,d".format(still.total - still.done)) else ""
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchSuggestions(
    hints: SearchHints?,
    recent: List<String>,
    remaining: Int?,
    onPick: (String) -> Unit,
    onClearRecent: () -> Unit,
    bottomPadding: Dp
) {
    val colors = KobbloDesign.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
    ) {
        if (recent.isNotEmpty()) {
            item(key = "recent_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = KobbloSpace.screen),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KobbloGroupHeader(
                        title = stringResource(R.string.explorer_recent_searches),
                        modifier = Modifier.weight(1f).padding(top = 0.dp)
                    )
                    Text(
                        text = stringResource(R.string.explorer_clear),
                        style = KobbloType.callout,
                        color = colors.action,
                        modifier = Modifier
                            .clip(CapsuleShape)
                            .clickable(onClick = onClearRecent)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            item(key = "recent") {
                KobbloGroup {
                    recent.forEachIndexed { index, text ->
                        KobbloRow(
                            title = text,
                            leading = { Icon(AppIcons.Clock, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(22.dp)) },
                            showDivider = index < recent.lastIndex,
                            onClick = { onPick(text) }
                        )
                    }
                }
            }
        }
        if (hints != null) {
            suggestionRow(R.string.gallery_search_kinds, hints.kinds, onPick)
            suggestionRow(R.string.gallery_search_years, hints.years, onPick)
            suggestionRow(R.string.gallery_places, hints.places, onPick)
            suggestionRow(R.string.gallery_search_cameras, hints.cameras, onPick)
            suggestionRow(R.string.gallery_tags, hints.tags, onPick)
        }
        if (remaining != null) {
            item(key = "reading") {
                Text(
                    text = stringResource(R.string.gallery_search_still_reading, "%,d".format(remaining)),
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = 16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.suggestionRow(title: Int, words: List<String>, onPick: (String) -> Unit) {
    if (words.isEmpty()) return
    item(key = "words_$title") {
        Column {
            KobbloGroupHeader(title = stringResource(title))
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                words.forEach { word -> WordChip(word) { onPick(word) } }
            }
        }
    }
}

@Composable
private fun WordChip(word: String, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(34.dp)
            .pressScale(pressedScale = 0.95f, interactionSource = source)
            .clip(CapsuleShape)
            .background(colors.snow)
            .quietClickable(source = source, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = word,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = colors.ink,
            maxLines = 1
        )
    }
}
