package com.kobblo.gallery.file.explorer.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.fileops.FileJobSpecs
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.haptics.HapticsController
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.security.AwayTrips
import com.kobblo.gallery.file.explorer.ui.FilePickerDialog
import com.kobblo.gallery.file.explorer.ui.appGradientBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Vault: a wizard until one exists, a locked door until it's opened, and then its files. Leaving
 * the screen by any route locks it again, and so does the screen going away on its own.
 */
@Composable
fun VaultScreen(
    state: AppState,
    onAction: (AppAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vault = rememberVaultScreenState()
    val newFiles = rememberVaultFileAdder(vault, onAction)
    // Registered here, where it's always in the composition: a result can arrive whatever is showing.
    val otherApps = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val answer = result.data
        if (result.resultCode == Activity.RESULT_OK && answer != null) newFiles.copyIn(answer.pickedDocuments())
    }
    val context = LocalContext.current

    VaultSessionEffect(
        isUnlocked = vault.isOpen,
        viewingPath = state.viewingFile?.path,
        onCloseViewer = { onAction(AppAction.CloseFileViewer) }
    )

    val leave = {
        vault.lock()
        onClose()
    }
    // The pages that have somewhere of their own to go back to are composed after this, and so come first.
    BackHandler(onBack = leave)

    Box(
        modifier = modifier
            .fillMaxSize()
            .appGradientBackground()
    ) {
        when (vault.stage) {
            VaultStage.Setup -> VaultSetupWizard(onSetupCompleted = vault::open, onClose = onClose)
            VaultStage.Locked -> VaultUnlockScreen(
                onUnlocked = vault::open,
                onForgotPassword = vault::startRecovery,
                onClose = onClose
            )
            VaultStage.Recovering -> VaultRecoveryScreen(
                onRecovered = {
                    // Getting in with the recovery key ends a run of wrong passwords, as any way in does.
                    VaultAttempts.recordSuccess(context)
                    vault.open()
                },
                onBack = vault::leaveRecovery
            )
            VaultStage.Open -> {
                VaultAutoLock(onLock = vault::lock)
                VaultEncryptionGate(onClose = leave) { retryEncryption ->
                    VaultSecurityHost { onOpenSecurity, onAddRecoveryKey ->
                        VaultBrowserScreen(
                            isAddingFiles = vault.isAddingFiles,
                            filesVersion = vault.filesVersion,
                            onAddFiles = vault::chooseFiles,
                            onLock = vault::lock,
                            onClose = leave,
                            onOpenSecurity = onOpenSecurity,
                            onAddRecoveryKey = onAddRecoveryKey,
                            onRetryEncryption = retryEncryption,
                            onAction = onAction
                        )
                    }
                }
            }
        }

        if (vault.isChoosingFiles) {
            FilePickerDialog(
                title = stringResource(R.string.vault_add_files_title),
                confirmText = stringResource(R.string.vault_add_to_vault),
                cornerRoundness = state.cornerRoundness,
                onDismiss = vault::doneChoosing,
                onFilesSelected = { chosen ->
                    vault.doneChoosing()
                    newFiles.moveIn(chosen.map { it.path })
                },
                onOpenSystemPicker = { otherApps.pickDocuments(context) }
            )
        }
    }
}

/**
 * Sends chosen files into the vault as a job of their own, which the app's job queue runs, reports
 * on and can stop, while the screen shows it's busy. When the job ends it says how it went.
 */
@Stable
private class VaultFileAdder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haptics: HapticsController,
    private val vault: VaultScreenState,
    private val onAction: (AppAction) -> Unit
) {
    private val app = context.applicationContext

    /** Documents another app handed over. Copies of them go in; the documents stay where they are. */
    fun copyIn(documents: List<Uri>) = send(documents.size, leavesStorage = false) { shouldStop ->
        VaultManager.importFiles(app, documents, shouldStop)
    }

    /** Files picked from this phone's storage. They move, so the file browser has to look again after. */
    fun moveIn(paths: List<String>) = send(paths.size, leavesStorage = true) { shouldStop ->
        VaultManager.importFilesFromPaths(app, paths, shouldStop)
    }

    private fun send(count: Int, leavesStorage: Boolean, work: (shouldStop: () -> Boolean) -> Int) {
        if (!vault.startAdding(count)) return
        val job = FileJobSpecs.vaultImport(
            app,
            count = count,
            import = work,
            onFinished = { added ->
                scope.launch {
                    vault.finishAdding()
                    haptics.tap()
                    Toast.makeText(context, addedMessage(added), Toast.LENGTH_SHORT).show()
                    if (leavesStorage) onAction(AppAction.Reload)
                }
            }
        )
        FileJobs.enqueue(job)
    }

    /** "Added 0 items" isn't worth saying when nothing could be added. */
    private fun addedMessage(added: Int): String =
        if (added > 0) {
            context.resources.getQuantityString(R.plurals.safety_job_vault_added, added, added)
        } else {
            context.getString(R.string.safety_job_vault_problem)
        }
}

/** A screen that has just appeared knows only whether there's a vault to unlock or one to make. */
@Composable
private fun rememberVaultScreenState(): VaultScreenState {
    val context = LocalContext.current
    return remember { VaultScreenState(hasVault = VaultManager.isVaultSetup(context)) }
}

@Composable
private fun rememberVaultFileAdder(vault: VaultScreenState, onAction: (AppAction) -> Unit): VaultFileAdder {
    val currentOnAction by rememberUpdatedState(onAction)
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    // The screen's own scope, so a job that finishes after the screen has gone tells no one.
    val scope = rememberCoroutineScope()
    return remember(vault) { VaultFileAdder(context, scope, haptics, vault) { currentOnAction(it) } }
}

/** Everything a picker sent back: all of a multiple selection, or else the one document. */
private fun Intent.pickedDocuments(): List<Uri> {
    val several = clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri } }.orEmpty()
    return several.ifEmpty { listOfNotNull(data) }
}

/**
 * Asks another app for files of any kind: the system's document picker if the phone has one, and
 * otherwise whichever app offers to hand files over.
 */
private fun ActivityResultLauncher<Intent>.pickDocuments(context: Context) {
    fun anyFiles(action: String) = Intent(action).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    val requests = listOf<() -> Intent>(
        { anyFiles(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE) },
        { Intent.createChooser(anyFiles(Intent.ACTION_GET_CONTENT), context.getString(R.string.vault_select_files)) }
    )
    val opened = requests.any { request ->
        try {
            val intent = request()
            // A picker is a trip the vault expects its owner back from, not a reason to lock.
            AwayTrips.begin()
            launch(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.vault_no_picker_app), Toast.LENGTH_SHORT).show()
    }
}
