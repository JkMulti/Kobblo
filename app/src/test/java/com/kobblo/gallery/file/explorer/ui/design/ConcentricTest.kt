package com.kobblo.gallery.file.explorer.ui.design

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ConcentricTest {
    @Test
    fun innerRadiusIsTheOuterLessTheGap() {
        assertEquals(20.dp, concentricRadius(28.dp, 8.dp))
        assertEquals(16.dp, concentricRadius(24.dp, 8.dp))
    }

    @Test
    fun aGapWiderThanTheCornerLeavesTheInnerShapeSquare() {
        assertEquals(0.dp, concentricRadius(12.dp, 16.dp))
    }

    @Test
    fun aMarkSitsAsFarInAsTheCornerIsRounderThanIt() {
        // A 16dp lock in a corner of 11dp: its center, 8dp from each edge, is 3dp further in.
        assertEquals(3.dp, concentricInset(11.dp, 8.dp))
        // As round as the corner, it sits flush, and a mark rounder than the corner can't go further out.
        assertEquals(0.dp, concentricInset(12.dp, 12.dp))
        assertEquals(0.dp, concentricInset(7.dp, 8.dp))
    }
}
