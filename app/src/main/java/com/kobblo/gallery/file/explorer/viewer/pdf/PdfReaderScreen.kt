package com.kobblo.gallery.file.explorer.viewer.pdf

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.GlassCircle
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.ToolbarAction
import com.kobblo.gallery.file.explorer.ui.ViewerTopBar
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.DetailRow
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSheetContent
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoader
import com.kobblo.gallery.file.explorer.ui.design.KobbloLoading
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSearchField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTextField
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlassSource
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import com.kobblo.gallery.file.explorer.viewer.findActivity
import com.kobblo.gallery.file.explorer.viewer.isExternalStream
import com.kobblo.gallery.file.explorer.viewer.isInVault
import com.kobblo.gallery.file.explorer.viewer.isOnSharedStorage
import com.kobblo.gallery.file.explorer.viewer.isOpenedFromOutside
import com.kobblo.gallery.file.explorer.viewer.openWithChooser
import com.kobblo.gallery.file.explorer.viewer.shareFile
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private sealed interface Phase {
    data object Opening : Phase
    class Locked(val wrongPassword: Boolean) : Phase
    class Ready(val doc: PdfDoc, val state: PdfReaderState) : Phase
    class Failed(val title: Int, val body: Int) : Phase
}

/**
 * Reading a PDF. It opens at once at any size, because nothing is rendered until it's needed, and comes back to
 * where it was left. A tap hides everything but the pages, the handle on the right edge travels the whole
 * document and shows where you are, and the rest lives in the `...` sheet.
 */
