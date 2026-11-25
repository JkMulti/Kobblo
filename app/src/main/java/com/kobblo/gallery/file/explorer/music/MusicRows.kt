package com.kobblo.gallery.file.explorer.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.pressedRowFill
import com.kobblo.gallery.file.explorer.ui.design.rowDivider

/** A row's cover, and how far its hairline starts in from the group's edge: where the text does. */
val CoverRowCoverSize = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail
val CoverRowDividerInset = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset + CoverRowCoverSize + com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap

/** Second line of a song: who it's by, or for an untagged file, where it lives. */
fun MusicTrack.subtitleLine(): String = when {
    artist.isNotBlank() && album.isNotBlank() && album != folderName -> "$artist · $album"
    artist.isNotBlank() -> artist
    else -> folderName
}

/** One row in an inset group. It keeps the group's outer corners when it's first or last. */
@Composable
fun MusicRow(
    title: String,
    subtitle: String?,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    dividerInset: Dp = CoverRowDividerInset,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    val fill = pressedRowFill(source)
    Row(
        modifier = modifier
            .padding(horizontal = KobbloSpace.screen)
            .fillMaxWidth()
            .clip(KobbloShapes.groupItem(index, count))
            .background(colors.snow)
            .background(fill)
            .quietCombinedClickable(source = source, onLongClick = onLongClick, onClick = onClick)
            .rowDivider(index < count - 1, dividerInset, colors.hairline)
            // A cover sits as far from the group's top and bottom as from its side.
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.fileRow)
            .padding(horizontal = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset, vertical = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KobbloType.body,
                color = if (isCurrent) colors.action else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = KobbloType.footnote,
                    color = colors.smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A song as a row: its cover, name and length, with bars over the cover while it's the one playing. */
@Composable
fun SongRow(
    track: MusicTrack,
    index: Int,
    count: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = track.subtitleLine(),
    leadingNumber: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    MusicRow(
        title = track.title,
        subtitle = subtitle,
        index = index,
        count = count,
        modifier = modifier,
        isCurrent = isCurrent,
        dividerInset = if (leadingNumber != null) com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset + com.kobblo.gallery.file.explorer.ui.design.KobbloSize.badge + com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap else CoverRowDividerInset,
        leading = {
            if (leadingNumber != null) {
                // An album's own screen shows the track's number; every row would have the same cover.
                Box(modifier = Modifier.size(com.kobblo.gallery.file.explorer.ui.design.KobbloSize.badge), contentAlignment = Alignment.Center) {
                    if (isCurrent) {
                        PlayingBars(isPlaying = isPlaying, color = colors.action, size = 18.dp)
                    } else {
                        Text(
                            text = if (leadingNumber > 0) leadingNumber.toString() else "",
                            style = KobbloType.callout.copy(fontFeatureSettings = "tnum"),
                            color = colors.smoke,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(contentAlignment = Alignment.Center) {
                    MusicCover(track = track, size = CoverRowCoverSize, shape = KobbloShapes.tile)
                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .size(CoverRowCoverSize)
                                .clip(KobbloShapes.tile)
                                .background(Color.Black.copy(alpha = 0.42f)),
                            contentAlignment = Alignment.Center
                        ) {
                            PlayingBars(isPlaying = isPlaying, color = Color.White, size = 20.dp)
                        }
                    }
                }
            }
        },
        trailing = trailing ?: if (track.durationMs > 0L) {
            {
                Text(
                    text = formatTrackTime(track.durationMs),
                    style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
                    color = colors.smoke
                )
            }
        } else null,
        onLongClick = onLongClick,
        onClick = onClick
    )
}

/**
 * The mark on the song that's playing: four bars dancing while it plays and resting at a low height
 * while it's paused, which also says "paused" without any more words.
 */
@Composable
fun PlayingBars(isPlaying: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val reduced = ExpressiveMotion.isReducedMotion()
    val animate = isPlaying && !reduced
    val description = stringResource(R.string.music_now_playing_mark)
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "playing_bars")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "playing_bars_phase"
        )
        value
    } else {
        0f
    }
    Canvas(modifier = modifier.size(size).semantics { contentDescription = description }) {
        val barWidth = this.size.width / 7f
        val gap = barWidth
        val offsets = floatArrayOf(0f, 0.37f, 0.71f, 0.19f)
        offsets.forEachIndexed { i, offset ->
            val height = if (animate) {
                val t = (phase + offset) % 1f
                val wave = if (t < 0.5f) t * 2f else (1f - t) * 2f
                0.28f + 0.72f * wave
            } else if (isPlaying) {
                0.6f + 0.3f * ((i + 1) % 2)
            } else {
                0.22f
            }
            val barHeight = this.size.height * height
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap) + barWidth / 2f, this.size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/** Play and Shuffle, side by side at the head of a list. Shuffle is the one that catches the eye. */
@Composable
fun PlayShuffleButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    shuffleLabel: String = stringResource(R.string.music_shuffle_all)
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BigButton(
            label = stringResource(R.string.music_play),
            icon = AppIcons.Play,
            container = colors.snow,
            content = colors.action,
            modifier = Modifier.weight(1f),
            onClick = {
                haptics.tap()
                onPlay()
            }
        )
        BigButton(
            label = shuffleLabel,
            icon = AppIcons.Shuffle,
            container = colors.action,
            content = colors.onAction,
            modifier = Modifier.weight(1.25f),
            onClick = {
                haptics.tap()
                onShuffle()
            }
        )
    }
}

