package com.kobblo.gallery.file.explorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace

/** One thing an item's menu offers: what it's called, its glyph, and what picking it does. */
internal class MenuChoice(val label: String, val icon: ImageVector, val onPick: () -> Unit)

/**
 * The short menu about one item of a list, under the item's name: the ordinary thing to do with it
 * and, set apart beneath in red, the way to be rid of it. The menu never closes itself, so what
 * becomes of it is part of each choice.
 */
@Composable
internal fun ItemMenuSheet(
    title: String,
    subtitle: String?,
    choice: MenuChoice,
    removal: MenuChoice,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    KobbloSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = KobbloSpace.lg)) {
            KobbloSheetTitle(title, subtitle = subtitle)
            KobbloGroup { MenuRow(choice, colors.ink) }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup { MenuRow(removal, colors.danger) }
        }
    }
}

@Composable
private fun MenuRow(choice: MenuChoice, color: Color) {
    KobbloRow(
        title = choice.label,
        titleColor = color,
        trailing = { Icon(choice.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) },
        onClick = choice.onPick
    )
}
