package com.kobblo.gallery.file.explorer.ui.welcome

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GalleryThumb
import com.kobblo.gallery.file.explorer.gallery.GalleryThumbFetcher
import com.kobblo.gallery.file.explorer.gallery.PhotoFlight
import com.kobblo.gallery.file.explorer.smart.SmartFeatures
import com.kobblo.gallery.file.explorer.smart.SmartWelcomeChoices
import com.kobblo.gallery.file.explorer.smart.buttonLabel
import com.kobblo.gallery.file.explorer.smart.rememberSmartChoices
import com.kobblo.gallery.file.explorer.smart.rememberSmartDownload
import com.kobblo.gallery.file.explorer.ui.design.BrandMark
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether the welcome has been through, once per install. */
object WelcomePrefs {
    private const val PREFS = "kobblo_prefs"
    private const val DONE = "welcome_done"

    fun isDone(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
    }
}

private enum class Step { Intro, Access, Smart, Ready }

/**
 * The first thing a new install shows: what the app does, the access it needs, Smart Features if the
 * phone can run them, and in. The last step shows the newest photos, which then fly into their own
 * tiles in the Home grid, already laid out underneath. [photos] are the library newest first.
 */
@Composable
fun WelcomeFlow(photos: List<FileItem>, onRequestFiles: () -> Unit, onFinish: (WelcomeExit?) -> Unit) {
    var step by rememberSaveable { mutableStateOf(Step.Intro) }
    var downloading by rememberSaveable { mutableStateOf(false) }
    val steps = remember { Step.entries.filter { it != Step.Smart || SmartFeatures.isSupported } }
    val reduced = ExpressiveMotion.isReducedMotion()
    fun next() {
        step = steps.getOrElse(steps.indexOf(step) + 1) { Step.Ready }
    }
    BackHandler(enabled = step != Step.Intro) { step = steps[(steps.indexOf(step) - 1).coerceAtLeast(0)] }

    // Opaque, and every touch stays here: the app is already built underneath.
    Box(
        Modifier
            .fillMaxSize()
            .background(KobbloDesign.colors.canvas)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (reduced) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(380)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(380))) togetherWith
                        (slideOutHorizontally(tween(380)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(220)))
                }
            },
            label = "welcome",
            modifier = Modifier.fillMaxSize()
        ) { shown ->
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                when (shown) {
                    Step.Intro -> Intro(::next)
                    Step.Access -> AccessStep(onRequestFiles, ::next)
                    Step.Smart -> SmartStep { started ->
                        downloading = started
                        next()
                    }
                    Step.Ready -> Ready(photos, downloading, onFinish)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen + 8.dp)
            .height(52.dp)
    ) {
        Text(text, style = KobbloType.headline)
    }
}

@Composable
private fun ColumnScope.SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp)) {
        Text(text, style = KobbloType.callout, color = KobbloDesign.colors.action)
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = KobbloSpace.screen + 8.dp)) {
        Spacer(Modifier.height(32.dp))
        Text(title, style = KobbloType.largeTitle, color = KobbloDesign.colors.ink)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = KobbloType.callout, color = KobbloDesign.colors.smoke)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ColumnScope.Intro(onNext: () -> Unit) {
    // Centered, and scrolls when it's taller than the screen, with small screens or large text.
    BoxWithConstraints(Modifier.weight(1f)) {
        val height = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                BrandMark(size = 84.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.welcome_title),
                    style = KobbloType.largeTitle,
                    color = KobbloDesign.colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(36.dp))
                Feature(AppIcons.Photo, KobbloTint.Indigo, R.string.welcome_photos_title, R.string.welcome_photos_body)
                Feature(AppIcons.FolderOpen, KobbloTint.Blue, R.string.welcome_files_title, R.string.welcome_files_body)
                Feature(AppIcons.Send, KobbloTint.Green, R.string.welcome_send_title, R.string.welcome_send_body)
                Feature(AppIcons.ShieldCheck, KobbloTint.Gray, R.string.welcome_private_title, R.string.welcome_private_body)
            }
        }
    }
    PrimaryButton(stringResource(R.string.welcome_continue), onNext)
}

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: Int, body: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        KobbloIconBadge(icon = icon, color = tint, size = 36.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = KobbloType.headline, color = KobbloDesign.colors.ink)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(body), style = KobbloType.footnote, color = KobbloDesign.colors.smoke)
        }
    }
}

@Composable
private fun ColumnScope.AccessStep(onRequestFiles: () -> Unit, onNext: () -> Unit) {
    val (access, refresh) = rememberAccess()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepTitle(stringResource(R.string.access_title), stringResource(R.string.access_subtitle))
        AccessGroups(access = access, onRefresh = refresh, onRequestFiles = onRequestFiles)
    }
    Spacer(Modifier.height(12.dp))
    if (access.files) {
        PrimaryButton(stringResource(R.string.welcome_continue), onNext)
    } else {
        PrimaryButton(stringResource(R.string.access_allow), onRequestFiles)
        SecondaryButton(stringResource(R.string.welcome_not_now), onNext)
    }
}

