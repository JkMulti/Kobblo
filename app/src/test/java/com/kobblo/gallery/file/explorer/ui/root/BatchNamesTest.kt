package com.kobblo.gallery.file.explorer.ui.root

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchNamesTest {
    private fun first(names: BatchNames, count: Int = 2) = List(count) { names.nameAt(it) }

    @Test
    fun withoutNumbersTheyAreAllAlike() {
        assertEquals(listOf("Trip.jpg", "Trip.jpg"), first(BatchNames("Trip", "jpg", start = 5, digits = 3)))
    }

    @Test
    fun aNumberComesAfterTheNameOrBeforeIt() {
        val brackets = BatchNames("Trip", "jpg", Numbering.InBrackets)
        assertEquals(listOf("Trip (1).jpg", "Trip (2).jpg"), first(brackets))
        assertEquals(listOf("(1) Trip.jpg", "(2) Trip.jpg"), first(brackets.copy(numberFirst = true)))
        val digits = BatchNames("Trip", "jpg", Numbering.Digits)
        assertEquals(listOf("Trip_1.jpg", "Trip_2.jpg"), first(digits))
        assertEquals(listOf("1_Trip.jpg", "2_Trip.jpg"), first(digits.copy(numberFirst = true)))
    }

    @Test
    fun numbersAreFilledOutWithZeros() {
        val names = BatchNames("Trip", "jpg", Numbering.Digits, start = 9, digits = 3)
        assertEquals(listOf("Trip_009.jpg", "Trip_010.jpg"), first(names))
        assertEquals("Trip_12345.jpg", names.copy(start = 12345).nameAt(0))
        assertEquals("Trip (09).jpg", names.copy(numbering = Numbering.InBrackets, digits = 2).nameAt(0))
        // A field anything can be typed into.
        assertEquals("Trip_9.jpg", names.copy(digits = -4).nameAt(0))
        assertEquals("Trip_0-1.jpg", names.copy(start = -1).nameAt(0))
    }

    @Test
    fun anExtensionIsTypedWithOrWithoutItsDot() {
        assertEquals("Trip.jpg", BatchNames("Trip", ".jpg").nameAt(0))
        assertEquals("Trip", BatchNames("Trip", "").nameAt(0))
        assertEquals("Trip.", BatchNames("Trip", ".").nameAt(0))
    }

    @Test
    fun noNameStartsOrEndsWithASpace() {
        assertEquals("Trip", BatchNames(" Trip ", "").nameAt(0))
        assertEquals("(1)", BatchNames("", "", Numbering.InBrackets).nameAt(0))
        assertEquals("(1)", BatchNames("", "", Numbering.InBrackets, numberFirst = true).nameAt(0))
    }

    @Test
    fun theRenameIsToldTheStyleByItsWord() {
        assertEquals(listOf("None", "(1)", "0001"), Numbering.entries.map { it.word })
    }

    @Test
    fun theSheetStartsWithTheFirstFilesName() {
        assertEquals("IMG_1" to "jpg", baseNameOf("IMG_1.jpg") to extensionFieldOf("IMG_1.jpg"))
        assertEquals("a.tar" to "gz", baseNameOf("a.tar.gz") to extensionFieldOf("a.tar.gz"))
        assertEquals("README" to "", baseNameOf("README") to extensionFieldOf("README"))
        assertEquals("" to "gitignore", baseNameOf(".gitignore") to extensionFieldOf(".gitignore"))
        assertEquals("" to "", baseNameOf("") to extensionFieldOf(""))
    }
}
