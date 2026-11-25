package com.kobblo.gallery.file.explorer.security

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSize
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a lock screen is for.
 * - [Setup]: choose a new lock.
 * - [Unlock]: enter the lock; fingerprint or face is offered first when they're turned on.
 * - [Change]: enter the current lock, then choose a new one.
 * - [Confirm]: enter the lock with no fingerprint shortcut, before something that weakens it, such as
 *   turning fingerprint on or App Lock off.
 */
enum class LockFlow { Setup, Unlock, Change, Confirm }

/** Where a lock screen is in its job. */
private sealed class Phase {
    /** Fingerprint is the only lock (an older version allowed that) and no lock has been set since. */
    data object BiometricOnly : Phase()
    data object PickKind : Phase()
    data class Enter(val purpose: LockFlow) : Phase()
    data class Create(val kind: LockType) : Phase()
    data class Repeat(val kind: LockType, val first: String) : Phase()
    data object SuggestBiometrics : Phase()
}

/**
 * Kobblo's lock, full screen and opaque, so nothing behind it shows or reacts to a touch. While the lock
 * is being entered, [heading] can replace the title ("Unlock to Open"), [subtitle] the line under it
 * (otherwise "[lockedName] is locked"), and [note] adds a line below, such as what locking an item does.
 */
@Composable
fun LockScreen(
    flow: LockFlow,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    lockedName: String,
    heading: String? = null,
    subtitle: String? = null,
    note: String? = null
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val controller = remember(flow) {
        LockController(context, context.findActivity(), LockStore.get(context), flow, lockedName, scope, haptics, onDone)
    }
    val colors = KobbloDesign.colors

    BackHandler(onBack = onCancel)
    // The keys pressed could show up in a screenshot or recording, so neither is allowed here.
    BlockCapture(active = true)

    // Unlocking with fingerprint turned on: ask for it at once, with the lock below as the way round.
    LaunchedEffect(controller) {
        if (controller.offersBiometricsOnEntry) {
            delay(250)
            controller.tryBiometrics()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = KobbloSpace.screen)
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(KobbloSize.toolbar), contentAlignment = Alignment.CenterStart) {
                KobbloIconButton(icon = AppIcons.Close, description = stringResource(R.string.cancel), onClick = onCancel)
            }
            Spacer(Modifier.height(16.dp))
            Icon(AppIcons.LockFill, contentDescription = null, tint = colors.action, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(14.dp))

            val phase = controller.phase
            val entering = phase is Phase.Enter
            Text(phaseTitle(phase, controller, flow, heading), style = KobbloType.title, color = colors.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            val problem = controller.problem
            Text(
                text = problem ?: subtitle?.takeIf { entering } ?: phaseLine(phase, lockedName),
                style = KobbloType.callout,
                color = if (problem != null) colors.danger else colors.smoke,
                textAlign = TextAlign.Center
            )
            if (note != null && entering) {
                Spacer(Modifier.height(6.dp))
                Text(note, style = KobbloType.footnote, color = colors.smoke, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .offset { IntOffset(controller.shake.value.roundToInt(), 0) },
                contentAlignment = Alignment.TopCenter
            ) {
                PhaseBody(controller)
            }

            if (entering) EnterFooter(controller)
        }
    }
}

/** [LockScreen] in a full-screen window of its own, above everything else the app shows, the tab bar included. */
@Composable
fun LockDialog(
    flow: LockFlow,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    lockedName: String,
    heading: String? = null,
    subtitle: String? = null,
    note: String? = null
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
    ) {
        LockScreen(flow, onDone, onCancel, lockedName, heading, subtitle, note)
    }
}

/** The name of a kind of lock, such as "PIN". */
internal fun typeName(type: LockType): Int = when (type) {
    LockType.Pin -> R.string.lock_pin
    LockType.Pattern -> R.string.lock_pattern
    LockType.Password -> R.string.lock_password
}

