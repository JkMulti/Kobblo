package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.explorer.ExplorerUi
import com.kobblo.gallery.file.explorer.explorer.PathTools
import com.kobblo.gallery.file.explorer.explorer.drag.FileDragModifiers
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kyant.shapes.RoundedRectangle

/**
 * One step of a folder's path bar.
 *
 * @param opens the location tapping it goes to, or null for a step that leads nowhere.
 * @param path its path on the phone, when it has one: what holding it copies, and where files being
 * dragged can be dropped.
 * @param isCurrent the folder being shown, which reads as where you are rather than as a link.
 */
internal data class Breadcrumb(val label: String, val opens: String?, val path: String?, val isCurrent: Boolean)

/**
 * The steps from a storage's root down to the folder at [location], the storage itself first. On the
 * phone they are the parts of the path below [root]. Anywhere else they are the folders that were
 * opened to get here ([folderStack], as location and name) and then the folder itself, which has a
 * name but no location of its own to offer.
 */
internal fun breadcrumbs(
    location: String,
    root: String,
    storageName: String,
    folderStack: List<Pair<String, String>>,
    folderName: String?
): List<Breadcrumb> = buildList {
    add(Breadcrumb(storageName, opens = root, path = root.takeIf { it.startsWith("/") }, isCurrent = false))
    if (location.startsWith("/")) {
        val steps = PathTools.segments(location, root)
        steps.forEachIndexed { position, step ->
            add(Breadcrumb(step.name, opens = step.path, path = step.path, isCurrent = position == steps.lastIndex))
        }
    } else {
        folderStack.mapTo(this) { (opened, name) -> Breadcrumb(name, opens = opened, path = null, isCurrent = false) }
        if (folderName != null) add(Breadcrumb(folderName, opens = null, path = null, isCurrent = true))
    }
}

private val CrumbShape = RoundedRectangle(6.dp)

/**
 * A folder's path bar: every step down to it in one line that scrolls sideways. Tapping a step goes
 * there, and holding one that is on the phone copies its path, including the folder you're already in.
 */
@Composable
internal fun BrowseBreadcrumbs(crumbs: List<Breadcrumb>, location: String, onNavigate: (String) -> Unit) {
    val scroll = rememberScrollState()
    // The newest step is the one just opened, so it is kept in view as the path grows.
    LaunchedEffect(location, scroll.maxValue) { scroll.animateScrollTo(scroll.maxValue) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { position, crumb ->
            if (position > 0) {
                Icon(AppIcons.ChevronRight, contentDescription = null, tint = KobbloDesign.colors.pewter, modifier = Modifier.size(14.dp))
            }
            Crumb(crumb, onNavigate)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Crumb(crumb: Breadcrumb, onNavigate: (String) -> Unit) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val path = crumb.path
    val leadsSomewhere = crumb.opens != null && !crumb.isCurrent

    // While files are being carried, any folder above this one is somewhere to drop them.
    val drop = if (LocalSelectionActive.current && path != null && !crumb.isCurrent) {
        with(FileDragModifiers) { Modifier.folderTarget(path, crumb.label, colors.action, CrumbShape) }
    } else Modifier
    val touch = if (leadsSomewhere || path != null) {
        drop.clip(CrumbShape).combinedClickable(
            onClick = { if (leadsSomewhere) crumb.opens?.let(onNavigate) },
            onLongClick = if (path == null) null else {
                {
                    haptics.tap()
                    ExplorerUi.copyPath(context, path)
                }
            }
        )
    } else Modifier

    Text(
        text = crumb.label,
        style = KobbloType.footnote.copy(fontWeight = if (crumb.isCurrent) FontWeight.Medium else FontWeight.Normal),
        color = if (crumb.isCurrent) colors.smoke else colors.action,
        maxLines = 1,
        modifier = touch.padding(horizontal = 2.dp, vertical = 4.dp)
    )
}
