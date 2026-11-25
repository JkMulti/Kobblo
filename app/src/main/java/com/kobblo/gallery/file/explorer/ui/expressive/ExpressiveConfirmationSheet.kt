package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace

/**
 * Asks before something that can't be taken back, or is hard to. The confirm button turns red for
 * [destructive] actions, which it works out from the label unless told.
 */
@Composable
fun ExpressiveConfirmationSheet(
    title: String,
    subtitle: String? = null,
    confirmLabel: String = stringResource(R.string.delete_action),
    cancelLabel: String = stringResource(R.string.cancel),
    destructive: Boolean = confirmLabel == stringResource(R.string.delete_action) ||
        isDestructiveAction(confirmLabel, stringResource(R.string.screen_destructive_words)),
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    KobbloSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = KobbloSpace.lg)
        ) {
            KobbloSheetTitle(title, subtitle = subtitle)
            Spacer(modifier = Modifier.height(12.dp))
            KobbloActionPair(
                confirmLabel = confirmLabel,
                onConfirm = onConfirm,
                onCancel = onDismissRequest,
                cancelLabel = cancelLabel,
                destructive = destructive
            )
        }
    }
}

private val DestructiveWords = listOf("delete", "remove", "empty", "clear", "erase", "disconnect", "log out", "sign out", "discard", "uninstall", "wipe")

/** Whether a label destroys something: English words, and the current language's ([localWords], comma-separated). */
private fun isDestructiveAction(label: String, localWords: String): Boolean =
    (DestructiveWords + wordList(localWords)).any { label.contains(it, ignoreCase = true) }

internal fun wordList(words: String): List<String> = words.split(',').map { it.trim() }.filter { it.isNotEmpty() }
