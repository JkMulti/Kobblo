package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle
import java.util.Locale

/**
 * Kobblo Design is Kobblo's design system, modeled on the iOS Files app: a system-gray canvas, white
 * inset groups with hairline separators, large titles, filled blue folders, and one blue tint.
 *
 * Token roles are part of the tokens. See docs/design/design-system.md before adding a color.
 */
object KobbloDesign {
    val colors: KobbloColors
        @Composable @ReadOnlyComposable get() = LocalKobbloColors.current
    val type: KobbloType = KobbloType
    val shapes: KobbloShapes = KobbloShapes
    val space: KobbloSpace = KobbloSpace
    val motion: KobbloMotion = KobbloMotion
}

// ── Color ─────────────────────────────────────────────────────────────────────

@Immutable
data class KobbloColors(
    /** Page background: the gray that groups sit on. */
    val canvas: Color,
    /** Grouped rows, cards, and round toolbar buttons. */
    val snow: Color,
    /** Search fields, pressed rows, and nested surfaces. */
    val mist: Color,
    /** Control outlines and the off state of a meter. */
    val silver: Color,
    /** Primary text and default icons. */
    val ink: Color,
    /** Secondary text and metadata. */
    val smoke: Color,
    /** Placeholders, chevrons, and disabled content only: too low contrast for reading. */
    val pewter: Color,
    /** The tint: actions, links, selection, and location icons. */
    val action: Color,
    val onAction: Color,
    /** Destructive actions. */
    val danger: Color,
    /** File-type indicators only. */
    val success: Color,
    /** File-type indicators only. */
    val magenta: Color,
    /** Fill behind the glass tab bar, also its fallback where blur is unavailable. */
    val glass: Color,
    /** Separators between grouped rows. */
    val hairline: Color,
    val scrim: Color,
    val ambient: Color,
    val isDark: Boolean,
    val isAmoled: Boolean = false
)

val LightKobbloColors = KobbloColors(
    canvas = Color(0xFFF2F2F7),
    snow = Color(0xFFFFFFFF),
    mist = Color(0xFFE5E5EA),
    silver = Color(0xFFC7C7CC),
    ink = Color(0xFF000000),
    smoke = Color(0xFF6C6C70),
    pewter = Color(0xFFAEAEB2),
    action = Color(0xFF007AFF),
    onAction = Color(0xFFFFFFFF),
    danger = Color(0xFFFF3B30),
    success = Color(0xFF34C759),
    magenta = Color(0xFFAF52DE),
    glass = Color(0xC7FFFFFF),
    hairline = Color(0x4A3C3C43),
    scrim = Color(0x66000000),
    ambient = Color(0x1F000000),
    isDark = false
)

val DarkKobbloColors = KobbloColors(
    canvas = Color(0xFF000000),
    snow = Color(0xFF1C1C1E),
    mist = Color(0xFF2C2C2E),
    silver = Color(0xFF48484A),
    ink = Color(0xFFFFFFFF),
    smoke = Color(0xFF98989F),
    pewter = Color(0xFF636366),
    action = Color(0xFF0A84FF),
    onAction = Color(0xFFFFFFFF),
    danger = Color(0xFFFF453A),
    success = Color(0xFF30D158),
    magenta = Color(0xFFBF5AF2),
    glass = Color(0x99262628),
    hairline = Color(0x80545458),
    scrim = Color(0x8C000000),
    ambient = Color(0x66000000),
    isDark = true
)

/** Dark already sits on true black, so this deepens the groups instead. */
val AmoledKobbloColors = DarkKobbloColors.copy(
    snow = Color(0xFF111113),
    mist = Color(0xFF1C1C1E),
    glass = Color(0x99141416),
    isAmoled = true
)

/**
 * The tint the owner picks in Settings, which becomes [KobbloColors.action]. Each reads as text on the
 * page and under white text in light and dark, at 3:1 or better. That is why orange, green, and teal
 * are deeper than Apple's. Red is left out because it already means destructive, and yellow because
 * no yellow reads on white.
 */
