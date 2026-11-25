package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * Where the app's toasts show. Whatever is sent to [hostState] waits its turn, so two messages never
 * overlap; each one rises into place and sinks away again, or only fades when motion is off.
 */
@Composable
fun ExpressiveToastHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val still = ExpressiveMotion.isReducedMotion()
    SnackbarHost(hostState, modifier) { toast ->
        AnimatedVisibility(visible = true, enter = toastArrival(still), exit = toastDeparture(still)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Toast(
                    message = toast.visuals.message,
                    actionLabel = toast.visuals.actionLabel,
                    onAction = toast::performAction
                )
            }
        }
    }
}

private fun toastArrival(still: Boolean): EnterTransition =
    if (still) fadeIn()
    else slideInVertically(ExpressiveMotion.IntOffsetSpringSpec) { height -> height } + fadeIn(ExpressiveMotion.FastTween)

private fun toastDeparture(still: Boolean): ExitTransition =
    if (still) fadeOut()
    else slideOutVertically(ExpressiveMotion.IntOffsetFastTween) { height -> height } + fadeOut(ExpressiveMotion.FadeTween)

/**
 * One toast: a quiet capsule that says what happened in a sentence, with a small mark only when it
 * went wrong or plainly worked. It floats, so it wears the one shadow floating things wear.
 */
@Composable
private fun Toast(message: String, actionLabel: String?, onAction: () -> Unit) {
    val colors = KobbloDesign.colors
    val localProblems = stringResource(R.string.screen_toast_problem_words)
    val localSuccesses = stringResource(R.string.screen_toast_done_words)
    val tone = remember(message, localProblems, localSuccesses) { toastTone(message, localProblems, localSuccesses) }

    Row(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .widthIn(min = 120.dp, max = 360.dp)
            .ambientShadow(CapsuleShape, colors.ambient)
            .clip(CapsuleShape)
            .background(colors.snow)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ToastGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (tone) {
            ToastTone.Problem -> Icon(AppIcons.Alert, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
            ToastTone.Done -> Icon(AppIcons.Check, contentDescription = null, tint = colors.action, modifier = Modifier.size(18.dp))
            null -> Unit
        }
        Text(
            text = message,
            // The words give way to the action beside them, never the other way round.
            modifier = Modifier.weight(1f, fill = false),
            style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.ink,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                style = KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.action,
                modifier = Modifier
                    .clip(CapsuleShape)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/** Between a toast's mark, its words and its action. */
private val ToastGap = 8.dp

/** What a message is about, where its words give that away. */
internal enum class ToastTone { Problem, Done }

private val EnglishProblemWords = listOf("couldn't", "can't", "failed", "error", "denied")
private val EnglishDoneWords = listOf("copied", "moved", "saved", "installed", "updated")

/**
 * Reads a message's tone from its words. Messages may arrive in English whatever the language, so the
 * English words always count, and so do the current language's own ([localProblemWords] and
 * [localDoneWords], each comma-separated). A message that mentions a problem is one, even if it also
 * says something was done.
 */
internal fun toastTone(message: String, localProblemWords: String, localDoneWords: String): ToastTone? {
    val text = message.lowercase()
    fun mentionsAny(words: List<String>) = words.any { it.lowercase() in text }
    return when {
        mentionsAny(EnglishProblemWords + wordList(localProblemWords)) -> ToastTone.Problem
        mentionsAny(EnglishDoneWords + wordList(localDoneWords)) -> ToastTone.Done
        else -> null
    }
}
