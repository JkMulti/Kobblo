package com.kobblo.gallery.file.explorer.music

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.LocalAudioPosition
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// This sits on the player's dark background, whatever the theme, so its text is white too.
private val LineCurrent = Color.White
private val LineOther = Color.White.copy(alpha = 0.38f)
private val LinePlain = Color.White.copy(alpha = 0.86f)
private val ControlTint = Color.White.copy(alpha = 0.72f)
private val ControlFill = Color.White.copy(alpha = 0.14f)

/** How long the lyrics leave the reader's scrolling alone before going back to following the song. */
private const val FollowAgainAfterMs = 4000L
private const val NudgeMs = 250L

/**
 * Lyrics in place of the cover. Synced lyrics follow the song and a tapped line seeks there. Their
 * timing can be nudged, and the nudge is remembered for that song. Plain lyrics just read.
 */
@Composable
fun LyricsPane(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { MusicPlayer.get(context) }
    val state by player.lyrics.collectAsState()
    val lyrics = state.lyrics.takeIf { state.path == path }
    // Each song starts from its own top, not wherever the last one had scrolled to.
    key(path) {
        when {
            lyrics == null && state.isLoading && state.path == path -> Box(modifier)
            lyrics == null -> NoLyrics(modifier)
            lyrics.synced -> SyncedLyrics(path, lyrics, modifier)
            else -> PlainLyrics(lyrics, modifier)
        }
    }
}

@Composable
private fun NoLyrics(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.music_no_lyrics),
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = LineCurrent
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.music_no_lyrics_body),
            style = KobbloType.callout,
            color = ControlTint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, modifier: Modifier) {
    Column(
        modifier = modifier
            .fadingEdges()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 28.dp)
    ) {
        lyrics.lines.forEach { line ->
            Text(
                text = line.text,
                style = KobbloType.title.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
                color = LinePlain,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun SyncedLyrics(path: String, lyrics: Lyrics, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember { MusicPlayer.get(context) }
    val store = remember { MusicStore.get(context) }
    val haptics = LocalHaptics.current
    val reduced = ExpressiveMotion.isReducedMotion()
    val data by store.data.collectAsState()
    val offset = data.lyricOffsets[path] ?: 0L
    val positions = LocalAudioPosition.current
    val lines = lyrics.lines

    // Only a new line redraws this, however fast the position ticks.
    val currentFlow = remember(lines, offset, positions) {
        positions.map { LrcParser.lineIndexAt(lines, it + offset) }.distinctUntilChanged()
    }
    val current by currentFlow.collectAsState(initial = LrcParser.lineIndexAt(lines, positions.value + offset))

    val list = rememberLazyListState()
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction) touchedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    Column(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LaunchedEffect(current) {
                if (android.os.SystemClock.elapsedRealtime() - touchedAt < FollowAgainAfterMs) return@LaunchedEffect
                val target = current.coerceAtLeast(0)
                // The top padding below is what lands the line a third of the way down.
                if (reduced) list.scrollToItem(target) else list.animateScrollToItem(target)
            }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().fadingEdges(),
                contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.6f)
            ) {
                itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                    val color by animateColorAsState(
                        targetValue = if (index == current) LineCurrent else LineOther,
                        animationSpec = if (reduced) snap() else KobbloMotion.state(),
                        label = "lyric_line"
                    )
                    Text(
                        text = line.text.ifBlank { "♪" },
                        style = KobbloType.title.copy(fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .quietClickable {
                                haptics.tap()
                                touchedAt = 0L
                                player.seekTo((line.timeMs - offset).coerceAtLeast(0L))
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
        TimingControl(
            offsetMs = offset,
            onChange = { next -> store.update { it.withLyricOffset(path, next) } }
        )
    }
}

/** Nudges the words earlier or later. Most files are a little off; few are off by much. */
@Composable
private fun TimingControl(offsetMs: Long, onChange: (Long) -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NudgeButton(AppIcons.Remove, stringResource(R.string.music_lyrics_later)) {
            haptics.tap()
            onChange(offsetMs - NudgeMs)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "%+.2fs".format(offsetMs / 1000f),
            style = KobbloType.caption.copy(fontFeatureSettings = "tnum"),
            color = ControlTint,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp)
        )
        Spacer(Modifier.width(6.dp))
        NudgeButton(AppIcons.Add, stringResource(R.string.music_lyrics_earlier)) {
            haptics.tap()
            onChange(offsetMs + NudgeMs)
        }
    }
}

@Composable
private fun NudgeButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressScale(pressedScale = 0.86f, interactionSource = source)
            .clip(CapsuleShape)
            .background(ControlFill)
            .quietClickable(source = source, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = ControlTint, modifier = Modifier.size(16.dp))
    }
}

/** Lets lines dissolve at the top and bottom of the pane rather than be cut off by its edge. */
private fun Modifier.fadingEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 36.dp.toPx() / size.height
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
