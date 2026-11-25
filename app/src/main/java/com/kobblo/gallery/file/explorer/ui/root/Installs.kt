package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.BatchApkInstallPopup
import com.kobblo.gallery.file.explorer.ui.BatchInstallSheet
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.InstallMode
import com.kobblo.gallery.file.explorer.ui.ParsedApkItem
import com.kobblo.gallery.file.explorer.ui.SingleApkInstallPopup
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.evaluateInstallModes
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether something is being done about one selected app or about several. */
internal enum class AppCount { One, Several }

/** The apps' packages among [files]. Installing is only offered when there's nothing else among them. */
internal fun packagesIn(files: List<FileItem>): List<FileItem> =
    files.filter { file -> PackageEndings.any { file.name.endsWith(it, ignoreCase = true) } }

private val PackageEndings = listOf(".apk", ".apks")

/** Which step of installing the selected apps is on screen. */
@Stable
internal class InstallDesk {
    /** The card of ways to install, over the selection bar. */
    var card by mutableStateOf<AppCount?>(null)

    /** A batch about to be installed this way, with the list of it still to be agreed to. */
    var confirming by mutableStateOf<InstallMode?>(null)

    /** An older version is about to go over a newer one, with what that does still to be agreed to. */
    var warning by mutableStateOf<AppCount?>(null)

    /** What was read of a batch, or null while it's being read. */
    var batch by mutableStateOf<List<ParsedApkItem>?>(null)

    /** Opens the card for [apps], or puts it away if it's the one that's open. */
    fun toggleCard(apps: List<FileItem>) {
        val wanted = if (apps.size == 1) AppCount.One else AppCount.Several
        card = wanted.takeIf { it != card }
    }
}

private const val CardMillis = 120
private const val CardSmallest = 0.9f
private val CardCorner = TransformOrigin(1f, 1f)

/** Where a card sits, a little above the bar and in from its end. */
private val CardPlace = Modifier.padding(bottom = 8.dp, end = 16.dp)

/** A card growing out of the button that opened it, at its bottom corner, and back into it. */
@Composable
private fun InstallCard(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(CardMillis), CardSmallest, CardCorner) + fadeIn(tween(CardMillis)),
        exit = scaleOut(tween(CardMillis), CardSmallest, CardCorner) + fadeOut(tween(CardMillis))
    ) { content() }
}

/** Stands in for a card while what goes on it is being read. */
@Composable
private fun ReadingApk(modifier: Modifier) {
    Box(modifier.size(56.dp), contentAlignment = Alignment.Center) { KobbloLoader(color = KobbloDesign.colors.action) }
}

/**
 * The cards over the selection bar for installing the selected [apps], and what follows from them. While
 * installing isn't [offered] they stay out of the way, and come back as they were when it is.
 */
@Composable
internal fun InstallCards(desk: InstallDesk, apps: List<FileItem>, offered: Boolean, state: AppState, onAction: (AppAction) -> Unit) {
    BatchReader(desk, apps)
    InstallCard(visible = offered && apps.size == 1 && desk.card == AppCount.One) {
        apps.firstOrNull()?.let { OneAppCard(it, desk, state, onAction) }
    }
    InstallCard(visible = offered && apps.size > 1 && desk.card == AppCount.Several) {
        SeveralAppsCard(desk, state)
    }
    DowngradeWarnings(desk, apps, onAction)
    desk.confirming?.let { BatchSheet(it, desk, state, onAction) }
}

/** Reads the batch while its card is open, and again if the selection changes under it. */
@Composable
private fun BatchReader(desk: InstallDesk, apps: List<FileItem>) {
    val context = LocalContext.current
    val open = desk.card == AppCount.Several
    LaunchedEffect(apps, open) {
        if (!open || apps.size < 2) return@LaunchedEffect
        desk.batch = null
        desk.batch = withContext(Dispatchers.IO) {
            apps.map { batchEntry(it, context.packageManager.readApk(it), context.getString(R.string.view_unknown)) }
        }
    }
}

