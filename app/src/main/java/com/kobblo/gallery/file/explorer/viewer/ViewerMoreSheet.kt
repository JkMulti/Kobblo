package com.kobblo.gallery.file.explorer.viewer

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.viewer.video.SubtitlePosition
import com.kobblo.gallery.file.explorer.viewer.video.SubtitleSize
import com.kobblo.gallery.file.explorer.viewer.video.VideoPrefs
import com.kobblo.gallery.file.explorer.viewer.video.VideoRotation
import com.kobblo.gallery.file.explorer.viewer.video.VideoSession
import com.kobblo.gallery.file.explorer.viewer.video.labelRes
import kotlinx.coroutines.launch

/** What the more sheet needs from the viewer, grouped so its call site stays readable. */
internal class MoreActions(
    val onAddToAlbum: () -> Unit,
    val onRename: () -> Unit,
    val onCopyImage: () -> Unit,
    val onSetAs: () -> Unit,
    val onPrint: () -> Unit,
    val onSaveFrame: () -> Unit,
    /** Set when the photo was opened from an album it could be the cover of. */
    val onUseAsCover: (() -> Unit)?,
    val onOpenWith: () -> Unit,
    val onSetWallpaper: () -> Unit,
    val onRotate: () -> Unit,
    val onShowInFolder: () -> Unit,
    val onPictureInPicture: () -> Unit,
    val onLockControls: () -> Unit,
    val onVideoRotation: (com.kobblo.gallery.file.explorer.viewer.video.VideoRotation) -> Unit,
    val onSubtitleAdded: (Boolean) -> Unit,
    val onStartSlideshow: (seconds: Int, skipVideos: Boolean) -> Unit,
    val onSaveMotionClip: () -> Unit,
    val onShareClean: () -> Unit,
    val onRemoveLocationCopy: () -> Unit,
    val onRemoveLocationReplace: () -> Unit
)

private enum class SheetPage { Main, Speed, Subtitles, Audio, Rotation, Slideshow, RemoveLocation, SetAs }

