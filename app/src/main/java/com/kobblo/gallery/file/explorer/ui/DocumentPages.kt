package com.kobblo.gallery.file.explorer.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

/**
 * One document's pages drawn at one width. The few shown most recently are kept, so coming back to a
 * page doesn't draw it again; the rest are let go, since a full-size page is megabytes of bitmap.
 */
internal class DrawnPages(private val document: DocumentRenderer, private val width: Int, keep: Int) {
    private val kept = LruCache<Int, Bitmap>(keep)

    val count: Int get() = document.pageCount

    /** The page, if it's among those kept. */
    fun peek(page: Int): Bitmap? = kept.get(page)

    suspend fun draw(page: Int): Bitmap? = document.renderPage(page, width)?.also { kept.put(page, it) }
}

/** The drawing of [page]: there at once if it was kept, a moment later if it has to be drawn. */
@Composable
private fun rememberDrawnPage(pages: DrawnPages, page: Int): Bitmap? {
    val drawing by produceState(initialValue = pages.peek(page), page, pages) {
        if (value == null) pages.draw(page)?.let { value = it }
    }
    return drawing
}

// ── The rail of thumbnails ────────────────────────────────────────────────────

private val RailShape = UnevenRoundedRectangle(topEnd = 20.dp, bottomEnd = 20.dp)
private val ThumbnailFrame = RoundedRectangle(10.dp)

/** Narrow enough for the rail, and near enough a page's proportions that little of it is cropped. */
private val ThumbnailSize = DpSize(54.dp, 70.dp)

/**
 * Every page in small, down the leading edge of the screen. It shows where in the document the open
 * page is and which pages are bookmarked, and a tap on one goes there.
 */
@Composable
internal fun PageRail(
    thumbnails: DrawnPages,
    currentPage: Int,
    bookmarks: Set<Int>,
    listState: LazyListState,
    onPick: (Int) -> Unit
) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .shadow(4.dp, RailShape, ambientColor = Color.Transparent, spotColor = colors.ambient.copy(alpha = 0.15f))
            .clip(RailShape)
            .background(colors.canvas.copy(alpha = 0.6f))
            .border(1.dp, colors.hairline, RailShape)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.viewer_pdf_detail_pages),
                style = KobbloType.tab,
                color = colors.smoke,
                letterSpacing = 0.8.sp
            )
        }
        // A rule in the tint, between the heading and the pages.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(colors.action.copy(alpha = 0.25f)))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(thumbnails.count) { page ->
                PageThumbnail(
                    thumbnails = thumbnails,
                    page = page,
                    selected = page == currentPage,
                    bookmarked = page in bookmarks,
                    onClick = { onPick(page) }
                )
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    thumbnails: DrawnPages,
    page: Int,
    selected: Boolean,
    bookmarked: Boolean,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val drawing = rememberDrawnPage(thumbnails, page)
    val press = remember { MutableInteractionSource() }
    // The open page stands a little proud of the others, on a glow of the tint.
    val lift = if (selected) {
        Modifier.shadow(6.dp, ThumbnailFrame, ambientColor = Color.Transparent, spotColor = colors.action.copy(alpha = 0.4f))
    } else {
        Modifier
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ThumbnailSize)
                .then(lift)
                .pressScale(pressedScale = 0.94f, interactionSource = press)
                .clip(ThumbnailFrame)
                .background(colors.snow)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.action else colors.hairline, ThumbnailFrame)
                .clickable(interactionSource = press, indication = null) {
                    haptics.tap()
                    onClick()
                }
        ) {
            if (drawing == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    KobbloLoader(size = 14.dp, strokeWidth = 2.dp, color = colors.action)
                }
            } else {
                Image(
                    bitmap = drawing.asImageBitmap(),
                    contentDescription = stringResource(R.string.page_number, page),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(2.dp).clip(RoundedRectangle(8.dp))
                )
            }
            if (bookmarked) {
                // A turned-down corner.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .background(colors.action, UnevenRoundedRectangle(bottomStart = 8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(AppIcons.BookmarkFill, contentDescription = null, tint = colors.onAction, modifier = Modifier.size(8.dp))
                }
            }
        }
        Text(
            text = "${page + 1}",
            style = KobbloType.tab,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) colors.action else colors.smoke,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ── The page ──────────────────────────────────────────────────────────────────

