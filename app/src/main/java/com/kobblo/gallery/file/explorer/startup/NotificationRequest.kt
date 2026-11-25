package com.kobblo.gallery.file.explorer.startup

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kobblo.gallery.file.explorer.compat.NotificationAsk
import com.kobblo.gallery.file.explorer.fileops.FileJobs
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.web.WebShareController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Asks for notifications, once ever, the first time something is going on that one would be about. */
internal class NotificationRequest(private val activity: ComponentActivity) {

    private val dialog = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Something to show a notification about: a file job, being ready to receive, or sharing over the web. */
    private val underWay: Flow<Boolean>
        get() = combine(FileJobs.state, NearbyShareRepository.isReceiving, WebShareController.state) { jobs, receiving, web ->
            jobs.busy || receiving || web.running
        }

    /** Asks once there's a reason to, unless it's been settled before. */
    fun waitForAReason() {
        if (!NotificationAsk.isWanted(activity)) return
        activity.lifecycleScope.launch {
            // Only while a person is here to answer. Being off screen calls the waiting off, since the question
            // is only ever put once and mustn't be put to no one.
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { ask() }
        }
    }

    private suspend fun ask() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !NotificationAsk.isWanted(activity)) return
        // A job that's over within a second never needed a notification, so it isn't the moment.
        underWay.awaitLasting(millis = 1_000)
        NotificationAsk.rememberAsked(activity)
        dialog.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Waits until this is true, and still or again true [millis] later. */
internal suspend fun Flow<Boolean>.awaitLasting(millis: Long) {
    do {
        first { it }
        delay(millis)
    } while (!first())
}
