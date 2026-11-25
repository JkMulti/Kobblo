package com.kobblo.gallery.file.explorer.tools.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.ContextThemeWrapper
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolConfirm
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.ToolsRoute
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

private const val PREFS = "tools_prefs"
private const val PREF_WRAP = "editor_wrap"
private const val PREF_LINE_NUMBERS = "editor_line_numbers"

/** Highlighting more than this makes editing sluggish, and nobody reads that many marks. */
private const val MAX_HIGHLIGHTS = 1000

/**
 * Text files, read and edited. A file too big to edit opens read-only through a line index, and one
 * that isn't text asks first, so nothing here can freeze the app or quietly damage a file.
 */
@Composable
internal fun TextEditorScreen(
    path: String,
    startEditing: Boolean,
    forceText: Boolean,
    onAction: (AppAction) -> Unit,
    goToLine: Int? = null
) {
    val context = LocalContext.current
    var allowBinary by remember { mutableStateOf(forceText) }
    var charset by remember { mutableStateOf<Charset?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<TextOpenResult?>(null) }
    val fileName = remember(path) { File(path).name }

    LaunchedEffect(path, allowBinary, charset, reloadKey) {
        result = null
        progress = 0f
        val job = coroutineContext
        result = TextFileStore.open(context, path, allowBinary, charset, { progress = it }, { job.isActive })
    }

    when (val loaded = result) {
        null -> Box(Modifier.fillMaxSize().background(KobbloDesign.colors.canvas).statusBarsPadding()) {
            ToolLoading(label = if (progress > 0f) "${(progress * 100).toInt()}%" else null)
        }
        is TextOpenResult.Failed -> MessageFrame(fileName) {
            KobbloMessage(
                icon = AppIcons.Alert,
                title = stringResource(R.string.tools_editor_open_failed),
                body = loaded.message
            )
        }
        TextOpenResult.Binary -> MessageFrame(fileName) {
            KobbloMessage(
                icon = AppIcons.Code,
                title = stringResource(R.string.tools_editor_binary_title),
                body = stringResource(R.string.tools_editor_binary_body),
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { allowBinary = true }) {
                            Text(stringResource(R.string.tools_open_as_text))
                        }
                        TextButton(
                            onClick = {
                                Hub.back()
                                Hub.open(ToolsRoute.Hex(path))
                            }
                        ) {
                            Text(stringResource(R.string.tools_view_hex), color = KobbloDesign.colors.action)
                        }
                    }
                }
            )
        }
        is TextOpenResult.TooLarge -> LargeTextScreen(
            path = path,
            index = loaded.index,
            onReopen = { charset = it }
        )
        is TextOpenResult.Editable -> EditorContent(
            path = path,
            fileName = fileName,
            opened = loaded,
            startEditing = startEditing,
            goToLine = goToLine,
            onAction = onAction,
            onReopen = {
                charset = it
                reloadKey++
            }
        )
    }
}

/** The toolbar and canvas around a screen with nothing but a message. */
@Composable
private fun MessageFrame(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(KobbloDesign.colors.canvas).statusBarsPadding()) {
        EditorToolbar(title = title, subtitle = null, onBack = { Hub.back() }) {}
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun EditorToolbar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit
) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = KobbloSpace.screen)
    ) {
        KobbloIconButton(
            icon = AppIcons.Back,
            description = stringResource(R.string.back),
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(subtitle, style = KobbloType.caption, color = colors.smoke, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            trailing()
        }
    }
}

private class EditorHolder {
    var view: CodeEditText? = null
    val history = EditHistory()
}

