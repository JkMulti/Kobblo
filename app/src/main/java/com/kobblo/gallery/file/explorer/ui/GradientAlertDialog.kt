package com.kobblo.gallery.file.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType

/**
 * The app's alert. A floating panel with its title, its text beneath and its actions at the end, in
 * the same slots as Material's, so every alert reads the same without each choosing its own colors.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradientAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clip(KobbloShapes.xl)
                .background(colors.snow)
                .padding(KobbloSpace.xl)
        ) {
            if (icon != null) {
                Box(modifier = Modifier.padding(bottom = KobbloSpace.sm)) { icon() }
            }
            if (title != null) {
                CompositionLocalProvider(LocalContentColor provides colors.ink) {
                    ProvideTextStyle(KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), title)
                }
                Spacer(Modifier.height(KobbloSpace.sm))
            }
            if (text != null) {
                // Takes what's left, so a long message can scroll with the actions still in reach.
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    CompositionLocalProvider(LocalContentColor provides colors.smoke) {
                        ProvideTextStyle(KobbloType.callout, text)
                    }
                }
                Spacer(Modifier.height(KobbloSpace.md))
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KobbloSpace.xxs, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(KobbloSpace.xxs)
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
