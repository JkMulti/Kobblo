package com.kobblo.gallery.file.explorer.ui

import com.kobblo.gallery.file.explorer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinRetentionTest {
    @Test
    fun everySavedUnitHasItsPlural() {
        assertEquals(R.plurals.view_retention_seconds, BinRetention.pluralFor("Seconds"))
        assertEquals(R.plurals.view_retention_minutes, BinRetention.pluralFor("Minutes"))
        assertEquals(R.plurals.view_retention_hours, BinRetention.pluralFor("Hours"))
        assertEquals(R.plurals.view_retention_days, BinRetention.pluralFor("Days"))
        assertEquals(R.plurals.view_retention_weeks, BinRetention.pluralFor("Weeks"))
        assertEquals(R.plurals.view_retention_months, BinRetention.pluralFor("Months"))
        assertEquals(R.plurals.view_retention_years, BinRetention.pluralFor("Years"))
    }

    @Test
    fun aUnitIsKnownInAnyCaseAndWithOrWithoutItsS() {
        for (spelling in listOf("Days", "days", "DAYS", "Day", "day")) {
            assertEquals(spelling, R.plurals.view_retention_days, BinRetention.pluralFor(spelling))
        }
        assertEquals(R.plurals.view_retention_weeks, BinRetention.pluralFor("week"))
    }

    @Test
    fun aWordThatIsNotAUnitHasNoPlural() {
        assertNull(BinRetention.pluralFor(""))
        assertNull(BinRetention.pluralFor("s"))
        assertNull(BinRetention.pluralFor("Fortnights"))
        assertNull(BinRetention.pluralFor("dayss"))
        assertNull(BinRetention.pluralFor(" days"))
    }

    @Test
    fun anUnknownUnitIsShownAsSavedInLowerCase() {
        assertEquals("2 fortnights", BinRetention.plainText(2, "Fortnights"))
    }

    @Test
    fun onlyDigitsCanBeTyped() {
        assertTrue(BinRetention.canBeTyped(""))
        assertTrue(BinRetention.canBeTyped("30"))
        assertFalse(BinRetention.canBeTyped("3 0"))
        assertFalse(BinRetention.canBeTyped("-3"))
        assertFalse(BinRetention.canBeTyped("3.5"))
        assertFalse(BinRetention.canBeTyped("a"))
    }

    @Test
    fun theLengthIsWhatWasTypedButNeverLessThanOne() {
        assertEquals(30, BinRetention.valueOf("30"))
        assertEquals(1, BinRetention.valueOf("1"))
        assertEquals(1, BinRetention.valueOf("0"))
        assertEquals(1, BinRetention.valueOf("000"))
    }

    @Test
    fun aBlankOrImpossibleLengthIsSevenDays() {
        assertEquals(7, BinRetention.valueOf(""))
        assertEquals(7, BinRetention.valueOf("99999999999999"))
        assertEquals(7, BinRetention.DefaultValue)
    }
}
