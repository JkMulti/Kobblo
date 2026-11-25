package com.kobblo.gallery.file.explorer.startup

import com.kobblo.gallery.file.explorer.startup.StorageAsking.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAskingTest {
    private val nothing = Step(reload = false, askSystem = false)
    private val reload = Step(reload = true, askSystem = false)

    private fun started(sdk: Int, hasAccess: Boolean) = StorageAsking(sdk).apply { onStart(hasAccess) }

    @Test
    fun withAccess_theFirstReturnDoesNothing_andEachLaterOneReadsAgain() {
        for (sdk in listOf(26, 29, 30, 36)) {
            val asking = started(sdk, hasAccess = true)

            assertEquals(nothing, asking.onReturn(hasAccess = true, mayAsk = true))
            assertEquals(reload, asking.onReturn(hasAccess = true, mayAsk = true))
            assertEquals(reload, asking.onReturn(hasAccess = true, mayAsk = true))
            assertFalse(asking.alertShown)
        }
    }

    @Test
    fun accessGainedBetweenStartingAndTheFirstReturn_readsAgain() {
        val asking = started(sdk = 34, hasAccess = false)

        assertEquals(reload, asking.onReturn(hasAccess = true, mayAsk = true))
    }

    @Test
    fun aScreenThatNeverStarted_readsNothingOnItsFirstReturn() {
        assertEquals(nothing, StorageAsking(34).onReturn(hasAccess = true, mayAsk = true))
    }

    @Test
    fun fromAndroid11_theAppsOwnAlertAsks() {
        for (sdk in listOf(30, 33, 36)) {
            val asking = started(sdk, hasAccess = false)

            assertEquals(nothing, asking.onReturn(hasAccess = false, mayAsk = true))
            assertTrue(asking.alertShown)
        }
    }

    @Test
    fun theAlert_goesOnceThereIsAccess() {
        val asking = started(sdk = 34, hasAccess = false)
        asking.onReturn(hasAccess = false, mayAsk = true)

        assertEquals(reload, asking.onReturn(hasAccess = true, mayAsk = true))
        assertFalse(asking.alertShown)
    }

    @Test
    fun theAlert_turnedDown_staysAway() {
        val asking = started(sdk = 34, hasAccess = false)
        asking.onReturn(hasAccess = false, mayAsk = true)

        asking.onTurnedDown()
        assertFalse(asking.alertShown)

        assertEquals(reload, asking.onReturn(hasAccess = false, mayAsk = true))
        assertFalse(asking.alertShown)
    }

    @Test
    fun theAlert_agreedToButNotFollowedThrough_comesBack() {
        val asking = started(sdk = 34, hasAccess = false)
        asking.onReturn(hasAccess = false, mayAsk = true)

        asking.onAgreed()
        assertFalse(asking.alertShown)

        asking.onReturn(hasAccess = false, mayAsk = true)
        assertTrue(asking.alertShown)
    }

    @Test
    fun beforeAndroid11_theSystemAsks_onceALaunch() {
        for (sdk in listOf(26, 28, 29)) {
            val asking = started(sdk, hasAccess = false)

            assertEquals(Step(reload = false, askSystem = true), asking.onReturn(hasAccess = false, mayAsk = true))
            asking.onSystemAsked()
            // Refusing brings the app back here, and raising it again would go on forever.
            assertEquals(reload, asking.onReturn(hasAccess = false, mayAsk = true))
            assertFalse(asking.alertShown)
        }
    }

    @Test
    fun whileTheWelcomeAsks_nothingElseDoes() {
        for (sdk in listOf(26, 29, 30, 36)) {
            val asking = started(sdk, hasAccess = false)

            assertEquals(nothing, asking.onReturn(hasAccess = false, mayAsk = false))
            assertEquals(reload, asking.onReturn(hasAccess = false, mayAsk = false))
            assertFalse(asking.alertShown)
        }
    }

    @Test
    fun notNowInTheWelcome_answersTheAlertToo() {
        val asking = started(sdk = 34, hasAccess = false)
        asking.onReturn(hasAccess = false, mayAsk = false)

        asking.onTurnedDown()

        assertEquals(reload, asking.onReturn(hasAccess = false, mayAsk = true))
        assertFalse(asking.alertShown)
    }

    @Test
    fun accessTakenAway_isAskedForAgain_andReadAgainOnceItsBack() {
        val asking = started(sdk = 34, hasAccess = true)
        asking.onReturn(hasAccess = true, mayAsk = true)

        assertEquals(reload, asking.onReturn(hasAccess = false, mayAsk = true))
        assertTrue(asking.alertShown)
        assertEquals(reload, asking.onReturn(hasAccess = true, mayAsk = true))
        assertFalse(asking.alertShown)
    }
}
