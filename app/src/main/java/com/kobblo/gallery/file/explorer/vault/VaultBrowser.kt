package com.kobblo.gallery.file.explorer.vault

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.data.core.FileSizes
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoading
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveChip
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.KobbloFileRow
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The open vault: what's in it, newest first, with a way to narrow it to one kind of file, and
 * everything that can be done to a file from here. [filesVersion] changing means files have arrived
 * and the vault should be read again; while [isAddingFiles] the list stands aside for them.
 */
@Composable
internal fun VaultBrowserScreen(
    isAddingFiles: Boolean,
    filesVersion: Int,
    onAddFiles: () -> Unit,
    onLock: () -> Unit,
    onClose: () -> Unit,
    onOpenSecurity: () -> Unit,
    onAddRecoveryKey: () -> Unit,
    onRetryEncryption: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val browser = remember { VaultBrowserState() }
    val fileActions = rememberVaultFileActions(onAction)
    val context = LocalContext.current
    val hasRecoveryKey = remember { VaultManager.hasRecoveryKey(context) }
    val notice = remember(browser.files) { VaultManager.currentNotice(context) }

    // Reading the vault decrypts every file's name, so it's done off the main thread.
    val scope = rememberCoroutineScope()
    suspend fun readVault() {
        browser.show(withContext(Dispatchers.IO) { VaultManager.getVaultFiles(context) })
    }
    fun readVaultAgain() {
        scope.launch(Dispatchers.Main) { readVault() }
    }
    LaunchedEffect(filesVersion) { readVault() }

    fun say(words: String) {
        Toast.makeText(context, words, Toast.LENGTH_SHORT).show()
    }
    val haptics = LocalHaptics.current
    fun open(file: FileItem) {
        haptics.tap()
        fileActions.open(file)
    }
    fun addFiles() {
        haptics.tap()
        onAddFiles()
    }
    fun restore(file: FileItem) {
        scope.launch(Dispatchers.IO) {
            val isBack = VaultManager.restoreFile(context, file)
            withContext(Dispatchers.Main) {
                if (isBack) {
                    readVaultAgain()
                    // The file browser behind the vault has a file it didn't have a moment ago.
                    onAction(AppAction.Reload)
                    say(context.getString(R.string.vault_restored_to_internal, file.name))
                } else {
                    say(context.getString(R.string.vault_restore_failed, file.name))
                }
            }
        }
    }
    fun delete(file: FileItem) {
        VaultManager.deleteFile(context, file)
        readVaultAgain()
        say(context.getString(R.string.vault_deleted))
    }

    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val totalSize = remember(browser.files) { FileSizes.label(browser.totalBytes) }

    Column(
        modifier = Modifier
            .kobbloReadableWidth()
            .nestedScroll(largeTitle.connection)
    ) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.vault_title),
            subtitle = pluralStringResource(R.plurals.vault_files_summary, browser.files.size, browser.files.size, totalSize),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = onClose,
            largeTitle = largeTitle,
            rightAction = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KobbloIconButton(
                        icon = AppIcons.Add,
                        description = stringResource(R.string.vault_add_files),
                        onClick = ::addFiles
                    )
                    VaultSecurityButton(onClick = onOpenSecurity)
                    KobbloIconButton(
                        icon = AppIcons.Lock,
                        description = stringResource(R.string.vault_lock),
                        onClick = {
                            haptics.tap()
                            onLock()
                        }
                    )
                }
            }
        )

        when {
            isAddingFiles -> KobbloLoading(label = stringResource(R.string.vault_adding_files))
            browser.isLoading -> KobbloLoading(label = stringResource(R.string.vault_opening_vault))
            else -> VaultFileList(
                browser = browser,
                // What the vault still needs from its owner comes before what's in it.
                banners = {
                    if (!hasRecoveryKey) {
                        item { AddRecoveryKeyPrompt(onClick = onAddRecoveryKey, modifier = BannerInsets) }
                    }
                    if (notice != null) {
                        item {
                            VaultNoticeRow(
                                notice = notice,
                                onEnterPassword = onLock,
                                onRetryEncryption = onRetryEncryption,
                                onChangePassword = onOpenSecurity,
                                modifier = BannerInsets
                            )
                        }
                    }
                },
                onAddFiles = ::addFiles,
                onOpen = ::open,
                onMenu = { file ->
                    haptics.tap()
                    browser.openMenu(file)
                }
            )
        }
    }

    browser.menuFile?.let { file ->
        VaultFileMenu(
            file = file,
            onDismiss = browser::closeMenu,
            onChoose = { choice ->
                // The sheet closes first, whatever was chosen.
                browser.closeMenu()
                when (choice) {
                    VaultFileChoice.Open -> fileActions.open(file)
                    VaultFileChoice.Restore -> restore(file)
                    VaultFileChoice.Export -> fileActions.export(file)
                    VaultFileChoice.Share -> fileActions.share(file)
                    VaultFileChoice.Delete -> browser.askToDelete(file)
                }
            }
        )
    }

    browser.fileToDelete?.let { file ->
        ExpressiveConfirmationSheet(
            title = stringResource(R.string.vault_delete_title, file.name),
            subtitle = stringResource(R.string.vault_delete_body),
            onConfirm = {
                browser.doneDeleting()
                delete(file)
            },
            onDismissRequest = browser::doneDeleting
        )
    }

    // A file being decrypted to open, share or export, with a way to stop.
    VaultFileActionsOverlay(fileActions)
}

