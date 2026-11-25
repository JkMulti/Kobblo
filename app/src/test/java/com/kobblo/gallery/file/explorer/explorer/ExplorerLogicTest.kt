package com.kobblo.gallery.file.explorer.explorer

import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.SortMode
import com.kobblo.gallery.file.explorer.explorer.search.IndexSelectionBuilder
import com.kobblo.gallery.file.explorer.explorer.search.SearchDate
import com.kobblo.gallery.file.explorer.explorer.search.SearchFilters
import com.kobblo.gallery.file.explorer.explorer.search.SearchKind
import com.kobblo.gallery.file.explorer.explorer.search.SearchMatcher
import com.kobblo.gallery.file.explorer.explorer.search.SearchSize
import com.kobblo.gallery.file.explorer.explorer.search.SearchText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ExplorerLogicTest {
    private fun file(
        name: String,
        type: String = "file",
        id: Int = name.hashCode(),
        size: Long = 0,
        modified: Long = 0,
        locked: Boolean = false
    ) = FileItem(
        id = id,
        path = "/storage/emulated/0/$name",
        name = name,
        type = type,
        kind = type,
        size = "",
        changed = "",
        owner = "Me",
        isLocked = locked,
        sizeBytes = size,
        lastModified = modified
    )

    private fun naturally(vararg names: String) = names.toList().sortedWith(NaturalOrder)

    // ---- natural order

    @Test
    fun numbersCompareAsNumbers() {
        assertEquals(listOf("file1", "file2", "file10", "file100"), naturally("file10", "file2", "file100", "file1"))
    }

    @Test
    fun caseDoesNotDecideOrder() {
        assertEquals(listOf("apple", "Banana", "cherry"), naturally("cherry", "apple", "Banana"))
    }

    @Test
    fun leadingZerosReadAsTheSameNumberButStayInAFixedOrder() {
        assertEquals(listOf("a01", "a1", "a2"), naturally("a2", "a1", "a01"))
        assertTrue(NaturalOrder.compare("a01", "a1") != 0)
    }

    @Test
    fun numbersLongerThanAnyIntegerStillCompare() {
        val small = "x99999999999999999999999998"
        val big = "x99999999999999999999999999"
        assertTrue(NaturalOrder.compare(small, big) < 0)
        assertTrue(NaturalOrder.compare("x" + "9".repeat(40), "x1" + "0".repeat(40)) < 0)
    }

    @Test
    fun aNameBeforeALongerOneThatStartsWithIt() {
        assertEquals(listOf("report", "report 2", "report.txt"), naturally("report.txt", "report 2", "report"))
    }

    @Test
    fun sameNameDiffersOnlyByCaseKeepsAFixedOrder() {
        assertTrue(NaturalOrder.compare("File", "file") != 0)
        assertEquals(-NaturalOrder.compare("File", "file"), NaturalOrder.compare("file", "File"))
    }

    @Test
    fun orderingHoldsTogetherOverAMixedSet() {
        val names = listOf("10", "9", "a", "A1", "a01", "a1", "a1b", "a-1", "a 1", "_x", ".hidden", "é", "e", "Z", "z2", "z10", "")
        for (a in names) for (b in names) {
            assertEquals("$a vs $b", Integer.signum(NaturalOrder.compare(a, b)), -Integer.signum(NaturalOrder.compare(b, a)))
            for (c in names) {
                if (NaturalOrder.compare(a, b) < 0 && NaturalOrder.compare(b, c) < 0) {
                    assertTrue("$a < $b < $c", NaturalOrder.compare(a, c) < 0)
                }
            }
        }
    }

    // ---- sorting a folder

    @Test
    fun foldersStayOnTopInEveryDirection() {
        val items = listOf(file("a.txt", size = 9, modified = 9), file("z", "folder"), file("b", "folder"))
        for (mode in SortMode.entries) {
            for (reversed in listOf(false, true)) {
                val sorted = items.sortedWith(ExplorerSort.comparator(mode, reversed))
                assertEquals("$mode $reversed", "a.txt", sorted.last().name)
            }
        }
    }

    @Test
    fun eachSortReadsTheWayPeopleExpectAndFlips() {
        val items = listOf(file("old.txt", size = 5, modified = 1), file("new.txt", size = 1, modified = 9), file("mid.pdf", size = 3, modified = 5))
        fun order(mode: SortMode, reversed: Boolean) = items.sortedWith(ExplorerSort.comparator(mode, reversed)).map { it.name }
        assertEquals(listOf("new.txt", "mid.pdf", "old.txt"), order(SortMode.DATE, false))
        assertEquals(listOf("old.txt", "mid.pdf", "new.txt"), order(SortMode.DATE, true))
        assertEquals(listOf("old.txt", "mid.pdf", "new.txt"), order(SortMode.SIZE, false))
        assertEquals(listOf("new.txt", "mid.pdf", "old.txt"), order(SortMode.SIZE, true))
        assertEquals(listOf("mid.pdf", "new.txt", "old.txt"), order(SortMode.ALPHABETICAL, false))
        assertEquals(listOf("old.txt", "new.txt", "mid.pdf"), order(SortMode.ALPHABETICAL, true))
        assertEquals(listOf("mid.pdf", "new.txt", "old.txt"), order(SortMode.KIND, false))
    }

    @Test
    fun equalDatesReadAlphabeticallyEvenReversed() {
        val items = listOf(file("b", modified = 1), file("a", modified = 1))
        assertEquals(listOf("a", "b"), items.sortedWith(ExplorerSort.comparator(SortMode.DATE, true)).map { it.name })
    }

    // ---- folder memory

    private val root = "/storage/emulated/0"
    private fun view(path: String, stored: Map<String, FolderView> = emptyMap(), fallbackList: Boolean = false) =
        FolderViewRules.resolve(path, listOf(root), fallbackList) { stored[it] }

    @Test
    fun anUntouchedFolderTakesTheGlobalViewAndNameOrder() {
        val seen = view("$root/Documents/Work", fallbackList = true)
        assertEquals(SortMode.ALPHABETICAL, seen.sort)
        assertFalse(seen.reversed)
        assertTrue(seen.list)
    }

    @Test
    fun aFolderRemembersItsOwnChoice() {
        val stored = mapOf("$root/Documents" to FolderView(SortMode.SIZE, reversed = true, list = true))
        val seen = view("$root/Documents", stored)
        assertEquals(SortMode.SIZE, seen.sort)
        assertTrue(seen.reversed)
        assertTrue(seen.list)
        // A sibling stays as it was.
        assertEquals(SortMode.ALPHABETICAL, view("$root/Music", stored).sort)
    }

    @Test
    fun subfoldersFollowTheNearestFolderAboveThem() {
        val stored = mapOf(
            root to FolderView(SortMode.SIZE),
            "$root/Documents" to FolderView(SortMode.KIND, list = true)
        )
        assertEquals(SortMode.KIND, view("$root/Documents/a/b", stored).sort)
        assertTrue(view("$root/Documents/a/b", stored).list)
        assertEquals(SortMode.SIZE, view("$root/Music/x", stored).sort)
    }

    @Test
    fun sortAndViewAreInheritedSeparately() {
        val stored = mapOf(
            "$root/Documents" to FolderView(list = true),
            root to FolderView(SortMode.DATE, reversed = true)
        )
        val seen = view("$root/Documents", stored)
        assertEquals(SortMode.DATE, seen.sort)
        assertTrue(seen.reversed)
        assertTrue(seen.list)
    }

    @Test
    fun downloadsAndPhotosStartNewestFirst() {
        assertEquals(SortMode.DATE, view("$root/Download").sort)
        assertEquals(SortMode.DATE, view("$root/DCIM/Camera").sort)
        assertFalse(view("$root/DCIM/Camera", fallbackList = true).list)
        // Only photo folders insist on icons.
        assertTrue(view("$root/Download", fallbackList = true).list)
    }

    @Test
    fun foldersInsideDownloadsGoByNameLikeAnywhereElse() {
        assertEquals(SortMode.ALPHABETICAL, view("$root/Download/Work").sort)
        // A choice made on Downloads itself still reaches them.
        val stored = mapOf("$root/Download" to FolderView(SortMode.SIZE))
        assertEquals(SortMode.SIZE, view("$root/Download/Work", stored).sort)
    }

    @Test
    fun aChoiceBeatsAWellKnownFolderStart() {
        val stored = mapOf("$root/Download" to FolderView(SortMode.ALPHABETICAL))
        assertEquals(SortMode.ALPHABETICAL, view("$root/Download", stored).sort)
    }

    @Test
    fun tappingTheSameSortFlipsItAndAnotherStartsFresh() {
        val current = ResolvedFolderView(SortMode.DATE, reversed = false, list = false)
        assertEquals(SortMode.DATE to true, FolderViewRules.nextSort(current, SortMode.DATE))
        assertEquals(SortMode.SIZE to false, FolderViewRules.nextSort(current.copy(reversed = true), SortMode.SIZE))
    }

    @Test
    fun anUprightPhoneGetsWhatWasAskedForAndWiderScreensFitMore() {
        assertEquals(3, FolderViewRules.columnsFor(3, 328f))
        assertEquals(3, FolderViewRules.columnsFor(3, 416f))
        assertEquals(6, FolderViewRules.columnsFor(3, 882f))
        assertEquals(4, FolderViewRules.columnsFor(2, 800f))
    }

    @Test
    fun aRememberedViewSurvivesBeingWrittenOut() {
        val view = FolderView(SortMode.KIND, reversed = true, list = false)
        assertEquals(view, FolderViewRules.decode(FolderViewRules.encode(view, 42L)))
        assertEquals(42L, FolderViewRules.stampOf(FolderViewRules.encode(view, 42L)))
        assertEquals(FolderView(), FolderViewRules.decode(FolderViewRules.encode(FolderView(), 1L)))
    }

    @Test
    fun aDamagedRememberedViewIsIgnored() {
        assertNull(FolderViewRules.decode(null))
        assertNull(FolderViewRules.decode("rubbish"))
        assertEquals(FolderView(), FolderViewRules.decode("NOT_A_SORT|x|y|z"))
    }

    // ---- search text

    @Test
    fun everyWordHasToAppearInAnyOrder() {
        val text = SearchText("2024 invoice")
        assertTrue(text.matches("Invoice-March-2024.pdf"))
        assertFalse(text.matches("Invoice-March.pdf"))
    }

    @Test
    fun aStarIsOnlyASeparator() {
        assertEquals(listOf(".pdf"), SearchText("*.pdf").tokens)
        assertEquals(listOf("a", "b"), SearchText("a*b").tokens)
    }

    @Test
    fun blankQueriesHaveNoWords() {
        assertTrue(SearchText("  ").isBlank)
        assertTrue(SearchText("*").isBlank)
    }

    @Test
    fun matchingIgnoresCaseOutsideAsciiToo() {
        assertTrue(SearchText("ÉTÉ").matches("souvenirs été 2019.txt"))
        assertTrue(SearchText("été").matches("SOUVENIRS ÉTÉ 2019.TXT"))
        assertFalse(SearchText("etes").matches("souvenirs été 2019.txt"))
    }

    @Test
    fun matchingFindsAWordAtEitherEndAndNotPastIt() {
        assertTrue(SearchText("rep").matches("REPORT.pdf"))
        assertTrue(SearchText("PDF").matches("report.pdf"))
        assertFalse(SearchText("pdfx").matches("report.pdf"))
        assertFalse(SearchText("a").matches(""))
        assertTrue(SearchText("aab").matches("aaab"))
    }

    @Test
    fun theBestFitRanksFirst() {
        val text = SearchText("report")
        assertEquals(0, text.rank("Report.pdf"))
        assertEquals(0, text.rank("report"))
        assertEquals(1, text.rank("report-2024.pdf"))
        assertEquals(2, text.rank("Q3 report.pdf"))
        assertEquals(3, text.rank("misreported.txt"))
    }

    @Test
    fun rangesCoverEachMatch() {
        val ranges = SearchText("ab").ranges("xabyAB")
        assertEquals(listOf(1..2, 4..5), ranges)
    }

    // ---- the index query

    private val zone = TimeZone.getTimeZone("UTC")

    private fun build(query: String = "", filters: SearchFilters = SearchFilters(), scope: String? = null, hidden: Boolean = false) =
        IndexSelectionBuilder.build(SearchText(query), filters, scope, hidden, nowMillis = 1_700_000_000_000L, zone = zone)

    @Test
    fun theNameIsCheckedFirstWithItsWildcardsEscaped() {
        val built = build("50%_a\\b")
        assertTrue(built.selection.startsWith("_display_name LIKE ? ESCAPE '\\'"))
        assertEquals(listOf("%50\\%\\_a\\\\b%"), built.args)
    }

    @Test
    fun eachWordGetsItsOwnClauseInOrder() {
        val built = build("alpha beta", scope = "/storage/emulated/0/Docs")
        assertEquals(listOf("%alpha%", "%beta%", "/storage/emulated/0/Docs/%"), built.args)
    }

    @Test
    fun aScopesWildcardsAreEscapedToo() {
        val built = build(scope = "/storage/emulated/0/100%_done/")
        assertEquals(listOf("/storage/emulated/0/100\\%\\_done/%"), built.args)
    }

    @Test
    fun whatTheUserTypedNeverReachesTheQueryText() {
        val hostile = "') OR 1=1 --"
        val built = build(hostile)
        assertFalse(built.selection.contains("1=1"))
        assertTrue(built.args.any { it.contains("1=1") })
    }

    @Test
    fun hiddenAndRestrictedFoldersStayOutUnlessAsked() {
        assertTrue(build().selection.contains("_data NOT LIKE '%/.%'"))
        assertFalse(build(hidden = true).selection.contains("_data NOT LIKE '%/.%'"))
        assertTrue(build(hidden = true).selection.contains("/Android/data"))
        assertTrue(build(hidden = true).selection.contains("/Android/obb"))
    }

    @Test
    fun aNonAsciiWordOnlyAsksForItsAsciiParts() {
        assertEquals(listOf("%caf%"), build("café").args)
        assertEquals(listOf("%t%"), build("été").args)
        assertTrue(build("日本語").args.isEmpty())
        assertEquals(listOf("ab", "cd"), IndexSelectionBuilder.asciiRuns("abécd"))
    }

    @Test
    fun kindsLeanOnTheSystemsColumnWhereItHasOne() {
        val photos = build(filters = SearchFilters(kind = SearchKind.Photos)).selection
        assertTrue(photos.contains("media_type = 1"))
        assertTrue(photos.contains("_data LIKE '%.tga'"))
        assertTrue(photos.contains("format IS NOT 12289"))
        val docs = build(filters = SearchFilters(kind = SearchKind.Documents)).selection
        assertFalse(docs.contains("media_type = "))
        assertTrue(docs.contains("_data LIKE '%.pdf'"))
    }

    @Test
    fun foldersAreAskedForByFormat() {
        val selection = build(filters = SearchFilters(kind = SearchKind.Folders)).selection
        assertTrue(selection.contains("format = 12289"))
    }

    @Test
    fun sizeAndDateLetUnscannedRowsThrough() {
        val selection = build(filters = SearchFilters(size = SearchSize.To100Mb, date = SearchDate.Today)).selection
        assertTrue(selection.contains("(_size IS NULL OR (_size >= ${1L shl 20} AND _size < ${100L shl 20}))"))
        // Midnight UTC on the day of the fixed clock.
        assertTrue(selection.contains("(date_modified IS NULL OR date_modified >= 1699920000)"))
        assertTrue(selection.contains("format IS NOT 12289"))
        val huge = build(filters = SearchFilters(size = SearchSize.Over1Gb)).selection
        assertTrue(huge.contains("(_size IS NULL OR _size >= ${1L shl 30})"))
    }

    @Test
    fun datesCountWholeDaysBack() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val today = IndexSelectionBuilder.sinceMillis(SearchDate.Today, now, zone)
        assertEquals(today - 6 * day, IndexSelectionBuilder.sinceMillis(SearchDate.Week, now, zone))
        assertEquals(today - 29 * day, IndexSelectionBuilder.sinceMillis(SearchDate.Month, now, zone))
    }

    // ---- deciding a row for good

    private fun matcher(
        query: String = "",
        filters: SearchFilters = SearchFilters(),
        scope: String? = null,
        hidden: Boolean = false,
        locked: Set<String> = emptySet()
    ) = SearchMatcher(SearchText(query), filters, scope, hidden, locked, nowMillis = 1_700_000_000_000L, zone = zone)

    private val photoPath = "/storage/emulated/0/DCIM/a.jpg"

    @Test
    fun aRowOutsideTheScopeIsOut() {
        val m = matcher(scope = "/storage/emulated/0/Docs")
        assertFalse(m.matches("/storage/emulated/0/Docs2/a.txt", "a.txt", false, 0, 1, 1))
        assertTrue(m.matches("/storage/emulated/0/Docs/x/a.txt", "a.txt", false, 0, 1, 1))
    }

    @Test
    fun anUnscannedRowIsDecidedByItsRealSize() {
        val m = matcher(filters = SearchFilters(size = SearchSize.Over1Gb))
        assertFalse(m.matches(photoPath, "a.jpg", false, 1, 10, 1))
        assertTrue(m.matches(photoPath, "a.jpg", false, 1, 2L shl 30, 1))
    }

    @Test
    fun kindGoesByTheSystemsColumnThenTheName() {
        val m = matcher(filters = SearchFilters(kind = SearchKind.Photos))
        assertTrue(m.matches(photoPath, "a.jpg", false, 0, 1, 1))
        assertTrue(m.matches("/x/odd.bin", "odd.bin", false, 1, 1, 1))
        assertFalse(m.matches("/x/a.mp4", "a.mp4", false, 3, 1, 1))
        assertFalse(m.matches("/x/folder.jpg", "folder.jpg", true, 0, 0, 1))
    }

    @Test
    fun foldersDropOutOfASizeSearch() {
        assertFalse(matcher(filters = SearchFilters(size = SearchSize.Under1Mb)).matches("/x/f", "f", true, 0, 0, 1))
    }

    @Test
    fun theInsideOfALockedFolderStaysOutButTheFolderShows() {
        val m = matcher(locked = setOf("/storage/emulated/0/Private"))
        assertTrue(m.matches("/storage/emulated/0/Private", "Private", true, 0, 0, 1))
        assertFalse(m.matches("/storage/emulated/0/Private/a.jpg", "a.jpg", false, 1, 1, 1))
        assertTrue(m.matches("/storage/emulated/0/PrivateNot/a.jpg", "a.jpg", false, 1, 1, 1))
    }

    @Test
    fun hiddenPathsStayOutUnlessAsked() {
        assertFalse(matcher().matches("/x/.h/a.txt", "a.txt", false, 0, 1, 1))
        assertTrue(matcher(hidden = true).matches("/x/.h/a.txt", "a.txt", false, 0, 1, 1))
    }

    @Test
    fun theBinIsNeverSearchedEvenWithHiddenFilesShowing() {
        assertFalse(matcher(hidden = true).matches("/x/.kobblo_trash/abc_a.txt", "abc_a.txt", false, 0, 1, 1))
        assertFalse(matcher(hidden = true).matches("/x/.kobblo_trash", ".kobblo_trash", true, 0, 0, 1))
        assertTrue(build(hidden = true).selection.contains(".kobblo_trash"))
    }

    // ---- paths

    @Test
    fun segmentsStepDownFromTheRoot() {
        assertEquals(
            listOf(PathSegment("Download", "$root/Download"), PathSegment("Work", "$root/Download/Work")),
            PathTools.segments("$root/Download/Work", root)
        )
        assertTrue(PathTools.segments(root, root).isEmpty())
        assertTrue(PathTools.segments("/elsewhere/a", root).isEmpty())
    }

    @Test
    fun typedPathsResolve() {
        val here = "$root/Documents"
        assertEquals("$root/Music", PathTools.resolve("/sdcard/Music", here, root))
        assertEquals(root, PathTools.resolve("/sdcard", here, root))
        assertEquals("$root/Music", PathTools.resolve("/storage/self/primary/Music/", here, root))
        assertEquals("$root/Music", PathTools.resolve("~/Music", here, root))
        assertEquals(root, PathTools.resolve("~", here, root))
        assertEquals("$here/Work", PathTools.resolve("Work", here, root))
        assertEquals("$root/Work", PathTools.resolve("Work", null, root))
        assertEquals("$root/Music", PathTools.resolve("../Music", here, root))
        assertEquals("/", PathTools.resolve("/", here, root))
        assertNull(PathTools.resolve("   ", here, root))
    }

    @Test
    fun aPathCannotClimbPastTheTop() {
        assertEquals("/etc", PathTools.normalize("/storage/../../../etc"))
    }

    @Test
    fun aResultsFolderReadsShort() {
        val volumes = listOf(root to "Internal storage", "/storage/1A2B-3C4D" to "SD card")
        assertEquals("Internal storage", PathTools.folderLine("$root/a.txt", volumes))
        assertEquals("Download › Work", PathTools.folderLine("$root/Download/Work/a.txt", volumes))
        assertEquals("SD card", PathTools.folderLine("/storage/1A2B-3C4D/a.txt", volumes))
        assertEquals("SD card › Music", PathTools.folderLine("/storage/1A2B-3C4D/Music/a.txt", volumes))
        assertEquals("/other/place", PathTools.folderLine("/other/place/a.txt", volumes))
    }

    // ---- selection

    @Test
    fun invertSwapsWhatsChosenAndLeavesLockedFilesOut() {
        val files = listOf(file("a", id = 1), file("b", id = 2), file("c", id = 3, locked = true))
        assertEquals(setOf(2), SelectionOps.invert(files, setOf(1)))
        assertEquals(setOf(1, 2), SelectionOps.invert(files, emptySet()))
        assertEquals(setOf(1, 2), SelectionOps.allSelectable(files))
    }

    @Test
    fun selectingByKindAddsToTheSelection() {
        val files = listOf(file("a.jpg", "image", 1), file("b.mp4", "video", 2), file("c.jpg", "image", 3), file("d", "folder", 4))
        assertEquals(setOf(1, 3, 2), SelectionOps.addKind(files, setOf(2), SelectKind.Photos))
    }

    @Test
    fun theSizeOfASelectionAddsUpItsFiles() {
        val files = listOf(file("a", id = 1, size = 5), file("b", id = 2, size = 7), file("c", id = 3, size = 100))
        assertEquals(12L, SelectionOps.totalBytes(files, setOf(1, 2)))
    }

    @Test
    fun aFoldersFilterKeepsWhatItNames() {
        val photo = file("a.jpg", "image")
        val song = file("a.mp3", "audio")
        val zip = file("a.zip", "archive")
        assertTrue(ExplorerSort.matchesFilter(photo, "all"))
        assertTrue(ExplorerSort.matchesFilter(photo, "image"))
        assertFalse(ExplorerSort.matchesFilter(song, "image"))
        assertTrue(ExplorerSort.matchesFilter(song, "audio"))
        assertTrue(ExplorerSort.matchesFilter(zip, "archive"))
        assertFalse(ExplorerSort.matchesFilter(zip, "video"))
    }

    @Test
    fun onlyKindsThatArePresentAreOffered() {
        val files = listOf(file("a.jpg", "image", 1), file("c.jpg", "image", 3), file("d", "folder", 4), file("s", "image", 5, locked = true))
        assertEquals(mapOf(SelectKind.Folders to 1, SelectKind.Photos to 2), SelectionOps.kindCounts(files))
    }
}