enum class KobbloAccent(val light: Color, val dark: Color, @androidx.annotation.StringRes val label: Int) {
    Blue(Color(0xFF007AFF), Color(0xFF0A84FF), com.kobblo.gallery.file.explorer.R.string.tint_blue),
    Indigo(Color(0xFF5856D6), Color(0xFF5E5CE6), com.kobblo.gallery.file.explorer.R.string.tint_indigo),
    Purple(Color(0xFFAF52DE), Color(0xFFBF5AF2), com.kobblo.gallery.file.explorer.R.string.tint_purple),
    Pink(Color(0xFFFF2D55), Color(0xFFFF375F), com.kobblo.gallery.file.explorer.R.string.tint_pink),
    Orange(Color(0xFFE27100), Color(0xFFE27100), com.kobblo.gallery.file.explorer.R.string.tint_orange),
    Green(Color(0xFF2BA54A), Color(0xFF2BA54A), com.kobblo.gallery.file.explorer.R.string.tint_green),
    Teal(Color(0xFF2B9DB2), Color(0xFF2B9DB2), com.kobblo.gallery.file.explorer.R.string.tint_teal);

    fun color(isDark: Boolean): Color = if (isDark) dark else light

    companion object {
        val Default = Blue

        /** Reads a saved name, falling back to [Default] for a missing or unknown one. */
        fun fromName(name: String?): KobbloAccent = entries.firstOrNull { it.name == name } ?: Default
    }
}

/** These colors with [accent] as the tint. */
fun KobbloColors.withAccent(accent: KobbloAccent): KobbloColors = copy(action = accent.color(isDark))

val LocalKobbloColors = staticCompositionLocalOf { LightKobbloColors }

/** The tints behind settings icons and category glyphs. They only ever label, never act. */
object KobbloTint {
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9500)
    val Yellow = Color(0xFFFFCC00)
    val Green = Color(0xFF34C759)
    val Teal = Color(0xFF30B0C7)
    val Blue = Color(0xFF007AFF)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Brown = Color(0xFFA2845E)
    val Gray = Color(0xFF8E8E93)
}

// ── Type ──────────────────────────────────────────────────────────────────────

/**
 * The device's own font is deliberate: it is what makes the app feel native on every phone.
 * Hierarchy comes from weight and tight tracking at display sizes, not from a display face.
 */
object KobbloType {
    private val base = TextStyle(fontFamily = FontFamily.Default)
    /** Values read one character at a time, such as a recovery key. */
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)

    private val displayTracked = base.copy(fontSize = 54.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
    private val largeTitleTracked = base.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    private val titleTracked = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    private val displayUntracked = displayTracked.copy(letterSpacing = 0.sp)
    private val largeTitleUntracked = largeTitleTracked.copy(letterSpacing = 0.sp)
    private val titleUntracked = titleTracked.copy(letterSpacing = 0.sp)

    /**
     * Arabic script joins its letters, so Android draws it without tracking, yet still measures it with
     * the tracking taken off its width: a tightly tracked title in Arabic or Urdu comes up short and is
     * cut off ("الرئيس…"). Languages written in it get no tracking.
     */
    private val joinedScriptLanguages = setOf("ar", "ur", "fa", "ps", "sd", "ug", "ckb", "ku")
    private fun joinedScript() = Locale.getDefault().language in joinedScriptLanguages

    /** Hero numbers, such as free storage. */
    val display: TextStyle get() = if (joinedScript()) displayUntracked else displayTracked
    /** Screen titles. */
    val largeTitle: TextStyle get() = if (joinedScript()) largeTitleUntracked else largeTitleTracked
    /** Section and sheet titles. */
    val title: TextStyle get() = if (joinedScript()) titleUntracked else titleTracked
    /** Toolbar titles and button labels. */
    val headline = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    /** Row titles and reading text. */
    val body = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** Metadata under a row title. */
    val callout = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    /** Names and notes under a grid item, and text beneath a group. */
    val footnote = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    val caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    /** Tab bar labels. */
    val tab = base.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
}

