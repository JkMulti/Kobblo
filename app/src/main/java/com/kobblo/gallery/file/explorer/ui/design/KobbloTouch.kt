package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier

/**
 * A tap target that draws nothing of its own when pressed. What can be tapped in Kobblo answers with a
 * fill, a change of scale or a haptic that its own design chose, and Material's ripple on top of that
 * would be a second answer to one touch.
 *
 * Pass [source] when something else follows the press, such as [pressedRowFill] or a press scale, so
 * that it and the tap watch the same finger.
 */
fun Modifier.quietClickable(
    enabled: Boolean = true,
    source: MutableInteractionSource? = null,
    onClick: () -> Unit
): Modifier = clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)

/** [quietClickable] for something that also answers a long press. */
fun Modifier.quietCombinedClickable(
    enabled: Boolean = true,
    source: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier = combinedClickable(
    interactionSource = source,
    indication = null,
    enabled = enabled,
    onLongClick = onLongClick,
    onClick = onClick
)
