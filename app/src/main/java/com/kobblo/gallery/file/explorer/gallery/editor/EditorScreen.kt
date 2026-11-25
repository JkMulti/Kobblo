package com.kobblo.gallery.file.explorer.gallery.editor

import android.graphics.Bitmap
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.GallerySheet
import com.kobblo.gallery.file.explorer.ui.design.quietClickable
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconButton
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloGlass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Editing in a photo library: the photo on black, whatever the app's theme, because a colored
 * surrounding changes how a picture's colors look. Tools sit under the photo, one row at a time.
 */
@Composable
internal fun EditorScreen(
    controller: EditorController,
    onClose: () -> Unit,
    onOtherApp: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSave by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val edited = !controller.state.isUnchanged

    LaunchedEffect(controller) { controller.load() }

    androidx.activity.compose.BackHandler {
        when {
            saving -> Unit
            edited -> showDiscard = true
            else -> onClose()
        }
    }

    fun save(replace: Boolean) {
        showSave = false
        saving = true
        scope.launch {
            val result = EditorSaver.save(context, controller.path, controller.state, replace)
            saving = false
            when (result) {
                is EditorSaver.Result.Saved -> {
                    message = when {
                        result.reduced -> context.getString(R.string.gallery_editor_saved_reduced, result.width, result.height)
                        replace -> context.getString(R.string.gallery_editor_replaced)
                        else -> context.getString(R.string.gallery_editor_saved_copy)
                    }
                    delay(if (result.reduced) 1800L else 750L)
                    onClose()
                }
                is EditorSaver.Result.Failed -> message = context.getString(
                    when (result.reason) {
                        EditorSaver.Reason.DECODE -> R.string.gallery_editor_failed_decode
                        EditorSaver.Reason.MEMORY -> R.string.gallery_editor_failed_memory
                        EditorSaver.Reason.WRITE -> R.string.gallery_editor_failed_write
                    }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        EditorTopBar(
            canUndo = controller.canUndo,
            canRedo = controller.canRedo,
            onCancel = { if (edited) showDiscard = true else onClose() },
            onUndo = controller::undo,
            onRedo = controller::redo,
            onMore = { showMore = true },
            onDone = { if (edited) showSave = true else onClose() }
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                controller.loadFailed -> KobbloMessage(
                    icon = AppIcons.Photo,
                    title = stringResource(R.string.gallery_editor_failed_decode)
                )
                controller.working == null -> com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 36.dp, color = colors.action)
                else -> EditorCanvas(controller)
            }
            message?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .kobbloGlass(null, CapsuleShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(text, style = KobbloType.callout.copy(fontWeight = FontWeight.Medium), color = colors.ink)
                }
            }
            if (saving) {
                Box(
                    modifier = Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center
                ) {
                    com.kobblo.gallery.file.explorer.ui.ExpressiveCircularLoader(size = 40.dp, color = colors.action)
                }
            }
        }

        if (controller.working != null) {
            ToolPanel(controller)
            ModeTabs(controller)
        }
    }

    if (showMore) {
        GallerySheet(onDismiss = { showMore = false }) {
            Spacer(Modifier.height(8.dp))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.gallery_editor_reset),
                    enabled = edited,
                    trailing = {
                        Icon(AppIcons.Restore, contentDescription = null, tint = if (edited) colors.action else colors.pewter, modifier = Modifier.size(22.dp))
                    },
                    showDivider = true,
                    onClick = {
                        showMore = false
                        controller.reset()
                    }
                )
                KobbloRow(
                    title = stringResource(R.string.gallery_editor_other_app),
                    trailing = {
                        Icon(AppIcons.OpenExternal, contentDescription = null, tint = colors.action, modifier = Modifier.size(22.dp))
                    },
                    onClick = {
                        showMore = false
                        onOtherApp()
                    }
                )
            }
        }
    }

    if (showDiscard) {
        GallerySheet(onDismiss = { showDiscard = false }) {
            SheetHeading(stringResource(R.string.gallery_editor_discard_title), null)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetButton(stringResource(R.string.gallery_editor_discard), danger = true) {
                    showDiscard = false
                    onClose()
                }
                SheetButton(stringResource(R.string.gallery_editor_keep_editing), secondary = true) { showDiscard = false }
            }
        }
    }

    if (showSave) {
        SaveSheet(
            controller = controller,
            onSaveCopy = { save(replace = false) },
            onReplace = { save(replace = true) },
            onDismiss = { showSave = false }
        )
    }
}