/** Everything a lock screen decides, kept apart from how it's drawn. */
@Stable
private class LockController(
    private val context: Context,
    private val activity: Activity?,
    val store: LockStore,
    private val flow: LockFlow,
    private val lockedName: String,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val onDone: () -> Unit
) {
    var phase by mutableStateOf(firstPhase())
        private set

    /** What went wrong with the last try, shown in place of the line under the title. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** A check or a save is running. */
    var working by mutableStateOf(false)
        private set

    /** The phone's screen lock vouched for a reset, so the next lock chosen is a new one. */
    var afterReset by mutableStateOf(false)
        private set

    /** Bumped after every failed try, which gives the entry a fresh, empty start. */
    var round by mutableIntStateOf(0)
        private set

    val shake = Animatable(0f)

    val biometricsReady: Boolean get() = activity != null && SystemAuth.canUseBiometrics(activity)

    val offersBiometricsOnEntry: Boolean
        get() = flow == LockFlow.Unlock && store.isSet && store.biometrics && biometricsReady

    private fun firstPhase(): Phase = when {
        flow != LockFlow.Setup && store.isSet -> Phase.Enter(purpose = flow)
        store.biometrics && biometricsReady -> Phase.BiometricOnly
        else -> Phase.PickKind
    }

    fun pick(kind: LockType) {
        phase = Phase.Create(kind)
    }

    /** One finished entry: a PIN, a pattern or a password, depending on the phase. */
    fun submit(entry: String) {
        if (working) return
        when (val current = phase) {
            is Phase.Enter -> check(entry, current.purpose)
            is Phase.Create -> {
                val issue = LockStore.problem(current.kind, entry)
                if (issue != null) {
                    reject(context.getString(ruleFor(issue)))
                } else {
                    problem = null
                    phase = Phase.Repeat(current.kind, entry)
                }
            }
            is Phase.Repeat -> {
                if (entry == current.first) save(current.kind, entry)
                else {
                    phase = Phase.Create(current.kind)
                    reject(context.getString(R.string.lock_mismatch))
                }
            }
            else -> Unit
        }
    }

    private fun check(entry: String, purpose: LockFlow) {
        working = true
        scope.launch {
            val verdict = store.verify(entry)
            working = false
            when (verdict) {
                LockCheck.Correct -> if (purpose == LockFlow.Change) {
                    problem = null
                    phase = Phase.PickKind
                } else complete()
                LockCheck.Wrong -> reject(
                    store.lockedOutFor().takeIf { it > 0 }?.let { waitMessage(it) }
                        ?: context.getString(wrongFor(store.type))
                )
                LockCheck.LockedOut -> reject(waitMessage(store.lockedOutFor()))
                LockCheck.NotSet -> phase = Phase.PickKind
            }
        }
    }

    private fun save(kind: LockType, entry: String) {
        working = true
        scope.launch {
            val saved = store.set(kind, entry)
            working = false
            when {
                !saved -> reject(context.getString(R.string.lock_save_failed))
                flow == LockFlow.Setup && !store.biometrics && biometricsReady -> phase = Phase.SuggestBiometrics
                else -> complete()
            }
        }
    }

    fun acceptBiometrics() {
        store.setBiometrics(true)
        complete()
    }

    fun complete() {
        problem = null
        onDone()
    }

    /** Fingerprint or face in place of the lock. */
    fun tryBiometrics() {
        val host = activity ?: return
        val kind = store.type ?: return
        SystemAuth.askBiometric(
            host,
            title = context.getString(R.string.lock_unlock_named, lockedName),
            declineLabel = context.getString(R.string.lock_use_type, context.getString(typeName(kind))),
            onPassed = ::complete
        )
    }

    /** Where fingerprint is the only lock: passing it moves on to choosing a real one. */
    fun passBiometricOnly() {
        val host = activity ?: return
        SystemAuth.askBiometric(
            host,
            title = context.getString(R.string.lock_fingerprint_gate_title),
            declineLabel = context.getString(R.string.cancel),
            onPassed = { phase = Phase.PickKind }
        )
    }

    /** Forgot the lock: the phone's screen lock vouches, then a new lock is chosen in its place. */
    fun recover() {
        val host = activity ?: return
        if (!SystemAuth.hasScreenLock(host)) {
            problem = context.getString(R.string.lock_reset_none)
            return
        }
        SystemAuth.askScreenLock(
            host,
            title = context.getString(R.string.lock_reset_title),
            subtitle = context.getString(R.string.lock_reset_subtitle)
        ) {
            store.clear()
            afterReset = true
            problem = null
            phase = Phase.PickKind
        }
    }

    private fun reject(message: String) {
        problem = message
        round++
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        scope.launch {
            for (x in floatArrayOf(-14f, 14f, -10f, 10f, -4f, 0f)) shake.animateTo(x, tween(durationMillis = 55))
        }
    }

    /** "Too many tries", in seconds while the wait is short, then in whole minutes, rounded up. */
    private fun waitMessage(waitMs: Long): String {
        val res = context.resources
        return if (waitMs < 2 * 60_000L) {
            val seconds = Math.ceil(waitMs / 1000.0).toInt()
            res.getQuantityString(R.plurals.lock_too_many_seconds_count, seconds, seconds)
        } else {
            val minutes = Math.ceil(waitMs / 60_000.0).toInt()
            res.getQuantityString(R.plurals.lock_too_many_minutes_count, minutes, minutes)
        }
    }
}

