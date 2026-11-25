package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.TextButton

/** A dialog around one text field, which has the keyboard from the start. [field] is drawn with the modifier it's given. */
@Composable
internal fun NamePrompt(
    title: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = onDismiss,
    canSave: Boolean = true,
    field: @Composable (Modifier) -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { field(Modifier.focusRequester(focus)) },
        confirmButton = { KobbloAlertAction(stringResource(R.string.save_action), enabled = canSave, onClick = onSave) },
        dismissButton = { KobbloAlertCancel(onClick = onCancel) }
    )
}

/** One of a dialog's answers. The [strong] ones are what it's asking about, and the rest are ways out of it. */
@Composable
internal fun Answer(label: String, color: Color, strong: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = color, fontWeight = if (strong) FontWeight.SemiBold else null)
    }
}