@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onCancel: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TopTextButton(stringResource(R.string.gallery_cancel), onClick = onCancel)
        Spacer(Modifier.weight(1f))
        KobbloIconButton(
            icon = AppIcons.Undo,
            description = stringResource(R.string.gallery_editor_undo),
            onClick = { if (canUndo) onUndo() },
            size = 36.dp,
            tint = if (canUndo) colors.ink else colors.pewter,
            background = Color.Transparent
        )
        KobbloIconButton(
            icon = AppIcons.Redo,
            description = stringResource(R.string.gallery_editor_redo),
            onClick = { if (canRedo) onRedo() },
            size = 36.dp,
            tint = if (canRedo) colors.ink else colors.pewter,
            background = Color.Transparent
        )
        KobbloIconButton(
            icon = AppIcons.MoreHoriz,
            description = stringResource(R.string.gallery_editor_more),
            onClick = onMore,
            size = 36.dp,
            tint = colors.ink,
            background = Color.Transparent
        )
        Spacer(Modifier.weight(1f))
        TopTextButton(stringResource(R.string.gallery_editor_done), emphasized = true, onClick = onDone)
    }
}

@Composable
private fun TopTextButton(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(pressedScale = 0.94f, interactionSource = source)
            .clip(CapsuleShape)
            .quietClickable(source = source, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = KobbloType.body.copy(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal),
            color = KobbloDesign.colors.action
        )
    }
}

/** Places the picture on screen: fit to the space, then any zoom from the brush. */
private fun viewTransform(
    contentWidth: Float,
    contentHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    inset: Float,
    zoom: Float,
    panX: Float,
    panY: Float
): Affine {
    val fit = min((canvasWidth - inset * 2f) / contentWidth, (canvasHeight - inset * 2f) / contentHeight)
    val x = (canvasWidth - contentWidth * fit * zoom) / 2f + panX
    val y = (canvasHeight - contentHeight * fit * zoom) / 2f + panY
    return Affine.scale(fit * zoom).then(Affine.translate(x, y))
}

