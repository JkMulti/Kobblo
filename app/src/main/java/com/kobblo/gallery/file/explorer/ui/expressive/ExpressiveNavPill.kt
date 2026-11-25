package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

enum class ExpressiveTab(val id: String, val icon: ImageVector, val activeIcon: ImageVector) {
    GALLERY("gallery", AppIcons.Home, AppIcons.HomeFill),
    RECENTS("home", AppIcons.Clock, AppIcons.ClockFill),
    BROWSE("browse", AppIcons.Folder, AppIcons.FolderFill),
    SEND("send", AppIcons.Send, AppIcons.SendFill),
    SETTINGS("settings", AppIcons.Gear, AppIcons.GearFill)
}

/** The floating glass tab bar. Becomes the selection bar while items are selected. */
@Composable
fun ExpressiveNavShell(
    activeTab: ExpressiveTab,
    onTabSelected: (ExpressiveTab) -> Unit,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    onSelectNone: (() -> Unit)? = null,
    onBatchRename: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onToggleHide: (() -> Unit)? = null,
    isAllHidden: Boolean = false,
    backdrop: Backdrop? = null
) {
    if (!isSelectionMode) {
        // The Vault isn't a tab: it opens from Browse's Tools, where hiding it hides it.
        KobbloTabBar(
            tabs = ExpressiveTab.entries,
            active = activeTab,
            onTabSelected = onTabSelected,
            backdrop = backdrop,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        return
    }
    // What every selection can do goes first, then what only some screens offer. A choice a screen
    // didn't supply is still on the bar, and does nothing.
    ExpressiveSelectionBar(
        selectedCount = selectedCount, onClearSelection = onClearSelection,
        onShare = onShare, onCopy = onCopy, onCut = onMove, onDelete = onDelete,
        onSelectAll = onSelectAll ?: NoAction,
        onSelectNone = onSelectNone ?: NoAction,
        onBatchRename = onBatchRename ?: NoAction,
        onArchive = onArchive, onToggleHide = onToggleHide, isAllHidden = isAllHidden,
        modifier = modifier,
        backdrop = backdrop
    )
}

private val NoAction: () -> Unit = {}

/**
 * The active capsule travels between tabs rather than cross-fading, and squeezes slightly in
 * flight. On arrival the tab's glyph fills in and gives one small hop.
 */
@Composable
private fun KobbloTabBar(
    tabs: List<ExpressiveTab>,
    active: ExpressiveTab,
    onTabSelected: (ExpressiveTab) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val activeIndex = tabs.indexOf(active).coerceAtLeast(0)

    val position = remember { Animatable(activeIndex.toFloat()) }
    LaunchedEffect(activeIndex) {
        if (reduced) position.snapTo(activeIndex.toFloat())
        else position.animateTo(activeIndex.toFloat(), spring(dampingRatio = 0.78f, stiffness = 420f))
    }

    Row(
        modifier = modifier
            .height(62.dp)
            .kobbloGlass(backdrop, CapsuleShape)
            .padding(4.dp)
            .drawWithContent {
                val slot = size.width / tabs.size
                val travel = position.value - floor(position.value)
                val squeeze = sin(PI.toFloat() * travel) * 3.dp.toPx()
                val capsule = Size(slot, size.height - squeeze * 2)
                val x = slot * position.value
                translate(if (layoutDirection == LayoutDirection.Ltr) x else size.width - slot - x, squeeze) {
                    drawOutline(
                        outline = CapsuleShape.createOutline(capsule, layoutDirection, this),
                        color = colors.ink.copy(alpha = if (colors.isDark) 0.14f else 0.07f)
                    )
                }
                drawContent()
            }
    ) {
        tabs.forEach { tab ->
            val label = when (tab) {
                ExpressiveTab.GALLERY -> stringResource(R.string.nav_home)
                ExpressiveTab.RECENTS -> stringResource(R.string.nav_recents)
                ExpressiveTab.SEND -> stringResource(R.string.nav_send)
                ExpressiveTab.SETTINGS -> stringResource(R.string.settings_nav)
                ExpressiveTab.BROWSE -> stringResource(R.string.nav_browse)
            }
            val isActive = tab == active
            val progress by animateFloatAsState(
                targetValue = if (isActive) 1f else 0f,
                animationSpec = if (reduced) snap() else KobbloMotion.state(),
                label = "tab_fill"
            )
            val tint = androidx.compose.ui.graphics.lerp(colors.ink, colors.action, progress)
            val source = remember { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(pressedScale = 0.9f, interactionSource = source)
                    .clickable(interactionSource = source, indication = null) {
                        com.kobblo.gallery.file.explorer.ui.TabSwitchLatencyTracker.onTabTap(tab.name)
                        onTabSelected(tab)
                    }
                    .semantics {
                        role = Role.Tab
                        selected = isActive
                        contentDescription = label
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TabGlyph(tab, tint, fill = progress)
                Spacer(Modifier.height(1.dp))
                // The bar and its slots are a fixed size, so with large text or on a narrow screen the
                // label shrinks to fit rather than losing its last letters.
                Text(
                    text = label,
                    style = KobbloType.tab,
                    color = tint,
                    maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                        minFontSize = 8.sp,
                        maxFontSize = KobbloType.tab.fontSize,
                        stepSize = 0.5.sp
                    )
                )
            }
        }
    }
}

/**
 * A tab's glyph: the outline fades into the filled one as [fill] goes from 0 to 1, and the pair
 * gives one small hop on the way, largest at the halfway point.
 */
@Composable
private fun TabGlyph(tab: ExpressiveTab, tint: Color, fill: Float) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .graphicsLayer {
                val hop = sin(PI.toFloat() * fill) * 0.14f
                scaleX = 1f + hop
                scaleY = 1f + hop
            }
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint.copy(alpha = 1f - fill), modifier = Modifier.size(25.dp))
        Icon(tab.activeIcon, contentDescription = null, tint = tint.copy(alpha = fill), modifier = Modifier.size(25.dp))
    }
}
