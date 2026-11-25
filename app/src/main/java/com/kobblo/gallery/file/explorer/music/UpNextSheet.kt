package com.kobblo.gallery.file.explorer.music

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import androidx.compose.ui.graphics.Color

/**
 * What plays next: the song that's on, then the queue in its true order, with Shuffle and Repeat at
 * the top. Tap a song to jump to it, drag its handle to move it, and × to take it out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val player = remember { MusicPlayer.get(context) }
    val playback by player.state.collectAsState()
    val queue = playback.queue
    val current = queue.current
    val upNext = queue.upNext
    val list = rememberLazyListState()

    // A song counts from here on: the list's first item is the one playing, so a list index minus
    // one, plus the songs already gone by, is its real place in the queue.
    val offset = queue.index + 1
    val reorder = rememberReorderState(
        list = list,
        canMoveOver = { it.key is Long },
        onMove = { from, to ->
            player.moveQueueItem(from - UpNextHeaderItems + offset, to - UpNextHeaderItems + offset)
        }
    )

    LaunchedEffect(current == null) {
        if (current == null) onDismiss()
    }

    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = KobbloSpace.screen, end = KobbloSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.music_up_next),
                    style = KobbloType.title,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { player.clearQueue() }, enabled = upNext.isNotEmpty() || queue.index > 0) {
                    Text(
                        text = stringResource(R.string.music_clear),
                        style = KobbloType.headline,
                        color = if (upNext.isNotEmpty() || queue.index > 0) colors.action else colors.pewter
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeButton(
                    label = stringResource(R.string.music_shuffle),
                    icon = AppIcons.Shuffle,
                    active = playback.isShuffled,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.tap()
                        player.toggleShuffle()
                    }
                )
                ModeButton(
                    label = stringResource(
                        when (playback.repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.string.music_repeat_one
                            Player.REPEAT_MODE_ALL -> R.string.music_repeat_all
                            else -> R.string.music_repeat
                        }
                    ),
                    icon = if (playback.repeatMode == Player.REPEAT_MODE_ONE) AppIcons.RepeatOne else AppIcons.Repeat,
                    active = playback.repeatMode != Player.REPEAT_MODE_OFF,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.tap()
                        player.cycleRepeat()
                    }
                )
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = KobbloSpace.xl)
            ) {
                if (current != null) {
                    item(key = "current", contentType = "current") {
                        SongRow(
                            track = current.track,
                            index = 0,
                            count = 1,
                            isCurrent = true,
                            isPlaying = playback.isPlaying,
                            trailing = {},
                            onClick = {},
                            onLongClick = {}
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (upNext.isEmpty()) {
                    item(key = "none", contentType = "none") {
                        KobbloGroupFooter(stringResource(R.string.music_up_next_empty))
                    }
                }
                itemsIndexed(upNext, key = { _, entry -> entry.uid }, contentType = { _, _ -> "song" }) { index, entry ->
                    val key: Long = entry.uid
                    SongRow(
                        track = entry.track,
                        index = index,
                        count = upNext.size,
                        isCurrent = false,
                        isPlaying = false,
                        modifier = Modifier
                            .then(if (reorder.draggedKey == key) Modifier else Modifier.animateItem())
                            .reorderRow(reorder, key),
                        onClick = { player.jumpTo(offset + index) },
                        onLongClick = {},
                        trailing = {
                            KobbloIconButton(
                                icon = AppIcons.Close,
                                description = stringResource(R.string.music_remove),
                                onClick = {
                                    haptics.tap()
                                    player.removeQueueItem(offset + index)
                                },
                                size = 32.dp,
                                iconSize = 16.dp,
                                tint = colors.smoke,
                                background = Color.Transparent
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = AppIcons.DragHandle,
                                contentDescription = stringResource(R.string.music_drag_to_reorder),
                                tint = colors.smoke,
                                modifier = Modifier
                                    .size(30.dp)
                                    .reorderHandle(reorder, key, onStart = { haptics.tap() })
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Items above the first upcoming song. */
private const val UpNextHeaderItems = 1

/** Shuffle and Repeat: tinted when on, quiet when off. */
@Composable
private fun ModeButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CapsuleShape)
            .background(if (active) colors.action else colors.snow)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (active) colors.onAction else colors.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = if (active) colors.onAction else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
