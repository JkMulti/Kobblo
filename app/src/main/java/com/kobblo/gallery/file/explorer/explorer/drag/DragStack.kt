package com.kobblo.gallery.file.explorer.explorer.drag

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One picture on its way somewhere, by its centre, in the host's pixels. */
internal class DragCard(
    val index: Int,
    x: Float,
    y: Float,
    size: Float
) {
    var x = x
    var y = y
    var size = size
    var rotation = 0f
    var alpha = 1f

    var velocityX = 0f
    var velocityY = 0f
    var velocitySize = 0f
    var velocityRotation = 0f

    /** Its place in the heap, in dp from the top card. Worked out once, for the size of the whole selection. */
    var restX = 0f
    var restY = 0f
    var restRotation = 0f
    var sizeFactor = 1f
    /** How see-through its place in the deck makes it. Kept apart from [alpha], which is about it coming and going. */
    var depthFade = 1f
    var stiffness = 0f
    var launchDelay = 0f

    fun takePlace(total: Int, limit: Float, side: Float = 1f) {
        restX = StackShape.restX(index, total, limit) * side
        depthFade = StackShape.depthFade(index, total)
        restY = StackShape.restY(index, total, limit)
        restRotation = StackShape.restRotation(index, total)
        sizeFactor = StackShape.sizeFactor(index, total)
        stiffness = StackShape.stiffness(index, total)
        launchDelay = StackShape.launchDelay(index, total)
    }

    /** Where it came from and would go back to, while that's on screen. */
    var hasHome = false
    var homeX = 0f
    var homeY = 0f
    var homeSize = 0f

    fun setHome(x: Float, y: Float, size: Float) {
        hasHome = true
        homeX = x
        homeY = y
        homeSize = size
    }

    /** What it stands for is off screen, so it came in from past this point and goes back out the same way. */
    var hasExit = false
    var exitX = 0f
    var exitY = 0f

    fun setExit(x: Float, y: Float) {
        hasExit = true
        exitX = x
        exitY = y
    }

    var landed = false

    /** Has it left where it started? Until then, the original tile is what's showing there. */
    var travelled = false
}

/** Sizes in the host's pixels, so the physics needs nothing from Android and can be tested as it is. */
internal class StackMetrics(
    /** How big a card is once it's being carried. */
    val stackSize: Float,
    /** How big the held card gets when it first lifts. */
    val liftSize: Float,
    /** How far above the finger the top card rides, so a finger doesn't hide it. */
    val liftOffset: Float,
    /** One density-independent pixel. */
    val dp: Float,
    /** The furthest a heap spreads from its middle, in dp. Less on a small screen. */
    val clusterLimit: Float = StackShape.MaxClusterRadius,
    /** Across the screen. Decides which way the deck leans. */
    val screenWidth: Float = Float.MAX_VALUE
)

/**
 * The whole heap's movement. Each card chases the one in front of it with a spring, so a quick move strings the cards
 * out behind the finger and they settle back when it stops, while the top card never leaves the finger.
 *
 * Plain numbers, stepped once a frame, so that nothing here costs a recomposition and it can all be tested off a device.
 *
 * @param total how many cards there will be, which can be more than [initial] until the ones off screen have been found.
 */