fun kobbloMaterialTypography(mainScale: Float = 1f, subScale: Float = 1f): Typography {
    fun TextStyle.scaled(scale: Float) = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
    return Typography(
        displayLarge = KobbloType.display.scaled(mainScale),
        displayMedium = KobbloType.largeTitle.copy(fontSize = 45.sp, lineHeight = 48.sp).scaled(mainScale),
        displaySmall = KobbloType.largeTitle.copy(fontSize = 36.sp, lineHeight = 40.sp).scaled(mainScale),
        headlineLarge = KobbloType.largeTitle.copy(fontSize = 32.sp, lineHeight = 36.sp).scaled(mainScale),
        headlineMedium = KobbloType.title.copy(fontSize = 28.sp, lineHeight = 32.sp).scaled(mainScale),
        headlineSmall = KobbloType.title.copy(fontSize = 24.sp, lineHeight = 30.sp).scaled(mainScale),
        titleLarge = KobbloType.title.scaled(mainScale),
        titleMedium = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold).scaled(mainScale),
        titleSmall = KobbloType.callout.copy(fontWeight = FontWeight.Medium).scaled(mainScale),
        // The default text style for every Text. Older screens set only a font size on top of it,
        // so a fixed line height here would space small text too loosely.
        bodyLarge = KobbloType.body.scaled(subScale).copy(lineHeight = TextUnit.Unspecified),
        bodyMedium = KobbloType.callout.scaled(subScale),
        bodySmall = KobbloType.caption.copy(fontWeight = FontWeight.Normal).scaled(subScale),
        labelLarge = KobbloType.callout.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = KobbloType.caption.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = KobbloType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
    )
}

// ── Shape ─────────────────────────────────────────────────────────────────────

/** Every corner in the app is drawn by Kyant's continuous curves. */
val CapsuleShape: Shape = Capsule()

object KobbloShapes {
    val capsule: Shape = CapsuleShape
    val xs: Shape = RoundedRectangle(8.dp)
    val sm: Shape = RoundedRectangle(12.dp)
    val md: Shape = RoundedRectangle(16.dp)
    val lg: Shape = RoundedRectangle(20.dp)
    val xl: Shape = RoundedRectangle(28.dp)
    val sheet: Shape = UnevenRoundedRectangle(topStart = 28.dp, topEnd = 28.dp)
    /** An inset group of rows. */
    val group: Shape = RoundedRectangle(GroupRadius)
    /** The corner radius of [group], for shapes drawn inside one. */
    val groupRadius: Dp = GroupRadius
    /** Icon badges and thumbnails: the radius follows the tile's size. */
    val tile: Shape = ProportionalContinuousShape(0.24f)

    /** The shape of one row in a group, which only rounds where it meets the group's edge. */
    fun groupItem(index: Int, count: Int): Shape = when {
        count <= 1 -> group
        index == 0 -> GroupTop
        index == count - 1 -> GroupBottom
        else -> RectangleShape
    }

    private val GroupTop = UnevenRoundedRectangle(topStart = GroupRadius, topEnd = GroupRadius)
    private val GroupBottom = UnevenRoundedRectangle(bottomStart = GroupRadius, bottomEnd = GroupRadius)

    /**
     * A shape nested in another's corner, such as a button in a bar or a highlight in a card, rounds by
     * the outer radius less the gap between them, so the two curves run parallel. The same radius on both
     * leaves the gap wider at the corner than along the sides.
     */
    fun concentric(outer: Dp, inset: Dp): Shape = RoundedRectangle(concentricRadius(outer, inset))
}

/** The radius of a shape [inset] inside a corner of radius [outer]. See [KobbloShapes.concentric]. */
fun concentricRadius(outer: Dp, inset: Dp): Dp = (outer - inset).coerceAtLeast(0.dp)

/**
 * How far from a corner of radius [outer] a round mark of [radius] sits so the two share a center:
 * a lock or a selection circle in a thumbnail's corner. A mark as round as the corner sits flush.
 */
fun concentricInset(outer: Dp, radius: Dp): Dp = (outer - radius).coerceAtLeast(0.dp)

/**
 * A group's corner: a badge's 8dp corner plus the [KobbloSpace.inset] around it, so the first and last
 * rows' badges sit concentric in it.
 */
private val GroupRadius = 20.dp

/** A Kyant rounded rectangle whose radius is a fraction of the shape's shorter side. */
@Immutable
class ProportionalContinuousShape(private val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { (size.minDimension * fraction).toDp() }
        return RoundedRectangle(radius).createOutline(size, layoutDirection, density)
    }

    override fun equals(other: Any?) = other is ProportionalContinuousShape && other.fraction == fraction
    override fun hashCode() = fraction.hashCode()
}

/**
 * Lets Material components, whose theme shapes must be a [CornerBasedShape], draw Kyant's
 * curves. Without this, default sheets, dialogs, cards, and fields would keep circular corners.
 */
