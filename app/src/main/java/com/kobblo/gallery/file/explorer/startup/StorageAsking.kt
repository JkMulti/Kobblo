package com.kobblo.gallery.file.explorer.startup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kobblo.gallery.file.explorer.compat.VersionRules

/**
 * When to ask for access to files, and when to read them again, from what each return to the app finds.
 * From Android 11 on it's Kobblo's own alert that asks, since only a page in Settings can grant it. Before
 * that it's the system's dialog.
 */
internal class StorageAsking(private val sdk: Int) {

    /** Whether Kobblo's own alert is up. */
    var alertShown by mutableStateOf(false)
        private set

    private var hadAccess: Boolean? = null
    private var hasReturnedBefore = false
    private var alertTurnedDown = false
    private var systemHasAsked = false

    /** The app has just started. */
    fun onStart(hasAccess: Boolean) {
        hadAccess = hasAccess
    }

    /** What to do on coming to the screen. [mayAsk] is false while something else is doing the asking. */
    fun onReturn(hasAccess: Boolean, mayAsk: Boolean): Step {
        val justGranted = hasAccess && hadAccess == false
        // Starting reads the files by itself, so the first time here only matters if access came meanwhile.
        val reload = justGranted || hasReturnedBefore
        hadAccess = hasAccess
        hasReturnedBefore = true

        if (hasAccess) alertShown = false
        else if (mayAsk && asksWithAlert && !alertTurnedDown) alertShown = true
        val askSystem = !hasAccess && mayAsk && VersionRules.asksForStorageOnResume(sdk, systemHasAsked)
        return Step(reload, askSystem)
    }

    /** The system's dialog is being put up. Once per launch is all it's put up unasked. */
    fun onSystemAsked() {
        systemHasAsked = true
    }

    /** Not Now, here or in the welcome. The screens still offer it, but nothing raises it again until the next launch. */
    fun onTurnedDown() {
        alertTurnedDown = true
        alertShown = false
    }

    /** The alert has been answered with a yes. Coming back without access puts it up again. */
    fun onAgreed() {
        alertShown = false
    }

    private val asksWithAlert: Boolean get() = sdk >= ANDROID_11

    /** Read everything again, put up the system's dialog, both or neither. */
    data class Step(val reload: Boolean, val askSystem: Boolean)

    private companion object {
        const val ANDROID_11 = 30
    }
}