/**
 * One page, filling what's left of the screen. A pinch enlarges it. While it's enlarged a drag moves it
 * about; at its natural size a drag is left alone, for the pager to turn the page with.
 */
@Composable
internal fun ZoomablePage(
    sheets: DrawnPages,
    page: Int,
    zoom: PageZoom,
    onZoom: (PageZoom) -> Unit
) {
    val colors = KobbloDesign.colors
    val drawing = rememberDrawnPage(sheets, page)
    // Gestures outlive recompositions, so they read the zoom as it is when a finger moves.
    val latest by rememberUpdatedState(zoom)
    val report by rememberUpdatedState(onZoom)
    val pinch = rememberTransformableState { factor, _, _ -> report(latest.pinched(factor)) }
    val gestures = if (zoom.scale > PageZoom.Min) {
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, factor, _ ->
                report(latest.transformed(factor, pan.x, pan.y, size.width.toFloat(), size.height.toFloat()))
            }
        }
    } else {
        Modifier.transformable(pinch, canPan = { false })
    }

    Box(
        modifier = Modifier.fillMaxSize().clipToBounds().then(gestures),
        contentAlignment = Alignment.Center
    ) {
        if (drawing == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KobbloLoader(strokeWidth = 3.dp, color = colors.action)
                Text(stringResource(R.string.view_loading_page), style = KobbloType.footnote, color = colors.smoke)
            }
        } else {
            // A soft mat behind the sheet, which moves and grows with it.
            Box(
                modifier = Modifier
                    .fillMaxSize(0.90f)
                    .graphicsLayer(scaleX = zoom.scale, scaleY = zoom.scale, translationX = zoom.x, translationY = zoom.y)
                    .clip(RoundedRectangle(16.dp))
                    .background(colors.canvas.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                val sheet = RoundedRectangle(12.dp)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .shadow(8.dp, sheet, ambientColor = Color.Transparent, spotColor = colors.ambient)
                        .clip(sheet)
                        .background(colors.snow)
                ) {
                    Image(
                        bitmap = drawing.asImageBitmap(),
                        contentDescription = stringResource(R.string.page_number, page),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ── Zoom and bookmark ─────────────────────────────────────────────────────────

/** The floating controls beside the page: larger, smaller, and a bookmark for the open page. */
@Composable
internal fun PageControls(
    bookmarked: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val shape = RoundedRectangle(25.dp)
    Column(
        modifier = modifier
            .shadow(12.dp, shape, ambientColor = Color.Transparent, spotColor = colors.ambient.copy(alpha = 0.35f))
            .clip(shape)
            .background(colors.snow)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PageControl(AppIcons.Add, R.string.zoom_in, onZoomIn)
        PageControlRule()
        PageControl(AppIcons.Remove, R.string.zoom_out, onZoomOut)
        PageControlRule()
        PageControl(if (bookmarked) AppIcons.BookmarkFill else AppIcons.Bookmark, R.string.bookmark, onBookmark)
    }
}

@Composable
private fun PageControl(icon: ImageVector, @StringRes description: Int, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    KobbloIconButton(
        icon = icon,
        description = stringResource(description),
        onClick = {
            haptics.tap()
            onClick()
        },
        iconSize = 18.dp,
        tint = KobbloDesign.colors.action,
        background = Color.Transparent
    )
}

@Composable
private fun PageControlRule() {
    Box(modifier = Modifier.width(22.dp).height(1.dp).background(KobbloDesign.colors.hairline))
}