internal class DragStack(
    initial: List<DragCard>,
    private val metrics: StackMetrics,
    private val reduced: Boolean,
    fingerX: Float,
    fingerY: Float,
    private val total: Int = initial.size
) {
    val cards = ArrayList<DragCard>(max(total, initial.size))
    private val isHeap = StackShape.isHeap(total)
    // The deck leans toward the middle of the screen, so it doesn't lean off the edge.
    private val side = if (fingerX > metrics.screenWidth / 2f) -1f else 1f

    private var seconds = 0f
    private var lastFingerX = fingerX
    private var fingerVelocityX = 0f

    // The top card's way of getting from where it was to right under the finger, without ever lagging behind it.
    private val top = initial.first()
    private var grabX = top.x - fingerX
    private var grabY = top.y - fingerY
    private var grabVelocityX = 0f
    private var grabVelocityY = 0f

    init {
        initial.forEach { add(it) }
        // No flight, so nothing should be seen anywhere but where it's going to be.
        if (reduced) placeRigid(fingerX, fingerY, moved = false)
    }

    /** One more, behind all the others. */
    fun add(card: DragCard) {
        card.takePlace(total, metrics.clusterLimit, side)
        cards.add(card)
    }

    /** The finger's still down. [moved] is whether it has gone anywhere yet: until it has, the held card stays big. */
    fun carry(frameSeconds: Float, fingerX: Float, fingerY: Float, moved: Boolean) {
        val frame = frameSeconds.coerceIn(0f, MaxFrameSeconds)
        if (frame > 0f) {
            // Touches and frames don't arrive in step, and a fast screen sees frames with no touch in them. Slow enough
            // not to pass that on: on a square card, a tilt that twitches shows up as its edges shimmering.
            val blend = min(1f, frame * 8f)
            fingerVelocityX += ((fingerX - lastFingerX) / frame - fingerVelocityX) * blend
        }
        lastFingerX = fingerX
        if (reduced) {
            placeRigid(fingerX, fingerY, moved)
            seconds += frame
            return
        }
        steps(frame) { step ->
            seconds += step
            carryStep(step, fingerX, fingerY, moved)
        }
    }

    private fun placeRigid(fingerX: Float, fingerY: Float, moved: Boolean) {
        val topSize = if (moved) metrics.stackSize else metrics.liftSize
        val spread = topSize / metrics.stackSize * metrics.dp
        for (i in cards.indices) {
            val card = cards[i]
            card.x = fingerX + card.restX * spread
            card.y = fingerY - metrics.liftOffset + card.restY * spread
            card.size = topSize * card.sizeFactor
            card.rotation = card.restRotation
            card.alpha = 1f
            card.travelled = true
        }
    }

    private fun carryStep(step: Float, fingerX: Float, fingerY: Float, moved: Boolean) {
        val topSize = if (moved) metrics.stackSize else metrics.liftSize
        val spread = topSize / metrics.stackSize * metrics.dp
        val tilt = (fingerVelocityX * TiltPerPixelPerSecond / metrics.dp).coerceIn(-MaxTilt, MaxTilt)

        grabVelocityX += (-grabX * GrabStiffness - grabVelocityX * GrabDamping) * step
        grabVelocityY += ((-metrics.liftOffset - grabY) * GrabStiffness - grabVelocityY * GrabDamping) * step
        grabX += grabVelocityX * step
        grabY += grabVelocityY * step

        top.x = fingerX + grabX
        top.y = fingerY + grabY
        top.chaseSize(step, topSize, SizeStiffness, SizeRatio)
        top.chaseRotation(step, tilt * 0.6f)
        top.travelled = true

        for (i in 1 until cards.size) {
            val card = cards[i]
            val age = seconds - card.launchDelay
            if (age < 0f) continue
            card.travelled = true
            val leader = cards[i - 1]
            when {
                !isHeap -> {
                    val launch = min(1f, 0.35f + age / LaunchRampSeconds)
                    card.chase(
                        step,
                        leader.x + (card.restX - leader.restX) * spread,
                        leader.y + (card.restY - leader.restY) * spread,
                        card.stiffness * launch,
                        ChaseRatio
                    )
                }

                age < FlightSeconds -> {
                    // Straight to its place. Waiting for a long line to arrive one card at a time would take too long.
                    val launch = min(1f, 0.35f + age / LaunchRampSeconds)
                    card.chase(
                        step,
                        top.x + card.restX * spread,
                        top.y + card.restY * spread,
                        FlightStiffness * launch,
                        FlightRatio
                    )
                }

                else -> {
                    val settling = min(1f, (age - FlightSeconds) / ChainRampSeconds)
                    card.chase(
                        step,
                        leader.x + (card.restX - leader.restX) * spread,
                        leader.y + (card.restY - leader.restY) * spread,
                        FlightStiffness + (card.stiffness - FlightStiffness) * settling * settling,
                        HeapRatio
                    )
                }
            }
            card.chaseSize(step, topSize * card.sizeFactor, SizeStiffness, SizeRatio)
            card.chaseRotation(step, card.restRotation + tilt)
        }
    }

    /** Everything goes back where it came from. The last card to arrive at the stack leaves it first. */
    fun startReturn() {
        seconds = 0f
        for (i in cards.indices) cards[i].landed = false
    }

    /** True once every card is home, or has faded because its home has scrolled away. */
    fun flyHome(frameSeconds: Float): Boolean {
        val frame = frameSeconds.coerceIn(0f, MaxFrameSeconds)
        if (reduced) {
            seconds += frame
            var done = true
            for (i in cards.indices) {
                val card = cards[i]
                card.alpha = (card.alpha - frame / ReducedFadeSeconds).coerceAtLeast(0f)
                if (card.alpha > 0f) done = false
            }
            return done
        }
        steps(frame) { step ->
            seconds += step
            homeStep(step)
        }
        return seconds > ReturnLimitSeconds || allLanded()
    }

    private fun allLanded(): Boolean {
        for (i in cards.indices) if (!cards[i].landed || cards[i].alpha > 0f) return false
        return true
    }

    private fun homeStep(step: Float) {
        val last = cards.size - 1
        val stagger = StackShape.returnStagger(total)
        val stiffness = if (isHeap) HeapReturnStiffness else ReturnStiffness
        for (i in cards.indices) {
            val card = cards[i]
            val age = seconds - stagger * (last - i)
            if (age < 0f) continue
            if (!card.hasHome && !card.hasExit) {
                // Its list may still be coming back after a folder change, so it gets a moment to reappear.
                if (age > HomeGraceSeconds) {
                    card.alpha = (card.alpha - step / FadeOutSeconds).coerceAtLeast(0f)
                    card.chaseSize(step, card.size * 0.9f, SizeStiffness, SizeRatio)
                    if (card.alpha <= 0f) card.landed = true
                }
                continue
            }
            if (card.hasHome) {
                card.chase(step, card.homeX, card.homeY, stiffness, ReturnRatio)
                card.chaseSize(step, card.homeSize, stiffness, ReturnRatio)
                card.chaseRotation(step, 0f)
                if (!card.landed && isAt(card, card.homeX, card.homeY)) card.landed = true
            } else {
                card.chase(step, card.exitX, card.exitY, stiffness, ReturnRatio)
                card.chaseRotation(step, 0f)
                if (!card.landed && isAt(card, card.exitX, card.exitY)) card.landed = true
            }
            // A tiny hand-off rather than a pop, since the picture under it has its own frame around it.
            if (card.landed) card.alpha = (card.alpha - step / LandFadeSeconds).coerceAtLeast(0f)
        }
    }

    private fun isAt(card: DragCard, x: Float, y: Float): Boolean =
        abs(card.x - x) < metrics.dp * 2f && abs(card.y - y) < metrics.dp * 2f &&
            abs(card.velocityX) + abs(card.velocityY) < metrics.dp * 120f

    private var absorbX = 0f
    private var absorbY = 0f

    /** Everything shrinks away into a point, which is whatever just took it. */
    fun startAbsorb(x: Float, y: Float) {
        seconds = 0f
        absorbX = x
        absorbY = y
    }

    /** True once it's all gone. */
    fun absorb(frameSeconds: Float): Boolean {
        val frame = frameSeconds.coerceIn(0f, MaxFrameSeconds)
        if (reduced) {
            seconds += frame
            var gone = true
            for (i in cards.indices) {
                val card = cards[i]
                card.alpha = (card.alpha - frame / ReducedFadeSeconds).coerceAtLeast(0f)
                if (card.alpha > 0f) gone = false
            }
            return gone
        }
        val stagger = StackShape.absorbStagger(total)
        steps(frame) { step ->
            seconds += step
            for (i in cards.indices) {
                val card = cards[i]
                val age = seconds - stagger * i
                if (age < 0f) continue
                card.chase(step, absorbX, absorbY, AbsorbStiffness, AbsorbRatio)
                card.chaseSize(step, metrics.stackSize * AbsorbSizeFactor, AbsorbStiffness, AbsorbRatio)
                card.chaseRotation(step, 0f)
                card.alpha = (card.alpha - step / AbsorbFadeSeconds * smoothstep(age / AbsorbFadeDelay)).coerceAtLeast(0f)
            }
        }
        if (seconds > AbsorbLimitSeconds) return true
        for (i in cards.indices) if (cards[i].alpha > 0f) return false
        return true
    }

    private inline fun steps(frame: Float, step: (Float) -> Unit) {
        if (frame <= 0f) return
        val count = ceil(frame / StepSeconds).toInt().coerceAtLeast(1)
        val each = frame / count
        repeat(count) { step(each) }
    }

    private fun DragCard.chase(step: Float, targetX: Float, targetY: Float, stiffness: Float, ratio: Float) {
        val damping = 2f * ratio * sqrt(stiffness)
        // A long line needs each link far stiffer than a plain step can hold on to without blowing up.
        val held = 1f + step * damping + step * step * stiffness
        if (stiffness * step * step > PlainStepLimit) {
            velocityX = (velocityX + (targetX - x) * stiffness * step) / held
            velocityY = (velocityY + (targetY - y) * stiffness * step) / held
        } else {
            velocityX += ((targetX - x) * stiffness - velocityX * damping) * step
            velocityY += ((targetY - y) * stiffness - velocityY * damping) * step
        }
        x += velocityX * step
        y += velocityY * step
    }

    private fun DragCard.chaseSize(step: Float, target: Float, stiffness: Float, ratio: Float) {
        val damping = 2f * ratio * sqrt(stiffness)
        velocitySize += ((target - size) * stiffness - velocitySize * damping) * step
        size += velocitySize * step
    }

    private fun DragCard.chaseRotation(step: Float, target: Float) {
        val damping = 2f * RotationRatio * sqrt(RotationStiffness)
        velocityRotation += ((target - rotation) * RotationStiffness - velocityRotation * damping) * step
        rotation += velocityRotation * step
    }

    private fun smoothstep(amount: Float): Float {
        val t = amount.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        const val StepSeconds = 1f / 120f
        // A long frame is a frozen frame, not a real jump in time: the cards shouldn't teleport for it.
        const val MaxFrameSeconds = 1f / 12f
        const val PlainStepLimit = 0.04f

        const val GrabStiffness = 520f
        val GrabDamping = 2f * sqrt(GrabStiffness)
        const val SizeStiffness = 420f
        const val SizeRatio = 0.72f
        const val ChaseRatio = 0.76f
        const val HeapRatio = 0.9f
        const val RotationStiffness = 240f
        const val RotationRatio = 0.62f
        const val TiltPerPixelPerSecond = 0.012f
        const val MaxTilt = 11f

        const val LaunchRampSeconds = 0.14f
        const val FlightStiffness = 560f
        const val FlightRatio = 0.85f
        const val FlightSeconds = 0.45f
        const val ChainRampSeconds = 0.12f

        const val ReturnStiffness = 300f
        const val HeapReturnStiffness = 520f
        const val ReturnRatio = 0.92f
        const val ReturnLimitSeconds = 1.1f
        const val HomeGraceSeconds = 0.14f
        const val FadeOutSeconds = 0.16f
        const val LandFadeSeconds = 0.09f

        const val AbsorbStiffness = 560f
        const val AbsorbRatio = 0.96f
        const val AbsorbSizeFactor = 0.26f
        const val AbsorbFadeSeconds = 0.13f
        const val AbsorbFadeDelay = 0.12f
        const val AbsorbLimitSeconds = 0.55f

        const val ReducedFadeSeconds = 0.12f
    }
}

