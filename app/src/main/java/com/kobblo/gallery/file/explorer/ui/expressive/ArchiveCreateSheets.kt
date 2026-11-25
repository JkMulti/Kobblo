package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.archive.ArchiveCreateConfig
import com.kobblo.gallery.file.explorer.archive.ArchiveFormat
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.formatSize
import java.io.File

/**
 * Names a new archive and picks its format, with an optional password for zip, the one format that can have
 * one here. That's all it needs: a good default level beats a choice of five.
 */
@Composable
fun ArchiveCreateBottomSheet(
    selectedFiles: List<FileItem>,
    defaultName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (config: ArchiveCreateConfig) -> Unit
) {
    var archiveName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(defaultName) }
    var lockWithPassword by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var password by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var format by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ArchiveFormat.ZIP) }

    val totalBytes = remember(selectedFiles) { selectedFiles.sumOf { it.sizeBytes } }
    val haptics = LocalHaptics.current

    KobbloSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = KobbloSpace.lg)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle(
                text = stringResource(R.string.archive_create_title),
                // A folder's size isn't in the list, so a total would come up short.
                subtitle = if (selectedFiles.any { it.type == "folder" }) {
                    pluralStringResource(R.plurals.archive_create_count, selectedFiles.size, selectedFiles.size)
                } else {
                    pluralStringResource(R.plurals.archive_create_subtitle, selectedFiles.size, selectedFiles.size, formatSize(totalBytes))
                }
            )

            com.kobblo.gallery.file.explorer.ui.design.KobbloTextField(
                value = archiveName,
                onValueChange = { archiveName = it },
                placeholder = stringResource(R.string.archive_create_name),
                modifier = Modifier.padding(horizontal = KobbloSpace.screen)
            )
            Spacer(Modifier.height(KobbloSpace.md))

            com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl(
                options = ArchiveFormat.entries.map { it.label },
                selectedIndex = format.ordinal,
                onSelect = { index ->
                    haptics.tap()
                    format = ArchiveFormat.entries[index]
                    archiveName = format.nameFor(archiveName.trim())
                },
                modifier = Modifier.padding(horizontal = KobbloSpace.screen)
            )
            Spacer(Modifier.height(KobbloSpace.groupGap))

            if (format == ArchiveFormat.ZIP) com.kobblo.gallery.file.explorer.ui.design.KobbloGroup {
                com.kobblo.gallery.file.explorer.ui.design.KobbloRow(
                    title = stringResource(R.string.archive_create_lock),
                    subtitle = stringResource(R.string.archive_create_lock_desc),
                    trailing = {
                        ExpressiveSwitch(checked = lockWithPassword, onCheckedChange = { wanted ->
                            haptics.tap()
                            lockWithPassword = wanted
                        })
                    },
                    showDivider = lockWithPassword
                )
                if (lockWithPassword) {
                    com.kobblo.gallery.file.explorer.ui.design.KobbloTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = stringResource(R.string.archive_password_placeholder),
                        secret = true,
                        modifier = Modifier.padding(KobbloSpace.sm)
                    )
                }
            }
            Spacer(Modifier.height(KobbloSpace.lg))

            val locked = format == ArchiveFormat.ZIP && lockWithPassword
            val canCreate = archiveName.isNotBlank() && (!locked || password.isNotBlank())
            com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair(
                confirmLabel = stringResource(R.string.archive_create_action),
                enabled = canCreate,
                onConfirm = {
                    haptics.tap()
                    onConfirm(
                        ArchiveCreateConfig(
                            name = format.nameFor(archiveName.trim()),
                            password = if (locked) password else null,
                            format = format
                        )
                    )
                },
                onCancel = withTap(onDismissRequest)
            )
        }
    }
}

/** [action], with the tap felt first. Every button on these sheets and alerts answers that way. */
@Composable
private fun withTap(action: () -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    return {
        haptics.tap()
        action()
    }
}

/** Shown while archiving: what's being compressed, how far along, and the way out. */
@Composable
fun ArchiveProgressDialog(
    archiveName: String,
    currentFileName: String,
    percent: Int,
    onCancel: () -> Unit
) {
    val cancel = withTap(onCancel)
    GradientAlertDialog(
        // Nothing here but Cancel can stop it, so a stray tap or Back doesn't look like it did.
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.polish_compressing)) },
        text = {
            Column {
                Text(
                    text = archiveName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(KobbloSpace.sm))
                com.kobblo.gallery.file.explorer.ui.design.StorageMeter(fraction = percent / 100f)
                Spacer(modifier = Modifier.height(KobbloSpace.xs))
                Text(
                    text = if (currentFileName.isNotBlank()) "$percent% · $currentFileName" else "$percent%",
                    style = KobbloType.footnote,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = { KobbloAlertCancel(onClick = cancel) }
    )
}

/** Shown when an archive is ready. Open and Share are the two things people do next. */
@Composable
fun ArchiveCompletedDialog(
    archiveFile: File,
    onOpenArchive: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    val done = withTap(onClose)
    val share = withTap(onShare)
    val open = withTap(onOpenArchive)
    GradientAlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.polish_archive_created)) },
        text = {
            Text("${archiveFile.name} · ${formatSize(archiveFile.length())}")
        },
        dismissButton = { KobbloAlertCancel(label = stringResource(R.string.done_action), onClick = done) },
        confirmButton = {
            Row {
                KobbloAlertAction(label = stringResource(R.string.share), onClick = share)
                KobbloAlertAction(label = stringResource(R.string.open_action), onClick = open)
            }
        }
    )
}