@Composable
private fun OneAppCard(app: FileItem, desk: InstallDesk, state: AppState, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var reading by remember { mutableStateOf<ApkReading?>(null) }
    var isRead by remember { mutableStateOf(false) }
    LaunchedEffect(app) {
        isRead = false
        reading = withContext(Dispatchers.IO) { context.packageManager.readApk(app) }
        isRead = true
    }
    if (!isRead) {
        ReadingApk(Modifier.padding(bottom = 8.dp))
        return
    }

    val unknown = stringResource(R.string.view_unknown)
    fun choose(then: () -> Unit): () -> Unit = {
        desk.card = null
        then()
    }
    SingleApkInstallPopup(
        appName = reading?.label ?: app.name,
        versionText = versionLine(reading, unknown, stringResource(R.string.view_unknown_version)),
        modes = evaluateInstallModes(candidate(reading, unknown), installedCopy(reading, unknown), state.hasShizuku),
        onNormalInstall = choose { FileOpener(context, state, focus, onAction).open(app) },
        onDowngradeInstall = choose { desk.warning = AppCount.One },
        onSilentInstall = choose { onAction(AppAction.SilentInstallApk(app.path, downgrade = false)) },
        modifier = CardPlace,
        cornerRoundness = state.cornerRoundness
    )
}

@Composable
private fun SeveralAppsCard(desk: InstallDesk, state: AppState) {
    val batch = desk.batch
    if (batch == null) {
        ReadingApk(CardPlace)
        return
    }
    val older = batch.count { it.isDowngrade }
    val newer = batch.size - older
    val newerText = pluralStringResource(R.plurals.view_app_count, newer, newer)
    fun confirm(mode: InstallMode): () -> Unit = {
        desk.card = null
        desk.confirming = mode
    }
    BatchApkInstallPopup(
        selectedCount = batch.size,
        normalPillText = newerText, normalPillIsCoral = older > 0,
        downgradePillText = pluralStringResource(R.plurals.view_app_count, older, older), downgradePillIsCoral = older == 0,
        silentPillText = newerText, silentPillIsCoral = older > 0,
        isShizukuConnected = state.hasShizuku,
        onNormalClick = confirm(InstallMode.NORMAL),
        onDowngradeClick = confirm(InstallMode.DOWNGRADE),
        onSilentClick = confirm(InstallMode.SILENT),
        modifier = CardPlace,
        cornerRoundness = state.cornerRoundness
    )
}

/** Asks whether to erase the apps' data on the way back to an older version, or to try to keep it. */
@Composable
private fun DowngradeWarnings(desk: InstallDesk, apps: List<FileItem>, onAction: (AppAction) -> Unit) {
    when (desk.warning) {
        AppCount.One -> DowngradeWarning(
            title = stringResource(R.string.view_apk_downgrade_one_title),
            body = stringResource(R.string.view_apk_downgrade_one_body),
            desk = desk,
            onDowngrade = { erase ->
                onAction(AppAction.SilentInstallApk(apps.first().path, downgrade = true, forceUninstall = erase))
            }
        )
        AppCount.Several -> DowngradeWarning(
            title = stringResource(R.string.view_apk_downgrade_many_title),
            body = stringResource(R.string.view_apk_downgrade_many_body),
            desk = desk,
            onDowngrade = { erase ->
                val older = desk.batch.orEmpty().installableBy(InstallMode.DOWNGRADE)
                onAction(AppAction.BatchInstallApks(older.map { it.file.path }, downgrade = true, silent = true, forceUninstall = erase))
            }
        )
        null -> Unit
    }
}

@Composable
private fun DowngradeWarning(title: String, body: String, desk: InstallDesk, onDowngrade: (erase: Boolean) -> Unit) {
    fun answer(erase: Boolean): () -> Unit = {
        desk.warning = null
        onDowngrade(erase)
    }
    GradientAlertDialog(
        onDismissRequest = { desk.warning = null },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            val label = stringResource(R.string.view_apk_erase_and_downgrade)
            Answer(label, ExpressiveTheme.colors.danger, strong = true, onClick = answer(erase = true))
        },
        dismissButton = {
            Answer(stringResource(R.string.view_apk_try_keeping_data), ExpressiveTheme.colors.muted, onClick = answer(erase = false))
        }
    )
}

/** The batch to be installed, to look over first. */
@Composable
private fun BatchSheet(mode: InstallMode, desk: InstallDesk, state: AppState, onAction: (AppAction) -> Unit) {
    val batch = desk.batch.orEmpty().installableBy(mode)
    BatchInstallSheet(
        fileCount = batch.size,
        selectedMode = mode,
        apks = batch.map { it.asRow() },
        isShizukuConnected = state.hasShizuku,
        cornerRoundness = state.cornerRoundness,
        onRemove = {},
        onCancel = { desk.confirming = null },
        onInstall = {
            desk.confirming = null
            if (mode == InstallMode.DOWNGRADE) {
                desk.warning = AppCount.Several
            } else {
                onAction(AppAction.BatchInstallApks(batch.map { it.file.path }, downgrade = false, silent = mode == InstallMode.SILENT))
            }
        }
    )
}