@Composable
internal fun PdfReaderScreen(
    fileItem: FileItem,
    onClose: () -> Unit,
    onLeave: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    var phase by remember(fileItem.path) { mutableStateOf<Phase>(Phase.Opening) }
    var password by remember(fileItem.path) { mutableStateOf<String?>(null) }
    var attempt by remember(fileItem.path) { mutableIntStateOf(0) }
    var showDetails by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    val store = remember(context) { PdfPlaceStore(context) }

    // Most files are open before anything here could be seen, and glass is the slowest thing on the screen to set up.
    var isSlowToOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OpeningPatienceMs)
        isSlowToOpen = true
    }

    LaunchedEffect(fileItem.path, attempt) {
        isChecking = true
        // Not cancellable, as a document that opened just as someone left still has to be closed.
        val outcome = withContext(NonCancellable) { PdfDoc.open(context, fileItem.path, password) }
        if (!currentCoroutineContext().isActive) {
            (outcome as? PdfOpenOutcome.Ready)?.doc?.close()
            return@LaunchedEffect
        }
        isChecking = false
        phase = when (outcome) {
            is PdfOpenOutcome.Ready -> {
                val saved = outcome.doc.fingerprint.takeIf { it.isNotEmpty() }?.let(store::get)
                Phase.Ready(outcome.doc, PdfReaderState(outcome.doc, context, saved))
            }
            PdfOpenOutcome.NeedsPassword -> Phase.Locked(wrongPassword = false)
            PdfOpenOutcome.WrongPassword -> Phase.Locked(wrongPassword = true)
            PdfOpenOutcome.Damaged -> Phase.Failed(R.string.viewer_pdf_damaged_title, R.string.viewer_pdf_damaged_body)
            PdfOpenOutcome.Missing -> Phase.Failed(R.string.viewer_pdf_missing_title, R.string.viewer_pdf_missing_body)
            PdfOpenOutcome.NoAccess -> Phase.Failed(R.string.viewer_pdf_no_access_title, R.string.viewer_pdf_no_access_body)
        }
    }

    when (val current = phase) {
        is Phase.Ready -> {
            DisposableEffect(current.doc) {
                onDispose { current.doc.close() }
            }
            PdfReader(fileItem, current.state, onClose, onLeave, onAction)
        }
        else -> {
            BackHandler { onLeave() }
            val backdrop = rememberLayerBackdrop {
                drawRect(colors.canvas)
                drawContent()
            }
            Box(modifier = Modifier.fillMaxSize().background(colors.canvas)) {
                Box(modifier = Modifier.fillMaxSize().kobbloGlassSource(backdrop), contentAlignment = Alignment.Center) {
                    when (current) {
                        is Phase.Failed -> KobbloMessage(
                            icon = AppIcons.FileText,
                            title = stringResource(current.title),
                            body = stringResource(current.body),
                            action = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { openWithChooser(context, fileItem) }) {
                                        Text(stringResource(R.string.viewer_open_with))
                                    }
                                    TextButton(onClick = { showDetails = true }) {
                                        Text(stringResource(R.string.viewer_details))
                                    }
                                }
                            }
                        )
                        is Phase.Locked -> KobbloMessage(
                            icon = AppIcons.Lock,
                            title = stringResource(R.string.viewer_pdf_locked_title),
                            body = stringResource(R.string.viewer_pdf_locked_body)
                        )
                        else -> if (isSlowToOpen) KobbloLoading(label = stringResource(R.string.viewer_pdf_opening))
                    }
                }
                if (current !is Phase.Opening || isSlowToOpen) {
                    ViewerTopBar(
                        title = fileItem.name,
                        subtitle = "",
                        backdrop = backdrop,
                        onBack = onLeave,
                        onMore = { showDetails = true },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            }
            (current as? Phase.Locked)?.let { locked ->
                PasswordDialog(
                    wrongPassword = locked.wrongPassword && !isChecking,
                    isChecking = isChecking,
                    onSubmit = {
                        password = it
                        attempt++
                    },
                    onCancel = onLeave
                )
            }
        }
    }

    if (showDetails) {
        PdfDetailsSheet(fileItem, pageCount = 0, info = PdfInfo.Empty, onDismiss = { showDetails = false })
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PdfReader(
    fileItem: FileItem,
    state: PdfReaderState,
    onClose: () -> Unit,
    onLeave: () -> Unit,
    onAction: (AppAction) -> Unit
) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val doc = state.doc
    val search = remember(state) { PdfSearchState(doc, state, scope) }
    val selection = remember(state) { PdfSelectionState(handleReachPx = with(density) { 28.dp.toPx() }) }

    var chromeVisible by remember { mutableStateOf(true) }
    var showMore by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var darkPages by remember { mutableStateOf(PdfPrefs.darkPages(context)) }
    var info by remember { mutableStateOf(PdfInfo.Empty) }

    // Pages first. The glass controls take far longer to set up on their first frame than a page takes to draw, and
    // holding the page back for them would make every file look slow to open.
    var pagesAreUp by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        withTimeoutOrNull(ControlsWaitMs) {
            snapshotFlow { state.redraw }.first { state.pageBitmap(state.currentPage) != null }
            // Two frames, so that the page has gone out before anything slower starts.
            withFrameNanos { }
            withFrameNanos { }
        }
        pagesAreUp = true
    }
    val reduced = ExpressiveMotion.isReducedMotion()
    val controlsShown by animateFloatAsState(
        targetValue = if (chromeVisible && pagesAreUp) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(KobbloMotion.Default, easing = KobbloMotion.EaseInOut),
        label = "pdf_controls"
    )

    LaunchedEffect(state) { state.runRenderLoop() }
    LaunchedEffect(state) { state.scanPageSizes() }
    LaunchedEffect(state) {
        // Waits for the first page, as this is the one read the file ever needs from PDFBox unless something asks.
        delay(InfoDelayMs)
        info = doc.info()
    }

    // The place is written as the page changes, so a killed app still comes back to about here.
    LaunchedEffect(state) {
        snapshotFlow { state.currentPage }.drop(1).collectLatest {
            delay(SavePlaceDelayMs)
            state.savePlace()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) state.savePlace()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            state.savePlace()
        }
    }

    // Reading gets the whole screen: moving the pages by hand puts the controls away, and a tap brings them back.
    LaunchedEffect(state) {
        var startY = 0.0
        var startZoom = 1f
        snapshotFlow { Triple(state.fingersDown, state.camera.y, state.camera.zoom) }.collect { (down, y, zoom) ->
            if (!down) {
                startY = y
                startZoom = zoom
            } else if (kotlin.math.abs(y - startY) > state.view.height / HideAfterScrollShare || zoom != startZoom) {
                if (!search.isOpen && !selection.isActive) chromeVisible = false
            }
        }
    }

    // On while someone's reading, but a phone left on a table does still go to sleep.
    val hostView = LocalView.current
    LaunchedEffect(state, chromeVisible) {
        snapshotFlow { state.camera.y to state.camera.zoom }.collectLatest {
            hostView.keepScreenOn = true
            delay(KeepAwakeMs)
            hostView.keepScreenOn = false
        }
    }
    DisposableEffect(hostView) {
        onDispose { hostView.keepScreenOn = false }
    }

    // With the controls hidden, the system's bars go too, as in the photo viewer.
    val controller = remember(hostView) {
        (hostView.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, hostView) }
    }
    LaunchedEffect(chromeVisible, search.isOpen) {
        controller ?: return@LaunchedEffect
        if (chromeVisible || search.isOpen) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(controller) {
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler {
        when {
            selection.isActive -> selection.clear()
            search.isOpen -> search.close()
            else -> onLeave()
        }
    }

    val pageBackground = if (darkPages) Color.Black else colors.canvas
    val backdrop = rememberLayerBackdrop {
        drawRect(pageBackground)
        drawContent()
    }
    val topPadding = with(density) { WindowInsets.statusBarsIgnoringVisibility.getTop(this) + ToolbarClearance.toPx() }
    val bottomPadding = with(density) { WindowInsets.navigationBarsIgnoringVisibility.getBottom(this) + BottomClearance.toPx() }
    val currentPage by remember(state) { derivedStateOf { state.currentPage } }
    val sideInsets = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

    // A turn of the screen can bring the new size and the new insets in separate passes.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    SideEffect {
        if (canvasSize != IntSize.Zero) {
            state.setViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat(), topPadding, bottomPadding)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(pageBackground)) {
        Box(modifier = Modifier.fillMaxSize().kobbloGlassSource(backdrop)) {
            val highlight = colors.action.copy(alpha = HighlightAlpha)
            val currentHighlight = colors.action.copy(alpha = CurrentHighlightAlpha)
            val failedPage = stringResource(R.string.viewer_pdf_page_failed)
            val pageLabel = stringResource(R.string.page_of_total, currentPage + 1, doc.pageCount)
            PdfPagesCanvas(
                state = state,
                search = search,
                selection = selection,
                darkPages = darkPages,
                highlight = highlight,
                currentHighlight = currentHighlight,
                failedPageMessage = failedPage,
                failedPageColor = if (darkPages) Color.White else Color.Black,
                onTap = { spot ->
                    if (selection.isActive) {
                        selection.clear()
                    } else {
                        scope.launch {
                            val link = state.linkAt(spot.x, spot.y, with(density) { LinkSlop.toPx() }, scope)
                            when {
                                link == null -> chromeVisible = !chromeVisible
                                link.page >= 0 -> state.goToPage(link.page)
                                link.uri != null -> openLink(context, link.uri)
                            }
                        }
                    }
                },
                onLongPress = { spot ->
                    val layout = state.layout
                    if (layout != null) {
                        val page = layout.pageAt((state.camera.y + spot.y - state.view.paddingTop) / state.camera.zoom)
                        val rect = layout.screenRect(page, state.camera, state.view)
                        val zoom = state.camera.zoom
                        val (x, y) = PdfGeometry.pageToPoints((spot.x - rect.left) / zoom, (spot.y - rect.top) / zoom, layout, page)
                        selection.isLoading = true
                        scope.launch {
                            val chars = doc.pageChars(page)
                            selection.isLoading = false
                            if (chars != null && selection.begin(page, chars, x, y)) {
                                haptics.tap()
                                chromeVisible = true
                            } else {
                                onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_no_text_here)))
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(sideInsets)
                    .onSizeChanged {
                        canvasSize = it
                        // Now rather than next frame, which on a screen just opening may be a long time coming.
                        state.setViewport(it.width.toFloat(), it.height.toFloat(), topPadding, bottomPadding)
                    }
                    .semantics { contentDescription = pageLabel }
            )
        }

        if (doc.isDead) {
            Box(modifier = Modifier.matchParentSize().background(colors.canvas), contentAlignment = Alignment.Center) {
                KobbloMessage(
                    icon = AppIcons.FileText,
                    title = stringResource(R.string.viewer_pdf_damaged_title),
                    body = stringResource(R.string.viewer_pdf_damaged_body),
                    action = {
                        Button(onClick = { openWithChooser(context, fileItem) }) {
                            Text(stringResource(R.string.viewer_open_with))
                        }
                    }
                )
            }
        }

        PdfScrollHandle(
            state = state,
            topPadding = with(density) { topPadding.toDp() },
            bottomPadding = with(density) { bottomPadding.toDp() },
            showAtStart = state.wasRestored,
            onLabelTap = { showGoTo = true }
        )

        val shared = !fileItem.isExternalStream && !fileItem.isInVault
        if (search.isOpen) {
            PdfSearchChrome(search = search, doc = doc, backdrop = backdrop, darkPages = darkPages)
        } else if (selection.isActive) {
            ViewerTopBar(
                title = fileItem.name,
                subtitle = stringResource(R.string.page_of_total, currentPage + 1, doc.pageCount),
                backdrop = backdrop,
                onBack = { selection.clear() },
                onMore = { showMore = true },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            SelectionBar(
                selection = selection,
                backdrop = backdrop,
                canCopy = !fileItem.isInVault,
                onCopied = { onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_copied))) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        } else if (controlsShown > 0f) {
            ViewerTopBar(
                title = fileItem.name,
                subtitle = stringResource(R.string.page_of_total, currentPage + 1, doc.pageCount),
                backdrop = backdrop,
                onBack = {
                    haptics.tap()
                    onLeave()
                },
                onMore = {
                    haptics.tap()
                    showMore = true
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = controlsShown }
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .graphicsLayer { alpha = controlsShown },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GlassCircle(
                    icon = AppIcons.Share,
                    description = stringResource(R.string.viewer_pdf_share),
                    backdrop = backdrop,
                    onClick = { shareFile(context, fileItem) },
                    size = 52.dp
                )
                Row(
                    modifier = Modifier
                        .height(52.dp)
                        .kobbloGlass(backdrop, CapsuleShape)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolbarAction(
                        icon = AppIcons.Search,
                        description = stringResource(R.string.viewer_pdf_search),
                        tint = colors.ink,
                        onClick = { search.isOpen = true }
                    )
                    if (info.outline.isNotEmpty() || state.bookmarks.isNotEmpty()) {
                        ToolbarAction(
                            icon = AppIcons.List,
                            description = stringResource(R.string.viewer_pdf_contents),
                            tint = colors.ink,
                            onClick = { showContents = true }
                        )
                    }
                    if (shared && !fileItem.isOpenedFromOutside) {
                        val favorites by remember(context) { com.kobblo.gallery.file.explorer.gallery.Favorites.paths(context) }.collectAsState()
                        val isFavorite = fileItem.path in favorites
                        ToolbarAction(
                            icon = if (isFavorite) AppIcons.StarFill else AppIcons.Star,
                            description = stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                            tint = if (isFavorite) colors.action else colors.ink,
                            onClick = { onAction(AppAction.TogglePin(fileItem.path)) }
                        )
                    }
                    ToolbarAction(
                        icon = AppIcons.Info,
                        description = stringResource(R.string.viewer_details),
                        tint = colors.ink,
                        onClick = { showDetails = true }
                    )
                }
                GlassCircle(
                    icon = AppIcons.OpenExternal,
                    description = stringResource(R.string.viewer_open_with),
                    backdrop = backdrop,
                    onClick = { openWithChooser(context, fileItem) },
                    size = 52.dp
                )
            }
        }
    }

    if (showDetails) {
        PdfDetailsSheet(fileItem, doc.pageCount, info, onDismiss = { showDetails = false })
    }

    if (showContents) {
        PdfContentsSheet(
            info = info,
            bookmarks = state.bookmarks,
            currentPage = currentPage,
            onGoToPage = { page ->
                showContents = false
                state.goToPage(page)
            },
            onDismiss = { showContents = false }
        )
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = doc.pageCount,
            currentPage = currentPage,
            onGo = { page ->
                showGoTo = false
                state.goToPage(page)
            },
            onCancel = { showGoTo = false }
        )
    }

    if (showMore) {
        PdfMoreSheet(
            fileItem = fileItem,
            currentPage = currentPage,
            pageCount = doc.pageCount,
            hasOutline = info.outline.isNotEmpty() || state.bookmarks.isNotEmpty(),
            isBookmarked = currentPage in state.bookmarks,
            darkPages = darkPages,
            onDismiss = { showMore = false },
            onGoToPage = {
                showMore = false
                showGoTo = true
            },
            onContents = {
                showMore = false
                showContents = true
            },
            onToggleBookmark = { state.toggleBookmark(currentPage) },
            onToggleDark = {
                darkPages = !darkPages
                PdfPrefs.setDarkPages(context, darkPages)
            },
            onRotate = {
                showMore = false
                state.rotateClockwise()
            },
            onCopyPageText = {
                showMore = false
                scope.launch {
                    val text = doc.pageText(currentPage)
                    if (text.isBlank()) {
                        onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_no_page_text)))
                    } else {
                        copyToClipboard(context, fileItem.name, text)
                        onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_page_copied, currentPage + 1)))
                    }
                }
            },
            onPrint = {
                showMore = false
                scope.launch {
                    val unlocked = if (doc.isLocked) doc.unlockedCopy() else null
                    val activity = context.findActivity()
                    val started = activity != null && PdfPrint.print(activity, fileItem.name, doc.pageCount) {
                        if (unlocked != null) {
                            ParcelFileDescriptor.open(unlocked, ParcelFileDescriptor.MODE_READ_ONLY)
                        } else {
                            PdfDoc.openDescriptor(context, fileItem.path)
                        }
                    }
                    if (!started || (doc.isLocked && unlocked == null)) {
                        onAction(AppAction.ShowToast(context.getString(R.string.viewer_cant_print)))
                    }
                }
            },
            onInspect = {
                showMore = false
                onClose()
                Hub.open(com.kobblo.gallery.file.explorer.tools.ToolsRoute.Inspect(fileItem.path))
            },
            onShowInFolder = {
                showMore = false
                File(fileItem.path).parent?.let { onAction(AppAction.SetLocation(it)) }
                onClose()
            },
            onCopyPath = {
                showMore = false
                copyToClipboard(context, context.getString(R.string.viewer_pdf_path_label), fileItem.path)
                onAction(AppAction.ShowToast(context.getString(R.string.viewer_pdf_path_copied)))
            }
        )
    }
}