/**
 * The deck at rest: one card behind another, each a little smaller, higher, further to one side and more see-through, so
 * it reads as a line going into the distance with an edge of every card showing, and what's behind it stays in sight. The
 * steps close up as there get to be more, and the deck stops growing at a limit. It goes up, away from the finger.
 */
internal object StackShape {
    /** How many get their own spring and stagger timing. Past this the timing shares out, so a long line still keeps up. */
    const val Cards = 5

    /**
     * The most cards that come out, the nearest ones. The badge always has the real number. This is the minimum asked
     * for, and hasn't been tuned from frame measurements yet.
     */
    const val MaxCards = 150

    /** The furthest back a deck reaches, in dp, however many there are. Past this each card shows less. */
    const val MaxClusterRadius = 132f

    private val Stiffness = floatArrayOf(0f, 300f, 235f, 190f, 160f)

    // How far behind the one in front a card sits, in dp, until the deck reaches its limit.
    private const val DeckStep = 15f
    // How much smaller each card is than the one in front, until the back of the deck reaches its smallest.
    private const val SizeStep = 0.05f
    private const val SmallestBack = 0.66f
    // How far it leans to one side for how far it goes back.
    private const val Lean = 0.35f
    // What's left of the card at the very back.
    private const val BackFade = 0.8f

