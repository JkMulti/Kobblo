package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.expressive.CreateBottomSheet

/** Something new that only needs a name. */
internal enum class NewThing { Folder, File }

/** Which step of making something new is on screen. */
@Stable
internal class Creating {
    /** The sheet behind the + button. */
    var choosing by mutableStateOf(false)

    var naming by mutableStateOf<NewThing?>(null)
}

/** What + offers: something new in this folder, or somewhere else to keep files. */
@Composable
internal fun CreateSheet(creating: Creating, forms: PlaceForms, onAction: (AppAction) -> Unit) {
    if (!creating.choosing) return
    CreateBottomSheet(
        onDismissRequest = { creating.choosing = false },
        onNewFolder = { creating.naming = NewThing.Folder },
        onNewFile = { creating.naming = NewThing.File },
        onAddCloud = { forms.choosing = true },
        onReceive = { onAction(AppAction.SetLocation("send")) }
    )
}

/** Asks what the new folder or file is called. */
@Composable
internal fun NewThingName(creating: Creating, onAction: (AppAction) -> Unit) {
    val close = { creating.naming = null }
    when (creating.naming) {
        NewThing.Folder -> NewName(
            title = stringResource(R.string.dialog_new_folder),
            placeholder = stringResource(R.string.folder_name_label),
            onNamed = { onAction(AppAction.CreateFolder(it)) },
            onDismiss = close
        )
        NewThing.File -> NewName(
            title = stringResource(R.string.dialog_new_file),
            placeholder = stringResource(R.string.file_name_label),
            onNamed = { onAction(AppAction.CreateFile(it)) },
            onDismiss = close
        )
        null -> Unit
    }
}

@Composable
private fun NewName(title: String, placeholder: String, onNamed: (String) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHaptics.current
    var name by rememberSaveable { mutableStateOf("") }
    NamePrompt(
        title = title,
        canSave = name.isNotBlank(),
        onSave = {
            onNamed(name)
            onDismiss()
        },
        onCancel = {
            haptics.tap()
            onDismiss()
        },
        onDismiss = onDismiss
    ) { field ->
        KobbloTextField(name, { name = it }, placeholder, field)
    }
}