@Immutable
class KyantCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(radius: Dp) : this(CornerSize(radius), CornerSize(radius), CornerSize(radius), CornerSize(radius))

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ) = KyantCornerShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ): Outline = UnevenRoundedRectangle(
        topStart = topStart.dp,
        topEnd = topEnd.dp,
        bottomEnd = bottomEnd.dp,
        bottomStart = bottomStart.dp
    ).createOutline(size, layoutDirection, PixelDensity)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KyantCornerShape) return false
        return topStart == other.topStart && topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd && bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    private companion object {
        // The radii above are already pixels, so converting them at density 1 keeps them intact.
        val PixelDensity = Density(1f)
    }
}

val KobbloMaterialShapes = Shapes(
    extraSmall = KyantCornerShape(8.dp),
    small = KyantCornerShape(12.dp),
    medium = KyantCornerShape(16.dp),
    large = KyantCornerShape(20.dp),
    extraLarge = KyantCornerShape(28.dp)
)

// ── Space and elevation ───────────────────────────────────────────────────────

/**
 * Every space in the app is a step of 4dp, and each kind of space has one value:
 *
 * - [screen]: a screen's side margin. Large titles, search fields, section titles and the edges of
 *   groups and cards all start on this line.
 * - [inset]: inside a group, card or panel, content sits this far from every edge it meets, the
 *   top as much as the side. A row is as tall as what leads it plus this above and below.
 * - [content]: the line text inside a group starts on, at [screen] plus [inset]. Notes above and
 *   below a group start here too, so they line up with the rows.
 * - [section]: above each section, between one group and the next one's title.
 * - [titleGap]: between a section's title and its group.
 * - [groupGap]: between two groups with no title between them.
 */
object KobbloSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Side margin of every screen, and the inset of every group. */
    val screen = 16.dp
    /** How far content sits inside a group or card, on every side. */
    val inset = 12.dp
    /** Where text inside a group starts, measured from the screen's edge. */
    val content = screen + inset
    /** Between a row's picture or badge and its text. */
    val leadingGap = 12.dp
    /** Above a section's title. */
    val section = 24.dp
    /** Between a section's title and its group. */
    val titleGap = 8.dp
    /** Between two groups with no title between them: the same as the screen's margin, so every group has as much canvas above and below it as beside it. */
    val groupGap = screen
    /** Room for scrolling content to clear the floating tab bar. */
    val tabBarClearance = 112.dp
    /** Further room for scrolling content when the mini player sits above the tab bar. */
    val miniPlayerClearance = 68.dp
    /** The widest a list of rows gets. Past this a row is too long to read across. */
    val readableWidth = 720.dp
}

/** The sizes the spaces are worked out around. */
object KobbloSize {
    /** A settings row's colored badge, or any glyph leading a row. */
    val badge = 32.dp
    /** A row led by a badge: the badge with [KobbloSpace.inset] above and below. */
    val row = badge + KobbloSpace.inset * 2
    /** The picture leading a row of files. */
    val thumbnail = 40.dp
    /** A row of files: its picture with [KobbloSpace.inset] above and below. */
    val fileRow = thumbnail + KobbloSpace.inset * 2
    /** Round buttons in a toolbar, and a row's menu button. */
    val toolbarButton = 40.dp
    val rowButton = 32.dp
    /** The toolbar at the top of every screen. */
    val toolbar = 52.dp
}

/**
 * Fills the screen with its content kept to [KobbloSpace.readableWidth] and centered, for a tablet
 * or a phone on its side. Grids of pictures don't use this: they're meant to go edge to edge.
 */
fun Modifier.kobbloReadableWidth(): Modifier = this
    .fillMaxSize()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = KobbloSpace.readableWidth)
    .fillMaxWidth()

/** The only shadow in the system: a soft, non-directional glow. */
fun Modifier.ambientShadow(shape: Shape, color: Color): Modifier =
    dropShadow(shape, Shadow(radius = 30.dp, color = color))

// ── Motion ────────────────────────────────────────────────────────────────────

/**
 * Motion must give feedback, keep continuity, or show hierarchy. Springs settle quickly with
 * no prolonged bounce. Anything interactive stays interruptible.
 */
object KobbloMotion {
    const val Fast = 120
    const val Default = 200
    const val Slow = 320

    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Tab capsule, selection marks, small things that settle. */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = 400f)

    /** Sheets, screens, large things that settle. */
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 280f)

    /** Press feedback: must feel instant. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 900f)

    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(Default, easing = EaseOut)
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(Fast, easing = EaseIn)
    fun <T> state(): FiniteAnimationSpec<T> = tween(Default, easing = EaseInOut)
}
