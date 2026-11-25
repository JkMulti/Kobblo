package com.kobblo.gallery.file.explorer.ui.expressive

import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppBrand
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.data.share.IncomingSharePrompt
import com.kobblo.gallery.file.explorer.data.share.SharedIncomingFile
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.ToolFolderPicker
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import java.io.File

/**
 * Shown when another app shares files to this one: what is coming in, where it will be saved, and the
 * choice to send it on to a nearby device instead. The folder starts as the prompt's own suggestion and
 * can be changed with a quick chip or the full folder picker. While a save is under way nothing here
 * can start another or close the sheet.
 */
@Composable
fun SaveToKobbloSheet(
    prompt: IncomingSharePrompt,
    appName: String = AppBrand.NAME,
    onSave: (destinationPath: String) -> Unit,
    onDismiss: () -> Unit,
    onSendNearby: (() -> Unit)? = null
) {
    val haptics = LocalHaptics.current
    val idle = !prompt.isSaving
    val incoming = prompt.files
    var destination by remember(prompt.selectedDestination) { mutableStateOf(prompt.selectedDestination) }
    var pickingFolder by remember { mutableStateOf(false) }
    val closeIfIdle = { if (idle) onDismiss() }

    if (pickingFolder) {
        ToolFolderPicker(
            title = stringResource(R.string.tools_choose_folder),
            confirmLabel = stringResource(R.string.settings_choose_folder),
            initialPath = destination,
            onDismiss = { pickingFolder = false },
            onPick = { picked ->
                destination = picked
                pickingFolder = false
            }
        )
    }

    KobbloSheet(onDismissRequest = closeIfIdle) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            KobbloSheetTitle(
                text = stringResource(R.string.screen_save_to, appName),
                subtitle = if (incoming.size == 1) null else {
                    pluralStringResource(R.plurals.transfer_incoming_summary, incoming.size, incoming.size, totalSizeText(incoming.sumOf { it.sizeBytes }))
                }
            )
            IncomingFiles(incoming)

            Spacer(Modifier.height(KobbloSpace.groupGap))
            DestinationRow(destination) {
                haptics.tap()
                pickingFolder = true
            }
            Spacer(Modifier.height(12.dp))
            QuickDestinations(destination) { path ->
                haptics.tap()
                destination = path
            }
            Spacer(Modifier.height(20.dp))

            if (onSendNearby != null) {
                SendNearbyButton(enabled = idle) {
                    haptics.tap()
                    onSendNearby()
                }
                Spacer(Modifier.height(12.dp))
            }
            KobbloActionPair(
                confirmLabel = stringResource(if (idle) R.string.tools_save_here else R.string.screen_saving),
                enabled = idle,
                onConfirm = {
                    haptics.tap()
                    onSave(destination)
                },
                onCancel = {
                    haptics.tap()
                    closeIfIdle()
                }
            )
        }
    }
}

/** A total for the sheet's subtitle. Senders don't always say how big a file is, and then there is none to give. */
internal fun totalSizeText(totalBytes: Long): String = if (totalBytes > 0) FileSizes.label(totalBytes) else "--"

/**
 * What a folder is called on its row. The root of the phone's storage has an unhelpful name of its
 * own ("0"), so it goes by [storageLabel], as does any path with no last part to show.
 */
internal fun destinationTitle(path: String, storageRoot: String, storageLabel: String): String =
    if (path == storageRoot) storageLabel else File(path).name.ifEmpty { storageLabel }

/** What's coming in: the one file with its size and type, or a short list that scrolls. */
@Composable
private fun IncomingFiles(files: List<SharedIncomingFile>) {
    val only = files.singleOrNull()
    KobbloGroup {
        if (only != null) {
            KobbloRow(
                title = only.name,
                subtitle = "${only.formattedSize} · ${only.mimeType ?: only.type}",
                leading = { KindGlyph(only.type, 26.dp) }
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp)) {
                itemsIndexed(files) { index, file ->
                    KobbloRow(
                        title = file.name,
                        leading = { KindGlyph(file.type, 24.dp) },
                        trailing = {
                            Text(file.formattedSize, style = KobbloType.footnote, color = KobbloDesign.colors.smoke, maxLines = 1)
                        },
                        showDivider = index < files.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun KindGlyph(type: String, size: Dp) {
    val glyph = when (type) {
        "image" -> AppIcons.Photo
        "video" -> AppIcons.PlayRect
        "audio" -> AppIcons.Music
        "doc" -> AppIcons.FileText
        "apk" -> AppIcons.Android
        "archive" -> AppIcons.FileZip
        else -> AppIcons.File
    }
    Icon(glyph, contentDescription = null, tint = ExpressiveTokens.categoryIconTint(type), modifier = Modifier.size(size))
}

/** Where it goes. Tapping the row opens the folder picker at that folder. */
@Composable
private fun DestinationRow(path: String, onChange: () -> Unit) {
    val title = destinationTitle(
        path = path,
        storageRoot = Environment.getExternalStorageDirectory().absolutePath,
        storageLabel = stringResource(R.string.internal_storage)
    )
    KobbloGroup {
        KobbloRow(
            title = title,
            subtitle = path,
            leading = {
                Icon(AppIcons.Folder, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp))
            },
            trailing = { KobbloChevron() },
            onClick = onChange
        )
    }
}

/** A folder people save to often enough to be one tap away. */
private class QuickDestination(@StringRes val label: Int, val path: String, val glyph: ImageVector)

private fun quickDestinations(): List<QuickDestination> {
    fun shared(kind: String) = Environment.getExternalStoragePublicDirectory(kind).absolutePath
    return listOf(
        QuickDestination(R.string.cat_downloads, shared(Environment.DIRECTORY_DOWNLOADS), AppIcons.Download),
        QuickDestination(R.string.cat_docs, shared(Environment.DIRECTORY_DOCUMENTS), AppIcons.FileText),
        QuickDestination(R.string.screen_pictures, shared(Environment.DIRECTORY_PICTURES), AppIcons.Photo),
        QuickDestination(R.string.screen_storage, Environment.getExternalStorageDirectory().absolutePath, AppIcons.SdCard)
    )
}

@Composable
private fun QuickDestinations(current: String, onPick: (String) -> Unit) {
    val places = remember { quickDestinations() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = KobbloSpace.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        places.forEach { place ->
            ExpressiveChip(
                label = stringResource(place.label),
                selected = current == place.path,
                onClick = { onPick(place.path) },
                icon = place.glyph,
                iconSize = 16.dp
            )
        }
    }
}

@Composable
private fun SendNearbyButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = colors.snow, contentColor = colors.action),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .height(KobbloActionHeight)
    ) {
        Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.transfer_send_nearby), style = KobbloType.headline)
    }
}
