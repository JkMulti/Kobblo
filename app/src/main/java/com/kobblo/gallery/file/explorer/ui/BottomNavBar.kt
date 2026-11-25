package com.kobblo.gallery.file.explorer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.archive.ArchiveProgress
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.fileops.JobKind
import com.kobblo.gallery.file.explorer.fileops.JobSurface
import com.kobblo.gallery.file.explorer.fileops.JobsState
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import kotlin.math.roundToInt

/** What can sit at the foot of the screen. Only one does at a time. */
internal enum class FootBar { Tabs, Selection, Organise, Clipboard, Extracting, Pasting }

/**
 * Which bar the foot of the screen shows. The tabs are there when nothing else is going on. Whatever
 * the person has in hand comes before anything running by itself: files they've selected, then files
 * waiting for a yes to be organised, then files cut or copied and waiting for somewhere to land. A
 * running extraction or paste only gets the bar once none of those wants it, and keeps going
 * meanwhile, with its notification to show how far it's got.
 */
internal fun footBar(
    selecting: Boolean,
    organisePending: Boolean,
    clipboardArmed: Boolean,
    extracting: Boolean,
    pasting: Boolean
): FootBar = when {
    selecting -> FootBar.Selection
    organisePending -> FootBar.Organise
    clipboardArmed -> FootBar.Clipboard
    extracting -> FootBar.Extracting
    pasting -> FootBar.Pasting
    else -> FootBar.Tabs
}

/** How far an extraction has got, from 0 to 1. One that hasn't counted its files yet is at 0. */
internal fun ArchiveProgress.fraction(): Float = when (this) {
    is ArchiveProgress.Complete -> 1f
    is ArchiveProgress.Running -> if (filesTotal > 0) filesDone.toFloat() / filesTotal else 0f
    else -> 0f
}

/**
 * The foot of the screen: the tab bar ([tabBar]), or whichever bar has taken its place.
 * The selection bar ([selectionModeContent]) and the tabs are drawn by the caller; the rest are here.
 *
 * [capturedTargetFolderName] is the folder a paste was started into, remembered by the caller through
 * [onCaptureTargetFolder] so the paste's bar can still name it after the person has moved elsewhere.
 */
@Composable
fun UnifiedBottomPill(
    state: AppState,
    archiveProgress: ArchiveProgress?,
    onCancelExtract: () -> Unit,
    onAction: (AppAction) -> Unit,
    capturedTargetFolderName: String?,
    onCaptureTargetFolder: (String?) -> Unit,
    selectionModeContent: @Composable () -> Unit,
    tabBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val jobs by FileJobs.state.collectAsState()
    val bar = footBar(
        selecting = state.isSelectionMode,
        organisePending = state.organisePendingFiles.isNotEmpty(),
        clipboardArmed = state.clipboardPaths.isNotEmpty(),
        extracting = archiveProgress is ArchiveProgress.Running || archiveProgress is ArchiveProgress.Complete,
        pasting = jobs.running?.surface == JobSurface.PASTE_PILL
    )
    val reducedMotion = ExpressiveMotion.isReducedMotion()

    AnimatedContent(
        targetState = bar,
        modifier = modifier,
        transitionSpec = { swapBars(reducedMotion) },
        contentAlignment = Alignment.Center,
        label = "unified_pill"
    ) { shown ->
        val wide = Modifier.fillMaxWidth()
        when (shown) {
            FootBar.Tabs -> tabBar()
            FootBar.Selection -> selectionModeContent()
            FootBar.Organise -> OrganiseBar(state.organisePendingFiles, onAction, wide)
            FootBar.Clipboard -> ClipboardBar(state, onAction, onCaptureTargetFolder, wide)
            // The bar goes on being drawn while it fades away, by when its extraction may be gone.
            FootBar.Extracting -> if (archiveProgress != null) ExtractBar(archiveProgress, onCancelExtract, wide)
            FootBar.Pasting -> PasteBar(jobs, capturedTargetFolderName ?: state.currentFolderName, onAction, wide)
        }
    }
}

/**
 * One bar giving way to the next: the new one fades in while growing the last little way to full
 * size, and the old one fades out a touch smaller. With motion reduced they simply change places.
 */
private fun AnimatedContentTransitionScope<FootBar>.swapBars(reducedMotion: Boolean): ContentTransform {
    val swap = if (reducedMotion) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else {
        val arriving = fadeIn(KobbloMotion.fadeIn()) + scaleIn(KobbloMotion.snappy(), initialScale = 0.94f)
        val leaving = fadeOut(KobbloMotion.fadeOut()) + scaleOut(KobbloMotion.fadeOut(), targetScale = 0.97f)
        arriving togetherWith leaving
    }
    // Not clipped while the size changes, or the soft shadow around a floating bar is cut off square.
    return swap using SizeTransform(clip = false)
}

/** What takes the tab bar's place while something is running or waiting for a yes. */
@Composable
private fun OperationPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = KobbloSpace.lg)
            .ambientShadow(KobbloShapes.xl, KobbloDesign.colors.ambient)
            .clip(KobbloShapes.xl)
            .background(KobbloDesign.colors.snow)
            .animateContentSize(),
        content = content
    )
}