    private const val LiftStaggerSeconds = 0.032f
    private const val LiftStaggerBudget = 0.2f
    private const val ReturnStaggerSeconds = 0.018f
    private const val ReturnStaggerBudget = 0.1f
    private const val AbsorbStaggerSeconds = 0.012f
    private const val AbsorbStaggerBudget = 0.144f

    private const val ShortTrail = 0.42f
    private const val LongTrail = 0.62f
    private const val LongTrailAt = 60f

    private fun at(values: FloatArray, index: Int) = values[index.coerceIn(0, values.lastIndex)]

    fun isHeap(total: Int) = total > Cards

    /** How far back the whole deck reaches, in dp. */
    fun clusterRadius(total: Int, limit: Float = MaxClusterRadius): Float =
        min(limit, DeckStep * max(0, total - 1))

    /** In dp from the top card, leaning to the right. Whoever's placing it decides which way it really goes. */
    fun restX(index: Int, total: Int = Cards, limit: Float = MaxClusterRadius): Float =
        -restY(index, total, limit) * Lean

    /** The further back, the fainter, which reads as distance and shows what's behind the deck. */
    fun depthFade(index: Int, total: Int = Cards): Float =
        if (total <= 1) 1f else 1f - (1f - BackFade) * index / (total - 1f)