private fun openLink(context: Context, uri: String) {
    if (!PdfLinkGeometry.isOpenableUri(uri)) return
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
    } catch (_: Exception) {
        // Nothing on this phone takes it, and a tap that does nothing says as much.
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

private const val OpeningPatienceMs = 400L
private const val ControlsWaitMs = 500L
private const val DialogFocusDelayMs = 150L
private const val HideAfterScrollShare = 6f
private const val InfoDelayMs = 600L
private const val SavePlaceDelayMs = 1500L
private const val KeepAwakeMs = 10 * 60 * 1000L
private const val HandleLingerMs = 1100L
private const val MinPagesForHandle = 3
private const val HighlightAlpha = 0.30f
private const val CurrentHighlightAlpha = 0.55f
private const val SearchDebounceMs = 400L
private val LinkSlop = 6.dp
private val ToolbarClearance = 62.dp
private val BottomClearance = 76.dp

// ── The handle on the right edge ───────────────────────────────────────────────

/** A thin handle, there only while the view moves. It says which page this is, and dragging it travels the whole file. */
@Composable
private fun BoxScope.PdfScrollHandle(
    state: PdfReaderState,
    topPadding: Dp,
    bottomPadding: Dp,
    showAtStart: Boolean,
    onLabelTap: () -> Unit
) {
    val colors = KobbloDesign.colors
    val density = LocalDensity.current
    var shown by remember { mutableStateOf(showAtStart) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val page by remember(state) { derivedStateOf { state.currentPage } }
    val direction = LocalLayoutDirection.current

    LaunchedEffect(state, dragging) {
        if (dragging) {
            shown = true
            return@LaunchedEffect
        }
        // At the start it's there to say where a reopened file came back to.
        delay(HandleLingerMs)
        shown = false
        snapshotFlow { state.camera.y to state.camera.zoom }.drop(1).collectLatest {
            shown = true
            delay(HandleLingerMs)
            shown = false
        }
    }

    if (state.pageCount < MinPagesForHandle) return

    BoxWithConstraints(
        modifier = Modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        val handleHeight = 52.dp
        val trackPx = with(density) { (maxHeight - handleHeight).toPx().coerceAtLeast(1f) }
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(KobbloMotion.fadeIn()),
            exit = fadeOut(KobbloMotion.fadeOut()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (state.scrollFraction * trackPx).roundToInt()) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(handleHeight)) {
                Box(
                    modifier = Modifier
                        .kobbloGlass(null, CapsuleShape)
                        .quietClickable(onClick = onLabelTap)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.viewer_page_of, page + 1, state.pageCount),
                        style = KobbloType.callout.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                        color = colors.ink,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(handleHeight)
                        .pointerInput(state, direction) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = state.scrollFraction
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragFraction = (dragFraction + amount / trackPx).coerceIn(0f, 1f)
                                    state.scrollToFraction(dragFraction)
                                }
                            )
                        },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .width(if (dragging) 8.dp else 5.dp)
                            .height(handleHeight)
                            .background(if (dragging) colors.action else colors.smoke.copy(alpha = 0.72f), CapsuleShape)
                    )
                }
            }
        }
    }
}

