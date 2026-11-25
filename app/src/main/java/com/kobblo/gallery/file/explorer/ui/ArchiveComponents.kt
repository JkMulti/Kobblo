package com.kobblo.gallery.file.explorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * What a tapped archive offers. An archive opened from another app has no folder here and isn't
 * ours to delete, so [extractHereSubtitle] says where it goes and [onDelete] is left out.
 */
@Composable
fun ArchiveExtractDialog(
    archiveName: String,
    itemCount: String,
    isRar: Boolean,
    extractHereSubtitle: String,
    onExtractHere: () -> Unit,
    onExtractTo: () -> Unit,
    onViewContents: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val haptics = LocalHaptics.current

    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = KobbloSpace.lg)
        ) {
            KobbloGroup {
                val kind = if (isRar) stringResource(R.string.archive_kind_rar) else stringResource(R.string.archive_kind)
                KobbloRow(
                    title = archiveName,
                    subtitle = if (itemCount.isNotBlank()) "$itemCount · $kind" else kind,
                    leading = { KobbloIconBadge(AppIcons.FileZip, KobbloTint.Brown) }
                )
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ArchiveAction(
                    icon = AppIcons.Unarchive,
                    title = stringResource(R.string.archive_extract_here),
                    subtitle = extractHereSubtitle,
                    onClick = tapping(onExtractHere),
                    showDivider = true
                )
                ArchiveAction(
                    icon = AppIcons.FolderMove,
                    title = stringResource(R.string.archive_extract_to),
                    subtitle = stringResource(R.string.archive_extract_to_subtitle),
                    onClick = tapping(onExtractTo),
                    showDivider = true
                )
                ArchiveAction(
                    icon = AppIcons.Eye,
                    title = stringResource(R.string.archive_view_contents),
                    subtitle = stringResource(R.string.archive_view_contents_subtitle),
                    onClick = tapping(onViewContents),
                    showDivider = onDelete != null
                )
                if (onDelete != null) {
                    ArchiveAction(
                        icon = AppIcons.Trash,
                        title = stringResource(R.string.archive_delete),
                        subtitle = null,
                        destructive = true,
                        onClick = { haptics.delete(); onDelete() }
                    )
                }
            }
        }
    }
}

/** [action] with the light tap these sheets and alerts give when a choice is made. */
@Composable
private fun tapping(action: () -> Unit): () -> Unit {
    val haptics = LocalHaptics.current
    return {
        haptics.tap()
        action()
    }
}

@Composable
private fun ArchiveAction(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    destructive: Boolean = false,
    showDivider: Boolean = false
) {
    val colors = KobbloDesign.colors
    KobbloRow(
        title = title,
        subtitle = subtitle,
        titleColor = if (destructive) colors.danger else colors.ink,
        leading = {
            Icon(icon, contentDescription = null, tint = if (destructive) colors.danger else colors.ink)
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

/**
 * The field an archive's password goes in, wherever it's asked for. With [onReveal] it carries an eye
 * that shows what was typed, for a password someone is working out rather than pasting.
 */
@Composable
internal fun ArchivePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    revealed: Boolean = false,
    onReveal: (() -> Unit)? = null
) {
    val eye: @Composable () -> Unit = {
        KobbloIconButton(
            icon = if (revealed) AppIcons.EyeOff else AppIcons.Eye,
            description = stringResource(if (revealed) R.string.viewer_pdf_password_hide else R.string.viewer_pdf_password_show),
            onClick = { onReveal?.invoke() },
            size = 36.dp,
            tint = KobbloDesign.colors.smoke,
            background = Color.Transparent
        )
    }
    KobbloTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(R.string.archive_password_placeholder),
        modifier = modifier,
        secret = !revealed,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailing = eye.takeIf { onReveal != null }
    )
}

/** Asks for the password an extraction stopped at. [attemptFailed] says the last one tried was wrong. */
@Composable
fun ArchivePasswordDialog(archiveName: String, attemptFailed: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    // The keyboard comes up with the dialog: there's nothing else here to do first.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = KobbloDesign.colors

    GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_password_title)) },
        text = {
            Column {
                Text(archiveName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(KobbloSpace.sm))
                ArchivePasswordField(
                    value = password,
                    onValueChange = { password = it },
                    onDone = { if (password.isNotEmpty()) onSubmit(password) },
                    modifier = Modifier.focusRequester(focus)
                )
                if (attemptFailed) {
                    Spacer(Modifier.height(KobbloSpace.xxs))
                    Text(stringResource(R.string.archive_password_wrong), style = KobbloType.footnote, color = colors.danger)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.unlock_action))
            }
        },
        dismissButton = {
            TextButton(onClick = tapping(onDismiss)) {
                Text(stringResource(R.string.cancel), color = colors.smoke)
            }
        }
    )
}

/** Says why an extraction stopped. */
@Composable
fun ArchiveFailedDialog(reason: String, onDismiss: () -> Unit) {
    GradientAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_failed_title)) },
        text = { Text(reason) },
        confirmButton = {
            TextButton(onClick = tapping(onDismiss)) {
                Text(stringResource(R.string.archive_failed_dismiss))
            }
        }
    )
}
