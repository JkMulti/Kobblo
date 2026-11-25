package com.kobblo.gallery.file.explorer.ui.expressive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

// What a folder's page says when it has no list to show: it couldn't be read, the system keeps it
// closed, or there is simply nothing in it.

/**
 * A folder that couldn't be listed, with the storage's own account of why. Trying again is always
 * offered. When the trouble is that Google Drive wants its permission renewed, [onRenewAccess] is given
 * and becomes the main button, with trying again beside it.
 */
@Composable
internal fun FolderErrorMessage(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRenewAccess: (() -> Unit)? = null
) {
    KobbloMessage(
        icon = AppIcons.CloudOff,
        title = stringResource(R.string.failed_to_load_files),
        body = reason,
        modifier = modifier,
        action = {
            if (onRenewAccess == null) {
                MessageButton(stringResource(R.string.retry), onRetry)
            } else {
                ButtonPair(
                    quiet = stringResource(R.string.retry),
                    onQuiet = onRetry,
                    main = stringResource(R.string.grant_permission),
                    onMain = onRenewAccess,
                    gap = 12.dp
                )
            }
        }
    )
}

/** A folder with nothing in it, or nothing that matches what was typed into the search field. */
@Composable
internal fun EmptyFolderMessage(query: String, action: (@Composable () -> Unit)? = null) {
    val searched = query.isNotEmpty()
    KobbloMessage(
        icon = if (searched) AppIcons.Search else AppIcons.Folder,
        title = if (searched) stringResource(R.string.no_files_found_query, query) else stringResource(R.string.this_folder_empty),
        action = action
    )
}

/** Two choices under a message: the quiet one as text, then the main one as a button. */
@Composable
private fun ButtonPair(
    quiet: String,
    onQuiet: () -> Unit,
    main: String,
    onMain: () -> Unit,
    gap: Dp,
    oneLine: Boolean = false
) {
    val lines = if (oneLine) 1 else Int.MAX_VALUE
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onQuiet) {
            Text(quiet, color = KobbloDesign.colors.action, maxLines = lines)
        }
        Button(onClick = onMain) {
            Text(main, fontWeight = FontWeight.SemiBold, maxLines = lines)
        }
    }
}

// ── Folders the system keeps closed ───────────────────────────────────────────

private const val ShizukuPackage = "moe.shizuku.privileged.api"

/** How often to look again for Shizuku while the message is up. */
private const val ShizukuCheckMillis = 2000L

/** How far along the way to reading a closed folder the phone is. */
private enum class ShizukuStage { NotInstalled, NotRunning, Running }

/**
 * Shown for the folders under Android/data and Android/obb, which the system no longer lets a file
 * manager read. Shizuku can open them, so this says where things stand with it and offers the next
 * step: install it, open it, or allow this app to use it. It keeps looking while it is on screen, and
 * reloads the folder the moment access is there.
 */
@Composable
internal fun RestrictedFolderMessage(
    location: String,
    onAction: (AppAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var running by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val installed = remember(context) { context.hasPackage(ShizukuPackage) }
    LaunchedEffect(location) {
        while (true) {
            running = shizukuAnswers()
            if (running && shizukuAllowsThisApp()) {
                onAction(AppAction.Reload)
                break
            }
            delay(ShizukuCheckMillis)
        }
    }
    val stage = when {
        running -> ShizukuStage.Running
        installed -> ShizukuStage.NotRunning
        else -> ShizukuStage.NotInstalled
    }
    val standing = stringResource(
        when (stage) {
            ShizukuStage.Running -> R.string.shizuku_running_desc
            ShizukuStage.NotRunning -> R.string.shizuku_not_running_desc
            ShizukuStage.NotInstalled -> R.string.shizuku_not_installed_desc
        }
    )
    KobbloMessage(
        icon = AppIcons.Lock,
        title = stringResource(R.string.access_restricted_title),
        body = stringResource(R.string.access_restricted_desc) + "\n\n" + standing,
        modifier = modifier,
        action = {
            val checkAgain = stringResource(R.string.check_again_action)
            val reload = { onAction(AppAction.Reload) }
            when (stage) {
                ShizukuStage.Running -> Button(onClick = { onAction(AppAction.RequestShizukuAccess) }) {
                    Text(stringResource(R.string.grant_access_action), fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                ShizukuStage.NotRunning -> ButtonPair(
                    quiet = checkAgain,
                    onQuiet = reload,
                    main = stringResource(R.string.open_shizuku_action),
                    onMain = { context.openShizuku() },
                    gap = 8.dp,
                    oneLine = true
                )
                ShizukuStage.NotInstalled -> ButtonPair(
                    quiet = checkAgain,
                    onQuiet = reload,
                    main = stringResource(R.string.install_shizuku_action),
                    onMain = { context.openShizukuInStore() },
                    gap = 8.dp,
                    oneLine = true
                )
            }
        }
    )
}

private fun Context.hasPackage(name: String): Boolean = try {
    packageManager.getPackageInfo(name, 0) != null
} catch (e: Exception) {
    false
}

/** Whether Shizuku's service is up. Asking can throw when it isn't, which is the same answer. */
private fun shizukuAnswers(): Boolean = try {
    Shizuku.pingBinder()
} catch (e: Exception) {
    false
}

private fun shizukuAllowsThisApp(): Boolean = try {
    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
} catch (e: Exception) {
    false
}

private fun Context.openShizuku() {
    packageManager.getLaunchIntentForPackage(ShizukuPackage)?.let { startActivity(it) }
}

/** Opens Shizuku's page in the store app, or in a browser on a phone that has no store app. */
private fun Context.openShizukuInStore() {
    fun viewing(address: String) = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(viewing("market://details?id=$ShizukuPackage"))
    } catch (e: Exception) {
        startActivity(viewing("https://play.google.com/store/apps/details?id=$ShizukuPackage"))
    }
}
