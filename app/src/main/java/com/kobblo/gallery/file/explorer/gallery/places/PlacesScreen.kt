package com.kobblo.gallery.file.explorer.gallery.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.AlbumTile
import com.kobblo.gallery.file.explorer.gallery.FolderAlbum
import com.kobblo.gallery.file.explorer.gallery.GalleryDest
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.gallery.MediaIndex
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoading
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TileMinWidth = 104.dp

/**
 * Where photos were taken, grouped by country and town. It's all worked out on this phone from the photo's own
 * coordinates, against a table that ships with the app. No coordinate leaves the device, and there's no map, because
 * a map would need tiles from somebody's server.
 */
@Composable
internal fun PlacesScreen(state: AppState, bottomPadding: Dp) {
    val context = LocalContext.current
    val index = remember { MediaIndex.get(context) }
    val details by index.details.collectAsState()
    val progress by index.progress.collectAsState()
    val largeTitle = rememberLargeTitleState(height = 64.dp)
    val groups by produceState<List<CountryGroup>?>(null, state.galleryFiles, details) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) {
            val table = PlaceTable.get(context)
            buildPlaceGroups(files, details, table::name, table::countryCode)
        }
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.gallery_places),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = stringResource(R.string.gallery_albums),
            largeTitle = largeTitle
        )
        val current = groups
        when {
            current == null -> KobbloLoading()
            current.isEmpty() -> KobbloMessage(
                icon = AppIcons.Location,
                title = stringResource(R.string.gallery_places_empty),
                body = stringResource(R.string.gallery_places_empty_body)
            )
            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val perRow = ((maxWidth - KobbloSpace.screen * 2) / TileMinWidth).toInt().coerceAtLeast(2)
                val coverPx = with(LocalDensity.current) { ((maxWidth - KobbloSpace.screen * 2) / perRow).roundToPx() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding)
                ) {
                    for (country in current) {
                        item(key = "country_${country.code}") {
                            CountryHeader(country.name, country.count) {
                                GalleryNav.open(GalleryDest.Country(country.code, country.name))
                            }
                        }
                        groupTiles(country.code, country.places, perRow, coverPx) { group ->
                            GalleryNav.open(GalleryDest.Place(group.place, group.name))
                        }
                    }
                    item(key = "credit") {
                        KobbloGroupFooter(
                            text = if (progress != null) {
                                stringResource(R.string.gallery_search_still_reading, "%,d".format(progress!!.total - progress!!.done)) + "\n" +
                                    stringResource(R.string.gallery_places_credit)
                            } else {
                                stringResource(R.string.gallery_places_credit)
                            },
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Tags already in photos, written by a camera or another app. This doesn't write any. */
@Composable
internal fun TagsScreen(state: AppState, bottomPadding: Dp) {
    val context = LocalContext.current
    val index = remember { MediaIndex.get(context) }
    val details by index.details.collectAsState()
    val largeTitle = rememberLargeTitleState(height = 64.dp)
    val groups by produceState<List<MediaGroup>?>(null, state.galleryFiles, details) {
        val files = state.galleryFiles
        value = withContext(Dispatchers.Default) { buildTagGroups(files, details) }
    }

    Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.gallery_tags),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = stringResource(R.string.gallery_albums),
            largeTitle = largeTitle
        )
        val current = groups
        when {
            current == null -> KobbloLoading()
            current.isEmpty() -> KobbloMessage(icon = AppIcons.Bookmark, title = stringResource(R.string.gallery_tags_empty))
            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val perRow = ((maxWidth - KobbloSpace.screen * 2) / TileMinWidth).toInt().coerceAtLeast(2)
                val coverPx = with(LocalDensity.current) { ((maxWidth - KobbloSpace.screen * 2) / perRow).roundToPx() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding)
                ) {
                    groupTiles("tags", current, perRow, coverPx) { group -> GalleryNav.open(GalleryDest.Tag(group.name)) }
                }
            }
        }
    }
}

private fun LazyListScope.groupTiles(
    prefix: String,
    groups: List<MediaGroup>,
    perRow: Int,
    coverPx: Int,
    onClick: (MediaGroup) -> Unit
) {
    items(groups.chunked(perRow), key = { row -> "${prefix}_${row.first().key}" }) { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KobbloSpace.screen, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            row.forEach { group ->
                AlbumTile(
                    album = FolderAlbum(group.key, group.name, group.count, group.covers, 0L, false),
                    coverPx = coverPx,
                    onClick = { onClick(group) },
                    onLongClick = { onClick(group) },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A country's name, which opens everything from there. */
@Composable
private fun CountryHeader(name: String, count: Int, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen - 4.dp)
            .clip(CapsuleShape)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = KobbloType.title.copy(fontWeight = FontWeight.Bold),
            color = colors.ink,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text(text = "%,d".format(count), style = KobbloType.callout, color = colors.smoke)
        Spacer(Modifier.size(6.dp))
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = colors.pewter, modifier = Modifier.size(16.dp))
    }
}