@Composable
private fun EditorContent(
    path: String,
    fileName: String,
    opened: TextOpenResult.Editable,
    startEditing: Boolean,
    goToLine: Int?,
    onAction: (AppAction) -> Unit,
    onReopen: (Charset) -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val holder = remember(opened) { EditorHolder() }

    var format by remember(opened) { mutableStateOf(opened.decoded.format) }
    var formatChanged by remember(opened) { mutableStateOf(false) }
    var baselineLength by remember(opened) { mutableStateOf(opened.length) }
    var baselineModified by remember(opened) { mutableStateOf(opened.modified) }
    var revision by remember(opened) { mutableIntStateOf(0) }

    val canEdit = opened.readOnlyReason == null
    var editing by remember(opened) { mutableStateOf(startEditing && canEdit) }
    var wrap by remember { mutableStateOf(prefs.getBoolean(PREF_WRAP, true)) }
    var lineNumbers by remember { mutableStateOf(prefs.getBoolean(PREF_LINE_NUMBERS, false)) }

    var showFind by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showEncodings by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    // What to do once the unsaved question is answered, or once a blocked save goes through.
    var afterDecision by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmConvert by remember { mutableStateOf(false) }
    var confirmEncoding by remember { mutableStateOf<Charset?>(null) }
    var saving by remember { mutableStateOf(false) }

    var loading by remember(opened) { mutableStateOf(true) }
    LaunchedEffect(opened) {
        val view = holder.view ?: return@LaunchedEffect
        view.load(opened.decoded.text)
        view.isReadOnly = !editing
        loading = false
        revision++
        if (editing) view.requestFocus()
        if (goToLine != null) view.goToLine(goToLine)
    }

    val dirty = (revision >= 0 && holder.history.isDirty) || formatChanged
    val savedMessage = stringResource(R.string.tools_editor_saved)
    val saveFailedTemplate = stringResource(R.string.tools_editor_save_failed)

    fun leave() {
        val view = holder.view
        if (view != null) {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        Hub.back()
    }

    /** The last gate: writes what's in the editor with the format as it now stands. */
    fun write(andThen: () -> Unit = {}) {
        val view = holder.view ?: return
        val text = view.text.toString()
        val writing = format
        saving = true
        scope.launch {
            val bytes = withContext(Dispatchers.Default) { TextCodec.encode(text, writing) }
            when (val saved = TextFileStore.save(context, path, bytes)) {
                is SaveResult.Saved -> {
                    holder.history.markSaved()
                    formatChanged = false
                    baselineLength = saved.length
                    baselineModified = saved.modified
                    revision++
                    onAction(AppAction.ShowToast(savedMessage))
                    andThen()
                }
                is SaveResult.Failed -> onAction(AppAction.ShowToast(saveFailedTemplate))
            }
            saving = false
        }
    }

    fun save(andThen: () -> Unit = {}) {
        val view = holder.view ?: return
        val file = File(path)
        // Someone else changed the file since it was opened: never overwrite that without asking.
        if (file.exists() && (file.length() != baselineLength || file.lastModified() != baselineModified)) {
            afterDecision = andThen
            confirmOverwrite = true
            return
        }
        if (!TextCodec.canEncode(view.text.toString(), format.charset)) {
            afterDecision = andThen
            confirmConvert = true
            return
        }
        write(andThen)
    }

    /** Runs [action], asking about unsaved edits first, because it takes this screen away. */
    fun guarded(action: () -> Unit) {
        if (dirty) {
            afterDecision = action
            confirmLeave = true
        } else {
            action()
        }
    }

    fun tryLeave() = guarded { leave() }

    /** One undo or redo. Taking back a Replace All swaps the whole text, so it loads piece by piece. */
    fun applyStep(edit: TextEdit) {
        val view = holder.view ?: return
        val swapsEverything = edit.start == 0 && edit.removed.length == view.length() && edit.inserted.length > 64 * 1024
        if (swapsEverything) {
            scope.launch {
                loading = true
                view.isReadOnly = true
                view.load(edit.inserted)
                view.isReadOnly = !editing
                loading = false
                revision++
            }
        } else {
            view.apply(edit)
            revision++
        }
    }

    BackHandler {
        if (showFind) showFind = false else tryLeave()
    }

    val subtitle = when {
        loading -> stringResource(R.string.tools_editor_loading)
        opened.readOnlyReason != null -> stringResource(R.string.tools_editor_read_only)
        dirty -> stringResource(R.string.tools_editor_edited)
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        EditorToolbar(title = fileName, subtitle = subtitle, onBack = { tryLeave() }) {
            if (editing) {
                TextButton(onClick = { save() }, enabled = dirty && !saving) {
                    Text(
                        text = stringResource(R.string.tools_save),
                        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                        color = if (dirty && !saving) colors.action else colors.pewter
                    )
                }
            } else if (canEdit) {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        editing = true
                        holder.view?.let { view ->
                            view.isReadOnly = false
                            view.requestFocus()
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(view, 0)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.tools_edit),
                        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
                        color = if (loading) colors.pewter else colors.action
                    )
                }
            }
            KobbloIconButton(
                icon = AppIcons.MoreHoriz,
                description = stringResource(R.string.tools_options),
                onClick = { showOptions = true }
            )
        }

        if (opened.readOnlyReason != null) {
            ReadOnlyNote(opened.readOnlyReason)
        }
        if (opened.decoded.format.mixedLineEndings && canEdit) {
            NoteLine(stringResource(R.string.tools_editor_mixed_endings, format.lineEnding.label))
        }

        if (showFind) {
            FindBar(
                holder = holder,
                revision = revision,
                readOnly = !editing,
                onReplaced = { revision++ },
                onClose = { showFind = false }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(colors.snow)) {
            val ink = colors.ink.toArgb()
            val smoke = colors.smoke.toArgb()
            val mist = colors.canvas.toArgb()
            val selection = colors.action.copy(alpha = 0.28f).toArgb()
            val accent = colors.action.toArgb()
            val isDark = colors.isDark
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { base ->
                    val themed = ContextThemeWrapper(
                        base,
                        if (isDark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
                    )
                    CodeEditText(themed).also { view ->
                        holder.view = view
                        view.setColors(ink, smoke, mist, selection, accent)
                        view.setWrap(wrap)
                        view.showLineNumbers = lineNumbers
                        view.isReadOnly = true
                        view.onEdit = { holder.history.record(it) }
                        view.onChanged = { revision++ }
                    }
                },
                update = { view ->
                    view.setColors(ink, smoke, mist, selection, accent)
                    view.showLineNumbers = lineNumbers
                }
            )
        }

        EditorBottomBar(
            canUndo = editing && revision >= 0 && holder.history.canUndo,
            canRedo = editing && revision >= 0 && holder.history.canRedo,
            status = "${format.charset.displayLabel()}${if (format.hasBom) " BOM" else ""} · ${format.lineEnding.label}",
            onUndo = { holder.history.undo()?.let(::applyStep) },
            onRedo = { holder.history.redo()?.let(::applyStep) },
            onFind = { if (!loading) showFind = !showFind }
        )
    }

    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_editor_find), AppIcons.Search, showDivider = true) {
                    showOptions = false
                    showFind = true
                }
                ToolMenuRow(stringResource(R.string.tools_editor_go_to_line), AppIcons.List) {
                    showOptions = false
                    showGoTo = true
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.tools_editor_wrap),
                    trailing = {
                        ExpressiveSwitch(checked = wrap, onCheckedChange = {
                            wrap = it
                            prefs.edit().putBoolean(PREF_WRAP, it).apply()
                            holder.view?.setWrap(it)
                        })
                    },
                    showDivider = true
                )
                KobbloRow(
                    title = stringResource(R.string.tools_editor_line_numbers),
                    trailing = {
                        ExpressiveSwitch(checked = lineNumbers, onCheckedChange = {
                            lineNumbers = it
                            prefs.edit().putBoolean(PREF_LINE_NUMBERS, it).apply()
                        })
                    }
                )
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(
                    title = stringResource(R.string.tools_editor_encoding),
                    trailingText = format.charset.displayLabel(),
                    showDivider = true,
                    icon = AppIcons.ChevronRight
                ) {
                    showOptions = false
                    showEncodings = true
                }
                val endings = LineEnding.entries
                endings.forEachIndexed { index, ending ->
                    ToolMenuRow(
                        title = stringResource(R.string.tools_editor_line_ending, ending.label),
                        checked = ending == format.lineEnding,
                        showDivider = index < endings.lastIndex,
                        enabled = canEdit
                    ) {
                        if (ending != format.lineEnding) {
                            format = format.copy(lineEnding = ending)
                            formatChanged = true
                        }
                        showOptions = false
                    }
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_view_hex), AppIcons.Hash, showDivider = true) {
                    showOptions = false
                    guarded { Hub.open(ToolsRoute.Hex(path)) }
                }
                ToolMenuRow(stringResource(R.string.tools_open_with), AppIcons.OpenExternal) {
                    showOptions = false
                    openElsewhere(context, path)
                }
            }
        }
    }

    if (showEncodings) {
        ToolSheet(title = stringResource(R.string.tools_editor_encoding), onDismiss = { showEncodings = false }) {
            KobbloGroup {
                TextCodec.choices.forEachIndexed { index, choice ->
                    ToolMenuRow(
                        title = choice.displayLabel(),
                        checked = choice == format.charset,
                        showDivider = index < TextCodec.choices.lastIndex
                    ) {
                        showEncodings = false
                        if (choice != format.charset) {
                            if (dirty) confirmEncoding = choice else onReopen(choice)
                        }
                    }
                }
            }
            com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter(stringResource(R.string.tools_editor_encoding_note))
        }
    }

    if (showGoTo) {
        GoToLineSheet(
            onDismiss = { showGoTo = false },
            onGo = { line ->
                showGoTo = false
                holder.view?.let { view ->
                    view.goToLine(line)
                    view.requestFocus()
                }
            }
        )
    }

    if (confirmLeave) {
        ToolConfirm(
            title = stringResource(R.string.tools_editor_leave_title, fileName),
            message = stringResource(R.string.tools_editor_leave_body),
            confirmLabel = stringResource(R.string.tools_save),
            secondaryLabel = stringResource(R.string.tools_editor_dont_save),
            onSecondary = {
                confirmLeave = false
                afterDecision?.invoke()
            },
            onConfirm = {
                confirmLeave = false
                save(afterDecision ?: {})
            },
            onDismiss = { confirmLeave = false }
        )
    }

    if (confirmOverwrite) {
        ToolConfirm(
            title = stringResource(R.string.tools_editor_changed_title),
            message = stringResource(R.string.tools_editor_changed_body),
            confirmLabel = stringResource(R.string.tools_editor_overwrite),
            danger = true,
            secondaryLabel = stringResource(R.string.tools_editor_reload),
            onSecondary = {
                confirmOverwrite = false
                onReopen(format.charset)
            },
            onConfirm = {
                confirmOverwrite = false
                write(afterDecision ?: {})
            },
            onDismiss = { confirmOverwrite = false }
        )
    }

    confirmEncoding?.let { choice ->
        ToolConfirm(
            title = stringResource(R.string.tools_editor_reopen_title, choice.displayLabel()),
            message = stringResource(R.string.tools_editor_reopen_body),
            confirmLabel = stringResource(R.string.tools_editor_reopen_action),
            danger = true,
            onConfirm = {
                confirmEncoding = null
                onReopen(choice)
            },
            onDismiss = { confirmEncoding = null }
        )
    }

    if (confirmConvert) {
        ToolConfirm(
            title = stringResource(R.string.tools_editor_convert_title, format.charset.displayLabel()),
            message = stringResource(R.string.tools_editor_convert_body),
            confirmLabel = stringResource(R.string.tools_editor_convert_action),
            onConfirm = {
                confirmConvert = false
                format = format.copy(charset = Charsets.UTF_8, hasBom = false)
                formatChanged = true
                write(afterDecision ?: {})
            },
            onDismiss = { confirmConvert = false }
        )
    }
}