@Composable
private fun BigButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A row that leads somewhere, such as a smart list or an artist. */
@Composable
fun NavigationRow(
    title: String,
    subtitle: String?,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailingText: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    MusicRow(
        title = title,
        subtitle = subtitle,
        index = index,
        count = count,
        modifier = modifier,
        leading = leading,
        onLongClick = onLongClick,
        onClick = onClick,
        trailing = {
            if (trailingText != null) {
                Text(trailingText, style = KobbloType.footnote, color = colors.smoke)
                Spacer(Modifier.width(6.dp))
            }
            KobbloChevron()
        }
    )
}

/** A chip of the view switcher, and of other short pick-one lists. */
@Composable
fun MusicChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KobbloDesign.colors
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(CapsuleShape)
            .background(if (selected) colors.action else colors.snow)
            .quietClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = if (selected) colors.onAction else colors.ink,
            maxLines = 1
        )
    }
}

/** "12 Songs, 45 min", for the foot of a list. */
@Composable
fun songsSummary(tracks: List<MusicTrack>): String {
    val songs = pluralStringResource(R.plurals.music_song_count, tracks.size, tracks.size)
    val total = tracks.sumOf { it.durationMs }
    return if (total > 0L) stringResource(R.string.media_songs_and_duration, songs, formatTotalDuration(total)) else songs
}

/** Total running time as a short phrase, for the foot of a list. */
@Composable
fun formatTotalDuration(durationMs: Long): String = when (val time = runningTimeOf(durationMs)) {
    is RunningTime.Hours -> stringResource(R.string.media_duration_hours_minutes, time.hours, time.minutes)
    is RunningTime.Minutes -> stringResource(R.string.media_duration_minutes, time.minutes)
    is RunningTime.Seconds -> stringResource(R.string.media_duration_seconds, time.seconds)
}

/** The name of an artist as it should read, untagged included. */
@Composable
fun artistLabel(name: String): String = name.ifBlank { stringResource(R.string.music_unknown_artist) }

@Composable
fun MusicAlbum.artistLine(): String = when {
    isVarious -> stringResource(R.string.music_various_artists)
    artist.isNotBlank() -> artist
    else -> ""
}

/** Extra room at the foot of a pushed screen for the mini player and the gesture bar. */
val PushedScreenBottomPadding: Dp = 96.dp

/** Spacing inside a pushed screen's header. */
val HeaderSpacing: Dp = 12.dp