// Each message is a whole sentence per kind of lock, so every language can word it naturally.
private fun wrongFor(type: LockType?): Int = when (type) {
    LockType.Pattern -> R.string.lock_wrong_pattern
    LockType.Password -> R.string.lock_wrong_password
    LockType.Pin, null -> R.string.lock_wrong_pin
}

private fun forgotFor(type: LockType?): Int = when (type) {
    LockType.Pattern -> R.string.lock_forgot_pattern
    LockType.Password -> R.string.lock_forgot_password
    LockType.Pin, null -> R.string.lock_forgot_pin
}

private fun ruleFor(issue: LockProblem): Int = when (issue) {
    LockProblem.PinLength, LockProblem.PinDigits -> R.string.lock_pin_rule
    LockProblem.PatternLength -> R.string.lock_pattern_rule
    LockProblem.PasswordLength -> R.string.lock_password_rule
    LockProblem.RetiredDefault -> R.string.lock_retired_default
}

@Composable
private fun phaseTitle(phase: Phase, controller: LockController, flow: LockFlow, heading: String?): String = when (phase) {
    Phase.BiometricOnly -> stringResource(R.string.lock_fingerprint_gate_title)
    Phase.PickKind -> stringResource(if (flow == LockFlow.Change || controller.afterReset) R.string.lock_choose_new else R.string.lock_choose)
    is Phase.Enter -> heading ?: stringResource(
        when (controller.store.type) {
            LockType.Pattern -> R.string.lock_draw_pattern
            LockType.Password -> R.string.lock_enter_password
            LockType.Pin, null -> R.string.lock_enter_pin
        }
    )
    is Phase.Create -> stringResource(
        when (phase.kind) {
            LockType.Pin -> R.string.lock_create_pin
            LockType.Pattern -> R.string.lock_create_pattern
            LockType.Password -> R.string.lock_create_password
        }
    )
    is Phase.Repeat -> stringResource(
        when (phase.kind) {
            LockType.Pin -> R.string.lock_confirm_pin
            LockType.Pattern -> R.string.lock_confirm_pattern
            LockType.Password -> R.string.lock_confirm_password
        }
    )
    Phase.SuggestBiometrics -> stringResource(R.string.lock_biometrics_title)
}

@Composable
private fun phaseLine(phase: Phase, lockedName: String): String = when (phase) {
    Phase.BiometricOnly -> stringResource(R.string.lock_fingerprint_gate_body)
    Phase.PickKind -> stringResource(R.string.lock_choose_body)
    is Phase.Enter -> when (phase.purpose) {
        LockFlow.Change -> stringResource(R.string.lock_enter_current)
        LockFlow.Confirm -> stringResource(R.string.lock_confirm_you)
        LockFlow.Setup, LockFlow.Unlock -> stringResource(R.string.lock_is_locked, lockedName)
    }
    is Phase.Create -> stringResource(
        when (phase.kind) {
            LockType.Pin -> R.string.lock_pin_rule
            LockType.Pattern -> R.string.lock_pattern_rule
            LockType.Password -> R.string.lock_password_rule
        }
    )
    is Phase.Repeat -> stringResource(if (phase.kind == LockType.Pattern) R.string.lock_draw_again else R.string.lock_enter_again)
    Phase.SuggestBiometrics -> stringResource(R.string.lock_biometrics_body)
}

@Composable
private fun PhaseBody(controller: LockController) {
    when (val phase = controller.phase) {
        Phase.BiometricOnly -> BiometricWait(onTry = controller::passBiometricOnly)
        Phase.PickKind -> KindList(onPick = controller::pick)
        Phase.SuggestBiometrics -> BiometricSuggestion(onAccept = controller::acceptBiometrics, onDecline = controller::complete)
        is Phase.Enter, is Phase.Create, is Phase.Repeat -> {
            val kind = when (phase) {
                is Phase.Create -> phase.kind
                is Phase.Repeat -> phase.kind
                else -> controller.store.type ?: LockType.Pin
            }
            val unlocking = phase is Phase.Enter
            val ready = !controller.working
            // A new, empty entry for every phase and after every failed try.
            key(phase, controller.round) {
                when (kind) {
                    LockType.Pin -> PinPad(
                        fixedLength = if (unlocking) controller.store.pinLength else 0,
                        enabled = ready,
                        onComplete = controller::submit
                    )
                    LockType.Pattern -> PatternPad(enabled = ready, onComplete = controller::submit)
                    LockType.Password -> PasswordBox(
                        actionLabel = stringResource(if (unlocking) R.string.lock_unlock else R.string.lock_next),
                        enabled = ready,
                        onComplete = controller::submit
                    )
                }
            }
        }
    }
}

