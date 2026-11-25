package com.kobblo.gallery.file.explorer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kobblo.gallery.file.explorer.compat.AppLanguage
import com.kobblo.gallery.file.explorer.gallery.widget.PhotoWidget
import com.kobblo.gallery.file.explorer.startup.AppContent
import com.kobblo.gallery.file.explorer.startup.CrashGuard
import com.kobblo.gallery.file.explorer.startup.IncomingIntents
import com.kobblo.gallery.file.explorer.startup.NotificationRequest
import com.kobblo.gallery.file.explorer.startup.ShizukuAccess
import com.kobblo.gallery.file.explorer.startup.StorageAccessFlow
import com.kobblo.gallery.file.explorer.startup.WelcomeStage
import com.kobblo.gallery.file.explorer.startup.clearLeftoverVaultCopies
import com.kobblo.gallery.file.explorer.startup.prepareLibraries
import com.kobblo.gallery.file.explorer.startup.tidyUpAfterEarlierVersions
import com.kobblo.gallery.file.explorer.startup.whenScreenGoesOff
import com.kobblo.gallery.file.explorer.tools.crash.CrashReportScreen
import com.kobblo.gallery.file.explorer.tools.picker.PickerTheme

/**
 * Kobblo's main screen, and where other apps, shortcuts and notifications come in. What it does is mostly to
 * put its parts together in the right order. A FragmentActivity, for the lock's fingerprint prompt.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // These two register for an answer from the system, which has to happen before the activity starts and in
    // the same order each time.
    private val storage = StorageAccessFlow(this) { viewModel }
    private val notifications = NotificationRequest(this)

    private val shizuku = ShizukuAccess(this) { viewModel }
    private val incoming = IncomingIntents(this) { viewModel }
    private val welcome = WelcomeStage(this)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        if (CrashGuard.startsOver(intent)) {
            super.onCreate(savedInstanceState)
            showCrashReport()
            return
        }
        CrashGuard.watch(this)
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Rebuilding the activity isn't another launch, and what its intent asked for has been done already.
        val isFreshLaunch = savedInstanceState == null
        startUp(isFreshLaunch)
        splash.stayWhileLoading()
        showApp()
        if (isFreshLaunch) incoming.handle(intent)
        notifications.waitForAReason()
    }

    private fun showCrashReport() {
        setContent {
            PickerTheme { CrashReportScreen(onClose = ::finish) }
        }
    }

    private fun startUp(isFreshLaunch: Boolean) {
        tidyUpAfterEarlierVersions(this)
        whenScreenGoesOff { viewModel.onPhoneLocked() }
        if (isFreshLaunch) viewModel.onAppLaunch()
        PhotoWidget.watch(this)
        clearLeftoverVaultCopies(this)
        drawBehindSystemBars()
        welcome.openIfNew()
        storage.onCreate()
        prepareLibraries()
        shizuku.listen()
    }

    private fun drawBehindSystemBars() {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Screens draw their own background there, and a scrim over it would show as a band.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    /** Goes straight from the splash to a filled screen, unless filling it takes too long to wait for. */
    private fun SplashScreen.stayWhileLoading() {
        val latest = SystemClock.elapsedRealtime() + LONGEST_SPLASH_MS
        setKeepOnScreenCondition { viewModel.state.value.isLoading && SystemClock.elapsedRealtime() < latest }
    }

    private fun showApp() {
        val actions = ActionDispatcher(viewModel, storage, shizuku, lifecycleScope, onLanguageChanged = ::recreate)
        setContent {
            AppContent(this, viewModel, storage, welcome, onAction = actions)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.handle(intent)
    }

    override fun onResume() {
        super.onResume()
        storage.onResume(welcomeOpen = welcome.isOpen)
    }

    private companion object {
        const val LONGEST_SPLASH_MS = 1_200L
    }
}
