package com.kobblo.gallery.file.explorer.explorer.drag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DragStackTest {
    private val metrics = StackMetrics(stackSize = 100f, liftSize = 120f, liftOffset = 30f, dp = 1f)

    private fun stack(count: Int, reduced: Boolean = false, startX: Float = 500f, startY: Float = 500f): DragStack {
        val cards = (0 until count).map { i ->
            DragCard(i, startX + i * 150f, startY + i * 40f, 80f).apply {
                setHome(startX + i * 150f, startY + i * 40f, 80f)
            }
        }
        return DragStack(cards, metrics, reduced, startX, startY)
    }

    private fun DragStack.settle(x: Float, y: Float, seconds: Float = 3f, moved: Boolean = true) {
        var left = seconds
        while (left > 0f) {
            carry(1f / 60f, x, y, moved)
            left -= 1f / 60f
        }
    }

    private fun lag(stack: DragStack, index: Int, x: Float, y: Float): Float =
        hypot(stack.cards[index].x - x, stack.cards[index].y - y)

    @Test
    fun theTopCardSitsOnTheFingerOnceItsCaughtUp() {
        val stack = stack(5)
        stack.settle(200f, 300f)
        assertEquals(200f, stack.cards[0].x, 0.5f)
        assertEquals(300f - metrics.liftOffset, stack.cards[0].y, 0.5f)
    }

    @Test
    fun theTopCardStaysWithAMovingFinger() {
        val stack = stack(5)
        stack.settle(200f, 300f)
        var x = 200f
        repeat(30) {
            x += 40f
            stack.carry(1f / 60f, x, 300f, true)
            assertEquals(x, stack.cards[0].x, 0.5f)
        }
    }

    @Test
    fun everyCardComesTogetherAndStops() {
        val stack = stack(5)
        stack.settle(200f, 300f)
        for (i in 1 until 5) {
            val expectedX = 200f + StackShape.restX(i)
            val expectedY = 300f - metrics.liftOffset + StackShape.restY(i)
            assertEquals("x of $i", expectedX, stack.cards[i].x, 1f)
            assertEquals("y of $i", expectedY, stack.cards[i].y, 1f)
            assertTrue("still moving: $i", abs(stack.cards[i].velocityX) < 2f && abs(stack.cards[i].velocityY) < 2f)
            assertEquals("rotation of $i", StackShape.restRotation(i), stack.cards[i].rotation, 0.5f)
        }
    }

    @Test
    fun aFastMoveStringsThemOutInOrder() {
        val stack = stack(5)
        stack.settle(100f, 500f)
        var x = 100f
        repeat(8) {
            x += 60f
            stack.carry(1f / 60f, x, 500f, true)
        }
        val lags = (0 until 5).map { lag(stack, it, x, 470f) }
        assertTrue("top card shouldn't trail: $lags", lags[0] < 1f)
        for (i in 1 until 5) assertTrue("card $i should trail the one in front: $lags", lags[i] > lags[i - 1])
    }

    @Test
    fun cardsLeaveOneAfterAnother() {
        val stack = stack(5)
        val starts = stack.cards.map { it.x }
        // Long enough for the first to have gone, too soon for the last.
        repeat(3) { stack.carry(1f / 60f, 500f, 500f, false) }
        assertTrue("second should be moving", abs(stack.cards[1].x - starts[1]) > 0.1f)
        assertEquals("last still waiting", starts[4], stack.cards[4].x, 0f)
    }

    @Test
    fun aSlowDeviceDoesntThrowThemOffScreen() {
        val stack = stack(5)
        // Frames that take 250 ms, as on a loaded emulator, with a finger going a long way.
        var x = 100f
        repeat(40) {
            x += 400f
            stack.carry(0.25f, x, 500f, true)
            stack.cards.forEach { assertTrue("ran away: ${it.x} for finger $x", abs(it.x - x) < 4_000f) }
        }
        stack.settle(x, 500f)
        for (i in 1 until 5) assertEquals(x + StackShape.restX(i), stack.cards[i].x, 1f)
    }

    @Test
    fun heldStillTheTopCardIsBigAndMovingItComesDown() {
        val stack = stack(1)
        stack.settle(500f, 500f, moved = false)
        assertEquals(metrics.liftSize, stack.cards[0].size, 0.5f)
        stack.settle(500f, 500f, moved = true)
        assertEquals(metrics.stackSize, stack.cards[0].size, 0.5f)
    }

    @Test
    fun returningLandsOnTheOriginalsAndFinishes() {
        val stack = stack(5)
        stack.settle(200f, 300f)
        stack.startReturn()
        var seconds = 0f
        var done = false
        while (seconds < 2f && !done) {
            done = stack.flyHome(1f / 60f)
            seconds += 1f / 60f
        }
        assertTrue(done)
        assertTrue("took $seconds s", seconds < 1.2f)
        stack.cards.forEachIndexed { i, card ->
            assertEquals(500f + i * 150f, card.x, 3f)
            assertEquals(500f + i * 40f, card.y, 3f)
            assertEquals(80f, card.size, 3f)
            assertEquals(0f, card.alpha, 0.01f)
        }
    }

    @Test
    fun aCardWhoseHomeHasScrolledAwayFades() {
        val stack = stack(3)
        stack.settle(200f, 300f)
        stack.cards[1].hasHome = false
        stack.startReturn()
        var seconds = 0f
        var done = false
        val whereItWas = stack.cards[1].x
        while (seconds < 2f && !done) {
            done = stack.flyHome(1f / 60f)
            seconds += 1f / 60f
        }
        assertTrue(done)
        assertEquals(0f, stack.cards[1].alpha, 0.01f)
        assertEquals("it shouldn't fly anywhere", whereItWas, stack.cards[1].x, 20f)
    }

    @Test
    fun absorbingGoesToTheTargetAndGoesOut() {
        val stack = stack(5)
        stack.settle(200f, 300f)
        stack.startAbsorb(800f, 900f)
        var seconds = 0f
        var done = false
        while (seconds < 2f && !done) {
            done = stack.absorb(1f / 60f)
            seconds += 1f / 60f
        }
        assertTrue(done)
        assertTrue("took $seconds s", seconds < 0.7f)
        stack.cards.forEach {
            assertEquals(0f, it.alpha, 0.01f)
            assertTrue("should have shrunk", it.size < metrics.stackSize)
            assertTrue("should have gone towards the target", it.x > 300f)
        }
    }

    @Test
    fun reducedMotionHasNoStaggerAndNoTrail() {
        val stack = stack(5, reduced = true)
        stack.carry(1f / 60f, 200f, 300f, true)
        val first = stack.cards.map { it.x to it.y }
        for (i in 0 until 5) {
            assertEquals(200f + StackShape.restX(i), first[i].first, 0.01f)
            assertEquals(300f - metrics.liftOffset + StackShape.restY(i), first[i].second, 0.01f)
        }
        stack.carry(1f / 60f, 900f, 100f, true)
        for (i in 0 until 5) {
            assertEquals(900f + StackShape.restX(i), stack.cards[i].x, 0.01f)
            assertEquals(100f - metrics.liftOffset + StackShape.restY(i), stack.cards[i].y, 0.01f)
        }
    }

    @Test
    fun reducedMotionFadesInsteadOfFlying() {
        val stack = stack(3, reduced = true)
        stack.carry(1f / 60f, 200f, 300f, true)
        val x = stack.cards[1].x
        stack.startReturn()
        var done = false
        var seconds = 0f
        while (seconds < 1f && !done) {
            done = stack.flyHome(1f / 60f)
            seconds += 1f / 60f
        }
        assertTrue(done)
        assertTrue("took $seconds s", seconds < 0.2f)
        assertEquals(x, stack.cards[1].x, 0f)
    }

    // ---- the deck

    @Test
    fun everyCardSitsDirectlyBehindTheOneInFront() {
        for (total in listOf(2, 3, 5, 10, 30, 100, 150)) {
            for (i in 1 until total) {
                assertTrue("$i of $total should lean further", StackShape.restX(i, total) > StackShape.restX(i - 1, total))
                assertTrue("$i of $total should be fainter", StackShape.depthFade(i, total) < StackShape.depthFade(i - 1, total))
                assertEquals(0f, StackShape.restRotation(i, total), 0f)
                assertTrue("$i of $total should be higher", StackShape.restY(i, total) < StackShape.restY(i - 1, total))
                assertTrue("$i of $total should be smaller", StackShape.sizeFactor(i, total) < StackShape.sizeFactor(i - 1, total))
            }
            assertEquals(0f, StackShape.restY(0, total), 0f)
            assertEquals(1f, StackShape.sizeFactor(0, total), 0f)
            assertEquals(1f, StackShape.depthFade(0, total), 0f)
            assertEquals(0.8f, StackShape.depthFade(total - 1, total), 0.0001f)
        }
    }

    @Test
    fun anEdgeOfEveryCardShows() {
        for (total in listOf(2, 3, 5, 10, 30, 100, 150)) {
            for (i in 1 until total) {
                val top = StackShape.restY(i, total) - 104f * StackShape.sizeFactor(i, total) / 2f
                val inFront = StackShape.restY(i - 1, total) - 104f * StackShape.sizeFactor(i - 1, total) / 2f
                assertTrue("$i of $total shows above the one in front", top < inFront)
            }
        }
    }

    @Test
    fun aFewCardsKeepTheirTiming() {
        val stiff = floatArrayOf(0f, 300f, 235f, 190f, 160f)
        for (total in 1..5) {
            for (i in 1 until total) {
                assertEquals(stiff[i], StackShape.stiffness(i, total), 0f)
                assertEquals(0.032f * i, StackShape.launchDelay(i, total), 0.0001f)
            }
        }
    }

    @Test
    fun theDeckGrowsWithTheCountAndThenStops() {
        assertEquals(15f, StackShape.clusterRadius(2), 0f)
        assertEquals(60f, StackShape.clusterRadius(5), 0f)
        assertEquals(StackShape.MaxClusterRadius, StackShape.clusterRadius(10), 0f)
        assertEquals(StackShape.MaxClusterRadius, StackShape.clusterRadius(500), 0f)
        assertEquals(70f, StackShape.clusterRadius(150, limit = 70f), 0f)
    }

    @Test
    fun theDeckGoesAwayFromTheFinger() {
        for (total in listOf(2, 5, 30, 150)) {
            for (i in 0 until total) assertTrue(StackShape.restY(i, total) <= 0f)
        }
    }

    // ---- how long things take

    @Test
    fun aLiftsStaggerIsBoundedWhateverTheCount() {
        for (total in listOf(2, 5, 6, 10, 30, 100, 150, 500, 5000)) {
            assertTrue("$total", StackShape.launchDelay(total - 1, total) <= 0.2f + 0.0001f)
        }
        // And the more there are, the closer together they leave.
        assertTrue(StackShape.launchDelay(1, 150) < StackShape.launchDelay(1, 30))
        assertTrue(StackShape.launchDelay(1, 30) < StackShape.launchDelay(1, 10))
    }

    @Test
    fun theWayBackIsBoundedWhateverTheCount() {
        for (total in listOf(2, 5, 6, 10, 30, 100, 150, 500, 5000)) {
            assertTrue("$total", StackShape.returnStagger(total) * (total - 1) <= 0.1f + 0.0001f)
            assertTrue("$total", StackShape.absorbStagger(total) * (total - 1) <= 0.144f + 0.0001f)
        }
    }

    private fun heap(total: Int, spreadOut: Float = 1500f): DragStack {
        val cards = (0 until total).map { i ->
            // Around a large part of the screen, as a real lift is, and a good way off for what's off screen.
            val x = (i * 73 % 1080).toFloat()
            val y = if (i % 3 == 0) -100f else if (i % 3 == 1) spreadOut else 500f
            DragCard(i, x, y, 90f).apply { setHome(x, y, 90f) }
        }
        return DragStack(cards, metrics, false, 500f, 700f)
    }

    private fun worstDistanceFromRest(stack: DragStack, x: Float, y: Float): Float =
        stack.cards.maxOf { hypot(it.x - (x + it.restX * metrics.dp), it.y - (y - metrics.liftOffset + it.restY * metrics.dp)) }

    @Test
    fun everyoneIsThereInAbout450Ms() {
        for (total in listOf(6, 10, 30, 100, 150, 500)) {
            val stack = heap(total)
            var seconds = 0f
            while (seconds < 0.45f) {
                stack.carry(1f / 60f, 500f, 700f, true)
                seconds += 1f / 60f
            }
            val off = worstDistanceFromRest(stack, 500f, 700f)
            assertTrue("$total still $off px off", off < 12f)
        }
    }

    @Test
    fun aLongLineStringsOutAndIsBounded() {
        for (total in listOf(10, 30, 100, 150, 500)) {
            val stack = heap(total)
            stack.settle(100f, 700f, seconds = 1.2f)
            var x = 100f
            val speed = 2400f
            var seconds = 0f
            while (seconds < 1f) {
                x += speed / 60f
                stack.carry(1f / 60f, x, 700f, true)
                seconds += 1f / 60f
            }
            // Measured along the way it's going, leaving out where each one sits in the heap.
            val behind = stack.cards.map { x + it.restX - it.x }
            assertTrue("$total top: ${behind[0]}", behind[0] < 1f)
            val expected = speed * StackShape.trailSeconds(total)
            assertTrue("$total last: ${behind.last()} against $expected", behind.last() > expected * 0.75f)
            assertTrue("$total last: ${behind.last()} against $expected", behind.last() < expected * 1.15f)
            for (i in 1 until behind.size) {
                assertTrue("$total: card $i is ahead of the one in front", behind[i] >= behind[i - 1] - 0.5f)
            }
        }
    }

    @Test
    fun aLongLineSettlesAndStaysPut() {
        for (total in listOf(30, 150, 500)) {
            val stack = heap(total)
            stack.settle(100f, 700f, seconds = 1.2f)
            var x = 100f
            repeat(30) {
                x += 40f
                stack.carry(1f / 60f, x, 700f, true)
            }
            var seconds = 0f
            while (worstDistanceFromRest(stack, x, 700f) > 2f && seconds < 3f) {
                stack.carry(1f / 60f, x, 700f, true)
                seconds += 1f / 60f
            }
            assertTrue("$total took $seconds s", seconds < 1.6f)
            stack.settle(x, 700f, seconds = 1f)
            stack.cards.forEach { assertTrue(abs(it.velocityX) + abs(it.velocityY) < 3f) }
        }
    }

    @Test
    fun aSlowDeviceDoesntThrowALongLineOffScreen() {
        val stack = heap(150)
        var x = 100f
        repeat(40) {
            x += 400f
            stack.carry(0.25f, x, 500f, true)
            stack.cards.forEach { assertTrue("ran away: ${it.x} for finger $x", abs(it.x - x) < 6_000f) }
        }
        stack.settle(x, 500f, seconds = 3f)
        assertTrue(worstDistanceFromRest(stack, x, 500f) < 2f)
    }

    @Test
    fun theLineIsSmoothOnEveryFrameRate() {
        for (fps in listOf(30f, 60f, 90f, 120f)) {
            val stack = heap(150)
            stack.settle(100f, 700f, seconds = 1.2f)
            var x = 100f
            var seconds = 0f
            while (seconds < 0.6f) {
                x += 2000f / fps
                stack.carry(1f / fps, x, 700f, true)
                seconds += 1f / fps
            }
            val behind = stack.cards.last().let { x + it.restX - it.x }
            val expected = 2000f * StackShape.trailSeconds(150)
            assertTrue("$fps: $behind against $expected", behind > expected * 0.7f && behind < expected * 1.2f)
        }
    }

    @Test
    fun theTopCardStaysLockedHoweverManyThereAre() {
        val stack = heap(150)
        stack.settle(200f, 300f, seconds = 1.2f)
        var x = 200f
        repeat(30) {
            x += 40f
            stack.carry(1f / 60f, x, 300f, true)
            assertEquals(x, stack.cards[0].x, 0.5f)
        }
        assertEquals(0, stack.cards[0].index)
    }

    @Test
    fun cardsAddedLaterTakeTheirPlaceAndCatchUp() {
        val first = (0 until 4).map { i -> DragCard(i, 400f + i * 80f, 600f, 90f).apply { setHome(x, y, 90f) } }
        val stack = DragStack(first, metrics, false, 500f, 700f, total = 30)
        // Places for 30 from the start, not for the 4 there are so far.
        assertEquals(StackShape.restX(3, 30), first[3].restX, 0f)
        repeat(4) { stack.carry(1f / 60f, 500f, 700f, true) }
        for (i in 4 until 30) {
            stack.add(DragCard(i, (i * 40 % 1080).toFloat(), if (i % 2 == 0) -90f else 2400f, 90f).apply { setExit(x, y) })
        }
        assertEquals(30, stack.cards.size)
        assertEquals(StackShape.restX(20, 30), stack.cards[20].restX, 0f)
        var seconds = 0f
        while (seconds < 0.5f) {
            stack.carry(1f / 60f, 500f, 700f, true)
            seconds += 1f / 60f
        }
        assertTrue(worstDistanceFromRest(stack, 500f, 700f) < 12f)
    }

    @Test
    fun whatCameFromOffScreenGoesBackThatWay() {
        val cards = (0 until 8).map { i ->
            DragCard(i, 100f + i * 100f, 400f, 90f).apply {
                if (i < 4) setHome(x, y, 90f) else setExit(x, -300f)
            }
        }
        val stack = DragStack(cards, metrics, false, 500f, 700f)
        stack.settle(500f, 700f, seconds = 1.2f)
        stack.startReturn()
        var seconds = 0f
        var done = false
        while (seconds < 2f && !done) {
            done = stack.flyHome(1f / 60f)
            seconds += 1f / 60f
        }
        assertTrue(done)
        stack.cards.forEach { assertEquals(0f, it.alpha, 0.01f) }
        for (i in 4 until 8) assertEquals(-300f, stack.cards[i].y, 3f)
        for (i in 0 until 4) assertEquals(400f, stack.cards[i].y, 3f)
    }

    @Test
    fun theWayBackTakesAbout450MsAtAnySize() {
        for (total in listOf(10, 30, 150, 500)) {
            val stack = heap(total)
            stack.settle(500f, 700f, seconds = 1.2f)
            stack.startReturn()
            var seconds = 0f
            var done = false
            while (seconds < 2f && !done) {
                done = stack.flyHome(1f / 60f)
                seconds += 1f / 60f
            }
            assertTrue(done)
            assertTrue("$total took $seconds s", seconds <= 0.56f)
        }
    }

    @Test
    fun goingIntoATargetTakesTheSameAtAnySize() {
        for (total in listOf(10, 30, 150, 500)) {
            val stack = heap(total)
            stack.settle(500f, 700f, seconds = 1.2f)
            stack.startAbsorb(800f, 900f)
            var seconds = 0f
            var done = false
            while (seconds < 2f && !done) {
                done = stack.absorb(1f / 60f)
                seconds += 1f / 60f
            }
            assertTrue(done)
            assertTrue("$total took $seconds s", seconds <= 0.56f)
        }
    }

    @Test
    fun reducedMotionHasEveryCardInPlaceAtOnce() {
        val cards = (0 until 30).map { i -> DragCard(i, 500f + i * 150f, 500f + i * 40f, 80f) }
        val stack = DragStack(cards, metrics, true, 500f, 500f)
        stack.carry(1f / 60f, 200f, 300f, true)
        stack.cards.forEachIndexed { i, card ->
            assertEquals(200f + StackShape.restX(i, 30), card.x, 0.01f)
            assertEquals(300f - metrics.liftOffset + StackShape.restY(i, 30), card.y, 0.01f)
            assertEquals(1f, card.alpha, 0f)
        }
        stack.carry(1f / 60f, 900f, 100f, true)
        stack.cards.forEachIndexed { i, card -> assertEquals(900f + StackShape.restX(i, 30), card.x, 0.01f) }
    }

    @Test
    fun reducedMotionTakesCardsAddedLaterStraightToTheirPlace() {
        val first = listOf(DragCard(0, 500f, 500f, 80f))
        val stack = DragStack(first, metrics, true, 500f, 500f, total = 8)
        for (i in 1 until 8) stack.add(DragCard(i, 500f, -90f, 80f).apply { setExit(x, y) })
        stack.carry(1f / 60f, 200f, 300f, true)
        for (i in 1 until 8) assertEquals(200f + StackShape.restX(i, 8), stack.cards[i].x, 0.01f)
    }

    // ---- where off-screen cards come from

    @Test
    fun whatsAboveComesFromTheTopAndWhatsBelowFromTheBottom() {
        assertEquals(-100f, CardEntry.edgeY(3, 40, -100f, 2500f), 0f)
        assertEquals(2500f, CardEntry.edgeY(90, 40, -100f, 2500f), 0f)
    }

    @Test
    fun itComesDownItsOwnColumn() {
        assertEquals(0, CardEntry.column(41, 40, heldColumn = 2, columns = 3))
        assertEquals(1, CardEntry.column(39, 40, heldColumn = 2, columns = 3))
        assertEquals(2, CardEntry.column(40, 40, heldColumn = 2, columns = 3))
        assertEquals(1, CardEntry.column(8, 40, heldColumn = 0, columns = 3))
        assertEquals(0, CardEntry.column(500, 40, heldColumn = 0, columns = 1))
    }

    @Test
    fun theCeilingIsAtLeastWhatWasPromised() {
        assertTrue(StackShape.MaxCards >= 150)
    }
}