@Composable
private fun ReadOnlyNote(reason: ReadOnlyReason) {
    NoteLine(
        stringResource(
            when (reason) {
                ReadOnlyReason.NOT_WRITABLE -> R.string.tools_editor_ro_not_writable
                ReadOnlyReason.TEMPORARY_COPY -> R.string.tools_editor_ro_temporary
                ReadOnlyReason.UNSAFE_ENCODING -> R.string.tools_editor_ro_encoding
            }
        )
    )
}

@Composable
internal fun NoteLine(text: String) {
    Text(
        text = text,
        style = KobbloType.footnote,
        color = KobbloDesign.colors.smoke,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 6.dp)
    )
}

@Composable
private fun EditorBottomBar(
    canUndo: Boolean,
    canRedo: Boolean,
    status: String,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KobbloIconButton(
            icon = AppIcons.Undo,
            description = stringResource(R.string.tools_editor_undo),
            onClick = { if (canUndo) onUndo() },
            tint = if (canUndo) colors.action else colors.pewter,
            background = androidx.compose.ui.graphics.Color.Transparent
        )
        KobbloIconButton(
            icon = AppIcons.Redo,
            description = stringResource(R.string.tools_editor_redo),
            onClick = { if (canRedo) onRedo() },
            tint = if (canRedo) colors.action else colors.pewter,
            background = androidx.compose.ui.graphics.Color.Transparent
        )
        KobbloIconButton(
            icon = AppIcons.Search,
            description = stringResource(R.string.tools_editor_find),
            onClick = onFind,
            tint = colors.action,
            background = androidx.compose.ui.graphics.Color.Transparent
        )
        Spacer(Modifier.weight(1f))
        Text(status, style = KobbloType.caption, color = colors.smoke, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun FindBar(holder: EditorHolder, revision: Int, readOnly: Boolean, onReplaced: () -> Unit, onClose: () -> Unit) {
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<IntRange>>(emptyList()) }
    var current by remember { mutableIntStateOf(-1) }
    val highlight = colors.action.copy(alpha = 0.22f).toArgb()
    val options = TextSearch.Options(matchCase = matchCase, regex = useRegex)

    fun clearHighlights() {
        val text = holder.view?.text ?: return
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach(text::removeSpan)
    }

    fun show(index: Int) {
        val view = holder.view ?: return
        val range = matches.getOrNull(index) ?: return
        current = index
        view.setSelection(range.first, range.last + 1)
        view.bringPointIntoView(range.first)
    }

    LaunchedEffect(query, matchCase, useRegex, revision) {
        delay(150)
        val view = holder.view ?: return@LaunchedEffect
        val regex = TextSearch.compile(query, options)
        val snapshot = view.text.toString()
        val found = if (regex == null) emptyList() else withContext(Dispatchers.Default) { TextSearch.findAll(snapshot, regex) }
        matches = found
        clearHighlights()
        val text = view.text ?: return@LaunchedEffect
        found.take(MAX_HIGHLIGHTS).forEach { range ->
            if (range.last < text.length) {
                text.setSpan(BackgroundColorSpan(highlight), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        current = if (found.isEmpty()) -1 else TextSearch.nearest(found, view.selectionStart, forward = true)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { clearHighlights() }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KobbloSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.tools_editor_find),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (matches.isEmpty()) "0" else "${current + 1}/${matches.size}",
                style = KobbloType.caption,
                color = colors.smoke,
                maxLines = 1
            )
            Spacer(Modifier.width(2.dp))
            KobbloIconButton(
                icon = AppIcons.ChevronUp,
                description = stringResource(R.string.tools_editor_previous),
                onClick = {
                    val caret = holder.view?.selectionStart ?: 0
                    show(TextSearch.nearest(matches, caret, forward = false))
                },
                size = 34.dp,
                iconSize = 18.dp,
                tint = colors.action,
                background = androidx.compose.ui.graphics.Color.Transparent
            )
            KobbloIconButton(
                icon = AppIcons.ChevronDown,
                description = stringResource(R.string.tools_editor_next),
                onClick = {
                    val caret = holder.view?.selectionEnd ?: 0
                    show(TextSearch.nearest(matches, caret, forward = true))
                },
                size = 34.dp,
                iconSize = 18.dp,
                tint = colors.action,
                background = androidx.compose.ui.graphics.Color.Transparent
            )
            KobbloIconButton(
                icon = AppIcons.Close,
                description = stringResource(R.string.tools_editor_close_find),
                onClick = onClose,
                size = 34.dp,
                iconSize = 16.dp,
                tint = colors.smoke,
                background = androidx.compose.ui.graphics.Color.Transparent
            )
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FindToggle("Aa", stringResource(R.string.tools_editor_match_case), matchCase) { matchCase = !matchCase }
            FindToggle(".*", stringResource(R.string.tools_editor_regex), useRegex) { useRegex = !useRegex }
            if (!readOnly) {
                BasicTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    singleLine = true,
                    textStyle = KobbloType.callout.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.action),
                    modifier = Modifier
                        .weight(1f)
                        .clip(CapsuleShape)
                        .background(colors.mist)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        if (replacement.isEmpty()) {
                            Text(stringResource(R.string.tools_editor_replace_with), style = KobbloType.callout, color = colors.smoke, maxLines = 1)
                        }
                        inner()
                    }
                )
                TextButton(
                    onClick = {
                        val view = holder.view ?: return@TextButton
                        val text = view.text ?: return@TextButton
                        val regex = TextSearch.compile(query, options) ?: return@TextButton
                        val range = matches.getOrNull(current) ?: return@TextButton
                        if (range.last >= text.length) return@TextButton
                        val value = TextSearch.replacementFor(text.toString(), range, regex, replacement, options)
                        text.replace(range.first, range.last + 1, value)
                        view.setSelection(range.first + value.length)
                    },
                    enabled = current >= 0,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                ) {
                    Text(stringResource(R.string.tools_editor_replace), style = KobbloType.callout, color = colors.action, maxLines = 1)
                }
                TextButton(
                    onClick = {
                        val view = holder.view ?: return@TextButton
                        val text = view.text ?: return@TextButton
                        val regex = TextSearch.compile(query, options) ?: return@TextButton
                        scope.launch {
                            val before = text.toString()
                            val plan = withContext(Dispatchers.Default) { TextSearch.planReplacements(before, regex, replacement, options) }
                            if (plan.isNotEmpty()) {
                                clearHighlights()
                                view.replaceAll(plan)
                                // One undo step for the whole thing, as the action was one tap.
                                holder.history.record(TextEdit(0, before, view.text.toString()))
                                onReplaced()
                            }
                        }
                    },
                    enabled = matches.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                ) {
                    Text(stringResource(R.string.tools_editor_replace_all), style = KobbloType.callout, color = colors.action, maxLines = 1)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FindToggle(label: String, description: String, on: Boolean, onToggle: () -> Unit) {
    val colors = KobbloDesign.colors
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 34.dp)
            .clip(CapsuleShape)
            .background(if (on) colors.action else colors.mist)
            .semantics { contentDescription = description }
            .quietClickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = KobbloType.footnote.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontWeight = FontWeight.Medium),
            color = if (on) colors.onAction else colors.ink
        )
    }
}