// ── Search ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.PdfSearchChrome(search: PdfSearchState, doc: PdfDoc, backdrop: Backdrop, darkPages: Boolean) {
    val colors = KobbloDesign.colors
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }
    LaunchedEffect(search.query) {
        delay(SearchDebounceMs)
        search.start(search.query)
    }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassCircle(
                icon = AppIcons.Back,
                description = stringResource(R.string.back),
                backdrop = backdrop,
                onClick = { search.close() }
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .kobbloGlass(backdrop, CapsuleShape)
                    .padding(4.dp)
            ) {
                KobbloSearchField(
                    query = search.query,
                    onQueryChange = { search.query = it },
                    placeholder = stringResource(R.string.viewer_pdf_search_hint),
                    focusRequester = focus,
                    autoCorrect = false,
                    onSubmit = {
                        search.start(search.query)
                        focusManager.clearFocus()
                    }
                )
            }
        }
    }

    val status = searchStatus(search, doc.pageCount)
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .kobbloGlass(backdrop, CapsuleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (search.isRunning) {
                KobbloLoader(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                text = status,
                style = KobbloType.footnote.copy(fontFeatureSettings = "tnum"),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (search.matchCount > 0) {
            GlassCircle(
                icon = AppIcons.ChevronUp,
                description = stringResource(R.string.viewer_pdf_search_previous),
                backdrop = backdrop,
                onClick = { search.previous() },
                size = 52.dp
            )
            GlassCircle(
                icon = AppIcons.ChevronDown,
                description = stringResource(R.string.viewer_pdf_search_next),
                backdrop = backdrop,
                onClick = { search.next() },
                size = 52.dp
            )
        }
    }
}

@Composable
private fun searchStatus(search: PdfSearchState, pageCount: Int): String = when {
    search.searchedFor.isEmpty() -> stringResource(R.string.viewer_pdf_search_prompt)
    search.matchCount > 0 && search.isRunning -> stringResource(
        R.string.viewer_pdf_search_found_while_running,
        search.currentIndex + 1,
        search.matchCount,
        search.pagesScanned,
        pageCount
    )
    search.matchCount > 0 && search.wasCut -> stringResource(R.string.viewer_pdf_search_cut, search.currentIndex + 1, search.matchCount)
    search.matchCount > 0 -> stringResource(R.string.viewer_page_of, search.currentIndex + 1, search.matchCount)
    search.isRunning -> stringResource(R.string.viewer_pdf_search_running, search.pagesScanned, pageCount)
    search.noTextOnPage -> stringResource(R.string.viewer_pdf_search_no_text)
    else -> stringResource(R.string.viewer_pdf_search_none)
}

// ── Selecting ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    selection: PdfSelectionState,
    backdrop: Backdrop,
    canCopy: Boolean,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
            .padding(bottom = 12.dp)
            .height(52.dp)
            .kobbloGlass(backdrop, CapsuleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canCopy) {
            TextButton(onClick = {
                copyToClipboard(context, context.getString(R.string.viewer_pdf_selection_label), selection.selectedText)
                selection.clear()
                onCopied()
            }) {
                Text(stringResource(R.string.viewer_pdf_copy), color = colors.action, style = KobbloType.headline)
            }
        }
        TextButton(onClick = { selection.selectAllOnPage() }) {
            Text(stringResource(R.string.viewer_pdf_select_page), color = colors.ink, style = KobbloType.headline)
        }
    }
}

