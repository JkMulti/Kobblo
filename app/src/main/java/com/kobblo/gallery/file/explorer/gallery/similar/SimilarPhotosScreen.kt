package com.kobblo.gallery.file.explorer.gallery.similar

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.AlbumCover
import com.kobblo.gallery.file.explorer.gallery.GalleryWorkScope
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.expressive.SelectionCircle
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exact copies and near lookalikes, grouped, with the best of each first. Opening it starts looking, because
 * that's the only reason to open it. Proven copies are already marked and one tap away. Lookalikes never are:
 * those are for the person to look at and choose.
 */
@Composable
internal fun SimilarPhotosScreen(onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val scan by SimilarScanner.state.collectAsState()

    LaunchedEffect(Unit) {
        if (SimilarScanner.state.value is SimilarScanner.State.Idle) SimilarScanner.start(context)
    }

    HubScreen(
        title = stringResource(R.string.gallery_similar_title),
        backLabel = stringResource(R.string.nav_browse),
        trailingIcon = if (scan is SimilarScanner.State.Done) AppIcons.Replay else null,
        trailingDescription = stringResource(R.string.gallery_similar_rescan),
        onTrailingClick = { SimilarScanner.rescan(context) }
    ) {
        when (val current = scan) {
            SimilarScanner.State.Idle -> ScanProgress(0, 0)
            is SimilarScanner.State.Scanning -> ScanProgress(current.done, current.total)
            is SimilarScanner.State.Done -> if (current.groups.isEmpty()) {
                KobbloMessage(
                    icon = AppIcons.Similar,
                    title = stringResource(R.string.gallery_similar_none),
                    body = pluralStringResource(R.plurals.gallery_similar_scanned, current.scanned, current.scanned)
                )
            } else {
                Results(current.groups, onAction)
            }
        }
    }
}

@Composable
private fun ScanProgress(done: Int, total: Int) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.xl, vertical = KobbloSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.gallery_similar_scanning),
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink
        )
        Spacer(Modifier.height(16.dp))
        StorageMeter(fraction = if (total > 0) done.toFloat() / total else 0f)
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (total > 0) stringResource(R.string.gallery_transfer_count, done, total) else " ",
            style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
            color = colors.smoke
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gallery_similar_scanning_body),
            style = KobbloType.footnote,
            color = colors.smoke,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun Results(groups: List<DuplicateGroup>, onAction: (AppAction) -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val marked = groups.flatMap { group -> group.members.filter { it.id in group.remove } }
    val freeable = marked.sumOf { it.sizeBytes }
    val freeableLabel = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(freeable)
    val tileBottomRoom = 112.dp

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = tileBottomRoom)
        ) {
            items(groups, key = { group -> group.members.first().id }) { group ->
                GroupCard(group, onOpen = { onAction(AppAction.ViewFile(it)) })
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = KobbloSpace.screen, vertical = 12.dp)
        ) {
            Button(
                onClick = { confirming = true },
                enabled = marked.isNotEmpty() && !deleting,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.danger,
                    contentColor = Color.White,
                    disabledContainerColor = colors.mist,
                    disabledContentColor = colors.pewter
                )
            ) {
                Text(
                    text = if (marked.isEmpty()) stringResource(R.string.gallery_similar_none_marked)
                    else pluralStringResource(R.plurals.gallery_similar_delete, marked.size, marked.size, freeableLabel),
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1
                )
            }
        }
    }

    if (confirming) {
        ExpressiveConfirmationSheet(
            title = pluralStringResource(R.plurals.gallery_similar_confirm_title, marked.size, marked.size),
            subtitle = stringResource(R.string.gallery_similar_confirm_body, freeableLabel),
            confirmLabel = stringResource(R.string.delete_label),
            onConfirm = {
                confirming = false
                deleting = true
                val app = context.applicationContext
                val current = groups
                GalleryWorkScope.launch {
                    // These results can be old by now. A photo only goes while the one it's being kept for is there.
                    val paths = withContext(Dispatchers.IO) {
                        current.flatMap { group ->
                            val keptSomething = group.members.any { it.id !in group.remove && File(it.path).exists() }
                            if (keptSomething) group.members.filter { it.id in group.remove }.map { it.path } else emptyList()
                        }
                    }
                    val report = withContext(Dispatchers.IO) { FileRepository(app).moveToTrash(paths) }
                    val gone = report.moved.toSet()
                    SimilarScanner.removeDeleted(marked.filter { it.path in gone }.map { it.id }.toSet())
                    deleting = false
                    val freed = marked.filter { it.path in gone }.sumOf { it.sizeBytes }
                    if (gone.isNotEmpty()) {
                        onAction(AppAction.ShowToast(app.getString(R.string.gallery_similar_freed, com.kobblo.gallery.file.explorer.data.core.FileSizes.label(freed))))
                    }
                    if (gone.size < marked.size) {
                        onAction(AppAction.ShowToast(app.getString(R.string.safety_similar_some_failed, marked.size - gone.size)))
                    }
                    onAction(AppAction.Reload)
                }
            },
            onDismissRequest = { confirming = false }
        )
    }
}

@Composable
private fun GroupCard(group: DuplicateGroup, onOpen: (FileItem) -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    Text(
        text = stringResource(if (group.isExact) R.string.gallery_similar_exact else R.string.gallery_similar_near),
        style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
        color = colors.smoke,
        modifier = Modifier.padding(start = KobbloSpace.content, top = 16.dp, bottom = 6.dp)
    )
    KobbloGroup {
        LazyRow(
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(group.members, key = { it.id }) { file ->
                DuplicateTile(
                    file = file,
                    marked = file.id in group.remove,
                    onToggle = {
                        val next = group.toggled(file.id)
                        if (next === group) {
                            // The last one left. Taking this away would delete every copy.
                        } else {
                            SimilarScanner.update(group, next)
                        }
                    },
                    onOpen = { onOpen(file) }
                )
            }
        }
    }
    if (!group.isExact) {
        KobbloGroupFooter(stringResource(R.string.safety_similar_look_first))
    }
}

@Composable
private fun DuplicateTile(
    file: FileItem,
    marked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val px = with(LocalDensity.current) { 128.dp.roundToPx() }
    Column(
        modifier = Modifier
            .width(128.dp)
            .pressScale(pressedScale = 0.97f, interactionSource = source)
            .quietCombinedClickable(source = source, onLongClick = onOpen, onClick = onToggle)
    ) {
        Box {
            AlbumCover(
                files = remember(file) { listOf(file) },
                sizePx = px,
                modifier = Modifier
                    .size(128.dp)
                    .clip(KobbloShapes.sm)
            )
            if (marked) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(KobbloShapes.sm)
                        .background(Color.Black.copy(alpha = 0.32f))
                )
            }
            KeepBadge(marked)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = file.name,
            style = KobbloType.caption.copy(fontWeight = FontWeight.Normal),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = com.kobblo.gallery.file.explorer.data.core.FileSizes.label(file.sizeBytes),
            style = KobbloType.caption.copy(fontWeight = FontWeight.Normal),
            color = colors.smoke,
            maxLines = 1
        )
    }
}

@Composable
private fun BoxScope.KeepBadge(marked: Boolean) {
    val colors = KobbloDesign.colors
    if (marked) {
        SelectionCircle(
            selected = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(24.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(colors.snow, CapsuleShape)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.gallery_similar_keep),
                style = KobbloType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink
            )
        }
    }
}
