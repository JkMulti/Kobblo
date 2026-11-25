package com.kobblo.gallery.file.explorer.tools.rename

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.editor.EditorToolbar
import com.kobblo.gallery.file.explorer.tools.storage.FolderSizes
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Carries out a plan one file at a time. If a step fails, the steps before it are put back. */
private suspend fun carryOut(moves: List<BatchRename.Move>, repository: FileRepository): Boolean {
    val done = ArrayList<BatchRename.Move>()
    for (move in moves) {
        val from = File(move.parent, move.fromName).path
        if (repository.renameFile(from, move.toName)) {
            done += move
        } else {
            done.asReversed().forEach { step ->
                repository.renameFile(File(step.parent, step.toName).path, step.fromName)
            }
            return false
        }
    }
    moves.forEach { FolderSizes.invalidate(File(it.parent, it.toName).path) }
    return true
}

@Composable
internal fun BatchRenameScreen(paths: List<String>, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val repository = remember { FileRepository(context) }

    val sources by produceState<List<RenameSource>?>(initialValue = null, paths) {
        value = withContext(Dispatchers.Default) {
            paths.map { path ->
                val file = File(path)
                RenameSource(file.name, file.parent.orEmpty(), file.lastModified(), file.isDirectory)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    var pattern by remember { mutableStateOf(TextFieldValue("{name}", TextRange(6))) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var caseIndex by remember { mutableStateOf(0) }
    var start by remember { mutableStateOf("1") }
    var previews by remember { mutableStateOf<List<RenamePreview>>(emptyList()) }
    var applied by remember { mutableStateOf<List<RenamePreview>?>(null) }
    var working by remember { mutableStateOf(false) }

    val rule = RenameRule(
        pattern = pattern.text,
        find = find,
        replace = replace,
        findIsRegex = useRegex,
        caseChange = CaseChange.entries[caseIndex],
        startNumber = start.toIntOrNull() ?: 1
    )

    // Worked out off the main thread: listing every folder for conflicts isn't free on a large batch.
    LaunchedEffect(sources, rule) {
        val list = sources ?: return@LaunchedEffect
        previews = withContext(Dispatchers.Default) {
            val listings = HashMap<String, Set<String>>()
            BatchRename.preview(
                list,
                rule,
                existingNames = { parent -> listings.getOrPut(parent) { File(parent).list()?.toSet().orEmpty() } }
            )
        }
    }

    val changing = previews.count { it.changes && it.problem == null }
    val hasProblem = previews.any { it.problem != null }
    val failedMessage = stringResource(R.string.tools_rename_failed)

    fun apply() {
        val batch = previews
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { carryOut(BatchRename.plan(batch), repository) }
            working = false
            if (ok) {
                applied = batch.filter { it.changes }
                onAction(AppAction.ClearSelection)
                onAction(AppAction.Reload)
            } else {
                onAction(AppAction.ShowToast(failedMessage))
                onAction(AppAction.Reload)
            }
        }
    }

    fun undo() {
        val done = applied ?: return
        working = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { carryOut(BatchRename.undoPlan(done), repository) }
            working = false
            if (ok) {
                applied = null
                onAction(AppAction.Reload)
                Hub.back()
            } else {
                onAction(AppAction.ShowToast(failedMessage))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        EditorToolbar(
            title = stringResource(R.string.tools_rename_title),
            subtitle = stringResource(R.string.tools_rename_count, paths.size),
            onBack = { Hub.back() }
        ) {}

        val list = sources
        if (list == null) {
            ToolLoading()
            return@Column
        }

        val done = applied
        if (done != null) {
            KobbloGroup(Modifier.padding(top = 12.dp)) {
                KobbloRow(title = stringResource(R.string.tools_rename_done, done.size))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                itemsIndexed(done) { index, preview -> PreviewRow(preview, index, done.size) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = ::undo, enabled = !working, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools_editor_undo), style = KobbloType.headline, color = colors.action)
                }
                Button(onClick = { Hub.back() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools_done), style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "pattern") {
                KobbloGroup(Modifier.padding(top = 4.dp)) {
                    RenameField(
                        label = stringResource(R.string.tools_rename_pattern),
                        value = pattern,
                        onValueChange = { pattern = it }
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = KobbloSpace.screen, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "{name}" to R.string.tools_rename_token_name,
                        "{n}" to R.string.tools_rename_token_number,
                        "{n:3}" to R.string.tools_rename_token_number_padded,
                        "{date}" to R.string.tools_rename_token_date,
                        "{parent}" to R.string.tools_rename_token_folder
                    ).forEach { (token, label) ->
                        TokenChip(stringResource(label)) {
                            val selection = pattern.selection
                            val text = pattern.text.replaceRange(selection.min, selection.max, token)
                            pattern = TextFieldValue(text, TextRange(selection.min + token.length))
                        }
                    }
                }
                KobbloGroup {
                    RenameField(stringResource(R.string.tools_rename_find), TextFieldValue(find, TextRange(find.length)), { find = it.text }, showDivider = true)
                    RenameField(stringResource(R.string.tools_rename_replace), TextFieldValue(replace, TextRange(replace.length)), { replace = it.text }, showDivider = true)
                    KobbloRow(
                        title = stringResource(R.string.tools_editor_regex),
                        trailing = { ExpressiveSwitch(checked = useRegex, onCheckedChange = { useRegex = it }) },
                        showDivider = true
                    )
                    RenameField(
                        stringResource(R.string.tools_rename_start),
                        TextFieldValue(start, TextRange(start.length)),
                        { start = it.text.filter(Char::isDigit).take(9) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                KobbloSegmentedControl(
                    options = listOf(
                        stringResource(R.string.tools_rename_case_keep),
                        "abc",
                        "ABC",
                        "Abc"
                    ),
                    selectedIndex = caseIndex,
                    onSelect = { caseIndex = it },
                    modifier = Modifier.padding(horizontal = KobbloSpace.screen)
                )
                KobbloGroupHeader(stringResource(R.string.tools_rename_preview))
            }
            itemsIndexed(previews, key = { _, preview -> preview.source.parent + "/" + preview.source.name }) { index, preview ->
                PreviewRow(preview, index, previews.size)
            }
            if (hasProblem) {
                item(key = "problem_note") { KobbloGroupFooter(stringResource(R.string.tools_rename_problem_note)) }
            }
        }

        // The list can be under the keyboard while typing, so one example stays in view.
        val example = previews.firstOrNull { it.problem != null } ?: previews.firstOrNull { it.changes }
        if (example != null) {
            Text(
                text = example.problem?.let { problemText(it) } ?: "${example.source.name}  →  ${example.newName}",
                style = KobbloType.footnote,
                color = if (example.problem != null) colors.danger else colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content, vertical = 2.dp)
            )
        }
        Button(
            onClick = ::apply,
            enabled = !working && !hasProblem && changing > 0,
            modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.tools_rename_apply, changing),
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun RenameField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    showDivider: Boolean = false
) {
    val colors = KobbloDesign.colors
    Row(
        Modifier
            .fillMaxWidth()
            .rowDivider(showDivider, KobbloSpace.inset, colors.hairline)
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.row)
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = KobbloType.body, color = colors.ink, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = KobbloType.body.copy(color = colors.smoke, textAlign = androidx.compose.ui.text.style.TextAlign.End),
            cursorBrush = SolidColor(colors.action),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TokenChip(label: String, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Text(
        text = label,
        style = KobbloType.footnote,
        color = colors.action,
        maxLines = 1,
        modifier = Modifier
            .clip(CapsuleShape)
            .background(colors.snow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun PreviewRow(preview: RenamePreview, index: Int, count: Int) {
    val colors = KobbloDesign.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(index, count))
            .background(colors.snow)
            .rowDivider(index < count - 1, KobbloSpace.inset, colors.hairline)
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.row)
            .padding(horizontal = KobbloSpace.inset, vertical = KobbloSpace.xs),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = preview.newName,
            style = KobbloType.body,
            color = when {
                preview.problem != null -> colors.danger
                preview.changes -> colors.ink
                else -> colors.smoke
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = preview.problem?.let { problemText(it) } ?: preview.source.name,
            style = KobbloType.footnote,
            color = if (preview.problem != null) colors.danger else colors.smoke,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun problemText(problem: RenameProblem): String = stringResource(
    when (problem) {
        RenameProblem.EMPTY_NAME -> R.string.tools_rename_problem_empty
        RenameProblem.INVALID_CHARACTER -> R.string.tools_rename_problem_invalid
        RenameProblem.DUPLICATE_IN_BATCH -> R.string.tools_rename_problem_duplicate
        RenameProblem.EXISTS -> R.string.tools_rename_problem_exists
        RenameProblem.TOO_LONG -> R.string.tools_rename_problem_long
    }
)