private val SpeedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Everything the viewer can do beyond its toolbar. Rows only appear when they apply, so a plain photo
 * still gets a short list. Choosing one setting opens its options in the same sheet.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerMoreSheet(
    file: FileItem,
    video: VideoSession?,
    fileCount: Int,
    hasLocation: Boolean,
    hasMotionClip: Boolean,
    actions: MoreActions,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    var page by remember { mutableStateOf(SheetPage.Main) }
    var rotationMode by remember { mutableStateOf(VideoPrefs.rotation(context)) }
    val isStream = file.isExternalStream || file.isInVault
    val canEditMetadata = com.kobblo.gallery.file.explorer.viewer.metadata.MetadataActions.canEdit(file)
    val canShareClean = !isStream && com.kobblo.gallery.file.explorer.viewer.metadata.MetadataActions.canShareClean(file)
    val isPhoto = file.type == "image"
    val isOurs = file.isOnSharedStorage
    val hasPip = supportsPictureInPicture(LocalContext.current)

    fun close(block: () -> Unit) {
        onDismiss()
        block()
    }

    KobbloSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        when (page) {
            SheetPage.Main -> ExpressiveSheetContent(title = file.name) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val hasPlayingVideo = video != null && video.problem == null && video.hasPlayer
                    if (hasPlayingVideo) {
                        KobbloGroup {
                            ValueRow(stringResource(R.string.viewer_video_speed), stringResource(R.string.viewer_video_speed_value, speedLabel(video!!.speed)), showDivider = true) {
                                page = SheetPage.Speed
                            }
                            KobbloRow(
                                title = stringResource(R.string.viewer_video_loop),
                                trailing = { ExpressiveSwitch(checked = video.isLooping, onCheckedChange = { video.toggleLoop() }) },
                                showDivider = true,
                                onClick = { video.toggleLoop() }
                            )
                            KobbloRow(
                                title = stringResource(R.string.viewer_video_scale),
                                trailing = {
                                    Text(
                                        stringResource(video.scale.labelRes()),
                                        style = KobbloType.callout,
                                        color = colors.smoke
                                    )
                                },
                                showDivider = true,
                                onClick = { video.scale = video.scale.next() }
                            )
                            ValueRow(
                                stringResource(R.string.viewer_video_subtitles),
                                video.subtitleChoices.firstOrNull { it.isSelected }?.label ?: stringResource(R.string.viewer_off),
                                showDivider = video.audioChoices.size > 1
                            ) { page = SheetPage.Subtitles }
                            if (video.audioChoices.size > 1) {
                                ValueRow(
                                    stringResource(R.string.viewer_video_audio),
                                    video.audioChoices.firstOrNull { it.isSelected }?.label.orEmpty(),
                                    showDivider = false
                                ) { page = SheetPage.Audio }
                            }
                        }
                        Spacer(Modifier.height(KobbloSpace.groupGap))
                        KobbloGroup {
                            ActionRow(stringResource(R.string.viewer_video_lock), AppIcons.Lock, showDivider = true) {
                                close(actions.onLockControls)
                            }
                            ValueRow(
                                stringResource(R.string.viewer_video_rotation),
                                stringResource(rotationMode.labelRes()),
                                showDivider = true
                            ) { page = SheetPage.Rotation }
                            KobbloRow(
                                title = stringResource(R.string.viewer_video_background),
                                trailing = {
                                    ExpressiveSwitch(
                                        checked = video.keepsPlayingInBackground,
                                        onCheckedChange = { video.chooseBackgroundPlay(it) }
                                    )
                                },
                                showDivider = isOurs || hasPip,
                                onClick = { video.chooseBackgroundPlay(!video.keepsPlayingInBackground) }
                            )
                            if (isOurs) {
                                ActionRow(stringResource(R.string.viewer_save_frame), AppIcons.Camera, showDivider = hasPip) {
                                    close(actions.onSaveFrame)
                                }
                            }
                            if (hasPip) {
                                ActionRow(stringResource(R.string.viewer_video_pip), AppIcons.PictureInPicture) {
                                    close(actions.onPictureInPicture)
                                }
                            }
                        }
                        Spacer(Modifier.height(KobbloSpace.md))
                    }
                    if (isOurs) {
                        RowGroup(
                            buildList {
                                add(SheetRow(stringResource(R.string.gallery_add_to_album), AppIcons.Albums) { close(actions.onAddToAlbum) })
                                add(SheetRow(stringResource(R.string.viewer_rename), AppIcons.Pencil) { close(actions.onRename) })
                            }
                        )
                        Spacer(Modifier.height(KobbloSpace.md))
                    }
                    RowGroup(
                        buildList {
                            add(SheetRow(stringResource(R.string.viewer_open_with), AppIcons.OpenExternal) { close(actions.onOpenWith) })
                            // The clipboard stays around for any app to read, which is no place for something from the Vault.
                            if (isPhoto && !file.isInVault) add(SheetRow(stringResource(R.string.viewer_copy_image), AppIcons.Copy) { close(actions.onCopyImage) })
                            if (isPhoto) add(SheetRow(stringResource(R.string.viewer_set_as), AppIcons.Photo) { page = SheetPage.SetAs })
                            add(
                                SheetRow(
                                    stringResource(
                                        if (isPhoto && !com.kobblo.gallery.file.explorer.viewer.metadata.PhotoRotation.canRotate(file)) R.string.viewer_rotate_view else R.string.viewer_rotate
                                    ),
                                    AppIcons.Rotate
                                ) { close(actions.onRotate) }
                            )
                            if (fileCount > 1) add(SheetRow(stringResource(R.string.viewer_slideshow), AppIcons.PlayRect) { page = SheetPage.Slideshow })
                            if (hasMotionClip) add(SheetRow(stringResource(R.string.viewer_motion_save), AppIcons.Motion) { close(actions.onSaveMotionClip) })
                            if (canShareClean) add(SheetRow(stringResource(R.string.viewer_share_clean), AppIcons.Share) { close(actions.onShareClean) })
                            if (canEditMetadata && hasLocation) add(SheetRow(stringResource(R.string.viewer_remove_location), AppIcons.Location) { page = SheetPage.RemoveLocation })
                            if (isPhoto) add(SheetRow(stringResource(R.string.viewer_print), AppIcons.Print) { close(actions.onPrint) })
                            actions.onUseAsCover?.let { cover -> add(SheetRow(stringResource(R.string.viewer_use_as_cover), AppIcons.Albums) { close(cover) }) }
                            if (!isStream) add(SheetRow(stringResource(R.string.viewer_show_in_folder), AppIcons.FolderOpen) { close(actions.onShowInFolder) })
                        }
                    )
                }
            }
            SheetPage.SetAs -> ExpressiveSheetContent(title = stringResource(R.string.viewer_set_as)) {
                KobbloGroup {
                    ActionRow(stringResource(R.string.viewer_set_wallpaper), AppIcons.Photo, showDivider = true) { close(actions.onSetWallpaper) }
                    KobbloRow(
                        title = stringResource(R.string.viewer_set_as_other),
                        subtitle = stringResource(R.string.viewer_set_as_other_body),
                        trailing = { Icon(AppIcons.Person, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp)) },
                        onClick = { close(actions.onSetAs) }
                    )
                }
            }
            SheetPage.Speed -> ExpressiveSheetContent(title = stringResource(R.string.viewer_video_speed)) {
                KobbloGroup {
                    SpeedChoices.forEachIndexed { index, choice ->
                        CheckRow(
                            title = stringResource(R.string.viewer_video_speed_value, speedLabel(choice)),
                            isSelected = video?.speed == choice,
                            showDivider = index < SpeedChoices.lastIndex
                        ) {
                            video?.applySpeed(choice)
                            page = SheetPage.Main
                        }
                    }
                }
            }
            SheetPage.Subtitles -> ExpressiveSheetContent(title = stringResource(R.string.viewer_video_subtitles)) {
                val choices = video?.subtitleChoices.orEmpty()
                val scope = rememberCoroutineScope()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null && video != null) {
                        scope.launch {
                            actions.onSubtitleAdded(video.addSubtitle(uri))
                            page = SheetPage.Main
                        }
                    }
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    KobbloGroup {
                        CheckRow(
                            title = stringResource(R.string.viewer_off),
                            isSelected = choices.none { it.isSelected },
                            showDivider = true
                        ) {
                            video?.turnSubtitlesOff()
                            page = SheetPage.Main
                        }
                        choices.forEach { choice ->
                            CheckRow(choice.label, choice.isSelected, showDivider = true) {
                                video?.selectTrack(choice, C.TRACK_TYPE_TEXT)
                                page = SheetPage.Main
                            }
                        }
                        ActionRow(stringResource(R.string.viewer_video_subtitles_add), AppIcons.Folder) {
                            // Subtitle files mostly have no type the system knows, so filtering would hide them.
                            picker.launch(arrayOf("*/*"))
                        }
                    }
                    if (video != null) {
                        Spacer(Modifier.height(KobbloSpace.xs))
                        Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) {
                            KobbloGroupHeader(stringResource(R.string.viewer_video_subtitles_size))
                            KobbloSegmentedControl(
                                options = listOf(
                                    stringResource(R.string.viewer_video_subtitles_small),
                                    stringResource(R.string.viewer_video_subtitles_medium),
                                    stringResource(R.string.viewer_video_subtitles_large)
                                ),
                                selectedIndex = video.subtitleSize.ordinal,
                                onSelect = { video.applySubtitleSize(SubtitleSize.entries[it]) }
                            )
                            Spacer(Modifier.height(KobbloSpace.xs))
                            KobbloGroupHeader(stringResource(R.string.viewer_video_subtitles_position))
                            KobbloSegmentedControl(
                                options = listOf(
                                    stringResource(R.string.viewer_video_subtitles_low),
                                    stringResource(R.string.viewer_video_subtitles_middle),
                                    stringResource(R.string.viewer_video_subtitles_high)
                                ),
                                selectedIndex = video.subtitlePosition.ordinal,
                                onSelect = { video.applySubtitlePosition(SubtitlePosition.entries[it]) }
                            )
                        }
                    }
                }
            }
            SheetPage.Rotation -> ExpressiveSheetContent(title = stringResource(R.string.viewer_video_rotation)) {
                KobbloGroup {
                    VideoRotation.entries.forEachIndexed { index, mode ->
                        CheckRow(stringResource(mode.labelRes()), mode == rotationMode, index < VideoRotation.entries.lastIndex) {
                            rotationMode = mode
                            VideoPrefs.setRotation(context, mode)
                            actions.onVideoRotation(mode)
                            page = SheetPage.Main
                        }
                    }
                }
            }
            SheetPage.Audio -> ExpressiveSheetContent(title = stringResource(R.string.viewer_video_audio)) {
                val choices = video?.audioChoices.orEmpty()
                KobbloGroup {
                    choices.forEachIndexed { index, choice ->
                        CheckRow(choice.label, choice.isSelected, index < choices.lastIndex) {
                            video?.selectTrack(choice, C.TRACK_TYPE_AUDIO)
                            page = SheetPage.Main
                        }
                    }
                }
            }
            SheetPage.Slideshow -> ExpressiveSheetContent(title = stringResource(R.string.viewer_slideshow)) {
                SlideshowOptions(onStart = { seconds, skip -> close { actions.onStartSlideshow(seconds, skip) } })
            }
            SheetPage.RemoveLocation -> ExpressiveSheetContent(title = stringResource(R.string.viewer_remove_location)) {
                var confirmingReplace by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(
                        if (confirmingReplace) R.string.viewer_replace_confirm_body
                        else if (file.type == "video") R.string.viewer_remove_location_body_video
                        else R.string.viewer_remove_location_body
                    ),
                    style = KobbloType.callout,
                    color = colors.smoke,
                    modifier = Modifier.padding(horizontal = KobbloSpace.xl, vertical = KobbloSpace.xs)
                )
                Spacer(Modifier.height(KobbloSpace.xs))
                KobbloGroup {
                    if (confirmingReplace) {
                        KobbloRow(
                            title = stringResource(R.string.viewer_replace_confirm_action),
                            titleColor = colors.danger,
                            onClick = { close(actions.onRemoveLocationReplace) }
                        )
                    } else {
                        KobbloRow(
                            title = stringResource(R.string.viewer_save_as_copy),
                            titleColor = colors.action,
                            showDivider = true,
                            onClick = { close(actions.onRemoveLocationCopy) }
                        )
                        KobbloRow(
                            title = stringResource(R.string.viewer_replace_original),
                            titleColor = colors.danger,
                            onClick = { confirmingReplace = true }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideshowOptions(onStart: (Int, Boolean) -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    var seconds by remember { mutableIntStateOf(ViewerPrefs.slideshowInterval(context)) }
    var skipVideos by remember { mutableStateOf(ViewerPrefs.slideshowSkipsVideos(context)) }
    val choices = Slideshow.IntervalChoices

    Column(modifier = Modifier.padding(horizontal = KobbloSpace.screen)) {
        KobbloGroupHeader(stringResource(R.string.viewer_slideshow_interval))
        KobbloSegmentedControl(
            options = choices.map { stringResource(R.string.viewer_seconds_short, it) },
            selectedIndex = choices.indexOf(seconds).coerceAtLeast(0),
            onSelect = { seconds = choices[it] }
        )
        Spacer(Modifier.height(KobbloSpace.xs))
        KobbloGroupHeader(stringResource(R.string.viewer_slideshow_videos))
        KobbloSegmentedControl(
            options = listOf(stringResource(R.string.viewer_slideshow_skip), stringResource(R.string.viewer_slideshow_play)),
            selectedIndex = if (skipVideos) 0 else 1,
            onSelect = { skipVideos = it == 0 }
        )
        Spacer(Modifier.height(KobbloSpace.lg))
        Button(
            onClick = {
                ViewerPrefs.setSlideshow(context, seconds, skipVideos)
                onStart(seconds, skipVideos)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.viewer_slideshow_start), color = colors.snow)
        }
    }
}

private class SheetRow(val title: String, val icon: ImageVector, val onClick: () -> Unit)

/** A group of rows where some appear only when they apply, so the hairlines take care of themselves. */
@Composable
private fun RowGroup(rows: List<SheetRow>) {
    KobbloGroup {
        rows.forEachIndexed { index, row ->
            ActionRow(row.title, row.icon, showDivider = index < rows.lastIndex, onClick = row.onClick)
        }
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, showDivider: Boolean = false, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        trailing = { Icon(icon, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = onClick
    )
}

@Composable
private fun ValueRow(title: String, value: String, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = KobbloType.callout, color = KobbloDesign.colors.smoke, maxLines = 1)
                KobbloChevron()
            }
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

@Composable
private fun CheckRow(title: String, isSelected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        trailing = {
            if (isSelected) {
                Icon(AppIcons.Check, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(20.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