@Composable
private fun ColumnScope.SmartStep(onDone: (Boolean) -> Unit) {
    val choices = rememberSmartChoices()
    val download = rememberSmartDownload(choices) { onDone(true) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        StepTitle(stringResource(R.string.smart_title), stringResource(R.string.smart_subtitle))
        SmartWelcomeChoices(choices)
    }
    Spacer(Modifier.height(12.dp))
    val context = LocalContext.current
    val total = remember(choices.wanted, choices.quality) { choices.total(context) }
    // Nothing chosen, or all of it already here, leaves nothing to download.
    PrimaryButton(buttonLabel(total)) { if (total == 0L) onDone(false) else download() }
    SecondaryButton(stringResource(R.string.welcome_not_now)) { onDone(false) }
}

// ── The last step, and the way into the app ──────────────────────────────────

/**
 * A photo from the last step at the moment Let's Go was tapped: where it sat, its tilt, and the pixel
 * size its picture was loaded at, so the entrance can show that same picture straight from memory.
 */
class ExitTile(val file: FileItem, val rect: Rect, val rotation: Float, val px: Int)

/** The last step's final frame: its photos, where the tap was, and the backdrop the entrance fades out. */
class WelcomeExit(val tiles: List<ExitTile>, val tap: Offset, val background: Color)

/** Columns in the collage. */
private const val CollageColumns = 4

/** How many photos the last step considers before seeing how many fit. */
private const val CollageCandidates = CollageColumns * 8

/** One photo of the collage, and where it is drawn at this moment. */
private class CollagePhoto(val file: FileItem, val column: Int, val row: Int) {
    var placed: LayoutCoordinates? = null
    var tilt = 0f
    var bob = 0f
    var loadedPx = 1

    /** Where the photo is right now, sway included, or null when it isn't on screen. */
    fun snapshot(): ExitTile? {
        val spot = placed?.takeIf { it.isAttached } ?: return null
        val origin = spot.positionInRoot()
        val box = Rect(Offset(origin.x, origin.y + bob), Size(spot.size.width.toFloat(), spot.size.height.toFloat()))
        return ExitTile(file, box, tilt, loadedPx)
    }
}

/**
 * A collage photo's picture, from the same system thumbnails the grid uses, cached under a key of its
 * own so the entrance draws the picture already on screen rather than loading it again.
 */
private fun collagePicture(context: Context, file: FileItem, px: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(GalleryThumb(file.id.toLong(), file.path, file.type == "video", px))
        .fetcherFactory(GalleryThumbFetcher.Factory(context))
        .memoryCacheKey(MemoryCache.Key("welcome-collage/${file.id}/${file.lastModified}/$px"))
        .size(px)
        .crossfade(false)
        .apply { if (file.type == "video") videoFrameMillis(1000L) }
        .build()

/** A fixed tilt for each place in the collage, between -8 and 8 degrees, so it looks laid by hand. */
private fun restingTilt(column: Int, row: Int): Float = ((column * 73 + row * 151) % 11 - 5) * 1.6f

/**
 * The newest photos in four staggered columns, every other column set half a photo lower, each photo
 * slightly tilted, the columns swaying slowly up and down out of step with each other.
 */
@Composable
private fun Collage(photos: List<CollagePhoto>, modifier: Modifier = Modifier) {
    val still = ExpressiveMotion.isReducedMotion()
    val sway by rememberInfiniteTransition(label = "collage").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing)),
        label = "sway"
    )
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val cell = maxWidth / CollageColumns
        val tile = cell * 0.84f
        val inset = (cell - tile) / 2
        val rows = ((maxHeight - cell / 2) / cell).toInt().coerceAtLeast(1)
        val usedRows = minOf(rows, (photos.size + CollageColumns - 1) / CollageColumns)
        val top = (maxHeight - cell * usedRows - cell / 2) / 2
        val px = with(density) { tile.roundToPx() }.coerceAtLeast(1)
        for (photo in photos) {
            if (photo.row >= rows) continue
            photo.loadedPx = px
            val lowered = if (photo.column % 2 == 1) cell / 2 else 0.dp
            AsyncImage(
                model = remember(photo.file.path, px) { collagePicture(context, photo.file, px) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(cell * photo.column + inset, top + cell * photo.row + lowered + inset)
                    .size(tile)
                    .onGloballyPositioned { photo.placed = it }
                    .graphicsLayer {
                        val turn = if (still) 0f else sway * 2f * PI.toFloat()
                        photo.bob = if (still) 0f else 5.dp.toPx() * sin(turn + photo.column * 1.1f)
                        photo.tilt = restingTilt(photo.column, photo.row) +
                            (if (still) 0f else 1.5f * sin(2f * turn + photo.row * 1.3f + photo.column * 0.6f))
                        translationY = photo.bob
                        rotationZ = photo.tilt
                    }
                    .clip(KobbloShapes.sm)
                    .background(KobbloDesign.colors.mist)
            )
        }
    }
}