@Composable
private fun EditorCanvas(controller: EditorController) {
    val density = LocalDensity.current
    val colors = KobbloDesign.colors
    val view = LocalView.current
    val inset = with(density) { 20.dp.toPx() }
    val handleReach = with(density) { 30.dp.toPx() }
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var cropDragging by remember { mutableStateOf(false) }

    val mode = controller.mode
    LaunchedEffect(mode) {
        if (mode != EditorMode.BRUSH) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
    }
    val currentMode by rememberUpdatedState(mode)
    val photoLabel = stringResource(R.string.media_editor_photo)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics { contentDescription = photoLabel }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bitmap = controller.working ?: return@awaitEachGesture
                    when (currentMode) {
                        EditorMode.CROP -> {
                            val geometry = controller.state.geometry
                            val (frameW, frameH) = frameSize(bitmap.width, bitmap.height, geometry)
                            val place = viewTransform(frameW.toFloat(), frameH.toFloat(), size.width.toFloat(), size.height.toFloat(), inset, 1f, 0f, 0f)
                            val handle = cropHandleAt(down.position, geometry.crop, frameW, frameH, place, handleReach) ?: return@awaitEachGesture
                            down.consume()
                            cropDragging = true
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val dx = (change.position.x - last.x) / (frameW * place.a)
                                val dy = (change.position.y - last.y) / (frameH * place.d)
                                last = change.position
                                val current = controller.state
                                val next = dragCrop(current.geometry.crop, handle, dx, dy, frameW, frameH, current.geometry.aspect)
                                controller.preview(current.copy(geometry = current.geometry.copy(crop = next)))
                            }
                            cropDragging = false
                            controller.commit()
                        }

                        EditorMode.BRUSH -> {
                            val state = controller.state
                            val geo = geometryTransform(bitmap.width, bitmap.height, state.geometry)
                            val (outW, outH) = outputSize(bitmap.width, bitmap.height, state.geometry, 1f)
                            val whole = geo.then(viewTransform(outW.toFloat(), outH.toFloat(), size.width.toFloat(), size.height.toFloat(), 0f, zoom, panX, panY))
                            val toSource = whole.inverse()
                            fun fractionAt(position: Offset): Pair<Float, Float> {
                                val (sx, sy) = toSource.apply(position.x, position.y)
                                return (sx / bitmap.width) to (sy / bitmap.height)
                            }
                            val shortSide = min(bitmap.width, bitmap.height)
                            val radius = (brushPixels(controller.brushSize, density.density) / 2f) / whole.magnification / shortSide
                            val first = fractionAt(down.position)
                            val points = ArrayList<Float>()
                            points.add(first.first)
                            points.add(first.second)
                            controller.liveRadius = radius
                            controller.liveStroke = points.toList()
                            var zooming = false
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (pressed >= 2) {
                                    // A second finger means moving the view, so the stroke so far was a false start.
                                    zooming = true
                                    controller.liveStroke = null
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
                                    val pan = event.calculatePan()
                                    panX += pan.x
                                    panY += pan.y
                                    event.changes.forEach { it.consume() }
                                } else if (!zooming) {
                                    val change: PointerInputChange = event.changes.first { it.pressed }
                                    change.consume()
                                    val at = fractionAt(change.position)
                                    points.add(at.first)
                                    points.add(at.second)
                                    controller.liveStroke = points.toList()
                                }
                            }
                            if (!zooming) controller.addStroke(points.toList(), radius)
                            if (zoom <= 1.01f) {
                                zoom = 1f
                                panX = 0f
                                panY = 0f
                            }
                        }

                        else -> Unit
                    }
                }
            }
    ) {
        val bitmap = controller.working ?: return@Canvas
        controller.workingVersion // Repaints when strokes land on the same bitmap.
        val state = controller.state
        val cropping = controller.mode == EditorMode.CROP
        val (frameW, frameH) = frameSize(bitmap.width, bitmap.height, state.geometry)
        val contentW = if (cropping) frameW.toFloat() else frameW * state.geometry.crop.width
        val contentH = if (cropping) frameH.toFloat() else frameH * state.geometry.crop.height
        val place = viewTransform(contentW, contentH, size.width, size.height, if (cropping) inset else 0f, zoom, panX, panY)
        val geo = geometryTransform(bitmap.width, bitmap.height, state.geometry, applyCrop = !cropping)
        paint.colorFilter = EditorRender.colorFilter(state)

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.concat(place.toMatrix())
            native.clipRect(0f, 0f, contentW, contentH)
            native.concat(geo.toMatrix())
            native.drawBitmap(bitmap, 0f, 0f, paint)
            val live = controller.liveStroke
            if (live != null) {
                val effects = controller.effects
                if (effects != null) {
                    native.drawPath(
                        strokePath(live, bitmap.width, bitmap.height),
                        effects.paintFor(controller.brushMode, controller.liveRadius, feather = false)
                    )
                }
            }
            native.restore()
        }

        if (cropping) {
            val crop = state.geometry.crop
            val (left, top) = place.apply(crop.left * frameW, crop.top * frameH)
            val (right, bottom) = place.apply(crop.right * frameW, crop.bottom * frameH)
            val (frameLeft, frameTop) = place.apply(0f, 0f)
            val (frameRight, frameBottom) = place.apply(frameW.toFloat(), frameH.toFloat())
            val dim = Color.Black.copy(alpha = 0.58f)
            drawRect(dim, Offset(frameLeft, frameTop), Size(frameRight - frameLeft, top - frameTop))
            drawRect(dim, Offset(frameLeft, bottom), Size(frameRight - frameLeft, frameBottom - bottom))
            drawRect(dim, Offset(frameLeft, top), Size(left - frameLeft, bottom - top))
            drawRect(dim, Offset(right, top), Size(frameRight - right, bottom - top))
            drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 1.dp.toPx()))
            if (cropDragging || state.geometry.straighten != 0f) {
                val grid = Color.White.copy(alpha = 0.45f)
                for (i in 1..2) {
                    val x = left + (right - left) * i / 3f
                    val y = top + (bottom - top) * i / 3f
                    drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 1f)
                    drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1f)
                }
            }
            val arm = 20.dp.toPx()
            val weight = 3.dp.toPx()
            for ((x, y, sx, sy) in listOf(
                Corner(left, top, 1f, 1f), Corner(right, top, -1f, 1f),
                Corner(left, bottom, 1f, -1f), Corner(right, bottom, -1f, -1f)
            )) {
                drawLine(Color.White, Offset(x, y), Offset(x + arm * sx, y), strokeWidth = weight, cap = StrokeCap.Square)
                drawLine(Color.White, Offset(x, y), Offset(x, y + arm * sy), strokeWidth = weight, cap = StrokeCap.Square)
            }
        }
    }

    // A tick as the view comes back to fit, so returning from a zoom is felt.
    LaunchedEffect(zoom == 1f) {
        if (zoom == 1f && mode == EditorMode.BRUSH) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

private data class Corner(val x: Float, val y: Float, val sx: Float, val sy: Float)

private fun brushPixels(size: Float, density: Float): Float = (10f + size * 70f) * density

private fun cropHandleAt(
    position: Offset,
    crop: CropRect,
    frameW: Int,
    frameH: Int,
    place: Affine,
    reach: Float
): CropHandle? {
    val (left, top) = place.apply(crop.left * frameW, crop.top * frameH)
    val (right, bottom) = place.apply(crop.right * frameW, crop.bottom * frameH)
    val x = position.x
    val y = position.y
    val nearLeft = abs(x - left) <= reach
    val nearRight = abs(x - right) <= reach
    val nearTop = abs(y - top) <= reach
    val nearBottom = abs(y - bottom) <= reach
    val insideX = x > left - reach && x < right + reach
    val insideY = y > top - reach && y < bottom + reach
    return when {
        nearLeft && nearTop -> CropHandle.TOP_LEFT
        nearRight && nearTop -> CropHandle.TOP_RIGHT
        nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
        nearLeft && insideY -> CropHandle.LEFT
        nearRight && insideY -> CropHandle.RIGHT
        nearTop && insideX -> CropHandle.TOP
        nearBottom && insideX -> CropHandle.BOTTOM
        x in left..right && y in top..bottom -> CropHandle.MOVE
        else -> null
    }
}

@Composable
private fun ToolPanel(controller: EditorController) {
    Box(modifier = Modifier.fillMaxWidth().height(172.dp)) {
        when (controller.mode) {
            EditorMode.ADJUST -> AdjustPanel(controller)
            EditorMode.FILTERS -> FiltersPanel(controller)
            EditorMode.CROP -> CropPanel(controller)
            EditorMode.BRUSH -> BrushPanel(controller)
        }
    }
}

private fun adjustIcon(tool: AdjustTool): ImageVector = when (tool) {
    AdjustTool.EXPOSURE -> AppIcons.Exposure
    AdjustTool.BRIGHTNESS -> AppIcons.Sun
    AdjustTool.CONTRAST -> AppIcons.Contrast
    AdjustTool.SATURATION -> AppIcons.Drop
    AdjustTool.WARMTH -> AppIcons.Thermometer
    AdjustTool.TINT -> AppIcons.Palette
}

@Composable
private fun adjustLabel(tool: AdjustTool): String = stringResource(
    when (tool) {
        AdjustTool.EXPOSURE -> R.string.gallery_editor_exposure
        AdjustTool.BRIGHTNESS -> R.string.gallery_editor_brightness
        AdjustTool.CONTRAST -> R.string.gallery_editor_contrast
        AdjustTool.SATURATION -> R.string.gallery_editor_saturation
        AdjustTool.WARMTH -> R.string.gallery_editor_warmth
        AdjustTool.TINT -> R.string.gallery_editor_tint
    }
)

@Composable
private fun AdjustPanel(controller: EditorController) {
    val state = controller.state
    val tool = controller.adjustTool
    val value = state.adjustments.valueOf(tool)
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = KobbloSpace.screen),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(AdjustTool.entries) { item ->
                ToolDial(
                    icon = adjustIcon(item),
                    label = adjustLabel(item),
                    value = state.adjustments.valueOf(item),
                    selected = item == tool,
                    onClick = { controller.adjustTool = item }
                )
            }
        }
        EditorRuler(
            value = value,
            label = (value * 100f).roundToInt().toString(),
            onValue = { next ->
                val current = controller.state
                controller.preview(current.copy(adjustments = current.adjustments.with(tool, next)))
            },
            onRelease = { controller.commit() }
        )
    }
}

