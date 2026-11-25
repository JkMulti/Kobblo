package com.kobblo.gallery.file.explorer.startup

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.ui.welcome.Entrance
import com.kobblo.gallery.file.explorer.ui.welcome.WelcomeExit
import com.kobblo.gallery.file.explorer.ui.welcome.WelcomeFlow
import com.kobblo.gallery.file.explorer.ui.welcome.WelcomePrefs

/** Where a new person is with the welcome: in it, on the way out of it into the app, or past it. */
internal class WelcomeStage(private val context: Context) {

    var isOpen by mutableStateOf(false)
        private set

    /** The photos of the welcome's last step, while they fly to their places in the app. */
    var exit by mutableStateOf<WelcomeExit?>(null)
        private set

    fun openIfNew() {
        isOpen = !WelcomePrefs.isDone(context)
    }

    fun leave(exit: WelcomeExit?) {
        WelcomePrefs.markDone(context)
        isOpen = false
        this.exit = exit
    }

    fun arrived() {
        exit = null
    }
}

/** The welcome and then the way out of it, over the app, or nothing once that's done. */
@Composable
internal fun WelcomeCover(stage: WelcomeStage, photos: List<FileItem>, onRequestFiles: () -> Unit, onFinished: () -> Unit) {
    if (stage.isOpen) {
        WelcomeFlow(
            photos = photos,
            onRequestFiles = onRequestFiles,
            onFinish = { exit ->
                onFinished()
                stage.leave(exit)
            }
        )
    }
    stage.exit?.let { Entrance(it, onDone = stage::arrived) }
}
