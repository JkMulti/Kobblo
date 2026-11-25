package com.kobblo.gallery.file.explorer.startup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.MainViewModel
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.compat.StorageAccess
import com.kobblo.gallery.file.explorer.compat.VersionRules
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel

/**
 * Access to files: telling the view model whether there is any, and getting it. Made along with the activity,
 * because the system's answer has to find its way back even if the activity was rebuilt while it was asking.
 */
internal class StorageAccessFlow(private val activity: ComponentActivity, private val model: () -> MainViewModel) {

    private val asking = StorageAsking(Build.VERSION.SDK_INT)

    private val systemDialog = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model().updateStorageAccessState(StorageAccess.has(activity))
        model().reload()
    }

    val alertShown: Boolean get() = asking.alertShown

    fun onCreate() {
        val hasAccess = StorageAccess.has(activity)
        asking.onStart(hasAccess)
        model().updateStorageAccessState(hasAccess)
        Log.i(
            TAG,
            "Starting on Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, access to files: $hasAccess"
        )
    }

    fun onResume(welcomeOpen: Boolean) {
        val hasAccess = StorageAccess.has(activity)
        // The welcome has a step of its own for this.
        val step = asking.onReturn(hasAccess, mayAsk = !welcomeOpen && !activity.isFinishing)
        model().updateStorageAccessState(hasAccess)
        if (step.reload) model().reload()
        if (step.askSystem) askSystem()
    }

    /** Someone asked for access, from a screen that can't show anything without it. */
    fun request() {
        when (StorageAccess.route(activity)) {
            VersionRules.StorageRoute.SystemDialog -> askSystem()
            VersionRules.StorageRoute.AllFilesSettings, VersionRules.StorageRoute.AppSettings -> openSettings()
        }
    }

    /** Leaving the welcome without access is an answer, so the alert doesn't ask the same thing straight after. */
    fun onWelcomeFinished() {
        if (!StorageAccess.has(activity)) asking.onTurnedDown()
    }

    fun onAlertTurnedDown() = asking.onTurnedDown()

    fun onAlertAgreed() {
        asking.onAgreed()
        openSettings()
    }

    private fun askSystem() {
        asking.onSystemAsked()
        StorageAccess.rememberAsked(activity)
        systemDialog.launch(StorageAccess.RuntimePermissions)
    }

    /** Some phones lack the first pages, so it's the best one that opens, or directions there in words. */
    private fun openSettings() {
        if (settingsPages().none(::opens)) {
            Toast.makeText(activity, R.string.permission_settings_fallback_toast, Toast.LENGTH_LONG).show()
        }
    }

    private fun settingsPages(): List<Intent> {
        val thisApp = Uri.parse("package:${activity.packageName}")
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, thisApp))
                add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, thisApp))
        }
    }

    private fun opens(page: Intent): Boolean = try {
        activity.startActivity(page)
        true
    } catch (e: Exception) {
        Log.w(TAG, "This phone has no ${page.action}", e)
        false
    }

    private companion object {
        const val TAG = "KobbloStorage"
    }
}

/** Says what the access is for and leads to the page that grants it, for anyone who hasn't turned it down. */
@Composable
internal fun StorageAccessAlert(storage: StorageAccessFlow) {
    if (!storage.alertShown) return
    GradientAlertDialog(
        onDismissRequest = storage::onAlertTurnedDown,
        title = { Text(stringResource(R.string.permission_dialog_title)) },
        text = { Text(stringResource(R.string.permission_dialog_message)) },
        confirmButton = { KobbloAlertAction(stringResource(R.string.permission_open_settings), storage::onAlertAgreed) },
        dismissButton = { KobbloAlertCancel(storage::onAlertTurnedDown, stringResource(R.string.permission_not_now)) }
    )
}
