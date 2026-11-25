package com.kobblo.gallery.file.explorer.vault

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.openFileInExternalApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


/**
 * Ties the vault key to the screen: it goes when the vault locks and when the screen leaves
 * composition, along with any viewer still showing a decrypted copy.
 */
@Composable
internal fun VaultSessionEffect(isUnlocked: Boolean, viewingPath: String?, onCloseViewer: () -> Unit) {
    val context = LocalContext.current
    val currentViewingPath by rememberUpdatedState(viewingPath)
    val currentOnCloseViewer by rememberUpdatedState(onCloseViewer)

    fun endSession() {
        if (VaultManager.isDecryptedCopy(currentViewingPath)) currentOnCloseViewer()
        VaultManager.lock(context)
    }

    LaunchedEffect(isUnlocked) {
        if (!isUnlocked) endSession()
    }
    DisposableEffect(Unit) {
        onDispose { endSession() }
    }
}

/**
 * Locks after a while in the background. Short trips to a file picker or a share target stay open.
 * An alarm does the locking because nothing here can be relied on by then: a stopped screen draws no
 * frames, a cached process is frozen, and a killed one has left its copies behind. The check on
 * return tells the screen, and covers an alarm that's running late.
 */
@Composable
internal fun VaultAutoLock(onLock: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLock by rememberUpdatedState(onLock)

    DisposableEffect(lifecycleOwner) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val lock = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, VaultLockReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var stoppedAt = 0L
        var delay = 0L
        // Adding the observer replays ON_START, which also cancels an alarm a dead process left set.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    stoppedAt = SystemClock.elapsedRealtime()
                    // Lock Vault After in Settings, with a grace for a picker or share sheet the Vault opened.
                    delay = com.kobblo.gallery.file.explorer.security.AwayTrips.delayFor(
                        VaultLockAfter.minutes(context) * 60_000L,
                        com.kobblo.gallery.file.explorer.security.AwayTrips.startedJustBefore(stoppedAt)
                    )
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        stoppedAt + delay,
                        lock
                    )
                }
                Lifecycle.Event.ON_START -> {
                    alarms.cancel(lock)
                    if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= delay) {
                        currentOnLock()
                    }
                    stoppedAt = 0L
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            alarms.cancel(lock)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

/** Set by [VaultAutoLock]. Starts the process again if it has to, so leftover copies still go. */
class VaultLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                VaultManager.lock(context.applicationContext)
            } finally {
                result.finish()
            }
        }.start()
    }
}

/**
 * Stands in front of the vault while plaintext from an older version is encrypted. Everything else
 * can then assume nothing is moving underneath it. [content] gets a way to try again.
 */
@Composable
internal fun VaultEncryptionGate(onClose: () -> Unit, content: @Composable (retry: () -> Unit) -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var isEncrypting by remember { mutableStateOf(VaultManager.needsEncryption(context)) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        if (VaultManager.needsEncryption(context)) {
            isEncrypting = true
            withContext(Dispatchers.IO) {
                VaultManager.encryptPendingFiles(
                    context,
                    isCancelled = { !isActive },
                    onProgress = { filesDone, filesTotal ->
                        done = filesDone
                        total = filesTotal
                    }
                )
            }
            isEncrypting = false
        }
        val activity = context as? FragmentActivity
        if (activity != null && VaultBiometrics.shouldOfferToEnable(context)) {
            VaultBiometrics.offerToEnable(activity)
        }
    }

    if (isEncrypting) {
        EncryptingFilesPage(done = done, total = total, onClose = onClose)
    } else {
        content { attempt++ }
    }
}

@Composable
private fun EncryptingFilesPage(done: Int, total: Int, onClose: () -> Unit) {
    val colors = KobbloDesign.colors
    VaultFlowPage(
        icon = com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons.Lock,
        title = stringResource(R.string.vault_encrypting_title),
        subtitle = stringResource(R.string.vault_encrypting_subtitle),
        onBack = onClose,
        backDescription = stringResource(R.string.vault_close)
    ) {
        // Counting files would leave a single large one looking stuck at nothing.
        LinearProgressIndicator(
            color = colors.ink,
            trackColor = colors.mist,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(KobbloSpace.xs))
        Text(
            text = if (total == 0) {
                stringResource(R.string.vault_getting_ready)
            } else {
                stringResource(R.string.vault_encrypting_progress, minOf(done + 1, total), total)
            },
            style = KobbloType.callout,
            color = colors.smoke
        )
        Spacer(Modifier.height(KobbloSpace.xl))
        Text(
            text = stringResource(R.string.vault_encrypting_note),
            style = KobbloType.callout,
            color = colors.smoke,
            textAlign = TextAlign.Center
        )
    }
}

