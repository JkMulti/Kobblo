package com.kobblo.gallery.file.explorer.music

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeRulesTest {
    private fun start(
        current: Long = 200_000L,
        next: Long = 180_000L,
        fade: Long = 5_000L,
        speed: Float = 1f,
        repeat: Int = Player.REPEAT_MODE_OFF,
        hasNext: Boolean = true
    ) = CrossfadeRules.tailStartMs(current, next, fade, speed, repeat, hasNext)

    @Test
    fun `starts one fade from the end`() {
        assertEquals(195_000L, start())
    }

    @Test
    fun `off means never`() {
        assertNull(start(fade = 0L))
    }

    @Test
    fun `the last song plays out`() {
        assertNull(start(hasNext = false))
    }

    @Test
    fun `repeat one keeps looping`() {
        assertNull(start(repeat = Player.REPEAT_MODE_ONE))
    }

    @Test
    fun `repeat all still fades`() {
        assertEquals(195_000L, start(repeat = Player.REPEAT_MODE_ALL))
    }

    @Test
    fun `a song shorter than two fades doesn't`() {
        assertNull(start(current = 9_999L))
        assertEquals(5_000L, start(current = 10_000L))
    }

    @Test
    fun `nor does one going into a song that short`() {
        assertNull(start(next = 9_999L))
        assertEquals(195_000L, start(next = 10_000L))
    }

    @Test
    fun `a next song of unknown length doesn't stop it`() {
        assertEquals(195_000L, start(next = 0L))
    }

    @Test
    fun `a length not known yet means no fade`() {
        assertNull(start(current = 0L))
        assertNull(start(current = -9_223_372_036_854_775_807L))
    }

    @Test
    fun `speed stretches the fade in the song's own time`() {
        assertEquals(190_000L, start(speed = 2f))
        assertNull(start(current = 19_999L, speed = 2f))
    }

    @Test
    fun `the curves meet at equal power`() {
        assertEquals(1f, CrossfadeRules.fadingOut(0f), 0.001f)
        assertEquals(0f, CrossfadeRules.fadingOut(1f), 0.001f)
        assertEquals(0f, CrossfadeRules.fadingIn(0f), 0.001f)
        assertEquals(1f, CrossfadeRules.fadingIn(1f), 0.001f)
        for (progress in listOf(0.1f, 0.5f, 0.9f)) {
            val out = CrossfadeRules.fadingOut(progress)
            val into = CrossfadeRules.fadingIn(progress)
            assertEquals(1f, out * out + into * into, 0.001f)
        }
        assertTrue(CrossfadeRules.fadingIn(2f) <= 1f)
    }
}
