package com.kobblo.gallery.file.explorer.music

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/** What this device's equalizer offers. Null wherever the platform has no equalizer to give. */
data class EqualizerInfo(
    /** Each band's centre, in hertz. */
    val bandsHz: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
    val hasBassBoost: Boolean
)

/**
 * The platform equalizer and bass boost, attached to the player's audio session. Some devices refuse
 * either one or both, and a refusal can come as any of several exceptions, so each is created and
 * driven behind a catch and the player carries on without it.
 */
class SoundEffects(private val context: Context) {
    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null

    @Synchronized
    fun attach(audioSessionId: Int, settings: SoundSettings) {
        if (audioSessionId == sessionId) {
            apply(settings)
            return
        }
        release()
        sessionId = audioSessionId
        if (audioSessionId == 0) return
        announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        apply(settings)
    }

    /** Only asks the platform for effects once they're wanted, since most listeners never turn them on. */
    @Synchronized
    fun apply(settings: SoundSettings) {
        if (sessionId == 0) return
        if (settings.eqEnabled || equalizer != null) applyEqualizer(settings)
        if (settings.bassBoost > 0 || bass != null) applyBass(settings)
    }

    private fun applyEqualizer(settings: SoundSettings) {
        val eq = equalizerOrNull() ?: return
        safely("set the equalizer") {
            if (settings.eqEnabled) {
                // Presets are saved as their band levels, so what was chosen survives a device update.
                settings.eqBands.forEachIndexed { band, level ->
                    if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.toShort())
                }
            }
            eq.enabled = settings.eqEnabled
        }
    }

    private fun applyBass(settings: SoundSettings) {
        val effect = bassOrNull() ?: return
        safely("set the bass boost") {
            if (effect.strengthSupported) effect.setStrength(settings.bassBoost.coerceIn(0, 1000).toShort())
            effect.enabled = settings.bassBoost > 0
        }
    }

    private fun equalizerOrNull(): Equalizer? {
        if (equalizer == null) {
            equalizer = safely("create the equalizer") { Equalizer(0, sessionId) }
        }
        return equalizer
    }

    private fun bassOrNull(): BassBoost? {
        if (bass == null) {
            bass = safely("create the bass boost") { BassBoost(0, sessionId) }
        }
        return bass
    }

    /** Describes the equalizer for the Sound sheet, or null if this device doesn't have one. */
    @Synchronized
    fun describe(): EqualizerInfo? {
        if (sessionId == 0) return null
        val eq = equalizerOrNull() ?: return null
        return safely("read the equalizer") {
            val range = eq.bandLevelRange
            EqualizerInfo(
                bandsHz = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                // Some devices pad these with NULs, which accessibility services choke on.
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()).substringBefore('\u0000').trim() },
                hasBassBoost = bassOrNull()?.strengthSupported == true
            )
        }
    }

    /** The band levels a preset stands for. */
    @Synchronized
    fun levelsOfPreset(preset: Int): List<Int>? {
        val eq = equalizerOrNull() ?: return null
        return safely("read a preset") {
            eq.usePreset(preset.toShort())
            (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
        }
    }

    @Synchronized
    fun release() {
        if (sessionId != 0) announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        safely("release the equalizer") { equalizer?.release() }
        safely("release the bass boost") { bass?.release() }
        equalizer = null
        bass = null
        sessionId = 0
    }

    /** Tells any system-wide sound app which session is ours, so it can act on this music too. */
    private fun announce(action: String) {
        safely("announce the audio session") {
            context.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
        }
    }

    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w("SoundEffects", "Couldn't $what", e)
        null
    }
}