// ── Sheets and alerts ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfMoreSheet(
    fileItem: FileItem,
    currentPage: Int,
    pageCount: Int,
    hasOutline: Boolean,
    isBookmarked: Boolean,
    darkPages: Boolean,
    onDismiss: () -> Unit,
    onGoToPage: () -> Unit,
    onContents: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDark: () -> Unit,
    onRotate: () -> Unit,
    onCopyPageText: () -> Unit,
    onPrint: () -> Unit,
    onInspect: () -> Unit,
    onShowInFolder: () -> Unit,
    onCopyPath: () -> Unit
) {
    KobbloSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ExpressiveSheetContent(title = fileItem.name) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                KobbloGroup {
                    IconRow(stringResource(R.string.viewer_pdf_go_to_page), AppIcons.Hash, showDivider = true, onClick = onGoToPage)
                    if (hasOutline) {
                        IconRow(stringResource(R.string.viewer_pdf_contents), AppIcons.List, showDivider = true, onClick = onContents)
                    }
                    IconRow(
                        stringResource(if (isBookmarked) R.string.viewer_pdf_bookmark_remove else R.string.viewer_pdf_bookmark_add, currentPage + 1),
                        if (isBookmarked) AppIcons.BookmarkFill else AppIcons.Bookmark,
                        onClick = onToggleBookmark
                    )
                }
                Spacer(Modifier.height(KobbloSpace.groupGap))
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.viewer_pdf_dark_pages),
                        trailing = { ExpressiveSwitch(checked = darkPages, onCheckedChange = { onToggleDark() }) },
                        showDivider = true,
                        onClick = onToggleDark
                    )
                    IconRow(stringResource(R.string.viewer_rotate_view), AppIcons.Rotate, onClick = onRotate)
                }
                Spacer(Modifier.height(KobbloSpace.groupGap))
                KobbloGroup {
                    // The clipboard stays around for any app to read, which is no place for something from the Vault.
                    if (!fileItem.isInVault) {
                        IconRow(stringResource(R.string.viewer_pdf_copy_page_text), AppIcons.Copy, showDivider = true, onClick = onCopyPageText)
                    }
                    IconRow(stringResource(R.string.viewer_print), AppIcons.Print, showDivider = true, onClick = onPrint)
                    if (fileItem.isOnSharedStorage) {
                        IconRow(stringResource(R.string.viewer_show_in_folder), AppIcons.FolderOpen, showDivider = true, onClick = onShowInFolder)
                        IconRow(stringResource(R.string.viewer_pdf_copy_path), AppIcons.Copy, showDivider = true, onClick = onCopyPath)
                    }
                    IconRow(stringResource(R.string.tools_inspect), AppIcons.Info, onClick = onInspect)
                }
            }
        }
    }
}

