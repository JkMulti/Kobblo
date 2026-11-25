package com.kobblo.gallery.file.explorer.viewer.video

import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.viewer.findActivity
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay

internal class VideoNeighborPages(val previous: Int, val next: Int)

/** The nearest videos each way in the same folder, whatever photos lie between them in the list. */
@Composable
internal fun rememberVideoNeighbors(files: List<FileItem>, page: Int): VideoNeighborPages = remember(files, page) {
    val paths = files.map { it.path }
    VideoNeighborPages(
        previous = VideoNeighbors.find(paths, { files[it].type == "video" }, page, -1),
        next = VideoNeighbors.find(paths, { files[it].type == "video" }, page, 1)
    )
}

/**
 * Offers the next video in the folder when this one ends. It only counts down by itself after something long enough
 * to have been watched on purpose, since a camera's folder of short clips isn't a series anyone asked to run through.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoUpNext(
    session: VideoSession,
    nextIndex: Int,
    nextName: String,
    backdrop: Backdrop,
    onPlay: (Int) -> Unit
) {
    val colors = KobbloDesign.colors
    val ended = session.isEnded && nextIndex >= 0 && !session.isLooping && !session.isLocked
    var cancelled by remember(session.file?.path) { mutableStateOf(false) }
    val autoPlays = session.durationMs >= AutoPlayMinimumMs
    var secondsLeft by remember(session.file?.path) { mutableIntStateOf(AutoPlaySeconds) }

    LaunchedEffect(ended, cancelled, autoPlays) {
        if (ended && !cancelled && autoPlays) {
            secondsLeft = AutoPlaySeconds
            while (secondsLeft > 0) {
                delay(1000L)
                secondsLeft--
            }
            onPlay(nextIndex)
        }
    }

    if (!ended || cancelled) return

    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(48.dp)
            .kobbloGlass(backdrop, CapsuleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            stringResource(R.string.viewer_video_up_next, nextName),
            style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (autoPlays) {
            TextButton(onClick = { cancelled = true }) {
                Text(stringResource(R.string.cancel), style = KobbloType.footnote, color = colors.smoke)
            }
        }
        TextButton(onClick = { onPlay(nextIndex) }) {
            Text(
                if (autoPlays) stringResource(R.string.viewer_video_up_next_in, secondsLeft) else stringResource(R.string.viewer_video_up_next_play),
                style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = colors.action
            )
        }
    }
}

/** Turns the screen as the Rotation setting says, for as long as a video's showing, and gives it back afterwards. */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoRotationEffect(session: VideoSession, isVideo: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val aspect = session.videoAspect
    val revision = session.rotationRevision
    LaunchedEffect(isVideo, aspect, revision) {
        val target = activity ?: return@LaunchedEffect
        target.requestedOrientation = when {
            !isVideo -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> when (VideoPrefs.rotation(context)) {
                VideoRotation.Automatic -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                VideoRotation.Locked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                VideoRotation.MatchVideo -> when {
                    aspect <= 0f -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    aspect >= 1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

private const val AutoPlaySeconds = 5
private const val AutoPlayMinimumMs = 60_000L
