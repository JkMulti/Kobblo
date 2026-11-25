package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * What the "+" in a folder offers: something new in this folder, or somewhere new to keep files.
 * Each choice closes the sheet before it starts, so whatever it opens isn't stacked on top of it.
 */
@Composable
fun CreateBottomSheet(
    onDismissRequest: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onAddCloud: () -> Unit,
    onReceive: () -> Unit
) {
    fun closing(choice: () -> Unit): () -> Unit = {
        onDismissRequest()
        choice()
    }
    KobbloSheet(onDismissRequest = onDismissRequest) {
        ExpressiveSheetContent(title = stringResource(R.string.create_title)) {
            KobbloGroup {
                CreateRow(stringResource(R.string.new_folder), AppIcons.FolderAdd, showDivider = true, onClick = closing(onNewFolder))
                CreateRow(stringResource(R.string.new_file), AppIcons.FileAdd, onClick = closing(onNewFile))
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                CreateRow(stringResource(R.string.add_location), AppIcons.CloudUpload, showDivider = true, onClick = closing(onAddCloud))
                CreateRow(stringResource(R.string.receive_action), AppIcons.Send, onClick = closing(onReceive))
            }
        }
    }
}

@Composable
private fun CreateRow(
    title: String,
    icon: ImageVector,
    showDivider: Boolean = false,
    onClick: () -> Unit
) {
    KobbloRow(
        title = title,
        leading = {
            Icon(icon, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp))
        },
        showDivider = showDivider,
        onClick = onClick
    )
}
