package com.kobblo.gallery.file.explorer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AppLanguagesTest {
    private val tags = AppLanguages.SUPPORTED_LANGUAGES.map { it.tag }

    /** The app's resources, when the tests are run from the module's folder, which is where Gradle runs them. */
    private val resources = File("src/main/res")

    @Test
    fun `the settings list the languages in this order`() {
        assertEquals(
            listOf("en", "fr", "es", "ru", "de", "pl", "pt", "ja", "zh", "hi", "ar", "it", "ko", "tr", "in", "ur"),
            tags
        )
    }

    @Test
    fun `no language is offered twice and each names itself`() {
        assertEquals(tags.size, tags.toSet().size)
        for (language in AppLanguages.SUPPORTED_LANGUAGES) assertTrue(language.tag, language.nativeName.isNotBlank())
    }

    @Test
    fun `following the phone is not one of the languages`() {
        assertEquals("device_default", AppLanguages.DEVICE_DEFAULT_TAG)
        assertFalse(AppLanguages.DEVICE_DEFAULT_TAG in tags)
    }

    @Test
    fun `every language offered has its translations`() {
        assumeTrue(resources.isDirectory)
        for (tag in tags - "en") {
            assertTrue("values-$tag/strings.xml", File(resources, "values-$tag/strings.xml").isFile)
        }
    }

    @Test
    fun `the system is told about exactly the languages offered`() {
        val config = File(resources, "xml/locales_config.xml")
        assumeTrue(config.isFile)
        val declared = Regex("android:name=\"([^\"]+)\"").findAll(config.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(tags.toSet(), declared)
    }
}