/** A round tool button whose ring fills as far as its value is set, so edits show at a glance. */
@Composable
private fun ToolDial(
    icon: ImageVector,
    label: String,
    value: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .width(62.dp)
            .quietClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val weight = 2.dp.toPx()
                drawCircle(if (selected) colors.ink else colors.silver, style = Stroke(width = weight))
                if (value != 0f) {
                    drawArc(
                        color = colors.action,
                        startAngle = -90f,
                        sweepAngle = value * 360f,
                        useCenter = false,
                        style = Stroke(width = weight, cap = StrokeCap.Round)
                    )
                }
            }
            Icon(icon, contentDescription = null, tint = if (selected) colors.ink else colors.smoke, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = KobbloType.tab,
            color = if (selected) colors.ink else colors.smoke,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A ruler that slides under a fixed mark, as a dial would. Sliding feels finer than a short slider,
 * and a double tap goes back to zero. It clicks once on passing through zero.
 */
@Composable
private fun EditorRuler(
    value: Float,
    label: String,
    onValue: (Float) -> Unit,
    onRelease: () -> Unit,
    range: Float = 1f
) {
    val colors = KobbloDesign.colors
    val view = LocalView.current
    val currentValue by rememberUpdatedState(value)
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val rulerLabel = stringResource(R.string.media_editor_adjustment_ruler)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = KobbloType.caption.copy(fontFeatureSettings = "tnum"),
            color = if (value == 0f) colors.smoke else colors.ink
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .semantics { contentDescription = rulerLabel }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        currentOnValue(0f)
                        currentOnRelease()
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { currentOnRelease() },
                        onDragCancel = { currentOnRelease() },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val before = currentValue
                            // The whole range takes most of the width, which leaves room for fine moves.
                            val raw = (before - amount / (size.width * 0.8f) * 2f * range).coerceIn(-range, range)
                            val snapped = if (abs(raw) < range * 0.012f) 0f else raw
                            if ((before == 0f) != (snapped == 0f)) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnValue(snapped)
                        }
                    )
                }
        ) {
            val center = size.width / 2f
            val spacing = 9.dp.toPx()
            val shift = (value / range) * (size.width * 0.4f)
            val ticks = (size.width * 0.4f / spacing).toInt()
            for (i in -ticks..ticks) {
                val x = center + i * spacing - shift
                if (x < 0f || x > size.width) continue
                val major = i % 5 == 0
                drawLine(
                    color = if (major) colors.smoke else colors.silver,
                    start = Offset(x, size.height * if (major) 0.22f else 0.34f),
                    end = Offset(x, size.height * if (major) 0.78f else 0.66f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            drawLine(colors.action, Offset(center, 0f), Offset(center, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun filterLabel(filter: FilterPreset): String = stringResource(
    when (filter) {
        FilterPreset.ORIGINAL -> R.string.gallery_editor_filter_original
        FilterPreset.VIVID -> R.string.gallery_editor_filter_vivid
        FilterPreset.WARM -> R.string.gallery_editor_filter_warm
        FilterPreset.COOL -> R.string.gallery_editor_filter_cool
        FilterPreset.DRAMATIC -> R.string.gallery_editor_filter_dramatic
        FilterPreset.FADE -> R.string.gallery_editor_filter_fade
        FilterPreset.MONO -> R.string.gallery_editor_filter_mono
        FilterPreset.NOIR -> R.string.gallery_editor_filter_noir
        FilterPreset.SEPIA -> R.string.gallery_editor_filter_sepia
    }
)

@Composable
private fun FiltersPanel(controller: EditorController) {
    val colors = KobbloDesign.colors
    val source = controller.preview ?: return
    val thumbnail = remember(source) { squareThumbnail(source, 200) }
    val state = controller.state
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = KobbloSpace.screen),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(FilterPreset.entries) { filter ->
                val selected = filter == state.filter
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .quietClickable { controller.commit(state.copy(filter = filter)) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = if (filter == FilterPreset.ORIGINAL) null else ColorFilter.colorMatrix(ColorMatrix(filterRows(filter))),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(KobbloShapes.sm)
                            .then(if (selected) Modifier.border(2.dp, colors.action, KobbloShapes.sm) else Modifier)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = filterLabel(filter),
                        style = KobbloType.tab,
                        color = if (selected) colors.ink else colors.smoke,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun squareThumbnail(source: Bitmap, side: Int): Bitmap {
    val short = min(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - short) / 2, (source.height - short) / 2, short, short)
    return Bitmap.createScaledBitmap(square, side, side, true)
}

/** A crop shape on offer. A null ratio is free, and [original] follows the photo. */
private class AspectChoice(val labelRes: Int, val ratio: Float?, val original: Boolean = false)

private val AspectChoices = listOf(
    AspectChoice(R.string.gallery_editor_aspect_free, null),
    AspectChoice(R.string.gallery_editor_aspect_original, null, original = true),
    AspectChoice(R.string.gallery_editor_aspect_square, 1f),
    AspectChoice(R.string.gallery_editor_aspect_4_5, 4f / 5f),
    AspectChoice(R.string.gallery_editor_aspect_5_4, 5f / 4f),
    AspectChoice(R.string.gallery_editor_aspect_3_4, 3f / 4f),
    AspectChoice(R.string.gallery_editor_aspect_4_3, 4f / 3f),
    AspectChoice(R.string.gallery_editor_aspect_2_3, 2f / 3f),
    AspectChoice(R.string.gallery_editor_aspect_3_2, 3f / 2f),
    AspectChoice(R.string.gallery_editor_aspect_9_16, 9f / 16f),
    AspectChoice(R.string.gallery_editor_aspect_16_9, 16f / 9f)
)

@Composable
private fun CropPanel(controller: EditorController) {
    val colors = KobbloDesign.colors
    val state = controller.state
    val geometry = state.geometry
    val bitmap = controller.working ?: return
    val (frameW, frameH) = frameSize(bitmap.width, bitmap.height, geometry)
    val originalRatio = frameW.toFloat() / frameH

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
        EditorRuler(
            value = geometry.straighten,
            label = "%.1f°".format(geometry.straighten),
            range = 45f,
            onValue = { next ->
                val current = controller.state
                controller.preview(current.copy(geometry = current.geometry.copy(straighten = next)))
            },
            onRelease = { controller.commit() }
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = KobbloSpace.screen),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                KobbloIconButton(
                    icon = AppIcons.RotateLeft,
                    description = stringResource(R.string.gallery_editor_rotate),
                    onClick = { controller.commit(state.copy(geometry = geometry.rotatedLeft())) },
                    tint = colors.ink,
                    background = colors.mist
                )
            }
            item {
                KobbloIconButton(
                    icon = AppIcons.FlipHorizontal,
                    description = stringResource(R.string.gallery_editor_flip_h),
                    onClick = { controller.commit(state.copy(geometry = geometry.flippedHorizontally())) },
                    tint = colors.ink,
                    background = colors.mist
                )
            }
            item {
                KobbloIconButton(
                    icon = AppIcons.FlipVertical,
                    description = stringResource(R.string.gallery_editor_flip_v),
                    onClick = { controller.commit(state.copy(geometry = geometry.flippedVertically())) },
                    tint = colors.ink,
                    background = colors.mist
                )
            }
            items(AspectChoices) { choice ->
                val ratio = if (choice.original) originalRatio else choice.ratio
                val selected = if (choice.original) {
                    geometry.aspect != null && abs(geometry.aspect - originalRatio) < 0.001f
                } else if (choice.ratio == null) {
                    geometry.aspect == null
                } else {
                    geometry.aspect != null && abs(geometry.aspect - choice.ratio) < 0.001f
                }
                val label = stringResource(choice.labelRes)
                Box(
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .background(if (selected) colors.ink else colors.mist)
                        .clickable {
                            val crop = if (ratio == null) geometry.crop else fitAspect(frameW, frameH, ratio)
                            controller.commit(state.copy(geometry = geometry.copy(aspect = ratio, crop = crop)))
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = label,
                        style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = if (selected) colors.canvas else colors.ink,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun BrushPanel(controller: EditorController) {
    val colors = KobbloDesign.colors
    val view = LocalView.current
    val sizeDp: Dp = (brushPixels(controller.brushSize, 1f)).dp
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = KobbloSpace.screen),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        KobbloSegmentedControl(
            options = listOf(
                stringResource(R.string.gallery_editor_brush_pixelate),
                stringResource(R.string.gallery_editor_brush_blur)
            ),
            selectedIndex = if (controller.brushMode == BrushMode.PIXELATE) 0 else 1,
            onSelect = { controller.brushMode = if (it == 0) BrushMode.PIXELATE else BrushMode.BLUR }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(sizeDp.coerceAtMost(40.dp))
                        .background(colors.ink.copy(alpha = 0.85f), CapsuleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            BrushSizeSlider(
                value = controller.brushSize,
                onValue = {
                    controller.brushSize = it
                },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.gallery_editor_brush_hint),
            style = KobbloType.footnote,
            color = colors.smoke,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun BrushSizeSlider(value: Float, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val colors = KobbloDesign.colors
    val currentOnValue by rememberUpdatedState(onValue)
    val brushLabel = stringResource(R.string.media_editor_brush_size)
    Canvas(
        modifier = modifier
            .height(36.dp)
            .semantics { contentDescription = brushLabel }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnValue((down.position.x / size.width).coerceIn(0f, 1f))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        currentOnValue((change.position.x / size.width).coerceIn(0f, 1f))
                    }
                }
            }
    ) {
        val y = size.height / 2f
        val track = 4.dp.toPx()
        drawLine(colors.silver, Offset(0f, y), Offset(size.width, y), strokeWidth = track, cap = StrokeCap.Round)
        drawLine(colors.action, Offset(0f, y), Offset(size.width * value, y), strokeWidth = track, cap = StrokeCap.Round)
        drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(size.width * value, y))
    }
}

@Composable
private fun ModeTabs(controller: EditorController) {
    val colors = KobbloDesign.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf(
            Triple(EditorMode.ADJUST, AppIcons.Adjust, R.string.gallery_editor_mode_adjust),
            Triple(EditorMode.FILTERS, AppIcons.Filters, R.string.gallery_editor_mode_filters),
            Triple(EditorMode.CROP, AppIcons.Crop, R.string.gallery_editor_mode_crop),
            Triple(EditorMode.BRUSH, AppIcons.Brush, R.string.gallery_editor_mode_brush)
        )
        tabs.forEach { (mode, icon, label) ->
            val selected = controller.mode == mode
            Column(
                modifier = Modifier
                    .weight(1f)
                    .quietClickable { controller.mode = mode },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) colors.action else colors.smoke, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(label),
                    style = KobbloType.tab,
                    color = if (selected) colors.action else colors.smoke,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SheetHeading(title: String, body: String?) {
    val colors = KobbloDesign.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = KobbloType.footnote,
                color = colors.smoke,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun SheetButton(label: String, secondary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = KobbloDesign.colors
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                danger -> colors.danger
                secondary -> colors.mist
                else -> colors.action
            },
            contentColor = if (secondary) colors.ink else colors.onAction
        )
    ) {
        Text(label, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** How to keep the edit: a copy by default, at any of a few sizes. Replacing asks first. */
@Composable
private fun SaveSheet(
    controller: EditorController,
    onSaveCopy: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KobbloDesign.colors
    var confirmReplace by remember { mutableStateOf(false) }
    val scales = listOf(1f, 0.75f, 0.5f, 0.25f)
    val state = controller.state
    val full = outputSize(controller.fullWidth, controller.fullHeight, state.geometry, state.outputScale)

    GallerySheet(onDismiss = onDismiss) {
        if (confirmReplace) {
            SheetHeading(
                stringResource(R.string.gallery_editor_replace_title),
                stringResource(R.string.gallery_editor_replace_body)
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetButton(stringResource(R.string.gallery_editor_replace), danger = true, onClick = onReplace)
                SheetButton(stringResource(R.string.gallery_cancel), secondary = true) { confirmReplace = false }
            }
        } else {
            SheetHeading(
                stringResource(R.string.gallery_editor_save_title),
                stringResource(R.string.gallery_editor_save_size, full.first, full.second)
            )
            KobbloSegmentedControl(
                options = listOf("100%", "75%", "50%", "25%"),
                selectedIndex = scales.indexOfFirst { abs(it - state.outputScale) < 0.001f }.coerceAtLeast(0),
                onSelect = { controller.commit(state.copy(outputScale = scales[it])) },
                modifier = Modifier.padding(horizontal = KobbloSpace.screen)
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetButton(stringResource(R.string.gallery_editor_save_copy), onClick = onSaveCopy)
                if (EditorSaver.canReplace(controller.path)) {
                    SheetButton(stringResource(R.string.gallery_editor_replace), secondary = true) { confirmReplace = true }
                }
            }
        }
    }
}