private val BannerInsets = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)

/**
 * The scrolling part of the open vault: [banners], then either an invitation to add the first file
 * or the kinds to choose from and the files of the chosen one.
 */
@Composable
private fun VaultFileList(
    browser: VaultBrowserState,
    banners: LazyListScope.() -> Unit,
    onAddFiles: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onMenu: (FileItem) -> Unit
) {
    // Worked out again only when the vault's contents or the chosen kind change, not on every scroll.
    val files = remember(browser.files, browser.filter) { browser.shownFiles }
    val offered = remember(browser.files) { browser.filters }
    // Room at the end for the mini player, which floats over the list while music plays.
    val clearance = KobbloSpace.miniPlayerClearance + 28.dp +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = clearance)) {
        banners()
        when {
            browser.files.isEmpty() -> item { EmptyVaultMessage(onAddFiles) }
            else -> {
                item {
                    VaultFilterChips(offered = offered, chosen = browser.filter, onChoose = browser::choose)
                }
                if (files.isEmpty()) {
                    item { NoFilesOfKindMessage(onShowAll = { browser.choose(VaultFilter.All) }) }
                } else {
                    itemsIndexed(files, key = { _, file -> file.path }) { position, file ->
                        KobbloFileRow(
                            // Everything in here is marked locked, and is past the password already.
                            file = remember(file) { file.copy(isLocked = false) },
                            isSelected = false,
                            groupIndex = position,
                            groupCount = files.size,
                            onSelect = { onOpen(file) },
                            onClick = { onOpen(file) },
                            onMenuClick = { onMenu(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultMessage(onAddFiles: () -> Unit) {
    KobbloMessage(
        icon = AppIcons.Shield,
        title = stringResource(R.string.vault_empty_title),
        body = stringResource(R.string.vault_empty_body),
        action = {
            Button(onClick = onAddFiles) {
                Text(text = stringResource(R.string.vault_add_files), fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

/** Shown when the last file of the chosen kind has just been restored or deleted. */
@Composable
private fun NoFilesOfKindMessage(onShowAll: () -> Unit) {
    KobbloMessage(
        icon = AppIcons.Grid,
        title = stringResource(R.string.vault_no_kind_title),
        action = {
            TextButton(onClick = onShowAll) {
                Text(stringResource(R.string.vault_show_all_files), color = KobbloDesign.colors.action)
            }
        }
    )
}

@Composable
private fun VaultFilterChips(offered: List<VaultFilter>, chosen: VaultFilter, onChoose: (VaultFilter) -> Unit) {
    val haptics = LocalHaptics.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp)
    ) {
        items(offered, key = { it.name }) { kind ->
            ExpressiveChip(
                label = stringResource(kind.label),
                selected = kind == chosen,
                onClick = {
                    haptics.tap()
                    onChoose(kind)
                }
            )
        }
    }
}

/** What a file's menu offers, in the order it's listed. */
private enum class VaultFileChoice(val icon: ImageVector, @StringRes val label: Int) {
    Open(AppIcons.File, R.string.open_action),

    /** Back to where it was moved in from, and out of the vault. */
    Restore(AppIcons.Restore, R.string.vault_restore_to_internal),

    /** A plain copy in Downloads. The vault keeps its own. */
    Export(AppIcons.Download, R.string.vault_export_to_downloads),
    Share(AppIcons.Share, R.string.share),
    Delete(AppIcons.Trash, R.string.delete_permanently_action)
}

/** What can be done with one file, with deleting it kept apart from the rest and in the danger color. */
@Composable
private fun VaultFileMenu(file: FileItem, onDismiss: () -> Unit, onChoose: (VaultFileChoice) -> Unit) {
    val colors = KobbloDesign.colors
    val harmless = VaultFileChoice.entries - VaultFileChoice.Delete

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            KobbloSheetTitle(file.name, subtitle = "${file.size} · ${formatFileDate(file)}")
            KobbloGroup {
                for (choice in harmless) {
                    VaultMenuRow(choice, tint = colors.ink, showDivider = choice != harmless.last(), onChoose = onChoose)
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                VaultMenuRow(VaultFileChoice.Delete, tint = colors.danger, showDivider = false, onChoose = onChoose)
            }
        }
    }
}

/** A menu row as elsewhere in the app: the label first and the glyph at the far edge. */
@Composable
private fun VaultMenuRow(
    choice: VaultFileChoice,
    tint: Color,
    showDivider: Boolean,
    onChoose: (VaultFileChoice) -> Unit
) {
    KobbloRow(
        title = stringResource(choice.label),
        titleColor = tint,
        trailing = { Icon(choice.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = { onChoose(choice) }
    )
}
