package com.kobblo.gallery.file.explorer.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.CleanerData
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.expressive.PageHeader

/** A size in bytes as people read it, such as "1.2 GB". */
fun formatSize(bytes: Long): String = FileSizes.label(bytes)

/**
 * The Storage Cleaner: how full the phone is, and what kinds of file fill it. Each kind leads to
 * the list of its files through [onNavigateToCategory], which is given the category's name. While a
 * scan is running the last figures stay up under a veil, so the screen doesn't empty and refill.
 */
@Composable
fun CleanerScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    snackbarHostState: SnackbarHostState,
    onNavigateToCategory: (String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val leave = { onAction(AppAction.SetCleanerScreenVisible(false)) }

    // Back steps out of an open category first, then out of the cleaner.
    BackHandler {
        if (state.currentCleanerCategory == null) leave() else onAction(AppAction.SetCleanerCategory(null))
    }

    Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        state.cleanerData?.let { scan ->
            Column(modifier = Modifier.kobbloReadableWidth()) {
                PageHeader(
                    title = stringResource(R.string.storage_cleaner_nav),
                    backLabel = stringResource(R.string.nav_browse),
                    onBack = leave
                )
                StorageOverview(
                    scan = scan,
                    clearMiniPlayer = state.currentAudioFile != null,
                    onOpenKind = { kind ->
                        haptics.cleaner()
                        onNavigateToCategory(kind.category)
                    },
                    onManage = context::openStorageSettings
                )
            }
        }
        if (state.cleanerLoading) ScanningVeil()
    }
}

/** Dims the screen and shows the spinner while the phone is being scanned. */
@Composable
private fun ScanningVeil() {
    Box(
        modifier = Modifier.fillMaxSize().background(KobbloDesign.colors.canvas.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        KobbloLoader()
    }
}

/**
 * The phone's own storage settings, where apps and caches can be cleared. A phone without that screen
 * gets the front page of its settings, and if not even that opens, the tap does nothing.
 */
private fun Context.openStorageSettings() {
    for (screen in listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS)) {
        if (runCatching { startActivity(Intent(screen)) }.isSuccess) return
    }
}

/**
 * One kind of file the cleaner adds up. [category] is the name the list of its files is opened by.
 * It's part of where the app says it is, not something anyone reads, so it's never translated.
 */
private class StorageKind(
    val category: String,
    @StringRes val label: Int,
    val bytes: Long,
    val color: Color,
    val icon: ImageVector
)

/** A scan's totals, in the order they're listed and drawn. */
private fun CleanerData.kinds(): List<StorageKind> = listOf(
    StorageKind("doc", R.string.cat_docs, documents.totalSizeBytes, KobbloTint.Blue, AppIcons.FileText),
    StorageKind("image", R.string.cat_images, images.totalSizeBytes, KobbloTint.Indigo, AppIcons.Photo),
    StorageKind("video", R.string.cat_videos, videos.totalSizeBytes, KobbloTint.Pink, AppIcons.PlayRect),
    StorageKind("audio", R.string.cat_audio, audio.totalSizeBytes, KobbloTint.Orange, AppIcons.Music),
    StorageKind("apk", R.string.cat_apps, apps.totalSizeBytes, KobbloTint.Green, AppIcons.Apps),
    StorageKind("archive", R.string.cat_archives, archives.totalSizeBytes, KobbloTint.Brown, AppIcons.Archive),
    StorageKind("download", R.string.cat_downloads, downloads.totalSizeBytes, KobbloTint.Teal, AppIcons.Download),
    StorageKind("large", R.string.cat_large, large.totalSizeBytes, KobbloTint.Gray, AppIcons.File),
    StorageKind("duplicates", R.string.view_cat_duplicates, duplicates.totalSizeBytes, KobbloTint.Purple, AppIcons.FolderOpen)
)

/** The smallest piece of the storage bar that can still be seen. */
private const val ThinnestShare = 0.02f

/**
 * How much of the storage bar a kind of file takes, where 1 is all of the space in use. A kind with
 * nothing in it takes none and isn't drawn. One with anything at all gets at least a sliver, however
 * small it is beside the rest.
 */
internal fun storageBarShare(bytes: Long, usedBytes: Long): Float {
    val share = (bytes.toFloat() / usedBytes.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
    return if (share > 0f) share.coerceAtLeast(ThinnestShare) else 0f
}

@Composable
private fun StorageOverview(
    scan: CleanerData,
    clearMiniPlayer: Boolean,
    onOpenKind: (StorageKind) -> Unit,
    onManage: () -> Unit
) {
    val kinds = remember(scan) { scan.kinds() }
    val belowTheList = KobbloSpace.xxl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        if (clearMiniPlayer) KobbloSpace.miniPlayerClearance else 0.dp

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = belowTheList)) {
        item { StorageInUse(scan, kinds) }
        item {
            KobbloGroupHeader(title = stringResource(R.string.storage_breakdown))
            KobbloGroup {
                kinds.forEachIndexed { index, kind ->
                    KobbloRow(
                        title = stringResource(kind.label),
                        leading = { KobbloIconBadge(kind.icon, kind.color) },
                        trailing = {
                            Text(
                                text = formatSize(kind.bytes),
                                style = KobbloType.callout,
                                color = KobbloDesign.colors.smoke,
                                maxLines = 1,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            KobbloChevron()
                        },
                        showDivider = index < kinds.lastIndex,
                        onClick = { onOpenKind(kind) }
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.view_manage_storage),
                    subtitle = stringResource(R.string.view_manage_storage_subtitle, formatSize(scan.freeStorageBytes)),
                    leading = { KobbloIconBadge(AppIcons.Gear, KobbloTint.Gray) },
                    trailing = { KobbloChevron() },
                    onClick = onManage
                )
            }
        }
    }
}

/** How much of the phone's storage is in use, in figures and as a bar split by kind of file. */
@Composable
private fun StorageInUse(scan: CleanerData, kinds: List<StorageKind>) {
    val colors = KobbloDesign.colors
    val usedBytes = (scan.totalStorageBytes - scan.freeStorageBytes).coerceAtLeast(0L)

    KobbloGroup {
        Column(modifier = Modifier.padding(KobbloSpace.inset)) {
            Text(stringResource(R.string.internal_storage), style = KobbloType.footnote, color = colors.smoke)
            Spacer(Modifier.height(KobbloSpace.xxs))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatSize(usedBytes), style = KobbloType.largeTitle, color = colors.ink)
                Spacer(Modifier.width(KobbloSpace.xs))
                Text(
                    text = stringResource(R.string.used_of_total, formatSize(scan.totalStorageBytes)),
                    style = KobbloType.callout,
                    color = colors.smoke,
                    // Lifts the smaller text onto the big figure's baseline.
                    modifier = Modifier.padding(bottom = KobbloSpace.xxs)
                )
            }
            Spacer(Modifier.height(KobbloSpace.sm))
            Row(modifier = Modifier.fillMaxWidth().height(12.dp).clip(CapsuleShape).background(colors.mist)) {
                kinds.forEachIndexed { index, kind ->
                    val share = storageBarShare(kind.bytes, usedBytes)
                    if (share > 0f) {
                        Box(modifier = Modifier.weight(share).fillMaxHeight().background(kind.color))
                        // A hairline of the track between one kind and the next.
                        if (index < kinds.lastIndex) Spacer(Modifier.width(1.dp))
                    }
                }
            }
        }
    }
}