@Composable
private fun IconRow(title: String, icon: ImageVector, showDivider: Boolean = false, onClick: () -> Unit) {
    KobbloRow(
        title = title,
        trailing = { Icon(icon, contentDescription = null, tint = KobbloDesign.colors.ink, modifier = Modifier.size(22.dp)) },
        showDivider = showDivider,
        onClick = onClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfContentsSheet(
    info: PdfInfo,
    bookmarks: Set<Int>,
    currentPage: Int,
    onGoToPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    val hasOutline = info.outline.isNotEmpty()
    val hasBookmarks = bookmarks.isNotEmpty()
    var showBookmarks by remember { mutableStateOf(!hasOutline) }
    // The entry being read is the last one starting at or before this page.
    val currentEntry = remember(info, currentPage) { info.outline.indexOfLast { it.page in 0..currentPage } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentEntry - 2).coerceAtLeast(0))

    KobbloSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ExpressiveSheetContent(title = stringResource(R.string.viewer_pdf_contents)) {
            if (hasOutline && hasBookmarks) {
                Box(modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.xs)) {
                    KobbloSegmentedControl(
                        options = listOf(stringResource(R.string.viewer_pdf_contents), stringResource(R.string.viewer_pdf_bookmarks)),
                        selectedIndex = if (showBookmarks) 1 else 0,
                        onSelect = { showBookmarks = it == 1 }
                    )
                }
            }
            if (showBookmarks) {
                val sorted = remember(bookmarks) { bookmarks.sorted() }
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen)) {
                    itemsIndexed(sorted) { index, page ->
                        Box(modifier = Modifier.clip(KobbloShapes.groupItem(index, sorted.size)).background(colors.snow)) {
                            KobbloRow(
                                title = stringResource(R.string.page_number, page + 1),
                                showDivider = index < sorted.lastIndex,
                                onClick = { onGoToPage(page) }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen)
                ) {
                    itemsIndexed(info.outline) { index, item ->
                        Box(modifier = Modifier.clip(KobbloShapes.groupItem(index, info.outline.size)).background(colors.snow)) {
                            KobbloRow(
                                title = item.title,
                                titleColor = if (index == currentEntry) colors.action else colors.ink,
                                modifier = Modifier.padding(start = (item.depth.coerceAtMost(4) * 14).dp),
                                enabled = item.page >= 0,
                                trailing = {
                                    if (item.page >= 0) {
                                        Text("${item.page + 1}", style = KobbloType.callout, color = colors.smoke)
                                    }
                                },
                                showDivider = index < info.outline.lastIndex,
                                onClick = { onGoToPage(item.page) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfDetailsSheet(fileItem: FileItem, pageCount: Int, info: PdfInfo, onDismiss: () -> Unit) {
    val size = remember(fileItem.path) {
        val onDisk = if (fileItem.isExternalStream) fileItem.sizeBytes else File(fileItem.path).length()
        com.kobblo.gallery.file.explorer.data.core.FileSizes.label(onDisk)
    }
    val modified = remember(fileItem.lastModified) {
        if (fileItem.lastModified > 0L) {
            DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(fileItem.lastModified))
        } else {
            fileItem.changed
        }
    }
    KobbloSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ExpressiveSheetContent(title = fileItem.name) {
            KobbloGroup {
                info.title?.let { DetailRow(stringResource(R.string.viewer_pdf_detail_title), it) }
                info.author?.let { DetailRow(stringResource(R.string.viewer_pdf_detail_author), it) }
                if (modified.isNotEmpty()) DetailRow(stringResource(R.string.viewer_pdf_detail_modified), modified)
                DetailRow(stringResource(R.string.viewer_pdf_detail_kind), FileKinds.extensionOf(fileItem.name).uppercase())
                if (pageCount > 0) DetailRow(stringResource(R.string.viewer_pdf_detail_pages), "$pageCount")
                DetailRow(stringResource(R.string.viewer_pdf_detail_size), size)
                if (!fileItem.isExternalStream) {
                    KobbloRow(title = stringResource(R.string.viewer_pdf_detail_location), subtitle = fileItem.path)
                }
            }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, currentPage: Int, onGo: (Int) -> Unit, onCancel: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val target = typed.toIntOrNull()?.takeIf { it in 1..pageCount }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(DialogFocusDelayMs)
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }
    GradientAlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.viewer_pdf_go_to_page)) },
        text = {
            KobbloTextField(
                value = typed,
                onValueChange = { new -> typed = new.filter(Char::isDigit).take(9) },
                placeholder = stringResource(R.string.viewer_pdf_go_to_hint, currentPage + 1, pageCount),
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { target?.let { onGo(it - 1) } })
            )
        },
        confirmButton = {
            KobbloAlertAction(label = stringResource(R.string.viewer_pdf_go), onClick = { target?.let { onGo(it - 1) } })
        },
        dismissButton = { KobbloAlertCancel(onClick = onCancel) }
    )
}

@Composable
private fun PasswordDialog(
    wrongPassword: Boolean,
    isChecking: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val colors = KobbloDesign.colors
    var typed by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(DialogFocusDelayMs)
        try { focus.requestFocus() } catch (_: IllegalStateException) {}
    }
    GradientAlertDialog(
        onDismissRequest = { if (!isChecking) onCancel() },
        title = { Text(stringResource(R.string.viewer_pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.viewer_pdf_password_body),
                    style = KobbloType.footnote,
                    color = colors.smoke
                )
                KobbloTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = stringResource(R.string.viewer_pdf_password_hint),
                    modifier = Modifier.focusRequester(focus),
                    secret = !visible,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (typed.isNotEmpty() && !isChecking) onSubmit(typed) }),
                    trailing = {
                        KobbloIconButton(
                            icon = if (visible) AppIcons.EyeOff else AppIcons.Eye,
                            description = stringResource(if (visible) R.string.viewer_pdf_password_hide else R.string.viewer_pdf_password_show),
                            onClick = { visible = !visible },
                            size = 36.dp,
                            tint = colors.smoke,
                            background = Color.Transparent
                        )
                    }
                )
                if (wrongPassword) {
                    Text(stringResource(R.string.viewer_pdf_password_wrong), style = KobbloType.footnote, color = colors.danger)
                }
                if (isChecking) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KobbloLoader(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.viewer_pdf_password_checking), style = KobbloType.footnote, color = colors.smoke)
                    }
                }
            }
        },
        confirmButton = {
            KobbloAlertAction(
                label = stringResource(R.string.viewer_pdf_unlock),
                onClick = { if (typed.isNotEmpty() && !isChecking) onSubmit(typed) }
            )
        },
        dismissButton = { KobbloAlertCancel(onClick = { if (!isChecking) onCancel() }) }
    )
}

internal object PdfPrefs {
    private const val Store = "kobblo_prefs"
    private const val KeyDark = "viewer_pdf_dark_pages"

    fun darkPages(context: Context): Boolean =
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).getBoolean(KeyDark, false)

    fun setDarkPages(context: Context, value: Boolean) {
        context.getSharedPreferences(Store, Context.MODE_PRIVATE).edit().putBoolean(KeyDark, value).apply()
    }
}