@Composable
private fun ColumnScope.Ready(photos: List<FileItem>, downloading: Boolean, onFinish: (WelcomeExit?) -> Unit) {
    val (access, _) = rememberAccess()
    val collage = remember(photos) {
        photos.asSequence()
            .filter { !it.isLocked }
            .take(CollageCandidates)
            .mapIndexed { index, file -> CollagePhoto(file, column = index % CollageColumns, row = index / CollageColumns) }
            .toList()
    }
    val wordsAlpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var tapPoint by remember { mutableStateOf(Offset.Zero) }
    var going by remember { mutableStateOf(false) }
    val backdrop = KobbloDesign.colors.canvas

    if (collage.isEmpty()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Icon(AppIcons.CheckCircleFill, contentDescription = null, tint = KobbloTint.Green, modifier = Modifier.size(88.dp))
        }
    } else {
        Collage(collage, Modifier.fillMaxWidth().weight(1f))
    }

    Column(Modifier.graphicsLayer { alpha = wordsAlpha.value }) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.welcome_ready_title),
            style = KobbloType.largeTitle,
            color = KobbloDesign.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        val body = when {
            !access.files -> R.string.welcome_ready_no_access
            downloading -> R.string.welcome_ready_downloading
            else -> R.string.welcome_ready_body
        }
        Text(
            text = stringResource(body),
            style = KobbloType.callout,
            color = KobbloDesign.colors.smoke,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.onGloballyPositioned { tapPoint = it.boundsInRoot().center }) {
            PrimaryButton(stringResource(R.string.welcome_lets_go)) {
                if (going) return@PrimaryButton
                going = true
                scope.launch {
                    // The words step aside first; the photos then leave from wherever they've swayed to.
                    wordsAlpha.animateTo(0f, tween(120))
                    val tiles = collage.mapNotNull { it.snapshot() }
                    onFinish(tiles.takeIf { it.isNotEmpty() }?.let { WelcomeExit(it, tapPoint, backdrop) })
                }
            }
        }
    }
}

/** The whole entrance, in milliseconds. */
private const val EntranceMs = 820

/** The share of the entrance over which photos set off, nearest the tap first. */
private const val SetOffSpread = 0.25f

/** The share of the entrance each photo spends flying. */
private const val FlightShare = 1f - SetOffSpread

/**
 * Into the app. Each photo from the last step flies into its own tile in the Home grid, already laid out
 * underneath, straightening and squaring its corners as it lands. They set off as a wave spreading out
 * from where Let's Go was tapped, while the backdrop fades. A photo whose tile isn't on screen sinks and
 * fades instead.
 */
@Composable
fun Entrance(exit: WelcomeExit, onDone: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val still = ExpressiveMotion.isReducedMotion()
    val clock = remember(exit) { Animatable(0f) }
    val homes = remember(exit) { arrayOfNulls<Rect>(exit.tiles.size) }
    val setOff = remember(exit) {
        val distances = exit.tiles.map { (it.rect.center - exit.tap).getDistance() }
        val farthest = distances.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        distances.map { SetOffSpread * it / farthest }
    }

    LaunchedEffect(exit) {
        if (!still) {
            // The grid is laying itself out underneath; give it a few frames to place most of the tiles.
            for (frame in 0 until 12) {
                withFrameNanos { }
                exit.tiles.forEachIndexed { i, tile -> homes[i] = PhotoFlight.cellRect(tile.file.path) ?: homes[i] }
                if (homes.count { it != null } * 4 >= exit.tiles.size * 3) break
            }
        }
        clock.animateTo(1f, tween(if (still) 200 else EntranceMs, easing = LinearEasing))
        onDone()
    }

    val now = clock.value
    val backdropAlpha = if (still) 1f - now else (1f - (now - 0.3f) / 0.65f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawRect(exit.background, alpha = backdropAlpha) }
        val startCorner = with(density) { 12.dp.toPx() }
        exit.tiles.forEachIndexed { i, tile ->
            val local = if (still) 0f else ((now - setOff[i]) / FlightShare).coerceIn(0f, 1f)
            val eased = EaseOutBack.transform(local)
            // Follow the tile every frame: the grid may still be settling into place.
            val home = PhotoFlight.cellRect(tile.file.path) ?: homes[i]
            val landing = home ?: tile.rect.translate(0f, tile.rect.height)
            val rect = lerp(tile.rect, landing, eased)
            val corner = (startCorner * (1f - eased)).coerceAtLeast(0f)
            val alpha = when {
                still -> 1f - now
                home == null -> (1f - eased).coerceIn(0f, 1f)
                else -> 1f
            }
            AsyncImage(
                model = remember(tile.file.path, tile.px) { collagePicture(context, tile.file, tile.px) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            Constraints.fixed(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1))
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                        }
                    }
                    .graphicsLayer {
                        rotationZ = tile.rotation * (1f - eased)
                        this.alpha = alpha
                        if (corner > 0.5f) {
                            shape = RoundedRectangle(corner.toDp())
                            clip = true
                        }
                    }
            )
        }
    }
}
