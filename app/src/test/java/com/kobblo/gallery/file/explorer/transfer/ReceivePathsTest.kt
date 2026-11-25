package com.kobblo.gallery.file.explorer.transfer

import com.kobblo.gallery.file.explorer.transfer.core.ReceivePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ReceivePathsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun plainNameIsLeftAlone() {
        assertEquals(listOf("photo.jpg"), ReceivePaths.sanitizeRelative("photo.jpg"))
    }

    @Test fun foldersAreKept() {
        assertEquals(listOf("Camera", "trip", "a.jpg"), ReceivePaths.sanitizeRelative("Camera/trip/a.jpg"))
    }

    @Test fun parentReferencesCantClimbOut() {
        assertEquals(listOf("etc", "passwd"), ReceivePaths.sanitizeRelative("../../etc/passwd"))
        assertEquals(listOf("a", "b.txt"), ReceivePaths.sanitizeRelative("a/../../b.txt"))
        assertEquals(listOf("x.txt"), ReceivePaths.sanitizeRelative("./x.txt"))
    }

    @Test fun absolutePathsBecomeRelative() {
        assertEquals(listOf("data", "app", "evil.apk"), ReceivePaths.sanitizeRelative("/data/app/evil.apk"))
    }

    @Test fun backslashesAreSeparators() {
        assertEquals(listOf("windows", "system32", "x.dll"), ReceivePaths.sanitizeRelative("..\\..\\windows\\system32\\x.dll"))
    }

    @Test fun nothingUsableFallsBackToAName() {
        for (raw in listOf("", ".", "..", "../..", "/", "///", "...", " . ", "\u0000", "‮")) {
            assertEquals("for [$raw]", listOf("file"), ReceivePaths.sanitizeRelative(raw))
        }
    }

    @Test fun controlAndBidiCharactersAreRemoved() {
        assertEquals(listOf("a.jpg"), ReceivePaths.sanitizeRelative("a\u0000.jpg"))
        assertEquals(listOf("cod.exe"), ReceivePaths.sanitizeRelative("‮cod.exe"))
        assertEquals(listOf("line.txt"), ReceivePaths.sanitizeRelative("li\nne\r.txt"))
    }

    @Test fun leadingDotsCantHideAFile() {
        assertEquals(listOf("nomedia"), ReceivePaths.sanitizeRelative(".nomedia"))
        assertEquals(listOf("env"), ReceivePaths.sanitizeRelative(".env"))
        assertEquals(listOf("git", "config"), ReceivePaths.sanitizeRelative(".git/config"))
    }

    @Test fun dotfilesInsideAFolderKeepTheirNames() {
        assertEquals(listOf("project", ".env"), ReceivePaths.sanitizeRelative("project/.env"))
        assertEquals(listOf("project", ".git", "config"), ReceivePaths.sanitizeRelative("project/.git/config"))
    }

    @Test fun noMediaCantGetInAtAnyDepth() {
        for (name in listOf(".nomedia", ".NOMEDIA", "..nomedia", ". nomedia", ".nomedia.")) {
            assertEquals(name, "nomedia", ReceivePaths.sanitizeRelative("album/$name").last().lowercase())
        }
    }

    @Test fun restoringADotCantMakeAParentReference() {
        assertEquals(listOf("a", "b"), ReceivePaths.sanitizeRelative("a/../b"))
        assertEquals(listOf("a", "b"), ReceivePaths.sanitizeRelative("a/.../b"))
        assertEquals(listOf("a", ".b"), ReceivePaths.sanitizeRelative("a/..b"))
    }

    @Test fun reservedCharactersAndNamesAreDefused() {
        assertEquals(listOf("a_b_c.txt"), ReceivePaths.sanitizeRelative("a:b*c.txt"))
        assertEquals(listOf("_CON.txt"), ReceivePaths.sanitizeRelative("CON.txt"))
    }

    @Test fun longNamesKeepTheirExtension() {
        val name = "x".repeat(400) + ".jpeg"
        val cleaned = ReceivePaths.sanitizeRelative(name).single()
        assertTrue(cleaned.endsWith(".jpeg"))
        assertTrue(cleaned.toByteArray().size <= 240)
    }

    @Test fun longNamesCountBytesNotCharacters() {
        val name = "写".repeat(200) + ".txt"
        val cleaned = ReceivePaths.sanitizeRelative(name).single()
        assertTrue(cleaned.endsWith(".txt"))
        assertTrue(cleaned.toByteArray().size <= 240)
    }

    @Test fun veryDeepPathsAreShortened() {
        val raw = (1..100).joinToString("/") { "d$it" } + "/f.txt"
        val cleaned = ReceivePaths.sanitizeRelative(raw)
        assertEquals(16, cleaned.size)
        assertEquals("f.txt", cleaned.last())
    }

    @Test fun numberedNames() {
        assertEquals("photo (2).jpg", ReceivePaths.numbered("photo.jpg", 2))
        assertEquals("README (3)", ReceivePaths.numbered("README", 3))
        assertEquals("a.tar (2).gz", ReceivePaths.numbered("a.tar.gz", 2))
    }

    @Test fun resolvedPathStaysInsideRoot() {
        val root = temp.newFolder("root")
        val target = ReceivePaths.resolveInside(root, ReceivePaths.sanitizeRelative("../../outside.txt"))
        assertTrue(target.canonicalPath.startsWith(root.canonicalPath + File.separator))
    }

    @Test(expected = IOException::class)
    fun symlinkOutOfRootIsRefused() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        ReceivePaths.resolveInside(root, listOf("link", "secret.txt"))
    }

    @Test fun duplicatesGetNewNamesAndNothingIsOverwritten() {
        val dir = temp.newFolder("dir")
        File(dir, "a.txt").writeText("original")
        val second = ReceivePaths.moveToFreeName(File(dir, "p2").apply { writeText("2") }, dir, "a.txt")
        val third = ReceivePaths.moveToFreeName(File(dir, "p3").apply { writeText("3") }, dir, "a.txt")
        assertEquals("a (2).txt", second.name)
        assertEquals("a (3).txt", third.name)
        assertEquals("original", File(dir, "a.txt").readText())
    }

    @Test fun firstClaimOnAFreeNameKeepsIt() {
        val dir = temp.newFolder("dir")
        assertEquals("new.txt", ReceivePaths.moveToFreeName(File(dir, "p").apply { writeText("x") }, dir, "new.txt").name)
        assertFalse(File(dir, "new (2).txt").exists())
    }

    @Test fun aLinkPointingNowhereStillCountsAsTaken() {
        val dir = temp.newFolder("dir")
        Files.createSymbolicLink(File(dir, "a.txt").toPath(), File(dir, "gone").toPath())
        val moved = ReceivePaths.moveToFreeName(File(dir, "p").apply { writeText("x") }, dir, "a.txt")
        assertEquals("a (2).txt", moved.name)
        assertFalse(File(dir, "gone").exists())
    }
}
