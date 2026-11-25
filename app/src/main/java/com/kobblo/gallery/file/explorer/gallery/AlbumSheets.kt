package com.kobblo.gallery.file.explorer.gallery

import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveConfirmationSheet
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The frame every sheet here shares, the same as the file menu's. */
@Composable
internal fun GallerySheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    KobbloSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
        color = KobbloDesign.colors.ink,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen, vertical = 12.dp)
    )
}

@Composable
private fun SheetAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    showDivider: Boolean = false,
    destructive: Boolean = false
) {
    val colors = KobbloDesign.colors
    KobbloRow(
        title = title,
        titleColor = if (destructive) colors.danger else colors.ink,
        trailing = {
            Icon(icon, contentDescription = null, tint = if (destructive) colors.danger else colors.action, modifier = Modifier.size(22.dp))
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

/** The Albums screen's `...`: how to order them, and the numbers behind the library. */
@Composable
internal fun AlbumOptionsSheet(
    sort: AlbumSort,
    onSort: (AlbumSort) -> Unit,
    onStats: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    GallerySheet(onDismiss) {
        KobbloGroupHeader(title = stringResource(R.string.gallery_sort_by), modifier = Modifier.padding(top = 0.dp))
        KobbloGroup {
            val options = listOf(
                AlbumSort.DATE to stringResource(R.string.gallery_sort_date),
                AlbumSort.NAME to stringResource(R.string.gallery_sort_name),
                AlbumSort.COUNT to stringResource(R.string.gallery_sort_count)
            )
            options.forEachIndexed { index, (option, label) ->
                KobbloRow(
                    title = label,
                    trailing = {
                        if (option == sort) {
                            Icon(AppIcons.Check, contentDescription = stringResource(R.string.media_selected), tint = colors.action, modifier = Modifier.size(20.dp))
                        }
                    },
                    showDivider = index < options.lastIndex,
                    onClick = { onSort(option) }
                )
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.gallery_library_stats),
                trailing = { KobbloChevron() },
                onClick = onStats
            )
        }
    }
}

/** The order inside one album. */
@Composable
internal fun PhotoSortSheet(
    sort: PhotoSort,
    onSort: (PhotoSort) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    GallerySheet(onDismiss) {
        KobbloGroupHeader(title = stringResource(R.string.gallery_sort_by), modifier = Modifier.padding(top = 0.dp))
        KobbloGroup {
            val options = listOf(
                PhotoSort.NEWEST to stringResource(R.string.gallery_sort_newest),
                PhotoSort.OLDEST to stringResource(R.string.gallery_sort_oldest),
                PhotoSort.NAME to stringResource(R.string.gallery_sort_name),
                PhotoSort.SIZE to stringResource(R.string.gallery_sort_size)
            )
            options.forEachIndexed { index, (option, label) ->
                KobbloRow(
                    title = label,
                    trailing = {
                        if (option == sort) {
                            Icon(AppIcons.Check, contentDescription = stringResource(R.string.media_selected), tint = colors.action, modifier = Modifier.size(20.dp))
                        }
                    },
                    showDivider = index < options.lastIndex,
                    onClick = { onSort(option) }
                )
            }
        }
    }
}

/** What a folder album's menu offers, from holding it in Albums or from `...` inside it. */
@Composable
internal fun FolderAlbumMenu(
    album: FolderAlbum,
    files: List<FileItem>,
    onAction: (AppAction) -> Unit,
    onDismiss: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onSort: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val store = remember { GalleryStore.get(context) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val folder = remember(album.path) { File(album.path) }

    when {
        renaming -> AlbumNameSheet(
            title = stringResource(R.string.gallery_album_rename),
            initial = album.name,
            confirmLabel = stringResource(R.string.gallery_album_rename_action),
            onConfirm = { typed ->
                onDismiss()
                val name = sanitizeAlbumName(typed)
                val target = name?.let { File(folder.parentFile, it) }
                GalleryWorkScope.launch {
                    val members = files.filter { it.path.substringBeforeLast('/') == album.path }
                    when {
                        target == null -> onAction(AppAction.ShowToast(context.getString(R.string.gallery_album_rename_failed)))
                        target.path == album.path -> Unit
                        target.exists() -> onAction(AppAction.ShowToast(context.getString(R.string.gallery_album_name_taken)))
                        AlbumTransfer.renameAlbum(context, folder, target, members) -> {
                            store.renameAlbum(album.path, target.path)
                            if (GalleryNav.current == GalleryDest.Folder(album.path, album.name)) {
                                GalleryNav.replace(GalleryDest.Folder(target.path, target.name))
                            }
                            onAction(AppAction.Reload)
                        }
                        else -> onAction(AppAction.ShowToast(context.getString(R.string.gallery_album_rename_failed)))
                    }
                }
            },
            onDismiss = onDismiss
        )

        deleting -> ExpressiveConfirmationSheet(
            title = stringResource(R.string.gallery_album_delete_title, album.name),
            subtitle = stringResource(R.string.gallery_album_delete_body),
            confirmLabel = stringResource(R.string.delete_label),
            onConfirm = {
                onDismiss()
                GalleryWorkScope.launch {
                    deleteAlbum(context, album, files)
                    if (GalleryNav.current == GalleryDest.Folder(album.path, album.name)) GalleryNav.back()
                    onAction(AppAction.Reload)
                }
            },
            onDismissRequest = onDismiss
        )

        else -> GallerySheet(onDismiss) {
            SheetTitle(album.name)
            KobbloGroup {
                if (onSelectAll != null) {
                    SheetAction(stringResource(R.string.gallery_select_all), AppIcons.CheckCircle, {
                        onDismiss()
                        onSelectAll()
                    }, showDivider = true)
                }
                if (onSort != null) {
                    SheetAction(stringResource(R.string.gallery_sort), AppIcons.Sort, onSort, showDivider = true)
                }
                SheetAction(
                    stringResource(if (album.isPinned) R.string.gallery_album_unpin else R.string.gallery_album_pin),
                    if (album.isPinned) AppIcons.PinFill else AppIcons.Pin,
                    {
                        store.togglePinnedAlbum(album.path)
                        onDismiss()
                    },
                    showDivider = true
                )
                SheetAction(
                    stringResource(if (album.isHiddenFromHome) R.string.gallery_album_show_on_home else R.string.gallery_album_hide_from_home),
                    if (album.isHiddenFromHome) AppIcons.Eye else AppIcons.EyeOff,
                    {
                        store.toggleHiddenAlbum(album.path)
                        onDismiss()
                        onAction(
                            AppAction.ShowToast(
                                context.getString(if (album.isHiddenFromHome) R.string.gallery_album_shown else R.string.gallery_album_hidden_note, album.name)
                            )
                        )
                    },
                    showDivider = true
                )
                if (album.path in store.albumCovers.value) {
                    SheetAction(stringResource(R.string.gallery_album_newest_cover), AppIcons.Photo, {
                        store.clearAlbumCover(album.path)
                        onDismiss()
                    }, showDivider = true)
                }
                SheetAction(stringResource(R.string.gallery_album_rename_action), AppIcons.Pencil, { renaming = true }, showDivider = true)
                SheetAction(stringResource(R.string.gallery_album_delete), AppIcons.Trash, { deleting = true }, destructive = true)
            }
        }
    }
}

/**
 * Everything in the album goes to Recently Deleted. Locked files stay where they are, since deleting
 * one needs the password, and so does anything that isn't media, which keeps the folder alive for them.
 */
private suspend fun deleteAlbum(context: android.content.Context, album: FolderAlbum, files: List<FileItem>) {
    withContext(Dispatchers.IO) {
        val paths = files
            .filter { it.path.substringBeforeLast('/') == album.path && !it.isLocked }
            .map { it.path }
        if (paths.isNotEmpty()) FileRepository(context.applicationContext).moveToTrash(paths)
        val folder = File(album.path)
        if (folder.list().isNullOrEmpty()) folder.delete()
        val store = GalleryStore.get(context)
        store.removeCreatedAlbum(album.path)
        if (album.path in store.pinnedAlbums.value && !folder.exists()) store.togglePinnedAlbum(album.path)
    }
}

/** Asks for an album's name, with the keyboard already up. */
@Composable
internal fun AlbumNameSheet(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String = stringResource(R.string.gallery_album_name_hint)
) {
    val colors = KobbloDesign.colors
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val valid = sanitizeAlbumName(value.text) != null

    GallerySheet(onDismiss) {
        Column(modifier = Modifier.imePadding()) {
            SheetTitle(title)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KobbloSpace.screen)
                    .clip(KobbloShapes.sm)
                    .background(colors.snow)
                    .padding(horizontal = KobbloSpace.md, vertical = 12.dp)
            ) {
                if (value.text.isEmpty()) {
                    Text(hint, style = KobbloType.body, color = colors.smoke)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = KobbloType.body.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.action),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = KobbloSpace.screen, end = KobbloSpace.screen, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.gallery_cancel), style = KobbloType.headline, color = colors.action)
                }
                Button(
                    onClick = { onConfirm(value.text) },
                    enabled = valid,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.action,
                        contentColor = colors.onAction,
                        disabledContainerColor = colors.mist,
                        disabledContentColor = colors.pewter
                    )
                ) {
                    Text(confirmLabel, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Where selected media goes: a list of albums, a new one at the top, and whether to move or copy. */
@Composable
internal fun AlbumPickerSheet(
    files: List<FileItem>,
    initiallyMoving: Boolean,
    onPick: (folder: File, move: Boolean) -> Unit,
    onFailed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GalleryStore.get(context) }
    val rootName = stringResource(R.string.gallery_album_root)
    var mode by remember { mutableIntStateOf(if (initiallyMoving) 0 else 1) }
    var naming by remember { mutableStateOf(false) }
    val coverPx = with(LocalDensity.current) { 44.dp.roundToPx() }
    val albums by produceState<List<FolderAlbum>>(emptyList(), files) {
        value = withContext(Dispatchers.Default) {
            buildFolderAlbums(
                files,
                store.pinnedAlbums.value,
                store.createdAlbums.value,
                AlbumSort.DATE,
                rootName
            )
        }
    }

    if (naming) {
        AlbumNameSheet(
            title = stringResource(R.string.gallery_album_new),
            initial = "",
            confirmLabel = stringResource(R.string.gallery_album_create),
            onConfirm = { name ->
                scope.launch {
                    when (val made = createAlbum(context, name)) {
                        is AlbumResult.Made -> onPick(made.folder, mode == 0)
                        AlbumResult.Taken -> {
                            val existing = sanitizeAlbumName(name)?.let { typed -> albums.firstOrNull { it.name == typed } }
                            if (existing != null) onPick(File(existing.path), mode == 0)
                            else onFailed(context.getString(R.string.gallery_album_name_taken))
                        }
                        AlbumResult.Failed -> onFailed(context.getString(R.string.gallery_album_create_failed))
                    }
                }
            },
            onDismiss = { naming = false }
        )
        return
    }

    GallerySheet(onDismiss) {
        SheetTitle(stringResource(R.string.gallery_add_to_album))
        KobbloSegmentedControl(
            options = listOf(stringResource(R.string.gallery_transfer_move), stringResource(R.string.gallery_transfer_copy)),
            selectedIndex = mode,
            onSelect = { mode = it },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen)
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
            item(key = "new") {
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.gallery_album_new),
                        titleColor = colors.action,
                        leading = {
                            Icon(AppIcons.Add, contentDescription = null, tint = colors.action, modifier = Modifier.size(26.dp))
                        },
                        onClick = { naming = true }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            itemsIndexed(albums, key = { _, album -> album.path }) { index, album ->
                val shape = KobbloShapes.groupItem(index, albums.size)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KobbloSpace.screen)
                        .clip(shape)
                        .background(colors.snow)
                ) {
                    KobbloRow(
                        title = album.name,
                        subtitle = pluralStringResource(R.plurals.gallery_item_count, album.count, album.count),
                        leading = {
                            AlbumCover(
                                files = album.covers,
                                sizePx = coverPx,
                                modifier = Modifier.size(38.dp).clip(KobbloShapes.xs)
                            )
                        },
                        showDivider = index < albums.lastIndex,
                        dividerInset = 66.dp,
                        onClick = { onPick(File(album.path), mode == 0) }
                    )
                }
            }
        }
    }
}
