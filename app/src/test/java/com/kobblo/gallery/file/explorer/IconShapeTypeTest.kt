package com.kobblo.gallery.file.explorer

import org.junit.Assert.assertEquals
import org.junit.Test

class IconShapeTypeTest {
    @Test
    fun theSavedNamesHaveNotChanged() {
        assertEquals(
            listOf("COOKIE", "CIRCLE", "SQUARE", "ROUNDED_SQUARE", "IOS", "ONE_UI", "AUTO"),
            IconShapeType.entries.map { it.name }
        )
    }

    @Test
    fun aSavedNameComesBackAsItsShape() {
        for (shape in IconShapeType.entries) {
            assertEquals(shape, IconShapeType.fromString(shape.name))
        }
    }

    @Test
    fun namesFromBeforeTheRenamesStillRead() {
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString("SYSTEM"))
        assertEquals(IconShapeType.IOS, IconShapeType.fromString("SQUIRCLE"))
    }

    @Test
    fun aMissingOrUnknownNameIsTheCookie() {
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString(null))
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString(""))
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString("HEXAGON"))
        // Names are matched exactly, as they were saved.
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString("ios"))
        assertEquals(IconShapeType.COOKIE, IconShapeType.fromString(" IOS"))
    }
}