@Composable
internal fun GoToLineSheet(onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    val colors = KobbloDesign.colors
    var value by remember { mutableStateOf("") }
    ToolSheet(title = stringResource(R.string.tools_editor_go_to_line), onDismiss = onDismiss) {
        BasicTextField(
            value = value,
            onValueChange = { typed -> value = typed.filter(Char::isDigit).take(10) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = KobbloType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.action),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KobbloSpace.screen)
                .clip(CapsuleShape)
                .background(colors.mist)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(stringResource(R.string.tools_editor_line_number_hint), style = KobbloType.body, color = colors.smoke)
                }
                inner()
            }
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { value.toIntOrNull()?.let(onGo) },
            enabled = value.toIntOrNull() != null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen)
        ) {
            Text(stringResource(R.string.tools_editor_go), style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun Charset.displayLabel(): String = when (name()) {
    "UTF-16LE" -> "UTF-16 LE"
    "UTF-16BE" -> "UTF-16 BE"
    "windows-1252" -> stringResource(R.string.tool_encoding_western)
    "ISO-8859-1" -> "Latin-1"
    "windows-1251" -> stringResource(R.string.tool_encoding_cyrillic)
    else -> name()
}

/** Offers a file to other apps. */
internal fun openElsewhere(context: Context, path: String) {
    try {
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        val mime = com.kobblo.gallery.file.explorer.tools.picker.StorageDocumentsProvider.mimeOf(File(path).name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.tools_open_with)))
    } catch (_: Exception) {
    }
}
