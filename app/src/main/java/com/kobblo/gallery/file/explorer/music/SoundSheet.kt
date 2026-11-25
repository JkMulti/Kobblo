package com.kobblo.gallery.file.explorer.music

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SpeedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SleepChoices = listOf(5, 15, 30, 45, 60, 90, 120)
private val CrossfadeChoices = listOf(0, 2, 5, 8, 12)
private const val PitchRange = 12

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

/** The presets the platform's equalizer ships with, by the English names it gives them. */
private val PresetNames = mapOf(
    "normal" to R.string.media_eq_preset_normal,
    "classical" to R.string.media_eq_preset_classical,
    "dance" to R.string.media_eq_preset_dance,
    "flat" to R.string.media_eq_preset_flat,
    "folk" to R.string.media_eq_preset_folk,
    "heavy metal" to R.string.media_eq_preset_heavy_metal,
    "hip hop" to R.string.media_eq_preset_hip_hop,
    "jazz" to R.string.media_eq_preset_jazz,
    "pop" to R.string.media_eq_preset_pop,
    "rock" to R.string.media_eq_preset_rock
)

/** A preset's name in the app's language. A device's own presets keep the names their maker gave them. */
@Composable
private fun presetLabel(name: String): String =
    PresetNames[name.lowercase(java.util.Locale.ROOT)]?.let { stringResource(it) } ?: name

/** Everything about how playback sounds, in one sheet so the player itself stays clean. */
@Composable
fun SoundSheet(onDismiss: () -> Unit) {
    val colors = KobbloDesign.colors
    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = KobbloSpace.xl)
        ) {
            Text(
                text = stringResource(R.string.music_sound),
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
            PlaybackGroup()
            SleepTimerGroup()
            EqualizerGroup()
        }
    }
}

