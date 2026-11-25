package com.kobblo.gallery.file.explorer.startup

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kobblo.gallery.file.explorer.MainViewModel
import com.kobblo.gallery.file.explorer.R
import rikka.shizuku.Shizuku

/**
 * Shizuku, a separate app that can lend Kobblo the right to folders Android keeps from it. It comes and goes
 * while Kobblo runs, and the view model hears of it for as long as the activity is there.
 */
internal class ShizukuAccess(
    private val activity: ComponentActivity,
    private val model: () -> MainViewModel
) : DefaultLifecycleObserver {

    private val onAnswer = Shizuku.OnRequestPermissionResultListener { _, answer ->
        if (answer == PackageManager.PERMISSION_GRANTED) model().reload()
    }
    private val onArrival = Shizuku.OnBinderReceivedListener { model().refreshShizukuStatus() }
    private val onDeparture = Shizuku.OnBinderDeadListener { model().refreshShizukuStatus() }

    /** Starts listening. If Shizuku is already running, that counts as it arriving. */
    fun listen() {
        activity.lifecycle.addObserver(this)
        try {
            Shizuku.addRequestPermissionResultListener(onAnswer)
            Shizuku.addBinderReceivedListenerSticky(onArrival)
            Shizuku.addBinderDeadListener(onDeparture)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Shizuku.removeRequestPermissionResultListener(onAnswer)
        Shizuku.removeBinderReceivedListener(onArrival)
        Shizuku.removeBinderDeadListener(onDeparture)
    }

    /** Asks Shizuku for its permission, or opens it so it can be started, or offers it to be installed. */
    fun request() {
        try {
            when {
                !Shizuku.pingBinder() -> openOrOffer()
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> model().reload()
                else -> Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (_: Exception) {
            model().showToast(activity.getString(R.string.app_shizuku_not_running))
        }
    }

    private fun openOrOffer() {
        val shizuku = activity.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (shizuku != null) activity.startActivity(shizuku) else offer()
    }

    /** In a store app, or on the web if the phone has none. */
    private fun offer() {
        try {
            visit("market://details?id=$PACKAGE")
        } catch (_: Exception) {
            visit("https://play.google.com/store/apps/details?id=$PACKAGE")
        }
    }

    private fun visit(address: String) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"

        /** Shizuku wants a number. Every answer is dealt with the same way, so nothing looks at it. */
        const val REQUEST_CODE = 100
    }
}