    /** In dp from the top card. */
    fun restY(index: Int, total: Int = Cards, limit: Float = MaxClusterRadius): Float =
        if (total <= 1) 0f else -clusterRadius(total, limit) * index / (total - 1f)

    /** A deck lines up. */
    fun restRotation(index: Int, total: Int = Cards): Float = 0f

    /** Further back is smaller, which is what makes it read as depth, and the back of the deck stops at a limit. */
    fun sizeFactor(index: Int, total: Int = Cards): Float {
        if (total <= 1) return 1f
        val back = max(SmallestBack, 1f - SizeStep * (total - 1))
        return 1f - (1f - back) * index / (total - 1f)
    }

    /**
     * How long the last card trails the finger. Today's five already take this long, so a longer line packs in closer
     * and only stretches a little, rather than taking longer in proportion.
     */
    fun trailSeconds(total: Int): Float =
        ShortTrail + (LongTrail - ShortTrail) * ((total - Cards) / (LongTrailAt - Cards)).coerceIn(0f, 1f)

    /** Each card is a little lazier than the one in front, which is what strings them out on a fast move. */
    fun stiffness(index: Int, total: Int = Cards): Float {
        if (!isHeap(total)) return at(Stiffness, index)
        // A spring trails what it's chasing by twice its damping ratio over its natural frequency.
        val frequency = 2f * 0.9f * (total - 1) / trailSeconds(total)
        return frequency * frequency
    }

    /** When a card sets off, which is sooner the more there are, so a lift takes the same time at any size. */
    fun launchDelay(index: Int, total: Int = Cards): Float = index * stagger(LiftStaggerSeconds, LiftStaggerBudget, total)

    /** Between one card leaving and the next, on the way back. */
    fun returnStagger(total: Int): Float = stagger(ReturnStaggerSeconds, ReturnStaggerBudget, total)

    /** Between one card going into a target and the next. */
    fun absorbStagger(total: Int): Float = stagger(AbsorbStaggerSeconds, AbsorbStaggerBudget, total)

    private fun stagger(most: Float, budget: Float, total: Int): Float =
        if (total <= 1) most else min(most, budget / (total - 1))
}

/** Where a card comes from when what it stands for is off screen. */
internal object CardEntry {
    /** Anything before what's held in the list and not on screen is above the screen, and anything after it is below. */
    fun edgeY(listIndex: Int, heldIndex: Int, top: Float, bottom: Float): Float =
        if (listIndex < heldIndex) top else bottom

    /** The column it would be in, counting from the one that's held. */
    fun column(listIndex: Int, heldIndex: Int, heldColumn: Int, columns: Int): Int =
        if (columns <= 1) 0 else Math.floorMod(heldColumn + listIndex - heldIndex, columns)
}