/** How far a running operation has got. */
@Composable
private fun OperationProgress(progress: Float, paused: Boolean = false) {
    val colors = KobbloDesign.colors
    val animated by animateFloatAsState(targetValue = progress, animationSpec = KobbloMotion.state(), label = "operation_progress")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.md, end = KobbloSpace.md, bottom = KobbloSpace.sm)
            .height(4.dp)
            .clip(CapsuleShape)
            .background(colors.mist)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(CapsuleShape)
                .background(if (paused) colors.pewter else colors.action)
        )
    }
}

/** A bar's first line: what is happening, or what is waiting. */
@Composable
private fun BarTitle(text: String) {
    Text(
        text = text,
        style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
        color = KobbloDesign.colors.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** A bar's second line, quieter: where to, or which file. */
@Composable
private fun BarDetail(text: String) {
    Text(
        text = text,
        style = KobbloType.footnote,
        color = KobbloDesign.colors.smoke,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// ── Files waiting for a yes ───────────────────────────────────────────────────

/**
 * A bar about files that will go somewhere once the person says so. It says how many and what's
 * planned for them, and tapping those words opens the list of names above. Cancel drops the plan and
 * [goAhead] holds the button that carries it out. [toggleSpoken] gives the chevron a name for a
 * screen reader.
 */
@Composable
private fun WaitingFilesBar(
    names: List<String>,
    title: String,
    subtitle: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    toggleSpoken: Boolean = false,
    goAhead: @Composable RowScope.() -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    var namesOpen by remember { mutableStateOf(false) }
    val chevronTurn by animateFloatAsState(
        targetValue = if (namesOpen) 180f else 0f,
        animationSpec = KobbloMotion.snappy(),
        label = "waiting_files_chevron"
    )

    OperationPanel(modifier) {
        if (namesOpen) WaitingFileNames(names)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KobbloSpace.md, top = KobbloSpace.xs, end = KobbloSpace.xs, bottom = KobbloSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptics.tap()
                        namesOpen = !namesOpen
                    }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarTitle(title)
                    Spacer(Modifier.width(KobbloSpace.xxs))
                    Icon(
                        imageVector = AppIcons.ChevronUp,
                        contentDescription = when {
                            !toggleSpoken -> null
                            namesOpen -> stringResource(R.string.view_hide_items)
                            else -> stringResource(R.string.view_show_items)
                        },
                        tint = colors.pewter,
                        modifier = Modifier.size(14.dp).rotate(chevronTurn)
                    )
                }
                BarDetail(subtitle)
            }
            TextButton(
                onClick = {
                    haptics.tap()
                    onCancel()
                }
            ) {
                Text(stringResource(R.string.cancel), color = colors.action)
            }
            goAhead()
        }
    }
}

