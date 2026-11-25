package com.kobblo.gallery.file.explorer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * The choices for one selected APK, in a card above the install button: the ways it can be installed,
 * with a lock on any that can't be used and a line underneath saying what's in the way.
 */
@Composable
fun SingleApkInstallPopup(
    appName: String,
    versionText: String,
    modes: InstallModesResult,
    onNormalInstall: () -> Unit,
    onDowngradeInstall: () -> Unit,
    onSilentInstall: () -> Unit,
    onFooterClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    cornerRoundness: Float
) {
    InstallCard(heading = "$appName · $versionText", modifier = modifier) {
        InstallOption(InstallMode.NORMAL, usable = modes.normal.usable, onClick = onNormalInstall)
        if (modes.downgrade.offered) {
            InstallOption(InstallMode.DOWNGRADE, usable = modes.downgrade.usable, onClick = onDowngradeInstall)
        }
        if (modes.silent.offered) {
            InstallOption(InstallMode.SILENT, usable = modes.silent.usable, onClick = onSilentInstall)
        }
        modes.notice?.let { InstallNotice(it.message, onClick = onFooterClick) }
    }
}

/**
 * The choices for several selected APKs. Each way of installing says how many of them it applies to,
 * since a batch can mix apps that would be downgraded with apps that wouldn't.
 */
@Composable
fun BatchApkInstallPopup(
    selectedCount: Int,
    normalPillText: String,
    normalPillIsCoral: Boolean,
    downgradePillText: String,
    downgradePillIsCoral: Boolean,
    silentPillText: String,
    silentPillIsCoral: Boolean,
    isShizukuConnected: Boolean,
    onNormalClick: () -> Unit,
    onDowngradeClick: () -> Unit,
    onSilentClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRoundness: Float
) {
    InstallCard(
        heading = pluralStringResource(R.plurals.view_apk_install_apps, selectedCount, selectedCount),
        modifier = modifier
    ) {
        InstallOption(InstallMode.NORMAL, usable = true, onClick = onNormalClick, tally = Tally(normalPillText, normalPillIsCoral))
        InstallOption(
            mode = InstallMode.DOWNGRADE,
            usable = isShizukuConnected,
            onClick = onDowngradeClick,
            tally = Tally(downgradePillText, downgradePillIsCoral)
        )
        InstallOption(
            mode = InstallMode.SILENT,
            usable = isShizukuConnected,
            onClick = onSilentClick,
            tally = Tally(silentPillText, silentPillIsCoral)
        )
        if (!isShizukuConnected) InstallNotice(InstallBlock.NoShizuku.message, onClick = null)
    }
}

/** The floating card both popups are: a heading, then rows with a hairline above each. */
@Composable
private fun InstallCard(heading: String, modifier: Modifier, rows: @Composable ColumnScope.() -> Unit) {
    val colors = KobbloDesign.colors
    Column(
        modifier = modifier
            .width(300.dp)
            .ambientShadow(KobbloShapes.lg, colors.ambient)
            .clip(KobbloShapes.lg)
            .background(colors.snow)
    ) {
        Text(
            text = heading,
            style = KobbloType.footnote,
            color = colors.smoke,
            modifier = Modifier.padding(start = KobbloSpace.md, top = KobbloSpace.sm, end = KobbloSpace.md, bottom = KobbloSpace.xs)
        )
        rows()
    }
}

@Composable
private fun CardHairline() {
    HorizontalDivider(thickness = 0.5.dp, color = KobbloDesign.colors.hairline)
}

/** How many of a batch a way of installing applies to. [short] draws it in red, for a count that leaves some out. */
private data class Tally(val text: String, val short: Boolean)

private val InstallMode.icon: ImageVector
    get() = when (this) {
        InstallMode.NORMAL -> AppIcons.ArrowDown
        InstallMode.DOWNGRADE -> AppIcons.DoubleChevronDown
        InstallMode.SILENT -> AppIcons.Bolt
    }

/** One way of installing. When it can't be used it greys out, stops answering taps and shows a lock. */
@Composable
private fun InstallOption(mode: InstallMode, usable: Boolean, onClick: () -> Unit, tally: Tally? = null) {
    val colors = KobbloDesign.colors
    CardHairline()
    KobbloRow(
        title = stringResource(mode.title),
        subtitle = stringResource(mode.explanation),
        enabled = usable,
        leading = { KobbloIconBadge(mode.icon, if (usable) colors.action else colors.pewter) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tally != null) {
                    Text(tally.text, style = KobbloType.footnote, color = if (tally.short) colors.danger else colors.smoke)
                }
                if (!usable) {
                    Icon(
                        imageVector = AppIcons.Lock,
                        contentDescription = stringResource(R.string.locked_badge),
                        tint = colors.pewter,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        onClick = onClick
    )
}

/** The line at the foot of a card, behind a red dot. With [onClick] it can be tapped to do something about it. */
@Composable
private fun InstallNotice(@StringRes message: Int, onClick: (() -> Unit)?) {
    val colors = KobbloDesign.colors
    CardHairline()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = KobbloSpace.md, vertical = KobbloSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).background(colors.danger, CapsuleShape))
        Text(stringResource(message), style = KobbloType.footnote, color = colors.danger)
    }
}

/**
 * The last look before a batch goes ahead: which apps, each with the version change it makes, and the
 * way they'll be installed in the heading.
 */
@Composable
fun BatchInstallSheet(
    fileCount: Int,
    selectedMode: InstallMode,
    apks: List<BatchApkItem>,
    isShizukuConnected: Boolean,
    onRemove: (BatchApkItem) -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRoundness: Float
) {
    KobbloSheet(onDismissRequest = onCancel, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(
                text = stringResource(selectedMode.sheetTitle),
                subtitle = pluralStringResource(R.plurals.view_file_count, fileCount, fileCount)
            )
            // The list gives way to the buttons under it: it takes what's left of the sheet and scrolls.
            KobbloGroup(modifier = Modifier.weight(1f, fill = false)) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(apks) { apk -> BatchApkRow(apk, onRemove = { onRemove(apk) }) }
                }
            }
            if (isShizukuConnected) {
                Text(
                    text = stringResource(R.string.view_shizuku_connected),
                    style = KobbloType.footnote,
                    color = KobbloDesign.colors.smoke,
                    modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = KobbloSpace.xs)
                )
            }
            Spacer(modifier = Modifier.height(KobbloSpace.sm))
            KobbloActionPair(
                confirmLabel = stringResource(R.string.view_apk_install_count, fileCount),
                onConfirm = onInstall,
                onCancel = onCancel
            )
        }
    }
}

@Composable
private fun BatchApkRow(apk: BatchApkItem, onRemove: () -> Unit) {
    KobbloRow(
        title = apk.name,
        subtitle = apk.summary,
        leading = { KobbloIconBadge(AppIcons.Android, KobbloTint.Green) },
        trailing = {
            KobbloIconButton(
                icon = AppIcons.Close,
                description = stringResource(R.string.view_remove),
                onClick = onRemove,
                size = 32.dp,
                iconSize = 16.dp,
                tint = KobbloDesign.colors.smoke,
                background = Color.Transparent
            )
        },
        showDivider = true
    )
}
