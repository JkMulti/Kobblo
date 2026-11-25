package com.kobblo.gallery.file.explorer.tools.picker

import androidx.compose.foundation.layout.heightIn
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.kobblo.gallery.file.explorer.compat.StorageAccess
import com.kobblo.gallery.file.explorer.compat.VersionRules
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.FileRepository
import com.kobblo.gallery.file.explorer.explorer.search.SearchEngine
import com.kobblo.gallery.file.explorer.explorer.search.SearchFilters
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.tools.storage.StorageVolumes
import com.kobblo.gallery.file.explorer.tools.storage.VolumeInfo
import com.kobblo.gallery.file.explorer.tools.volumeIcon
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveFileThumbnail
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.LocalSelectionActive
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileMeta
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.pressedRowFill
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.rowDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private enum class PickerSort { NAME, DATE, SIZE }

@Composable
internal fun PickerScreen(
    filter: MimeFilter,
    allowMultiple: Boolean,
    requesterName: String?,
    startAt: String? = null,
    onCancel: () -> Unit,
    onPicked: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val repository = remember { FileRepository(context) }
    val volumes by StorageVolumes.volumes.collectAsState()

    var hasAccess by remember { mutableStateOf(StorageAccess.has(context)) }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = StorageAccess.has(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = StorageAccess.has(context)
                StorageVolumes.refresh(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Null means the top of the picker: recent files and every location.
    var location by rememberSaveable { mutableStateOf(startAt) }
    var search by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(PickerSort.NAME.name) }
    var asGrid by rememberSaveable { mutableStateOf(filter.isVisualOnly) }
    var showOptions by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val sort = PickerSort.valueOf(sortName)
    val current = location

    fun goUp() {
        val at = current ?: return onCancel()
        location = if (volumes.any { it.path == at }) null else File(at).parent
    }

    BackHandler {
        if (search.isNotEmpty()) search = "" else goUp()
    }

    fun pick(file: FileItem) {
        if (file.type == "folder") {
            search = ""
            location = file.path
            return
        }
        if (!filter.accepts(StorageDocumentsProvider.mimeOf(file.name))) return
        if (allowMultiple) {
            if (file.path in selected) selected.remove(file.path) else selected.add(file.path)
        } else {
            onPicked(listOf(file.path))
        }
    }

    val isTop = current == null
    val title = when {
        !isTop -> volumes.firstOrNull { it.path == current }?.name ?: File(current!!).name
        allowMultiple -> stringResource(R.string.tools_picker_title_many)
        else -> stringResource(R.string.tools_picker_title_one)
    }
    val backLabel = when {
        isTop -> stringResource(R.string.cancel)
        volumes.any { it.path == current } -> stringResource(R.string.tools_picker_locations)
        else -> File(current!!).parentFile?.let { parent ->
            volumes.firstOrNull { it.path == parent.path }?.name ?: parent.name
        } ?: stringResource(R.string.back)
    }

    val entries by produceState<List<FileItem>?>(initialValue = null, current, search, sort, hasAccess) {
        value = null
        if (!hasAccess) return@produceState
        if (search.isNotEmpty()) {
            val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
            SearchEngine.run(
                context = context,
                repository = repository,
                query = search,
                filters = SearchFilters(everywhere = current == null),
                location = current.orEmpty(),
                showHidden = false,
                current = emptyList()
            ) { snapshot ->
                // Before the first answer it would read as no results, rather than as still looking.
                val shown = snapshot.files.filterNot { locks.covers(it.path) }
                if (shown.isNotEmpty() || snapshot.done) value = sortEntries(shown, sort, keepRecency = false)
            }
            return@produceState
        }
        val listed = withContext(Dispatchers.IO) {
            try {
                when {
                    current != null -> {
                        val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
                        repository.getFiles(current).filter { !it.isHidden && !locks.covers(it.path) }
                    }
                    else -> recentFiles(context, repository, filter)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        value = sortEntries(listed, sort, keepRecency = current == null)
    }

    val largeTitle = rememberLargeTitleState(height = 100.dp)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = navBottom + if (selected.isNotEmpty()) 88.dp else 16.dp

    Box(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
            ExpressiveScreenHeader(
                title = title,
                subtitle = if (isTop && requesterName != null) stringResource(R.string.tools_picker_for, requesterName) else "",
                modifier = Modifier.padding(horizontal = KobbloSpace.screen),
                onBack = { goUp() },
                backLabel = backLabel,
                rightAction = {
                    KobbloIconButton(
                        icon = AppIcons.MoreHoriz,
                        description = stringResource(R.string.tools_options),
                        onClick = { showOptions = true }
                    )
                },
                showSearch = hasAccess,
                query = search,
                onQueryChange = { search = it },
                searchPlaceholder = stringResource(R.string.search_files_hint),
                largeTitle = largeTitle
            )

            val list = entries
            when {
                !hasAccess -> KobbloMessage(
                    icon = AppIcons.Lock,
                    title = stringResource(R.string.tools_picker_access_title),
                    body = stringResource(R.string.tools_picker_access_body),
                    action = {
                        Button(onClick = {
                            val activity = context as? Activity
                            if (activity != null && StorageAccess.route(activity) == VersionRules.StorageRoute.SystemDialog) {
                                StorageAccess.rememberAsked(context)
                                askStorage.launch(StorageAccess.RuntimePermissions)
                            } else {
                                openAllFilesSettings(context)
                            }
                        }) {
                            Text(stringResource(R.string.tools_picker_access_action))
                        }
                    }
                )
                list == null -> ToolLoading()
                else -> CompositionLocalProvider(LocalSelectionActive provides allowMultiple) {
                    PickerList(
                        files = list,
                        showTop = isTop && search.isEmpty(),
                        volumes = volumes,
                        filter = filter,
                        selected = selected,
                        asGrid = asGrid,
                        bottomPadding = bottomPadding,
                        onOpen = { location = it },
                        onPick = ::pick
                    )
                }
            }
        }

        if (selected.isNotEmpty()) {
            Button(
                onClick = { onPicked(selected.toList()) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = KobbloSpace.screen, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.tools_picker_open_count, selected.size),
                    style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }

    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            KobbloSegmentedControl(
                options = listOf(stringResource(R.string.view_icons), stringResource(R.string.view_list)),
                selectedIndex = if (asGrid) 0 else 1,
                onSelect = { asGrid = it == 0 },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                val modes = listOf(
                    PickerSort.NAME to R.string.sort_name,
                    PickerSort.DATE to R.string.sort_date_modified,
                    PickerSort.SIZE to R.string.sort_size_label
                )
                modes.forEachIndexed { index, (mode, label) ->
                    ToolMenuRow(stringResource(label), checked = mode == sort, showDivider = index < modes.lastIndex) {
                        sortName = mode.name
                        showOptions = false
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerList(
    files: List<FileItem>,
    showTop: Boolean,
    volumes: List<VolumeInfo>,
    filter: MimeFilter,
    selected: List<String>,
    asGrid: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpen: (String) -> Unit,
    onPick: (FileItem) -> Unit
) {
    val fileIsAllowed = { file: FileItem ->
        file.type == "folder" || filter.accepts(StorageDocumentsProvider.mimeOf(file.name))
    }
    if (asGrid && !showTop && files.isNotEmpty()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(files, key = { it.path }) { file ->
                PickerGridCell(file, fileIsAllowed(file), file.path in selected) { onPick(file) }
            }
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        if (showTop) {
            if (files.isNotEmpty()) {
                item(key = "recent_header") {
                    KobbloGroupHeader(stringResource(R.string.tools_picker_recent), modifier = Modifier.padding(top = 0.dp))
                }
            }
        }
        itemsIndexed(files, key = { _, file -> file.path }) { index, file ->
            PickerRow(
                file = file,
                allowed = fileIsAllowed(file),
                isSelected = file.path in selected,
                index = index,
                count = files.size,
                onClick = { onPick(file) }
            )
        }
        if (showTop) {
            item(key = "locations") {
                KobbloGroupHeader(stringResource(R.string.tools_picker_locations))
                KobbloGroup {
                    volumes.forEachIndexed { index, volume ->
                        KobbloRow(
                            title = volume.name,
                            leading = {
                                Icon(volumeIcon(volume), contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp))
                            },
                            trailing = { KobbloChevron() },
                            showDivider = index < volumes.lastIndex,
                            onClick = { onOpen(volume.path) }
                        )
                    }
                }
            }
            item(key = "folders") {
                val folders = remember { commonFolders() }
                if (folders.isNotEmpty()) {
                    KobbloGroupHeader(stringResource(R.string.tools_picker_folders))
                    KobbloGroup {
                        folders.forEachIndexed { index, folder ->
                            KobbloRow(
                                title = folder.name,
                                leading = {
                                    Icon(AppIcons.Folder, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp))
                                },
                                trailing = { KobbloChevron() },
                                showDivider = index < folders.lastIndex,
                                onClick = { onOpen(folder.path) }
                            )
                        }
                    }
                }
            }
        } else if (files.isEmpty()) {
            item(key = "empty") {
                KobbloMessage(icon = AppIcons.FolderOpen, title = stringResource(R.string.tools_picker_empty))
            }
        }
    }
}

@Composable
private fun PickerRow(
    file: FileItem,
    allowed: Boolean,
    isSelected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    val fill = pressedRowFill(source)
    val selectedFill = if (isSelected) colors.action.copy(alpha = 0.12f) else colors.action.copy(alpha = 0f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen)
            .clip(KobbloShapes.groupItem(index, count))
            .background(colors.snow)
            .background(selectedFill)
            .background(fill)
            .quietClickable(enabled = allowed, source = source, onClick = onClick)
            .rowDivider(index < count - 1, com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset + com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail + com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap, colors.hairline)
            .alpha(if (allowed) 1f else 0.38f)
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.fileRow)
            .padding(horizontal = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset, vertical = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalSelectionActive provides (LocalSelectionActive.current && file.type != "folder" && allowed)) {
            ExpressiveFileThumbnail(file = file, isSelected = isSelected, size = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail)
        }
        Spacer(Modifier.width(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = KobbloType.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (file.type == "folder") file.size else formatFileMeta(file),
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (file.type == "folder") {
            Spacer(Modifier.width(8.dp))
            KobbloChevron()
        }
    }
}

@Composable
private fun PickerGridCell(file: FileItem, allowed: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (allowed) 1f else 0.38f)
            .quietClickable(enabled = allowed, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalSelectionActive provides (LocalSelectionActive.current && file.type != "folder" && allowed)) {
                ExpressiveFileThumbnail(file = file, isSelected = isSelected, size = 96.dp)
            }
        }
        Text(
            text = file.name,
            style = KobbloType.footnote,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun sortEntries(files: List<FileItem>, sort: PickerSort, keepRecency: Boolean): List<FileItem> {
    if (keepRecency) return files
    val folderFirst = compareBy<FileItem> { it.type != "folder" }
    return when (sort) {
        PickerSort.NAME -> files.sortedWith(folderFirst.thenBy { it.name.lowercase() })
        PickerSort.DATE -> files.sortedWith(folderFirst.thenByDescending { it.lastModified })
        PickerSort.SIZE -> files.sortedWith(folderFirst.thenByDescending { it.sizeBytes })
    }
}

/** The newest files that fit the request, since what someone wants to attach is usually recent. */
private suspend fun recentFiles(context: Context, repository: FileRepository, filter: MimeFilter): List<FileItem> {
    val paths = mutableListOf<String>()
    val locks = com.kobblo.gallery.file.explorer.fileops.Locks.forLists(context)
    try {
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.DISPLAY_NAME),
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} OR ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL",
            null,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && paths.size < RECENT_COUNT) {
                val path = cursor.getString(0) ?: continue
                val name = path.substringAfterLast('/')
                if (name.startsWith(".") || "/." in path || locks.covers(path)) continue
                if (!filter.accepts(StorageDocumentsProvider.mimeOf(name))) continue
                val file = File(path)
                if (file.isFile) paths += path
            }
        }
    } catch (_: Exception) {
    }
    val byFolder = paths.groupBy { File(it).parent.orEmpty() }
    val items = byFolder.flatMap { (folder, names) ->
        try {
            repository.getFiles(folder).filter { it.path in names }
        } catch (_: Exception) {
            emptyList()
        }
    }.associateBy { it.path }
    // The list is keyed by path, and the index could hold a path twice.
    return paths.distinct().mapNotNull { items[it] }
}

private const val RECENT_COUNT = 6

private fun commonFolders(): List<File> = listOf(
    Environment.DIRECTORY_DOWNLOADS,
    Environment.DIRECTORY_DOCUMENTS,
    Environment.DIRECTORY_PICTURES,
    Environment.DIRECTORY_DCIM,
    Environment.DIRECTORY_MOVIES,
    Environment.DIRECTORY_MUSIC
).map(Environment::getExternalStoragePublicDirectory).filter { it.isDirectory }

private fun openAllFilesSettings(context: Context) {
    // Android 8 to 10 asks with the system's dialog, which this screen's launcher opens. This is for after a "don't ask again".
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return StorageAccess.openAppSettings(context)
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
