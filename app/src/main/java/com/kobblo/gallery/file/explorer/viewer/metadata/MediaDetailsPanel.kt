package com.kobblo.gallery.file.explorer.viewer.metadata

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.formatSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetGrabber
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * What swiping up reveals beneath a photo or video. It stays calm by default, with the few facts most
 * people want. Show All opens the whole explorer in the same panel, so it costs no extra screen.
 */
private val ShowAllRowHeight = 56.dp

@Composable
internal fun MediaDetailsPanel(
    file: FileItem,
    details: MediaDetails,
    onShowInFolder: () -> Unit,
    onShowAllChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val isStream = file.path.startsWith("content://")
    var showAll by rememberSaveable(file.path) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    var moreAt by remember { mutableIntStateOf(0) }
    val above = with(LocalDensity.current) { ShowAllRowHeight.toPx().toInt() }
    // What Show All adds lands below the fold, so on its own a tap looks like it did nothing. The panel grows for it, and
    // this brings it into view.
    LaunchedEffect(showAll) {
        onShowAllChange(showAll)
        if (showAll) {
            withFrameNanos { }
            withFrameNanos { }
            scroll.animateScrollTo((moreAt - above).coerceAtLeast(0))
        } else {
            scroll.animateScrollTo(0)
        }
    }
    val size = remember(file.path, details) {
        if (isStream) null else formatSize(File(file.path).length())
    }
    val modified = remember(file.lastModified) {
        if (file.lastModified > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(file.lastModified))
        } else file.changed.ifEmpty { null }
    }
    val kind = remember(file.name) { FileKinds.extensionOf(file.name).uppercase() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobbloShapes.sheet)
            .background(colors.canvas)
            .verticalScroll(scroll)
            .padding(bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        KobbloSheetGrabber()
        Text(
            text = file.name,
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)
        )
        KobbloGroup {
            modified?.let { DetailRow(stringResource(if (isStream) R.string.viewer_pdf_detail_modified else R.string.media_detail_date), it) }
            if (kind.isNotEmpty()) DetailRow(stringResource(R.string.viewer_pdf_detail_kind), kind)
            size?.let { DetailRow(stringResource(R.string.viewer_pdf_detail_size), it) }
            details.dimensions?.let { DetailRow(stringResource(R.string.dimensions_label), it) }
            details.duration?.let { DetailRow(stringResource(R.string.media_detail_duration), it) }
            details.camera?.let { DetailRow(stringResource(R.string.viewer_group_camera), it) }
            details.exposure?.let { DetailRow(stringResource(if (file.type == "video") R.string.media_detail_video else R.string.viewer_group_exposure), it) }
            if (details.hasLocation) {
                KobbloRow(
                    title = stringResource(R.string.viewer_open_in_maps),
                    subtitle = listOfNotNull(details.place, MediaFormatting.coordinate(details.latitude!!, details.longitude!!)).joinToString("\n"),
                    trailing = { KobbloChevron() },
                    showDivider = true,
                    onClick = { openInMaps(context, details.latitude, details.longitude) }
                )
            }
            if (!isStream) {
                KobbloRow(
                    title = stringResource(R.string.viewer_show_in_folder),
                    subtitle = File(file.path).parent,
                    trailing = { KobbloChevron() },
                    showDivider = true,
                    onClick = onShowInFolder
                )
            }
            KobbloRow(
                title = stringResource(if (showAll) R.string.viewer_show_less else R.string.viewer_show_all),
                titleColor = colors.action,
                trailing = {
                    Icon(
                        imageVector = if (showAll) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.action,
                        modifier = Modifier.size(15.dp)
                    )
                },
                onClick = { showAll = !showAll }
            )
        }
        Spacer(Modifier.onPlaced { moreAt = it.positionInParent().y.toInt() })
        if (showAll) {
            details.groups.forEach { group ->
                KobbloGroupHeader(stringResource(group.title) + group.titleSuffix.orEmpty())
                KobbloGroup {
                    group.rows.forEach { (label, value) -> DetailRow(label, value) }
                }
            }
            if (details.groups.isNotEmpty()) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.viewer_copy_details),
                        titleColor = colors.action,
                        trailing = { Icon(AppIcons.Copy, contentDescription = null, tint = colors.action, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            val text = details.groups.joinToString("\n\n") { group ->
                                val title = context.getString(group.title) + group.titleSuffix.orEmpty()
                                title + "\n" + group.rows.joinToString("\n") { (label, value) -> context.getString(R.string.media_detail_copy_line, label, value) }
                            }
                            com.kobblo.gallery.file.explorer.viewer.PhotoActions.copyText(context, file.name, text)
                        }
                    )
                }
            }
        }
    }
}

private fun openInMaps(context: Context, latitude: Double, longitude: Double) {
    try {
        context.startActivity(com.kobblo.gallery.file.explorer.viewer.PhotoActions.mapsIntent(latitude, longitude))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.viewer_no_maps_app, Toast.LENGTH_SHORT).show()
    }
}