/** The vault's file actions, which all have to decrypt first. Large files can take a while. */
@Stable
internal class VaultFileActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onAction: (AppAction) -> Unit
) {
    var workingOn by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun open(file: FileItem) = decryptThen(context.getString(R.string.vault_opening_file, file.name), file) { copy ->
        when (file.type) {
            // Photos and videos open in the app's viewer, on their decrypted copy alone: the files beside it
            // are still sealed, so there's nothing to swipe to.
            "image", "video" -> {
                com.kobblo.gallery.file.explorer.viewer.ViewerQueue.files = listOf(copy)
                onAction(AppAction.ViewFile(copy))
            }
            // The music player would add it to the library and Recents, and an installer needs another app.
            "audio" -> {
                com.kobblo.gallery.file.explorer.security.AwayTrips.begin()
                openFileInExternalApp(context, copy, "audio/*")
            }
            "apk" -> {
                com.kobblo.gallery.file.explorer.security.AwayTrips.begin()
                openFileInExternalApp(context, copy, "application/vnd.android.package-archive")
            }
            else -> onAction(AppAction.ViewFile(copy))
        }
    }

    fun share(file: FileItem) = decryptThen(context.getString(R.string.vault_preparing_file, file.name), file) { copy ->
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(copy.path))
            // Whichever app is chosen may read this one copy, and nothing else of the vault's.
            val send = Intent(Intent.ACTION_SEND)
                .setType("*/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            com.kobblo.gallery.file.explorer.security.AwayTrips.begin()
            context.startActivity(Intent.createChooser(send, context.getString(R.string.vault_share_file, file.name)))
        } catch (e: Exception) {
            VaultManager.discardDecryptedCopy(copy.path)
            Toast.makeText(context, context.getString(R.string.vault_share_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    fun export(file: FileItem) = run(context.getString(R.string.vault_exporting_file, file.name)) { isCancelled ->
        val exported = VaultManager.exportFile(file, isCancelled)
        val message = if (exported) {
            context.getString(R.string.vault_exported_file, file.name)
        } else {
            context.getString(R.string.vault_export_failed)
        }
        return@run { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    fun cancel() {
        job?.cancel()
        workingOn = null
    }

    private fun decryptThen(label: String, file: FileItem, then: (FileItem) -> Unit) = run(label) { isCancelled ->
        val copy = VaultManager.openDecryptedCopy(context, file, isCancelled)
        return@run {
            if (copy != null) {
                then(copy)
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.vault_open_failed, file.name),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Does [work] off the main thread, then the step it returns back on it, unless cancelled by then. */
    private fun run(label: String, work: (isCancelled: () -> Boolean) -> () -> Unit) {
        if (workingOn != null) return
        workingOn = label
        job = scope.launch(Dispatchers.IO) {
            val finish = work { !isActive }
            withContext(Dispatchers.Main) {
                workingOn = null
                finish()
            }
        }
    }
}

@Composable
internal fun rememberVaultFileActions(onAction: (AppAction) -> Unit): VaultFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnAction by rememberUpdatedState(onAction)
    return remember { VaultFileActions(context, scope) { currentOnAction(it) } }
}

@Composable
internal fun VaultFileActionsOverlay(actions: VaultFileActions) {
    val label = actions.workingOn ?: return
    val colors = KobbloDesign.colors
    BackHandler(onBack = actions::cancel)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            // Holds touches back from the list underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = KobbloSpace.screen)
                .fillMaxWidth()
                .clip(KobbloShapes.xl)
                .background(colors.canvas)
                .padding(KobbloSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KobbloSpace.sm)
        ) {
            CircularProgressIndicator(color = colors.ink, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Text(
                text = label,
                // A long file name gets two lines and no more, so Cancel doesn't move as names change.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = KobbloType.callout,
                color = colors.ink
            )
            TextButton(onClick = actions::cancel) {
                Text(text = stringResource(R.string.cancel), style = KobbloType.headline, color = colors.action)
            }
        }
    }
}

internal fun vaultImportMessage(context: Context, count: Int): String = when {
    count > 0 -> context.resources.getQuantityString(R.plurals.vault_import_added, count, count)
    VaultManager.isUnlocked && !VaultManager.canEncrypt -> context.getString(R.string.vault_import_needs_password)
    !VaultManager.isUnlocked -> context.getString(R.string.vault_import_locked)
    else -> context.getString(R.string.vault_import_failed)
}
