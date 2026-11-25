package com.kobblo.gallery.file.explorer.data

import java.util.Locale

/**
 * A language the app can be shown in. [nativeName] is the language's name for itself, which stays the same
 * whatever language the app is in, so anyone can find their own.
 */
data class AppLanguage(
    val tag: String,
    val nativeName: String,
    /**
     * The language to name under [nativeName], as a language tag, or null for no name there. Chinese names its
     * script as well, so it reads "Chinese (Simplified)" and not just "Chinese".
     */
    private val glossTag: String? = tag
) {
    /**
     * The language's name in the language the app is in now, shown under [nativeName]: "French" in English,
     * "Französisch" in German. Null for English, and where it would only say [nativeName] again. It keeps the
     * name it had when this was always in English, for the screens that read it.
     */
    val englishGloss: String?
        get() {
            val glossed = Locale.forLanguageTag(glossTag ?: return null)
            // The app's language: Android sets it for the whole process from 13, and compat.AppLanguage before that.
            val shownIn = Locale.getDefault()
            // Not Locale.getDisplayName, which calls a script by its longer name: "Chinese (Simplified Han)".
            val name = android.icu.text.LocaleDisplayNames.getInstance(
                android.icu.util.ULocale.forLocale(shownIn),
                android.icu.text.DisplayContext.CAPITALIZATION_FOR_UI_LIST_OR_MENU
            ).localeDisplayName(glossed)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(shownIn) else it.toString() }
            return name.takeUnless { it.isBlank() || it.equals(nativeName, ignoreCase = true) }
        }
}

/** The languages the settings offer, in the order they're listed there. */
object AppLanguages {
    /** What's saved when the app is left to follow the phone's language. It isn't a language tag. */
    const val DEVICE_DEFAULT_TAG = "device_default"

    /**
     * English is the text the app is written in. Every other entry needs a `values-<tag>/strings.xml`
     * beside it and a line in `res/xml/locales_config.xml`, and a new language is one more entry here
     * with those two.
     */
    val SUPPORTED_LANGUAGES = listOf(
        AppLanguage("en", "English", glossTag = null),
        AppLanguage("fr", "Français"),
        AppLanguage("es", "Español"),
        AppLanguage("ru", "Русский"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("pl", "Polski"),
        AppLanguage("pt", "Português"),
        AppLanguage("ja", "日本語"),
        AppLanguage("zh", "简体中文", glossTag = "zh-Hans"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("ar", "العربية"),
        AppLanguage("it", "Italiano"),
        AppLanguage("ko", "한국어"),
        AppLanguage("tr", "Türkçe"),
        // "in" is the code Android still names its folders by. "id" is the one the language goes by now.
        AppLanguage("in", "Bahasa Indonesia", glossTag = "id"),
        AppLanguage("ur", "اردو")
    )
}
