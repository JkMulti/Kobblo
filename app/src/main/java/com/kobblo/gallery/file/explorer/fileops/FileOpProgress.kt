package com.kobblo.gallery.file.explorer.fileops

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import kotlinx.coroutines.delay
import java.text.NumberFormat

/**
 * The bar over everything while files are being dealt with, for the jobs with no pill of their own. Nothing shows
 * for what's over in a moment.
 */
@Composable
fun FileOpProgressHost() {
    val jobs by FileJobs.state.collectAsState()
    val current = jobs.running?.takeIf { it.surface == JobSurface.BAR }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(current != null) {
        if (current != null) {
            delay(SHOW_AFTER_MILLIS)
            show = true
        } else {
            show = false
        }
    }
    AnimatedVisibility(
        visible = show && current != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        val shown = current ?: return@AnimatedVisibility
        val colors = KobbloDesign.colors
        val numbers = remember { NumberFormat.getIntegerInstance() }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = KobbloSpace.screen, vertical = 96.dp)
                    .fillMaxWidth()
                    .kobbloGlass(null, KobbloShapes.md)
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = shown.title,
                        style = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!shown.stopping) {
                        KobbloIconButton(
                            icon = AppIcons.Close,
                            description = stringResource(R.string.cancel),
                            onClick = { FileJobs.cancel(shown.id) },
                            size = 36.dp,
                            iconSize = 16.dp,
                            tint = colors.action,
                            background = Color.Transparent
                        )
                    }
                }
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    StorageMeter(fraction = shown.fraction ?: 0f)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when {
                            shown.stopping -> stringResource(R.string.safety_job_stopping)
                            shown.total > 0 -> stringResource(R.string.safety_progress_count, numbers.format(shown.done), numbers.format(shown.total))
                            else -> stringResource(R.string.percentage, ((shown.fraction ?: 0f) * 100).toInt())
                        } + if (jobs.waiting > 0) " · " + pluralStringResource(R.plurals.safety_job_more_waiting, jobs.waiting, jobs.waiting) else "",
                        style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
                        color = colors.smoke
                    )
                }
            }
        }
    }
}

/**
 * Says what a job killed partway did, once, on the next launch. It stays until it's been read, since a kill is
 * exactly when nobody was looking.
 */
@Composable
fun JobNoticeHost(onOpenFolder: (String) -> Unit) {
    val notices by FileJobs.notices.collectAsState()
    val notice = notices.firstOrNull() ?: return
    val colors = KobbloDesign.colors
    GradientAlertDialog(
        onDismissRequest = { FileJobs.dismissNotice(notice.id) },
        title = { Text(notice.title, style = KobbloType.headline, color = colors.ink) },
        text = { Text(notice.body, style = KobbloType.body, color = colors.smoke) },
        dismissButton = notice.folder?.let { folder ->
            {
                KobbloAlertCancel(
                    onClick = {
                        FileJobs.dismissNotice(notice.id)
                        onOpenFolder(folder)
                    },
                    label = stringResource(R.string.safety_notice_open_folder)
                )
            }
        },
        confirmButton = {
            KobbloAlertAction(
                label = stringResource(R.string.archive_failed_dismiss),
                onClick = { FileJobs.dismissNotice(notice.id) }
            )
        }
    )
}

private const val SHOW_AFTER_MILLIS = 400L