@Composable
private fun PlaybackGroup() {
    val context = LocalContext.current
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val sound = data.sound
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.music_speed),
            showDivider = true,
            below = {
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    SpeedChoices.forEach { speed ->
                        SoundChip(
                            label = speedLabel(speed),
                            selected = sound.speed == speed,
                            onClick = { store.update { it.copy(sound = it.sound.copy(speed = speed)) } }
                        )
                    }
                }
            }
        )
        KobbloRow(
            title = stringResource(R.string.music_pitch),
            showDivider = true,
            trailing = {
                KobbloIconButton(
                    icon = AppIcons.Remove,
                    description = stringResource(R.string.music_pitch_down),
                    size = 32.dp,
                    iconSize = 16.dp,
                    tint = colors.action,
                    background = colors.mist,
                    onClick = {
                        haptics.tap()
                        store.update { it.copy(sound = it.sound.copy(pitchSemitones = (it.sound.pitchSemitones - 1).coerceAtLeast(-PitchRange))) }
                    }
                )
                Text(
                    text = if (sound.pitchSemitones == 0) "0" else stringResource(R.string.music_pitch_value, sound.pitchSemitones),
                    style = KobbloType.callout.copy(fontFeatureSettings = "tnum"),
                    color = colors.ink,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(40.dp)
                )
                KobbloIconButton(
                    icon = AppIcons.Add,
                    description = stringResource(R.string.music_pitch_up),
                    size = 32.dp,
                    iconSize = 16.dp,
                    tint = colors.action,
                    background = colors.mist,
                    onClick = {
                        haptics.tap()
                        store.update { it.copy(sound = it.sound.copy(pitchSemitones = (it.sound.pitchSemitones + 1).coerceAtMost(PitchRange))) }
                    }
                )
            }
        )
        KobbloRow(
            title = stringResource(R.string.music_skip_silence),
            showDivider = true,
            trailing = {
                ExpressiveSwitch(
                    checked = sound.skipSilence,
                    onCheckedChange = { checked -> store.update { it.copy(sound = it.sound.copy(skipSilence = checked)) } }
                )
            }
        )
        KobbloRow(
            title = stringResource(R.string.music_crossfade),
            below = {
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    CrossfadeChoices.forEach { seconds ->
                        SoundChip(
                            label = if (seconds == 0) stringResource(R.string.music_crossfade_off) else stringResource(R.string.music_crossfade_seconds, seconds),
                            selected = sound.crossfadeSeconds == seconds,
                            onClick = { store.update { it.copy(sound = it.sound.copy(crossfadeSeconds = seconds)) } }
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun SleepTimerGroup() {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val player = remember { MusicPlayer.get(context) }
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val endsAt by player.sleepEndsAt.collectAsState()
    val remaining by produceState(0L, endsAt) {
        val end = endsAt ?: return@produceState
        while (true) {
            value = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(1000)
        }
    }
    KobbloGroupHeader(title = stringResource(R.string.music_sleep_timer))
    KobbloGroup {
        if (endsAt != null) {
            KobbloRow(
                title = stringResource(R.string.music_sleep_left, formatTrackTime(remaining)),
                showDivider = true,
                leading = { Icon(AppIcons.Moon, contentDescription = null, tint = colors.action, modifier = Modifier.size(22.dp)) }
            )
            KobbloRow(
                title = stringResource(R.string.music_sleep_cancel),
                titleColor = colors.action,
                onClick = { player.cancelSleepTimer() }
            )
        } else {
            KobbloRow(
                title = stringResource(R.string.music_sleep_start, data.sound.lastSleepMinutes),
                titleColor = colors.action,
                showDivider = true,
                leading = { Icon(AppIcons.Moon, contentDescription = null, tint = colors.action, modifier = Modifier.size(22.dp)) },
                onClick = { player.startSleepTimer(data.sound.lastSleepMinutes) }
            )
            Box(modifier = Modifier.padding(start = KobbloSpace.md, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                ChipRow {
                    SleepChoices.forEach { minutes ->
                        SoundChip(
                            label = stringResource(R.string.music_sleep_minutes, minutes),
                            selected = data.sound.lastSleepMinutes == minutes,
                            onClick = { store.update { it.copy(sound = it.sound.copy(lastSleepMinutes = minutes)) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerGroup() {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val player = remember { MusicPlayer.get(context) }
    val store = remember { MusicStore.get(context) }
    val data by store.data.collectAsState()
    val scope = rememberCoroutineScope()
    val sound = data.sound
    var checked by remember { mutableStateOf(false) }
    val info by produceState<EqualizerInfo?>(null) {
        value = player.describeEqualizer()
        checked = true
    }

    KobbloGroupHeader(title = stringResource(R.string.music_equalizer))
    val eq = info
    if (eq == null) {
        if (checked) KobbloGroupFooter(stringResource(R.string.music_eq_unavailable))
        return
    }
    val levels = sound.eqBands.takeIf { it.size == eq.bandsHz.size } ?: List(eq.bandsHz.size) { 0 }

    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.music_equalizer),
            showDivider = true,
            trailing = {
                ExpressiveSwitch(
                    checked = sound.eqEnabled,
                    onCheckedChange = { on -> store.update { it.copy(sound = it.sound.copy(eqEnabled = on)) } }
                )
            }
        )
        AnimatedVisibility(visible = sound.eqEnabled) {
            Column {
                Box(modifier = Modifier.padding(start = KobbloSpace.md, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
                    ChipRow {
                        SoundChip(
                            label = stringResource(R.string.music_preset_custom),
                            selected = sound.eqPreset < 0,
                            onClick = { store.update { it.copy(sound = it.sound.copy(eqPreset = -1)) } }
                        )
                        eq.presets.forEachIndexed { index, name ->
                            SoundChip(
                                label = presetLabel(name),
                                selected = sound.eqPreset == index,
                                onClick = {
                                    scope.launch {
                                        val bands = player.presetLevels(index) ?: return@launch
                                        store.update { it.copy(sound = it.sound.copy(eqPreset = index, eqBands = bands)) }
                                    }
                                }
                            )
                        }
                    }
                }
                eq.bandsHz.forEachIndexed { band, hz ->
                    val level = levels[band]
                    BandSlider(
                        label = if (hz >= 1000) stringResource(R.string.music_band_khz, hz / 1000f) else stringResource(R.string.music_band_hz, hz),
                        value = level,
                        min = eq.minLevel,
                        max = eq.maxLevel,
                        onChange = { next ->
                            store.update { data ->
                                val updated = levels.toMutableList().also { it[band] = next }
                                data.copy(sound = data.sound.copy(eqPreset = -1, eqBands = updated))
                            }
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    if (eq.hasBassBoost) {
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.music_bass_boost),
                below = {
                    Spacer(Modifier.height(8.dp))
                    MusicSlider(
                        fraction = sound.bassBoost / 1000f,
                        onChange = { fraction ->
                            val strength = (fraction * 1000f).roundToInt()
                            store.update { it.copy(sound = it.sound.copy(bassBoost = strength)) }
                        }
                    )
                }
            )
        }
    }
    if (sound.eqEnabled || sound.bassBoost > 0) {
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.music_reset),
                titleColor = colors.action,
                onClick = {
                    store.update {
                        it.copy(sound = it.sound.copy(eqPreset = -1, eqBands = List(eq.bandsHz.size) { 0 }, bassBoost = 0))
                    }
                }
            )
        }
    }
}

@Composable
private fun BandSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val colors = KobbloDesign.colors
    val range = (max - min).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.md, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
            color = colors.smoke,
            modifier = Modifier.width(64.dp)
        )
        MusicSlider(
            fraction = (value - min).toFloat() / range,
            centred = true,
            onChange = { fraction -> onChange((min + fraction * range).roundToInt()) },
            modifier = Modifier.weight(1f)
        )
    }
}

/** The app's slider on a light surface. With [centred], it fills out from the middle, as gain does. */
@Composable
private fun MusicSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    centred: Boolean = false
) {
    val colors = KobbloDesign.colors
    val direction = LocalLayoutDirection.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(direction) {
                detectTapGestures { offset -> onChange(toFraction(offset.x, size.width, direction)) }
            }
            .pointerInput(direction) {
                // Only sideways, so a drag that starts here going up or down still scrolls the sheet.
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange(toFraction(change.position.x, size.width, direction))
                }
            }
            .drawBehind {
                val track = 6.dp.toPx()
                val top = (size.height - track) / 2f
                val radius = CornerRadius(track / 2f)
                val at = fraction.coerceIn(0f, 1f).let { if (layoutDirection == LayoutDirection.Ltr) it else 1f - it } * size.width
                drawRoundRect(colors.mist, Offset(0f, top), Size(size.width, track), radius)
                val start = if (centred) size.width / 2f else if (layoutDirection == LayoutDirection.Ltr) 0f else size.width
                drawRoundRect(colors.action, Offset(minOf(start, at), top), Size(kotlin.math.abs(at - start), track), radius)
                val knob = 10.dp.toPx()
                drawCircle(colors.action, radius = knob, center = Offset(at.coerceIn(knob, size.width - knob), size.height / 2f))
            }
    )
}

private fun toFraction(x: Float, width: Int, direction: LayoutDirection): Float {
    val raw = (x / width).coerceIn(0f, 1f)
    return if (direction == LayoutDirection.Ltr) raw else 1f - raw
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/** A chip on a white group, which needs the darker fill the view switcher's chips don't. */
@Composable
private fun SoundChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(com.kobblo.gallery.file.explorer.ui.design.CapsuleShape)
            .background(if (selected) colors.action else colors.mist)
            .quietClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
            color = if (selected) colors.onAction else colors.ink,
            maxLines = 1
        )
    }
}
