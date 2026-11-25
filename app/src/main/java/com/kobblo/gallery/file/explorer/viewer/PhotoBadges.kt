package com.kobblo.gallery.file.explorer.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.viewer.motion.PhotoExtras
import com.kyant.backdrop.Backdrop

/** How far below the top of the screen the viewer's top bar ends. */
private val BelowTopBar = 62.dp

/**
 * The quiet labels under the top bar, for what a photo can do beyond being looked at. They live with
 * the viewer's other chrome, because glass can't sample a layer it's drawn inside of.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoBadges(
    extras: PhotoExtras,
    shownPage: Int,
    backdrop: Backdrop,
    onPageChange: (Int) -> Unit,
    onPlayMotion: () -> Unit,
    onPanorama: () -> Unit,
    onSphere: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (extras.motion == null && !extras.isPanorama && extras.pageCount <= 1 && onSphere == null) return
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(start = 16.dp, top = BelowTopBar),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (extras.motion != null) {
            Badge(AppIcons.Motion, stringResource(R.string.viewer_motion), backdrop, onPlayMotion)
        }
        if (onSphere != null) {
            Badge(AppIcons.Globe, stringResource(R.string.viewer_sphere), backdrop, onSphere)
        } else if (extras.isPanorama) {
            Badge(AppIcons.Panorama, stringResource(R.string.viewer_panorama), backdrop, onPanorama)
        }
        if (extras.pageCount > 1) {
            PageStepper(shownPage, extras.pageCount, backdrop, onPageChange)
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, backdrop: Backdrop, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .height(32.dp)
            .kobbloGlass(backdrop, CapsuleShape)
            .quietClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        Text(label, style = KobbloType.caption.copy(fontWeight = FontWeight.Medium), color = colors.ink)
    }
}

/** Moves through a multi-page file inside its one spot in the pager. */
@Composable
private fun PageStepper(page: Int, count: Int, backdrop: Backdrop, onChange: (Int) -> Unit) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .height(32.dp)
            .kobbloGlass(backdrop, CapsuleShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepButton(AppIcons.Back, stringResource(R.string.viewer_page_previous), enabled = page > 0) { onChange(page - 1) }
        Text(
            text = stringResource(R.string.viewer_page_of, page + 1, count),
            style = KobbloType.caption.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = colors.ink
        )
        StepButton(AppIcons.ChevronRight, stringResource(R.string.viewer_page_next), enabled = page < count - 1) { onChange(page + 1) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .quietClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) colors.ink else colors.pewter, modifier = Modifier.size(14.dp))
    }
}
