package com.kobblo.gallery.file.explorer.ui.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionPair
import com.kobblo.gallery.file.explorer.ui.design.KobbloFormField
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheetTitle
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace

/** How many of the new names the sheet shows before they're agreed to. */
private const val PreviewedNames = 5

private val NumberKeys = KeyboardOptions(keyboardType = KeyboardType.Number)

/** What has been typed into the rename sheet. The numbers stay as text, so a field can be empty while it's being changed. */
@Stable
private class BatchForm(firstName: String) {
    var base by mutableStateOf(baseNameOf(firstName))
    var extension by mutableStateOf(extensionFieldOf(firstName))
    var numbering by mutableStateOf(Numbering.None)
    var numberFirst by mutableStateOf(false)
    var start by mutableStateOf("1")
    var digits by mutableStateOf("0")

    val names: BatchNames
        get() = BatchNames(base, extension, numbering, numberFirst, start.toIntOrNull() ?: 1, digits.toIntOrNull() ?: 0)
}

/** Renames everything selected at once, when that's been asked for. */
@Composable
internal fun BatchRenameSheet(state: AppState, onAction: (AppAction) -> Unit) {
    if (!state.showBatchRenameDialog) return
    val count = state.files.count { it.id in state.selectedFiles }
    val firstPicked = state.selectedFiles.firstOrNull()
    val form = remember { BatchForm(state.files.find { it.id == firstPicked }?.name.orEmpty()) }
    val close = { onAction(AppAction.SetShowBatchRenameDialog(false)) }

    KobbloSheet(onDismissRequest = close) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = KobbloSpace.lg)
                .verticalScroll(rememberScrollState())
        ) {
            KobbloSheetTitle(
                text = stringResource(R.string.batch_rename),
                subtitle = pluralStringResource(R.plurals.view_file_count, count, count)
            )
            NameFields(form)
            NumberingFields(form)
            NamePreview(form.names, count)
            Spacer(Modifier.height(KobbloSpace.md))
            KobbloActionPair(
                confirmLabel = stringResource(R.string.rename_action),
                onConfirm = { onAction(form.names.asAction()) },
                onCancel = close
            )
        }
    }
}

private fun BatchNames.asAction() = AppAction.BatchRenameFiles(
    base, extension, padding = digits, startNumber = start, isPrefix = numberFirst, style = numbering.word
)

@Composable
private fun NameFields(form: BatchForm) {
    KobbloGroup {
        KobbloFormField(stringResource(R.string.base_name_label), form.base, { form.base = it })
        KobbloFormField(stringResource(R.string.file_type_label), form.extension, { form.extension = it }, showDivider = false)
    }
}

@Composable
private fun NumberingFields(form: BatchForm) {
    val haptics = LocalHaptics.current
    KobbloGroupHeader(stringResource(R.string.numbering_style_label))
    KobbloSegmentedControl(
        options = Numbering.entries.map { if (it == Numbering.None) stringResource(R.string.view_numbering_none) else it.word },
        selectedIndex = form.numbering.ordinal,
        onSelect = {
            haptics.tap()
            form.numbering = Numbering.entries[it]
        },
        modifier = Modifier.padding(horizontal = KobbloSpace.screen)
    )
    if (form.numbering == Numbering.None) return

    Spacer(Modifier.height(KobbloSpace.sm))
    KobbloSegmentedControl(
        options = listOf(stringResource(R.string.prefix_label), stringResource(R.string.suffix_label)),
        selectedIndex = if (form.numberFirst) 0 else 1,
        onSelect = {
            haptics.tap()
            form.numberFirst = it == 0
        },
        modifier = Modifier.padding(horizontal = KobbloSpace.screen)
    )
    Spacer(Modifier.height(KobbloSpace.sm))
    KobbloGroup {
        KobbloFormField(stringResource(R.string.starts_from_label), form.start, { form.start = it }, keyboardOptions = NumberKeys)
        KobbloFormField(
            stringResource(R.string.leading_zeros_label), form.digits, { form.digits = it },
            showDivider = false, keyboardOptions = NumberKeys
        )
    }
}

/** The first few new names, as they'd come out. */
@Composable
private fun NamePreview(names: BatchNames, count: Int) {
    KobbloGroupHeader(stringResource(R.string.live_preview))
    val shown = count.coerceAtMost(PreviewedNames)
    KobbloGroup {
        repeat(shown) { index -> KobbloRow(title = names.nameAt(index), showDivider = index < shown - 1) }
    }
    if (count > shown) KobbloGroupFooter(stringResource(R.string.and_more_files, count - shown))
}
