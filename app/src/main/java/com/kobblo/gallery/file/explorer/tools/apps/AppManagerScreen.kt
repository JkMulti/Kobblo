package com.kobblo.gallery.file.explorer.tools.apps

import androidx.compose.foundation.layout.heightIn
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.tools.ToolBarAction
import com.kobblo.gallery.file.explorer.tools.ToolFolderPicker
import com.kobblo.gallery.file.explorer.tools.ToolLoading
import com.kobblo.gallery.file.explorer.tools.ToolMenuRow
import com.kobblo.gallery.file.explorer.tools.ToolSelectionBar
import com.kobblo.gallery.file.explorer.tools.ToolSheet
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.SelectionCircle
import com.kobblo.gallery.file.explorer.ui.expressive.formatFileSize
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val APK_MIME = "application/vnd.android.package-archive"

@Composable
internal fun AppManagerScreen(onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val scope = rememberCoroutineScope()
    val apps by InstalledApps.apps.collectAsState()

    var showSystem by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(AppSort.NAME.name) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var openApp by remember { mutableStateOf<InstalledApp?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var pickFolderFor by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var progress by remember { mutableStateOf<ExportProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var uninstallQueue by remember { mutableStateOf(listOf<String>()) }

    val sort = AppSort.valueOf(sortName)
    val visible = remember(apps, showSystem, search, sort) {
        AppQuery.apply(apps.orEmpty(), showSystem, search, sort)
    }
    val byPackage = remember(apps) { apps.orEmpty().associateBy { it.packageName } }
    val selecting = selected.isNotEmpty()

    LaunchedEffect(Unit) { InstalledApps.refresh(context) }

    // A package coming or going updates the list wherever it happened, including the system's own
    // uninstall dialog that this screen opens.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                scope.launch { InstalledApps.refresh(context) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch { InstalledApps.refresh(context) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val uninstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        uninstallQueue = uninstallQueue.drop(1)
    }
    // One system dialog at a time: the next opens when the last one is answered.
    LaunchedEffect(uninstallQueue.firstOrNull()) {
        uninstallQueue.firstOrNull()?.let { pkg ->
            try {
                uninstaller.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            } catch (_: Exception) {
                uninstallQueue = uninstallQueue.drop(1)
            }
        }
    }

    fun toast(message: String) = onAction(AppAction.ShowToast(message))

    fun share(list: List<InstalledApp>) {
        job = scope.launch {
            try {
                val uris = ApkExport.forSharing(context, list) { progress = it }
                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = APK_MIME
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = APK_MIME
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.tools_apps_share_title)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                toast(context.getString(R.string.tools_apps_share_failed))
            } finally {
                progress = null
            }
        }
    }

    fun backUp(list: List<InstalledApp>, folder: String) {
        job = scope.launch {
            try {
                val result = ApkExport.backUp(list, File(folder)) { progress = it }
                val where = File(folder).name
                val saved = result.saved.size
                toast(
                    when {
                        result.failed.isNotEmpty() -> context.getString(R.string.tools_apps_backup_partial, saved, result.failed.size)
                        result.saved.any { it.name.endsWith(".apks") } -> context.resources.getQuantityString(R.plurals.tools_apps_backup_done_bundle, saved, saved, where)
                        else -> context.resources.getQuantityString(R.plurals.tools_apps_backup_done, saved, saved, where)
                    }
                )
            } finally {
                progress = null
            }
        }
    }

    fun uninstall(list: List<InstalledApp>) {
        val removable = list.filter { !it.isSystem }
        if (removable.size < list.size) toast(context.getString(R.string.tools_apps_system_kept))
        uninstallQueue = removable.map { it.packageName }
    }

    BackHandler(enabled = selecting) { selected = emptySet() }

    val largeTitle = rememberLargeTitleState(height = 150.dp)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
            ExpressiveScreenHeader(
                title = stringResource(R.string.tools_apps_title),
                modifier = Modifier.padding(horizontal = KobbloSpace.screen),
                onBack = { Hub.back() },
                backLabel = stringResource(R.string.nav_browse),
                rightAction = {
                    KobbloIconButton(
                        icon = AppIcons.MoreHoriz,
                        description = stringResource(R.string.tools_options),
                        onClick = { showOptions = true }
                    )
                },
                showSearch = true,
                query = search,
                onQueryChange = { search = it },
                searchPlaceholder = stringResource(R.string.tools_apps_search),
                largeTitle = largeTitle,
                belowTitle = {
                    Spacer(Modifier.height(12.dp))
                    KobbloSegmentedControl(
                        options = listOf(stringResource(R.string.tools_apps_user), stringResource(R.string.tools_apps_system)),
                        selectedIndex = if (showSystem) 1 else 0,
                        onSelect = { showSystem = it == 1 }
                    )
                }
            )

            when {
                apps == null -> ToolLoading()
                visible.isEmpty() -> KobbloMessage(
                    icon = if (search.isEmpty()) AppIcons.Apps else AppIcons.Search,
                    title = stringResource(if (search.isEmpty()) R.string.tools_apps_empty else R.string.tools_apps_no_match)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = navBottom + if (selecting) 150.dp else 16.dp)
                ) {
                    itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
                        AppRow(
                            app = app,
                            sort = sort,
                            selecting = selecting,
                            isSelected = app.packageName in selected,
                            index = index,
                            count = visible.size,
                            onToggle = {
                                selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName
                            },
                            onOpen = { openApp = app }
                        )
                    }
                    item(key = "footer") {
                        KobbloGroupFooter(
                            pluralStringResource(
                                R.plurals.tools_apps_footer,
                                visible.size,
                                visible.size,
                                formatFileSize(visible.sumOf { it.sizeBytes })
                            )
                        )
                    }
                }
            }
        }

        if (selecting) {
            val chosen = selected.mapNotNull { byPackage[it] }
            ToolSelectionBar(
                selectedCount = selected.size,
                onSelectAll = { selected = visible.map { it.packageName }.toSet() },
                onDone = { selected = emptySet() },
                actions = listOf(
                    ToolBarAction(AppIcons.Download, stringResource(R.string.tools_apps_backup)) { pickFolderFor = chosen },
                    ToolBarAction(AppIcons.Share, stringResource(R.string.share)) {
                        share(chosen)
                        selected = emptySet()
                    },
                    ToolBarAction(AppIcons.Trash, stringResource(R.string.tools_apps_uninstall), danger = true) {
                        uninstall(chosen)
                        selected = emptySet()
                    }
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showOptions) {
        ToolSheet(title = stringResource(R.string.tools_options), onDismiss = { showOptions = false }) {
            KobbloGroup {
                val entries = listOf(
                    AppSort.NAME to R.string.sort_name,
                    AppSort.SIZE to R.string.sort_size_label,
                    AppSort.DATE to R.string.tools_apps_sort_date
                )
                entries.forEachIndexed { index, (mode, label) ->
                    ToolMenuRow(
                        title = stringResource(label),
                        checked = mode == sort,
                        showDivider = index < entries.lastIndex
                    ) {
                        sortName = mode.name
                        showOptions = false
                    }
                }
            }
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(title = stringResource(R.string.select_action), icon = AppIcons.CheckCircle) {
                    showOptions = false
                    visible.firstOrNull()?.let { selected = setOf(it.packageName) }
                }
            }
        }
    }

    openApp?.let { app ->
        AppSheet(
            app = app,
            onDismiss = { openApp = null },
            onShare = {
                openApp = null
                share(listOf(app))
            },
            onBackUp = {
                openApp = null
                pickFolderFor = listOf(app)
            },
            onUninstall = {
                openApp = null
                uninstall(listOf(app))
            },
            onLaunchFailed = { toast(context.getString(R.string.tools_apps_no_launcher)) }
        )
    }

    pickFolderFor?.let { list ->
        ToolFolderPicker(
            title = stringResource(R.string.tools_apps_backup_apk),
            confirmLabel = stringResource(R.string.tools_save_here),
            initialPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
            onDismiss = { pickFolderFor = null },
            onPick = { folder ->
                pickFolderFor = null
                selected = emptySet()
                backUp(list, folder)
            }
        )
    }

    progress?.let { current ->
        ToolSheet(
            title = stringResource(R.string.tools_apps_exporting),
            onDismiss = {
                job?.cancel()
                progress = null
            }
        ) {
            Text(
                text = "${current.appLabel} (${current.done + 1}/${current.total})",
                style = KobbloType.callout,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))
            com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 36.dp, color = colors.action)
            Spacer(Modifier.height(16.dp))
            com.kobblo.gallery.file.explorer.ui.design.TextButton(
                onClick = {
                    job?.cancel()
                    progress = null
                }
            ) {
                Text(stringResource(R.string.cancel), style = KobbloType.headline, color = colors.action)
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    sort: AppSort,
    selecting: Boolean,
    isSelected: Boolean,
    index: Int,
    count: Int,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    val colors = KobbloDesign.colors
    val haptics = LocalHaptics.current
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
            .quietCombinedClickable(
                source = source,
                onClick = if (selecting) onToggle else onOpen,
                onLongClick = {
                    haptics.tap()
                    onToggle()
                }
            )
            .rowDivider(index < count - 1, com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset + com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail + com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap, colors.hairline)
            .heightIn(min = com.kobblo.gallery.file.explorer.ui.design.KobbloSize.fileRow)
            .padding(horizontal = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.inset, vertical = com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.quietClickable(onClick = onToggle)) {
            AppIconImage(app, com.kobblo.gallery.file.explorer.ui.design.KobbloSize.thumbnail)
            if (selecting) {
                SelectionCircle(selected = isSelected, modifier = Modifier.align(Alignment.BottomEnd).size(22.dp))
            }
        }
        Spacer(Modifier.width(com.kobblo.gallery.file.explorer.ui.design.KobbloSpace.leadingGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = KobbloType.body, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = appMeta(app, sort),
                style = KobbloType.footnote,
                color = colors.smoke,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun appMeta(app: InstalledApp, sort: AppSort): String {
    val parts = mutableListOf<String>()
    if (app.versionName.isNotEmpty()) parts += app.versionName
    parts += formatFileSize(app.sizeBytes)
    if (app.isSplit) parts += stringResource(R.string.tools_apps_split)
    if (sort == AppSort.DATE) parts += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(app.updatedAt))
    return parts.joinToString(" · ")
}

@Composable
private fun AppIconImage(app: InstalledApp, size: androidx.compose.ui.unit.Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by rememberAppIcon(app, px)
    val current = icon
    if (current != null) {
        Image(bitmap = current, contentDescription = null, modifier = Modifier.size(size).clip(KobbloShapes.tile))
    } else {
        Box(modifier = Modifier.size(size).clip(KobbloShapes.tile).background(KobbloDesign.colors.mist))
    }
}

@Composable
private fun AppSheet(
    app: InstalledApp,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onBackUp: () -> Unit,
    onUninstall: () -> Unit,
    onLaunchFailed: () -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    ToolSheet(title = app.label, onDismiss = onDismiss) {
        AppIconImage(app, 64.dp)
        Spacer(Modifier.height(12.dp))
        KobbloGroup {
            ToolMenuRow(stringResource(R.string.tools_apps_open), AppIcons.OpenExternal, showDivider = true) {
                onDismiss()
                val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
                if (launch != null) context.startActivity(launch) else onLaunchFailed()
            }
            ToolMenuRow(stringResource(R.string.tools_apps_info), AppIcons.Info) {
                onDismiss()
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                )
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            ToolMenuRow(stringResource(R.string.tools_apps_share_apk), AppIcons.Share, showDivider = true, onClick = onShare)
            ToolMenuRow(stringResource(R.string.tools_apps_backup_apk), AppIcons.Download, onClick = onBackUp)
        }
        if (!app.isSystem) {
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                ToolMenuRow(stringResource(R.string.tools_apps_uninstall), AppIcons.Trash, tint = colors.danger, onClick = onUninstall)
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            if (app.versionName.isNotEmpty()) DetailRow(stringResource(R.string.tools_apps_version), "${app.versionName} (${app.versionCode})")
            DetailRow(stringResource(R.string.sort_size_label), formatFileSize(app.sizeBytes))
            DetailRow(stringResource(R.string.tools_apps_installed), dates.format(Date(app.installedAt)))
            DetailRow(stringResource(R.string.tools_apps_updated), dates.format(Date(app.updatedAt)))
            DetailRow(stringResource(R.string.tools_apps_target_sdk), app.targetSdk.toString())
            KobbloRow(title = stringResource(R.string.tools_apps_package), subtitle = app.packageName)
        }
        if (app.isSplit) {
            KobbloGroupFooter(pluralStringResource(R.plurals.tools_apps_split_note, app.allApkPaths.size, app.allApkPaths.size))
        }
    }
}
