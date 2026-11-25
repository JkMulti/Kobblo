package com.kobblo.gallery.file.explorer.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.Coil
import com.kobblo.gallery.file.explorer.data.LauncherLabel
import com.kobblo.gallery.file.explorer.tools.crash.CrashLog
import com.kobblo.gallery.file.explorer.vault.VaultManager
import com.kobblo.gallery.file.explorer.viewer.decoder.ViewerImages
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

// What has to be done as the app starts, and has nothing to do with what's on screen.

/** Puts right what earlier versions did differently. Most starts find nothing to do. */
internal fun tidyUpAfterEarlierVersions(context: Context) {
    val app = context.applicationContext
    thread {
        CrashLog.removeOldPublicCopies(app)
        LauncherLabel.resetIfChanged(app)
    }
}

/** Deletes decrypted Vault copies a viewer left behind, if the app last stopped without closing it. */
internal fun clearLeftoverVaultCopies(context: Context) {
    val app = context.applicationContext
    thread { VaultManager.clearLeftoverCopies(app) }
}

/** Makes ready the libraries that read PDFs and draw pictures. Either can fail without the rest of the app minding. */
internal fun ComponentActivity.prepareLibraries() {
    val app = applicationContext
    lifecycleScope.launch(Dispatchers.IO) {
        orLog("PDF files can't be read") { PDFBoxResourceLoader.init(app) }
    }
    orLog("Pictures can't be loaded") { Coil.setImageLoader { ViewerImages.buildLoader(app) } }
}

private inline fun orLog(trouble: String, work: () -> Unit) {
    try {
        work()
    } catch (e: Exception) {
        Log.e("MainActivity", trouble, e)
    }
}

/** Calls [then] each time the phone's screen goes off, for as long as the activity is there. */
internal fun ComponentActivity.whenScreenGoesOff(then: () -> Unit) {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) then()
        }
    }
    try {
        registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    } catch (_: Exception) {
        return
    }
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = unregisterReceiver(receiver)
    })
}