/** The names of the waiting files, in a list that scrolls once there are more than a handful. */
@Composable
private fun WaitingFileNames(names: List<String>) {
    val colors = KobbloDesign.colors
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 190.dp)
            .padding(horizontal = KobbloSpace.md, vertical = KobbloSpace.sm),
        verticalArrangement = Arrangement.spacedBy(KobbloSpace.xs)
    ) {
        items(names) { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.File, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(KobbloSpace.xs))
                Text(name, style = KobbloType.footnote, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Downloads sorted into folders, held until the person agrees to the move. */
@Composable
private fun OrganiseBar(files: List<FileItem>, onAction: (AppAction) -> Unit, modifier: Modifier) {
    val haptics = LocalHaptics.current
    WaitingFilesBar(
        names = files.map { it.name },
        title = pluralStringResource(R.plurals.items_ready, files.size, files.size),
        subtitle = stringResource(R.string.organize_pill_subtitle),
        onCancel = { onAction(AppAction.CancelOrganiseDownloads) },
        modifier = modifier
    ) {
        Button(
            onClick = {
                haptics.tap()
                onAction(AppAction.ConfirmOrganiseDownloads)
            }
        ) {
            Text(stringResource(R.string.organize_pill_action))
        }
    }
}

/**
 * Files cut or copied, waiting for somewhere to land. In a folder its button pastes there. Anywhere
 * that isn't a folder, such as a category, nothing can land, and with this bar in the tabs' place
 * there'd be no way to reach a folder either, so the button leads to the list of places to browse
 * instead. On that list itself every way onward is already on screen, so there is no button.
 */
@Composable
private fun ClipboardBar(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onCaptureTargetFolder: (String?) -> Unit,
    modifier: Modifier
) {
    val haptics = LocalHaptics.current
    val moving = state.clipboardAction == "cut"
    val count = state.clipboardPaths.size
    val folder = state.location.takeIf { it.startsWith("/") }
    // A volume's root goes by the volume's name, as its path ends in something like "0".
    val folderName = folder?.let { path ->
        StorageVolumes.forPath(path)?.takeIf { it.path == path }?.name ?: path.substringAfterLast('/')
    }

    WaitingFilesBar(
        names = state.clipboardPaths.map { it.substringAfterLast("/") },
        title = pluralStringResource(
            if (moving) R.plurals.view_clipboard_items_to_move else R.plurals.view_clipboard_items_to_copy,
            count,
            count
        ),
        subtitle = if (folderName != null) {
            stringResource(R.string.explorer_paste_into, folderName)
        } else {
            stringResource(R.string.explorer_paste_open_folder)
        },
        onCancel = { onAction(AppAction.ClearClipboard) },
        modifier = modifier,
        toggleSpoken = true
    ) {
        if (state.location != "locations") {
            Button(
                onClick = {
                    if (folder != null) {
                        haptics.copyPaste()
                        onCaptureTargetFolder(folderName)
                        onAction(AppAction.PasteClipboard(folder))
                    } else {
                        haptics.tap()
                        onAction(AppAction.SetLocation("locations"))
                    }
                }
            ) {
                Text(
                    text = stringResource(
                        when {
                            folder == null -> R.string.explorer_choose_folder
                            moving -> R.string.view_move_here
                            else -> R.string.paste_here
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Work under way ────────────────────────────────────────────────────────────

/**
 * A bar about something running: what it's doing, a line of detail, how far along it is as a number
 * and as a bar, and [controls] to pause or stop it.
 */
@Composable
private fun RunningBar(
    title: String,
    detail: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    controls: @Composable RowScope.() -> Unit
) {
    OperationPanel(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = KobbloSpace.md, top = KobbloSpace.xs, end = KobbloSpace.xs, bottom = KobbloSpace.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BarTitle(title)
                BarDetail(detail)
            }
            Spacer(Modifier.width(KobbloSpace.xs))
            Text(
                text = stringResource(R.string.percentage, (fraction * 100).roundToInt()),
                style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
                color = KobbloDesign.colors.smoke
            )
            controls()
        }
        OperationProgress(fraction, paused)
    }
}

/** A small round control at the end of a running bar. */
@Composable
private fun BarControl(icon: ImageVector, description: String, onClick: () -> Unit) {
    KobbloIconButton(
        icon = icon,
        description = description,
        onClick = onClick,
        size = 36.dp,
        iconSize = 16.dp,
        tint = KobbloDesign.colors.action,
        background = Color.Transparent
    )
}

/**
 * A copy or move in progress. It names the file in hand and the folder it's all going to: the job's
 * own, or failing that [fallbackTarget], the folder the person was in when they pasted.
 */
@Composable
private fun PasteBar(jobs: JobsState, fallbackTarget: String?, onAction: (AppAction) -> Unit, modifier: Modifier) {
    val haptics = LocalHaptics.current
    val job = jobs.running
    val paused = job?.paused == true
    val doing = stringResource(
        when {
            job?.stopping == true -> R.string.safety_job_stopping
            paused -> R.string.paused
            job?.kind == JobKind.MOVE -> R.string.moving
            else -> R.string.copying
        }
    )
    val file = job?.item.orEmpty()
    val into = stringResource(R.string.into_target, job?.target ?: fallbackTarget ?: stringResource(R.string.tools_inspect_folder))
    val queued = jobs.waiting

    RunningBar(
        title = if (file.isEmpty()) doing else stringResource(R.string.status_and_filename, doing, file),
        detail = if (queued > 0) "$into · ${pluralStringResource(R.plurals.safety_job_more_waiting, queued, queued)}" else into,
        fraction = job?.fraction ?: 0f,
        modifier = modifier,
        paused = paused
    ) {
        BarControl(
            icon = if (paused) AppIcons.Play else AppIcons.Pause,
            description = stringResource(if (paused) R.string.op_resume else R.string.pause),
            onClick = {
                haptics.copyPaste()
                onAction(AppAction.TogglePastePause)
            }
        )
        BarControl(
            icon = AppIcons.Close,
            description = stringResource(R.string.cancel),
            onClick = {
                haptics.copyPaste()
                onAction(AppAction.CancelPaste)
            }
        )
    }
}

/**
 * An archive being unpacked, then the news that it's done. Its cross stops the extraction while it
 * runs. Once done, the whole bar can be tapped to put it away, the cross included.
 */
@Composable
private fun ExtractBar(progress: ArchiveProgress, onCancel: () -> Unit, modifier: Modifier) {
    val haptics = LocalHaptics.current
    val done = progress is ArchiveProgress.Complete
    val putAway = if (done) {
        Modifier.clickable {
            haptics.tap()
            onCancel()
        }
    } else Modifier

    RunningBar(
        title = stringResource(if (done) R.string.extract_pill_done else R.string.extract_pill_running),
        detail = when (progress) {
            is ArchiveProgress.Complete -> stringResource(R.string.extract_pill_done_detail)
            // An entry is a path inside the archive; only its last part is worth the room.
            is ArchiveProgress.Running -> progress.currentEntryName.substringAfterLast('/')
            else -> ""
        },
        fraction = progress.fraction(),
        modifier = modifier.then(putAway)
    ) {
        BarControl(
            icon = AppIcons.Close,
            description = stringResource(R.string.cancel),
            onClick = {
                haptics.copyPaste()
                onCancel()
            }
        )
    }
}
