package com.kobblo.gallery.file.explorer.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.kobblo.gallery.file.explorer.HapticEvent
import com.kobblo.gallery.file.explorer.HapticsSettings

/**
 * The app's vibration feedback. Each call names what just happened, and the motor runs only if
 * Settings allows vibration for that kind of event.
 *
 * It drives the [Vibrator] itself instead of asking a view for haptic feedback. A view's feedback is
 * silenced by the phone's own "touch vibration" switch, which would leave Kobblo's Vibration Feedback
 * setting on and nothing happening. Driven directly, Kobblo's setting is the one that decides.
 */
class HapticsController(
    private val vibrator: Vibrator?,
    private val settingsProvider: () -> HapticsSettings,
) {
    fun fire(event: HapticEvent) = play(event) {
        VibrationEffect.createOneShot(event.pulseMillis, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    fun fileOpen() = fire(HapticEvent.FileOpen)

    fun copyPaste() = fire(HapticEvent.CopyPaste)

    fun delete() = fire(HapticEvent.Delete)

    fun cleaner() = fire(HapticEvent.Cleaner)

    fun settingsToggle() = fire(HapticEvent.SettingsToggle)

    fun fab() = fire(HapticEvent.Fab)

    /** An ordinary tap: anything that has no kind of its own. */
    fun tap() = fire(HapticEvent.General)

    /**
     * Lighter than [tap], for each item passed while scrubbing along a list, where a tap's weight on
     * every one would run together into a buzz. The same setting allows it.
     */
    fun tick() = play(HapticEvent.General) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(FallbackTickMillis, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    /**
     * Runs the motor with [effect] if [event] is allowed and this device has a motor at all. The
     * effect is only built once both are known, so a tablet with no motor does no work here.
     */
    private inline fun play(event: HapticEvent, effect: () -> VibrationEffect) {
        if (!settingsProvider().isEnabled(event)) return
        val motor = vibrator?.takeIf { it.hasVibrator() } ?: return
        motor.vibrate(effect())
    }
}

/** The device's vibration motor. Android 12 moved it behind a manager, as a phone may have several. */
private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * A [HapticsController] that lasts as long as the screen it's made in. [settingsProvider] is asked
 * every time something happens, so a change in Settings takes effect on the very next touch without
 * a new controller being made.
 */
@Composable
fun rememberHapticsController(settingsProvider: () -> HapticsSettings): HapticsController {
    val context = LocalContext.current
    val latestSettings by rememberUpdatedState(settingsProvider)
    return remember(context) { HapticsController(context.defaultVibrator()) { latestSettings() } }
}

/** The controller every screen under the app's root shares. Outside the root there is none to give. */
val LocalHaptics = staticCompositionLocalOf<HapticsController> {
    error("No HapticsController provided")
}
