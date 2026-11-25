package com.kobblo.gallery.file.explorer.explorer.drag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropRulesTest {
    private val root = "/storage/emulated/0"
    private val a = "$root/x/a.jpg"
    private val b = "$root/x/b.jpg"
    private val folderA = "$root/x/Trips"
    private val folderB = "$root/x/Work"

    private fun outcome(hit: DropHit, paths: List<String>, folders: List<String> = emptyList()) =
        DropRules.outcome(hit, { paths }, { folders })

    // ---- what's under the finger

    private fun resolve(
        chrome: DropHit? = null,
        blocked: Boolean = false,
        tileFolder: String? = null,
        onDragged: Boolean = false,
        pageFolder: String? = "$root/x",
        origin: String? = "$root/x"
    ) = DropRules.resolve(chrome, blocked, tileFolder, onDragged, pageFolder, origin)

    @Test
    fun aFolderInTheListIsATarget() {
        assertEquals(DropHit.Folder(folderA), resolve(tileFolder = folderA))
    }

    @Test
    fun theBarsComeBeforeWhatsBehindThem() {
        assertEquals(DropHit.Action("Delete"), resolve(chrome = DropHit.Action("Delete"), tileFolder = folderA))
        assertEquals(DropHit.Folder(root), resolve(chrome = DropHit.Folder(root), tileFolder = folderA))
    }

    @Test
    fun aBarThatsInTheWayHidesTheListBehindIt() {
        assertEquals(DropHit.Nothing, resolve(blocked = true, tileFolder = folderA))
        assertEquals(DropHit.Nothing, resolve(blocked = true, pageFolder = folderB))
    }

    @Test
    fun aDraggedItemIsNoTargetEvenIfItsAFolder() {
        assertEquals(DropHit.Nothing, resolve(onDragged = true, tileFolder = folderA))
    }

    @Test
    fun theFolderItStartedInDoesntCountAsSomewhereToDrop() {
        assertEquals(DropHit.Nothing, resolve(pageFolder = "$root/x", origin = "$root/x"))
    }

    @Test
    fun aFolderTheDragOpenedTakesTheDropOnItsList() {
        assertEquals(DropHit.Here(folderB), resolve(pageFolder = folderB, origin = "$root/x"))
    }

    @Test
    fun aFeedHasNoFolderToDropInto() {
        assertEquals(DropHit.Nothing, resolve(pageFolder = null, origin = "gallery"))
    }

    // ---- what letting go means

    @Test
    fun droppingOnAFolderMovesEverything() {
        assertEquals(DropOutcome.MoveTo(folderA, listOf(a, b)), outcome(DropHit.Folder(folderA), listOf(a, b)))
    }

    @Test
    fun droppingOnAPathSegmentMovesThere() {
        assertEquals(DropOutcome.MoveTo(root, listOf(a, b)), outcome(DropHit.Folder(root), listOf(a, b)))
    }

    @Test
    fun droppingIntoAnOpenedFolderMovesThere() {
        assertEquals(DropOutcome.MoveTo(folderB, listOf(a)), outcome(DropHit.Here(folderB), listOf(a)))
    }

    @Test
    fun whereItAlreadyIsIsANoOpWithNothingSaid() {
        assertEquals(DropOutcome.GoBack(refused = false), outcome(DropHit.Folder("$root/x"), listOf(a, b)))
    }

    @Test
    fun whatsAlreadyThereIsLeftOutOfAMixedDrop() {
        val here = "$root/y/c.jpg"
        assertEquals(DropOutcome.MoveTo("$root/y", listOf(a)), outcome(DropHit.Folder("$root/y"), listOf(a, here)))
    }

    @Test
    fun aFolderCantGoIntoItsOwnChild() {
        val outcome = outcome(DropHit.Folder("$folderA/2024/July"), listOf(folderA), listOf(folderA))
        assertEquals(DropOutcome.GoBack(refused = true), outcome)
    }

    @Test
    fun aFolderCantGoIntoItself() {
        assertEquals(DropOutcome.GoBack(refused = true), outcome(DropHit.Folder(folderA), listOf(folderA), listOf(folderA)))
    }

    @Test
    fun aSiblingWithTheSameStartIsNotInside() {
        val outcome = outcome(DropHit.Folder("$folderA-old"), listOf(folderA), listOf(folderA))
        assertEquals(DropOutcome.MoveTo("$folderA-old", listOf(folderA)), outcome)
    }

    @Test
    fun anActionRuns() {
        assertEquals(DropOutcome.RunAction("Share"), outcome(DropHit.Action("Share"), listOf(a)))
    }

    @Test
    fun nothingIsACancel() {
        assertEquals(DropOutcome.GoBack(refused = false), outcome(DropHit.Nothing, listOf(a)))
    }

    @Test
    fun anActionDoesntLoadThePaths() {
        var loaded = false
        val outcome = DropRules.outcome(
            DropHit.Action("Share"),
            {
                loaded = true
                listOf(a)
            },
            { emptyList() }
        )
        assertEquals(DropOutcome.RunAction("Share"), outcome)
        assertFalse(loaded)
    }

    // ---- what can light up

    @Test
    fun aFolderCanReceiveUnlessItsDraggedOrInsideSomethingDragged() {
        assertTrue(DropRules.canReceive(folderB, listOf(folderA)))
        assertFalse(DropRules.canReceive(folderA, listOf(folderA)))
        assertFalse(DropRules.canReceive("$folderA/sub", listOf(folderA)))
    }

    // ---- hover timing

    @Test
    fun stayingOpensOnceAfterTheWait() {
        val clock = HoverClock(700)
        assertFalse(clock.update("a", 1000))
        assertFalse(clock.update("a", 1699))
        assertTrue(clock.update("a", 1700))
        assertFalse(clock.update("a", 2500))
    }

    @Test
    fun leavingStartsAgain() {
        val clock = HoverClock(700)
        clock.update("a", 0)
        clock.update(null, 650)
        assertFalse(clock.update("a", 660))
        assertFalse(clock.update("a", 1300))
        assertTrue(clock.update("a", 1360))
    }

    @Test
    fun sweepingAcrossOpensNothing() {
        val clock = HoverClock(700)
        for (i in 0 until 40) assertFalse(clock.update("folder$i", i * 100L))
    }

    @Test
    fun carryingAcrossAFolderSlowlyOpensNothing() {
        val clock = HoverClock(700)
        // Two seconds on one wide folder, with the finger moving the whole way.
        for (i in 0..40) {
            assertFalse(clock.update("folder", i * 50L, x = i * 20f, y = 0f, stillSlop = 40f))
        }
    }

    @Test
    fun aHandThatIsntPerfectlySteadyStillOpens() {
        val clock = HoverClock(700)
        clock.update("folder", 0, x = 100f, y = 100f, stillSlop = 40f)
        assertFalse(clock.update("folder", 300, x = 120f, y = 90f, stillSlop = 40f))
        assertFalse(clock.update("folder", 600, x = 85f, y = 115f, stillSlop = 40f))
        assertTrue(clock.update("folder", 700, x = 110f, y = 105f, stillSlop = 40f))
    }

    @Test
    fun stoppingAfterCarryingCountsFromTheStop() {
        val clock = HoverClock(700)
        clock.update("folder", 0, x = 0f, y = 0f, stillSlop = 40f)
        assertFalse(clock.update("folder", 500, x = 300f, y = 0f, stillSlop = 40f))
        assertFalse(clock.update("folder", 1100, x = 300f, y = 0f, stillSlop = 40f))
        assertTrue(clock.update("folder", 1200, x = 300f, y = 0f, stillSlop = 40f))
    }

    @Test
    fun aScrollingListStopsTheClock() {
        val clock = HoverClock(700)
        clock.update("a", 0)
        assertFalse(clock.update("a", 800, paused = true))
        assertFalse(clock.update("a", 1400))
        assertTrue(clock.update("a", 1500))
    }

    @Test
    fun aFingerLeftWhereSomethingOpenedOpensNothingElse() {
        val clock = HoverClock(700)
        clock.update("back", 0, x = 100f, y = 100f, slop = 50f)
        assertTrue(clock.update("back", 700, x = 100f, y = 100f, slop = 50f))
        clock.restAt(100f, 100f)
        // The parent's Back button is now under the same finger, for as long as it likes.
        assertFalse(clock.update("back of parent", 800, x = 100f, y = 100f, slop = 50f))
        assertFalse(clock.update("back of parent", 5000, x = 110f, y = 90f, slop = 50f))
    }

    @Test
    fun goingSomewhereAfterwardsWorksAsUsual() {
        val clock = HoverClock(700)
        clock.restAt(100f, 100f)
        assertFalse(clock.update("folder", 0, x = 100f, y = 100f, slop = 50f))
        // It starts counting from when the finger arrives, not from when it was resting.
        assertFalse(clock.update("folder", 1000, x = 300f, y = 100f, slop = 50f))
        assertFalse(clock.update("folder", 1600, x = 300f, y = 100f, slop = 50f))
        assertTrue(clock.update("folder", 1700, x = 300f, y = 100f, slop = 50f))
    }

    @Test
    fun nothingNeverFires() {
        val clock = HoverClock(700)
        assertFalse(clock.update(null, 0))
        assertFalse(clock.update(null, 5000))
    }

    // ---- scrolling by itself

    @Test
    fun scrollsOnlyInTheZones() {
        assertEquals(0f, DropRules.autoScrollRate(500f, 100f, 1000f, 100f), 0f)
        assertTrue(DropRules.autoScrollRate(120f, 100f, 1000f, 100f) < 0f)
        assertTrue(DropRules.autoScrollRate(980f, 100f, 1000f, 100f) > 0f)
    }

    @Test
    fun goesFasterTowardsTheEdge() {
        val slow = DropRules.autoScrollRate(920f, 100f, 1000f, 100f)
        val fast = DropRules.autoScrollRate(990f, 100f, 1000f, 100f)
        assertTrue(fast > slow)
    }

    @Test
    fun aFingerAboveTheListIsOnTheHeaderSoNothingMoves() {
        assertEquals(0f, DropRules.autoScrollRate(50f, 100f, 1000f, 100f), 0f)
    }

    @Test
    fun aFingerOnTheBarIsChoosingAnAction() {
        assertEquals(0f, DropRules.autoScrollRate(1100f, 100f, 1000f, 100f, floatingBelow = true), 0f)
    }

    @Test
    fun aListThatsNoRoomScrollsNothing() {
        assertEquals(0f, DropRules.autoScrollRate(100f, 100f, 100f, 100f), 0f)
    }

    // ---- which cards fly

    @Test
    fun theHeldOneComesFirstThenTheNearest() {
        val order = flyingOrder(5, listOf(1, 2, 5, 9, 7), { kotlin.math.abs(it - 5).toFloat() }, 14)
        assertEquals(listOf(5, 7, 2, 1, 9), order)
    }

    @Test
    fun neverMoreThanTheLimit() {
        val order = flyingOrder(0, (0 until 100_000).toList(), { it.toFloat() }, 14)
        assertEquals(14, order.size)
        assertEquals(0, order.first())
    }

    // ---- what's off screen

    @Test
    fun nearestFirstFromBothSides() {
        val found = nearestOutward(20, 10, 6) { true }
        assertEquals(listOf(9, 11, 8, 12, 7, 13), found.toList())
    }

    @Test
    fun onlyWhatsWanted() {
        val found = nearestOutward(30, 10, 100) { it % 7 == 0 }
        assertEquals(listOf(7, 14, 0, 21, 28), found.toList())
    }

    @Test
    fun itDoesntCountTheOneItStartedFrom() {
        assertEquals(listOf(1, 2), nearestOutward(3, 0, 5) { true }.toList())
        assertEquals(listOf(1, 0), nearestOutward(3, 2, 5) { true }.toList())
        assertEquals(emptyList<Int>(), nearestOutward(1, 0, 5) { true }.toList())
    }

    @Test
    fun theCeilingHolds() {
        assertEquals(150, nearestOutward(100_000, 50_000, 150) { true }.size)
    }

    @Test
    fun aDenseSelectionLooksAtAboutAsManyAsItNeeds() {
        var looked = 0
        nearestOutward(100_000, 50_000, 150) { looked++; true }
        assertEquals(150, looked)
        // Near one end, it's a few more.
        looked = 0
        nearestOutward(100_000, 10, 150) { looked++; true }
        assertEquals(150, looked)
    }

    @Test
    fun aThinSelectionGetsFoundAtEitherEnd() {
        val found = nearestOutward(100_000, 50_000, 150) { it == 3 || it == 99_990 }
        assertEquals(listOf(99_990, 3), found.toList())
    }

    // ---- undo

    private fun plan(ok: Boolean = true, renamed: Int = 0, from: List<String>, outputs: List<String>, folder: String) =
        DropRules.undoPlan(ok, renamed, from, outputs, folder)

    @Test
    fun undoPutsThingsBackWhereTheyCameFrom() {
        val plan = plan(
            from = listOf("$root/x/a.jpg", "$root/x/b.jpg", "$root/y/c.jpg"),
            outputs = listOf("$root/z/a.jpg", "$root/z/b.jpg", "$root/z/c.jpg"),
            folder = "$root/z"
        )
        assertEquals(mapOf("$root/x" to listOf("$root/z/a.jpg", "$root/z/b.jpg"), "$root/y" to listOf("$root/z/c.jpg")), plan)
    }

    @Test
    fun noUndoIfANameChanged() {
        assertNull(plan(renamed = 1, from = listOf(a), outputs = listOf("$root/z/a (1).jpg"), folder = "$root/z"))
    }

    @Test
    fun noUndoIfAnythingFailed() {
        assertNull(plan(ok = false, from = listOf(a), outputs = listOf("$root/z/a.jpg"), folder = "$root/z"))
    }

    @Test
    fun noUndoIfNothingMoved() {
        assertNull(plan(from = emptyList(), outputs = listOf(a), folder = "$root/x"))
    }

    @Test
    fun noUndoIfItCantFindWhereSomethingWent() {
        assertNull(plan(from = listOf(a), outputs = listOf("$root/z/other.jpg"), folder = "$root/z"))
    }
}