@Composable
private fun EnterFooter(controller: LockController) {
    val colors = KobbloDesign.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (controller.offersBiometricsOnEntry) {
            Icon(
                AppIcons.Fingerprint,
                contentDescription = stringResource(R.string.lock_use_fingerprint),
                tint = colors.action,
                modifier = Modifier
                    .clip(CapsuleShape)
                    .clickable(onClick = controller::tryBiometrics)
                    .padding(10.dp)
                    .size(28.dp)
            )
        }
        Text(
            stringResource(forgotFor(controller.store.type)),
            style = KobbloType.callout,
            color = colors.action,
            modifier = Modifier
                .clip(CapsuleShape)
                .clickable(onClick = controller::recover)
                .padding(12.dp)
        )
    }
}

@Composable
private fun KindList(onPick: (LockType) -> Unit) {
    val kinds = listOf(
        Triple(LockType.Pin, R.string.lock_pin, R.string.lock_pin_hint),
        Triple(LockType.Pattern, R.string.lock_pattern, R.string.lock_pattern_hint),
        Triple(LockType.Password, R.string.lock_password, R.string.lock_password_hint)
    )
    KobbloGroup(modifier = Modifier.widthIn(max = 480.dp)) {
        for ((index, entry) in kinds.withIndex()) {
            val (kind, name, hint) = entry
            KobbloRow(
                title = stringResource(name),
                subtitle = stringResource(hint),
                trailing = { KobbloChevron() },
                showDivider = index != kinds.lastIndex,
                onClick = { onPick(kind) }
            )
        }
    }
}

@Composable
private fun BiometricWait(onTry: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(250)
        onTry()
    }
    Icon(
        AppIcons.Fingerprint,
        contentDescription = stringResource(R.string.lock_use_fingerprint),
        tint = KobbloDesign.colors.action,
        modifier = Modifier
            .clip(CapsuleShape)
            .clickable(onClick = onTry)
            .padding(16.dp)
            .size(56.dp)
    )
}

@Composable
private fun BiometricSuggestion(onAccept: () -> Unit, onDecline: () -> Unit) {
    val colors = KobbloDesign.colors
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledAction(label = stringResource(R.string.lock_use_fingerprint), onClick = onAccept)
        Text(
            stringResource(R.string.lock_not_now),
            style = KobbloType.body,
            color = colors.action,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(CapsuleShape)
                .clickable(onClick = onDecline)
                .padding(14.dp)
        )
    }
}

@Composable
private fun ColumnScope.FilledAction(label: String, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    val presses = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressScale(interactionSource = presses)
            .clip(CapsuleShape)
            .background(colors.action)
            .clickable(interactionSource = presses, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = KobbloType.body.copy(fontWeight = FontWeight.SemiBold), color = colors.onAction)
    }
}

/** A text button that's only active when [enabled]. */
@Composable
private fun TextAction(label: String, enabled: Boolean, topPadding: Int, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Text(
        label,
        style = KobbloType.body.copy(fontWeight = FontWeight.SemiBold),
        color = if (enabled) colors.action else colors.pewter,
        modifier = Modifier
            .padding(top = topPadding.dp)
            .clip(CapsuleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp)
    )
}

private val PadRows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(null, "0", PadDelete)
)
private const val PadDelete = "⌫"

/**
 * A PIN keypad. When unlocking, [fixedLength] is the stored PIN's length and the PIN is checked the moment
 * that many digits are in; otherwise it shows a Next button once the PIN is long enough.
 */
