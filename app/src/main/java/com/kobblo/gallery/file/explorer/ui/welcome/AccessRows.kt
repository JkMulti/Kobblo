package com.kobblo.gallery.file.explorer.ui.welcome

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.compat.NotificationAsk
import com.kobblo.gallery.file.explorer.compat.PhotoLocations
import com.kobblo.gallery.file.explorer.compat.StorageAccess
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** What the app may do right now. Read again whenever the screen comes back from the system's settings. */
@Immutable
internal data class Access(val files: Boolean, val notifications: Boolean, val locations: Boolean) {
    companion object {
        fun of(context: Context) = Access(
            files = StorageAccess.has(context),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            locations = PhotoLocations.allowed(context)
        )
    }
}

/** [Access] as it stands, kept current as the person goes to the system's settings and back. */
@Composable
internal fun rememberAccess(): Pair<Access, () -> Unit> {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) version++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val access = remember(version) { Access.of(context) }
    return access to { version++ }
}

/**
 * The permissions, as switches: files, which everything needs, and the optional ones below it. A
 * switch the app can't turn off itself, or a permission refused for good, opens the system's page
 * for it instead, and says so.
 */
@Composable
internal fun AccessGroups(access: Access, onRefresh: () -> Unit, onRequestFiles: () -> Unit, inWelcome: Boolean = true) {
    val context = LocalContext.current
    val activity = context as? Activity
    // Refused twice, Android answers at once without asking, so the row says where to go instead.
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var locationsBlocked by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onRefresh()
        notificationsBlocked = !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
    }
    val askLocations = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onRefresh()
        locationsBlocked = !granted && activity?.shouldShowRequestPermissionRationale(PhotoLocations.Permission) == false
    }

    KobbloGroupHeader(stringResource(R.string.access_needed))
    KobbloGroup {
        AccessRow(
            icon = AppIcons.FolderOpen,
            tint = KobbloTint.Blue,
            title = stringResource(R.string.access_files),
            subtitle = stringResource(R.string.access_files_body),
            on = access.files,
            // Android has no way for an app to give this back itself, so off is the system's page.
            onTurnOff = { context.openAllFilesSettings() },
            onTurnOn = onRequestFiles
        )
    }

    KobbloGroupHeader(stringResource(R.string.access_optional))
    KobbloGroup {
        AccessRow(
            icon = AppIcons.Bell,
            tint = KobbloTint.Red,
            title = stringResource(R.string.access_notifications),
            subtitle = stringResource(
                if (notificationsBlocked && !access.notifications) R.string.access_notifications_blocked else R.string.access_notifications_body
            ),
            on = access.notifications,
            showDivider = PhotoLocations.isAsked(),
            onTurnOff = { context.openNotificationSettings() },
            onTurnOn = {
                NotificationAsk.rememberAsked(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsBlocked) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.openNotificationSettings()
                }
            }
        )
        if (PhotoLocations.isAsked()) {
            AccessRow(
                icon = AppIcons.Location,
                tint = KobbloTint.Green,
                title = stringResource(R.string.access_locations),
                subtitle = stringResource(
                    if (locationsBlocked && !access.locations) R.string.access_locations_blocked else R.string.access_locations_body
                ),
                on = access.locations,
                onTurnOff = { context.openAppDetails() },
                onTurnOn = {
                    if (locationsBlocked) context.openAppDetails() else askLocations.launch(PhotoLocations.Permission)
                }
            )
        }
    }
    // Settings is where this is said to be, so it says so only in the welcome.
    if (inWelcome) KobbloGroupFooter(stringResource(R.string.access_footer))
}

@Composable
private fun AccessRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    on: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit,
    showDivider: Boolean = false
) {
    val haptics = LocalHaptics.current
    val toggle = {
        haptics.tap()
        if (on) onTurnOff() else onTurnOn()
    }
    KobbloRow(
        title = title,
        subtitle = subtitle,
        leading = { KobbloIconBadge(icon = icon, color = tint) },
        trailing = { ExpressiveSwitch(checked = on, onCheckedChange = { toggle() }) },
        showDivider = showDivider,
        onClick = toggle
    )
}

private fun Context.openAllFilesSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.isSuccess
        if (opened) return
    }
    openAppDetails()
}

private fun Context.openNotificationSettings() {
    val opened = runCatching {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }.isSuccess
    if (!opened) openAppDetails()
}

private fun Context.openAppDetails() {
    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
}
