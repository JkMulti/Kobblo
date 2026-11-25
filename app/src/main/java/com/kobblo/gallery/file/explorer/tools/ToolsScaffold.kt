package com.kobblo.gallery.file.explorer.tools

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kyant.shapes.RoundedRectangle

/** A sheet with the app's own title and grabber, scrolling when it outgrows the screen. */
@Composable
internal fun ToolSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    KobbloSheet(onDismissRequest = onDismiss) {
        ExpressiveSheetContent(
            title = title,
            modifier = Modifier.verticalScroll(rememberScrollState()),
            content = content
        )
    }
}

/** A row that reads like a context menu entry: the label first and the glyph at the far edge. */
@Composable
internal fun ToolMenuRow(
    title: String,
    icon: ImageVector? = null,
    showDivider: Boolean = false,
    tint: Color = KobbloDesign.colors.ink,
    enabled: Boolean = true,
    subtitle: String? = null,
    trailingText: String? = null,
    checked: Boolean = false,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    KobbloRow(
        title = title,
        subtitle = subtitle,
        titleColor = tint,
        enabled = enabled,
        trailing = {
            if (trailingText != null) {
                Text(trailingText, style = KobbloType.callout, color = colors.smoke, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
            if (checked) {
                Icon(com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Check, contentDescription = stringResource(R.string.tools_selected), tint = colors.action, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (enabled) tint else colors.pewter, modifier = Modifier.size(22.dp))
            }
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

/** One action of [ToolSelectionBar]. */
internal data class ToolBarAction(
    val icon: ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

private val BarRadius = 28.dp

/** How far the actions sit inside the bar, at its sides and its foot alike. */
private val BarInset = 8.dp
private val BarShape = RoundedRectangle(BarRadius)

/** The actions at either end sit in the bar's lower corners, so they round with them. */
private val BarActionShape = com.kobblo.gallery.file.explorer.ui.design.KobbloShapes.concentric(BarRadius, BarInset)

/** The same floating bar as selecting files, for a tool's own list. */
@Composable
internal fun ToolSelectionBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    actions: List<ToolBarAction>,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .kobbloGlass(null, BarShape)
            .padding(top = 12.dp, bottom = BarInset)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolBarTextButton(stringResource(R.string.select_all), onClick = onSelectAll)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = pluralStringResource(R.plurals.tools_selected_count, selectedCount, selectedCount),
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1
                )
            }
            ToolBarTextButton(stringResource(R.string.tools_done), emphasized = true, onClick = onDone)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = BarInset),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            actions.forEach { ToolBarActionItem(it) }
        }
    }
}

@Composable
private fun ToolBarTextButton(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(pressedScale = 0.94f, interactionSource = source)
            .clip(CapsuleShape)
            .quietClickable(source = source) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = KobbloType.body.copy(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
            color = colors.action,
            maxLines = 1
        )
    }
}

@Composable
private fun ToolBarActionItem(action: ToolBarAction) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    val source = remember { MutableInteractionSource() }
    val tint = if (action.danger) colors.danger else colors.action
    Column(
        modifier = Modifier
            .width(76.dp)
            .pressScale(pressedScale = 0.92f, interactionSource = source)
            .clip(BarActionShape)
            .quietClickable(source = source) {
                haptics.tap()
                action.onClick()
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(action.icon, contentDescription = action.label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(action.label, style = KobbloType.tab, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A short question with an answer on each side, as an inset sheet so it matches the other menus. */
@Composable
internal fun ToolConfirm(
    title: String,
    message: String?,
    confirmLabel: String,
    danger: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    ToolSheet(title = title, onDismiss = onDismiss) {
        if (message != null) {
            Text(
                text = message,
                style = KobbloType.callout,
                color = colors.smoke,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        if (secondaryLabel == null) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair(
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                onCancel = onDismiss,
                destructive = danger
            )
        } else {
            // Three answers don't fit side by side. Same order of weight, top to bottom.
            val rowHeight = com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight
            val fill = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(rowHeight)
            Button(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (danger) colors.danger else colors.action,
                    contentColor = colors.onAction
                ),
                modifier = fill
            ) {
                Text(confirmLabel, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSecondary,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.snow, contentColor = if (danger) colors.ink else colors.danger),
                modifier = fill
            ) {
                Text(secondaryLabel, style = KobbloType.headline)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.snow, contentColor = colors.ink),
                modifier = fill
            ) {
                Text(stringResource(R.string.cancel), style = KobbloType.headline)
            }
        }
    }
}

/** The whole-screen spinner the tools show before their first result. */
@Composable
internal fun ToolLoading(modifier: Modifier = Modifier, label: String? = null) {
    com.kobblo.gallery.file.explorer.ui.design.KobbloLoading(modifier = modifier, label = label)
}
