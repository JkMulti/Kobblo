package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The brand mark: the launcher icon itself, so the welcome and About show what's on the home screen.
 * An adaptive icon's layers are 108dp and a launcher shows the middle 72dp, so both are drawn half as
 * big again as the tile and cropped to it. Its white tile gets a faint edge on light screens.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val edge = if (KobbloDesign.colors.isDark) Modifier else Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.1f), KobbloShapes.tile)
    Box(
        modifier = modifier
            .size(size)
            .clip(KobbloShapes.tile)
            .then(edge),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}

/**
 * The way back: the chevron alone in a round toolbar button, like the top bar's others. [label] names
 * the screen it returns to for TalkBack only; nothing is written beside the button.
 */
@Composable
fun KobbloBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val colors = KobbloDesign.colors
    val source = remember { MutableInteractionSource() }
    val back = stringResource(R.string.back)
    val backDescription = if (label == null || label == back) back else stringResource(R.string.screen_back_to, label)
    Box(
        modifier = modifier
            .size(KobbloSize.toolbarButton)
            .pressScale(pressedScale = 0.94f, interactionSource = source)
            .clip(CapsuleShape)
            .background(colors.snow)
            .quietClickable(source = source, onClick = onClick)
            .semantics { contentDescription = backDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(AppIcons.Back, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * A round toolbar button: an ink icon, black in light and white in dark, on a snow circle. Top bar
 * icons are always ink, while text in a top bar keeps the tint. Away from the top bar, pass the
 * tint where an icon is the action itself.
 */
@Composable
fun KobbloIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = KobbloSize.toolbarButton,
    iconSize: Dp = 20.dp,
    tint: Color = KobbloDesign.colors.ink,
    background: Color = KobbloDesign.colors.snow,
    onLongClick: (() -> Unit)? = null
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(pressedScale = 0.9f, interactionSource = source)
            .clip(CapsuleShape)
            .background(background)
            .quietCombinedClickable(source = source, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** The app's search field. */
@Composable
fun KobbloSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    autoCorrect: Boolean = true
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CapsuleShape)
            .background(colors.mist)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.Search, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (onFocusChanged != null) Modifier.onFocusChanged { onFocusChanged(it.isFocused) } else Modifier),
            textStyle = KobbloType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.action),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrectEnabled = autoCorrect,
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit?.invoke() }),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(placeholder, style = KobbloType.body, color = colors.smoke, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        )
        if (query.isNotEmpty()) {
            KobbloIconButton(
                icon = AppIcons.CloseCircleFill,
                description = stringResource(R.string.screen_clear_search),
                onClick = { onQueryChange("") },
                size = 30.dp,
                iconSize = 18.dp,
                tint = colors.pewter,
                background = Color.Transparent
            )
        } else {
            trailing?.invoke()
        }
    }
}

/** A one-line field for a name, a path or a password. A value set in code can carry a selection. */
@Composable
fun KobbloTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(KobbloShapes.sm)
            .background(colors.mist)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = KobbloType.body.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.action),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(placeholder, style = KobbloType.body, color = colors.smoke, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        )
        trailing?.invoke()
    }
}

/** [KobbloTextField] for callers that only care about the text. */
@Composable
fun KobbloTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    // Keeps the cursor where it is across recomposition, as a field holding only a String can't.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val current = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    KobbloTextField(
        value = current,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        placeholder = placeholder,
        modifier = modifier,
        secret = secret,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailing = trailing
    )
}

/**
 * Picks one of a few short options. A single capsule slides between them, the same gesture of
 * motion as the tab bar, so the selection reads as moving rather than swapping.
 */
@Composable
fun KobbloSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val position = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selectedIndex) {
        if (reduced) position.snapTo(selectedIndex.toFloat())
        else position.animateTo(selectedIndex.toFloat(), KobbloMotion.snappy())
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CapsuleShape)
            .background(colors.mist)
            .padding(KobbloSpace.xxs)
            .drawBehind {
                val slot = size.width / options.size
                val x = slot * position.value
                translate(left = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) x else size.width - slot - x) {
                    drawOutline(
                        outline = CapsuleShape.createOutline(Size(slot, size.height), layoutDirection, this),
                        color = colors.snow
                    )
                }
            }
    ) {
        options.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = index == selectedIndex,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelect(index) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = KobbloType.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = if (index == selectedIndex) colors.ink else colors.smoke,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A storage meter that fills to [fraction] when first shown. */
@Composable
fun StorageMeter(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val target = fraction.coerceIn(0f, 1f)
    val animated = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target) {
        if (reduced) animated.snapTo(target)
        else animated.animateTo(target, tween(480, easing = KobbloMotion.Emphasized))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(colors.mist, cornerRadius = radius)
                if (animated.value > 0f) {
                    val width = (size.width * animated.value).coerceAtLeast(size.height)
                    drawRoundRect(colors.action, Offset.Zero, Size(width, size.height), radius)
                }
            }
    )
}