@Composable
private fun PinPad(fixedLength: Int, enabled: Boolean, onComplete: (String) -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHapticFeedback.current
    var digits by remember { mutableStateOf("") }
    val deleteLabel = stringResource(R.string.lock_delete)

    fun press(key: String) {
        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
        if (key == PadDelete) {
            digits = digits.dropLast(1)
            return
        }
        if (digits.length >= LockStore.MaxPinLength) return
        digits += key
        if (fixedLength > 0 && digits.length == fixedLength) onComplete(digits)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val slots = if (fixedLength > 0) fixedLength else maxOf(LockStore.MinLength, digits.length)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (slot in 0 until slots) {
                val filled = slot < digits.length
                Box(Modifier.size(13.dp).clip(CapsuleShape).background(if (filled) colors.ink else colors.silver))
            }
        }
        Spacer(Modifier.height(28.dp))
        for (row in PadRows) {
            Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (key in row) {
                    if (key == null) {
                        Spacer(Modifier.size(76.dp))
                    } else {
                        PadKey(
                            label = if (key == PadDelete) deleteLabel else key,
                            quiet = key == PadDelete,
                            enabled = enabled,
                            onPress = { press(key) }
                        )
                    }
                }
            }
        }
        if (fixedLength == 0) {
            val longEnough = digits.length in LockStore.MinLength..LockStore.MaxPinLength
            TextAction(stringResource(R.string.lock_next), enabled = longEnough && enabled, topPadding = 8) { onComplete(digits) }
        }
    }
}

@Composable
private fun PadKey(label: String, quiet: Boolean, enabled: Boolean, onPress: () -> Unit) {
    val colors = KobbloDesign.colors
    val presses = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(76.dp)
            .pressScale(pressedScale = 0.9f, interactionSource = presses)
            .clip(CapsuleShape)
            .background(if (quiet) colors.canvas else colors.snow)
            .clickable(interactionSource = presses, indication = null, enabled = enabled, onClick = onPress),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = if (quiet) KobbloType.callout else KobbloType.title.copy(fontWeight = FontWeight.Normal),
            color = colors.ink
        )
    }
}

/**
 * A 3 × 3 pattern grid. Dots join as the finger crosses them; lifting the finger hands over the dots in
 * the order they were joined, as a string of their positions (0 to 8).
 */
@Composable
private fun PatternPad(enabled: Boolean, onComplete: (String) -> Unit) {
    val colors = KobbloDesign.colors
    val haptics = LocalHapticFeedback.current
    val joined = remember { mutableStateListOf<Int>() }
    var fingertip by remember { mutableStateOf<Offset?>(null) }
    val description = stringResource(R.string.lock_pattern)

    fun dotCenter(index: Int, side: Float): Offset {
        val step = side / 3f
        return Offset(step * (index % 3 + 0.5f), step * (index / 3 + 0.5f))
    }

    Canvas(
        Modifier
            .size(288.dp)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val side = size.width.toFloat()
                val reach = side / 3f * 0.32f
                fun touch(point: Offset) {
                    val dot = (0..8).firstOrNull {
                        val c = dotCenter(it, side)
                        hypot(c.x - point.x, c.y - point.y) < reach
                    } ?: return
                    if (dot !in joined) {
                        joined += dot
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    joined.clear()
                    fingertip = down.position
                    touch(down.position)
                    var lifted = false
                    while (!lifted) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            if (change.positionChange() != Offset.Zero) change.consume()
                            fingertip = change.position
                            touch(change.position)
                        } else {
                            lifted = true
                        }
                    }
                    fingertip = null
                    val entry = joined.joinToString(separator = "")
                    joined.clear()
                    if (lifted && entry.isNotEmpty()) onComplete(entry)
                }
            }
    ) {
        val side = size.width
        val line = 6.dp.toPx()
        for (dot in 0..8) {
            val chosen = dot in joined
            if (chosen) drawCircle(colors.action.copy(alpha = 0.2f), radius = 26.dp.toPx(), center = dotCenter(dot, side))
        }
        for (i in 1 until joined.size) {
            drawLine(colors.action, dotCenter(joined[i - 1], side), dotCenter(joined[i], side), line, StrokeCap.Round)
        }
        val tip = fingertip
        if (tip != null && joined.isNotEmpty()) {
            drawLine(colors.action.copy(alpha = 0.5f), dotCenter(joined.last(), side), tip, line, StrokeCap.Round)
        }
        for (dot in 0..8) {
            drawCircle(if (dot in joined) colors.action else colors.pewter, radius = 8.dp.toPx(), center = dotCenter(dot, side))
        }
    }
}

@Composable
private fun PasswordBox(actionLabel: String, enabled: Boolean, onComplete: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val canSubmit = text.isNotEmpty() && enabled
    Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        KobbloTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.lock_password),
            secret = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) onComplete(text) }),
            modifier = Modifier.focusRequester(focus)
        )
        TextAction(actionLabel, enabled = canSubmit, topPadding = 12) { onComplete(text) }
    }
}