/** Centered message for an empty or blocked screen. */
@Composable
fun KobbloMessage(
    icon: ImageVector,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.xl, vertical = KobbloSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KobbloSpace.xs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.pewter,
            modifier = Modifier.padding(bottom = KobbloSpace.xs).size(52.dp)
        )
        Text(title, style = KobbloType.headline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = colors.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) {
            Text(body, style = KobbloType.callout, color = colors.smoke, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(KobbloSpace.xs))
            action()
        }
    }
}

/**
 * Rows settle into place when they first appear: a short fade and a small rise, staggered by
 * [index]. The stagger is capped so a long list never makes anyone wait for it.
 */
fun Modifier.kobbloAppear(index: Int = 0): Modifier = composed {
    val reduced = ExpressiveMotion.isReducedMotion()
    if (reduced) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(8) * 24L))
        progress.animateTo(1f, tween(KobbloMotion.Default, easing = KobbloMotion.EaseOut))
    }
    val rise = with(LocalDensity.current) { 8.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

/**
 * The iOS large title: it folds away as the content scrolls up and returns when the content
 * reaches the top. Attach [connection] to the scrolling content's parent.
 */
@Stable
class LargeTitleState(initialMaxPx: Float) {
    var collapsedPx by mutableFloatStateOf(0f)
        private set

    // What can fold is whatever the title block measures, which grows with a search field or a
    // filter chip. Only scrolling reads this, so it needs no snapshot state.
    private var maxPx = initialMaxPx

    /** 0 when the title is fully open, 1 when it has folded away. */
    val fraction: Float get() = if (maxPx <= 0f) 0f else (collapsedPx / maxPx).coerceIn(0f, 1f)

    /** Reopens the title, for when the content below it is replaced. */
    fun reset() {
        collapsedPx = 0f
    }

    /** The same, moving the way a finger would open it. */
    suspend fun open(spec: AnimationSpec<Float>) {
        if (collapsedPx > 0f) animate(collapsedPx, 0f, animationSpec = spec) { value, _ -> collapsedPx = value }
    }

    internal fun restore(px: Float) {
        collapsedPx = px
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || collapsedPx >= maxPx) return Offset.Zero
            val take = (-available.y).coerceAtMost(maxPx - collapsedPx)
            collapsedPx += take
            return Offset(0f, -take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f || collapsedPx <= 0f) return Offset.Zero
            val take = available.y.coerceAtMost(collapsedPx)
            collapsedPx -= take
            return Offset(0f, take)
        }
    }

    /**
     * Clips the large title's height as it folds, and fades it out early. The clip has to wrap the
     * layout: inside it, a folded title still reaches up over the toolbar, invisible but tappable, so a
     * toolbar button could press a filter hidden on top of it.
     */
    fun Modifier.foldingTitle(): Modifier = this
        .graphicsLayer {
            alpha = (1f - fraction * 1.6f).coerceIn(0f, 1f)
            clip = true
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            maxPx = placeable.height.toFloat()
            val height = (placeable.height - collapsedPx).roundToInt().coerceAtLeast(0)
            layout(placeable.width, height) {
                placeable.placeRelative(0, height - placeable.height)
            }
        }
}

@Composable
fun rememberLargeTitleState(height: Dp = 64.dp): LargeTitleState {
    val px = with(LocalDensity.current) { height.toPx() }
    return androidx.compose.runtime.saveable.rememberSaveable(
        px,
        saver = androidx.compose.runtime.saveable.Saver(
            save = { it.collapsedPx },
            restore = { saved -> LargeTitleState(px).apply { restore(saved) } }
        )
    ) { LargeTitleState(px) }
}
