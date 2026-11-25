package com.kobblo.gallery.file.explorer.viewer.video

import android.content.Context
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** When a saved position is worth coming back to. */
internal object ResumePolicy {
    private const val MinWatchedMs = 5_000L

    /** A clip this short is quicker to watch again than it is to find your place in. */
    private const val MinDurationMs = 30_000L

    /** Past here it's the credits or the last few frames, and finished in all but name. */
    private const val MinRemainingMs = 5_000L
    private const val MinRemainingShare = 0.02

    /** A spot at the very start or end isn't a place anyone left off. */
    fun isWorthKeeping(positionMs: Long, durationMs: Long): Boolean {
        if (positionMs < MinWatchedMs) return false
        // An unknown duration can't rule anything out, so it keeps the place.
        if (durationMs <= 0L) return true
        if (durationMs < MinDurationMs) return false
        val mustRemain = maxOf(MinRemainingMs, (durationMs * MinRemainingShare).toLong())
        return durationMs - positionMs >= mustRemain
    }

    /** Which entries to forget so the store stays small: the ones touched longest ago. */
    fun keysToForget(lastUsed: Map<String, Long>, capacity: Int): List<String> {
        if (lastUsed.size <= capacity) return emptyList()
        return lastUsed.entries.sortedBy { it.value }.take(lastUsed.size - capacity).map { it.key }
    }

    fun encode(positionMs: Long, savedAtMs: Long): String = "$positionMs,$savedAtMs"

    fun decodePosition(value: String?): Long = value?.substringBefore(',')?.toLongOrNull() ?: 0L

    fun decodeSavedAt(value: String?): Long = value?.substringAfter(',', "")?.toLongOrNull() ?: 0L

    /** Goes with the file's size as well, so a different video that took over a name doesn't start partway through. */
    fun keyFor(path: String, sizeBytes: Long): String = "$path|$sizeBytes"
}

internal class VideoResumeStore(context: Context) {
    private val prefs = context.getSharedPreferences("viewer_video_resume", Context.MODE_PRIVATE)

    private fun keyFor(path: String): String {
        val size = if (path.startsWith("/")) File(path).length() else 0L
        return ResumePolicy.keyFor(path, size)
    }

    fun get(path: String): Long = ResumePolicy.decodePosition(prefs.getString(keyFor(path), null))

    fun put(path: String, positionMs: Long, durationMs: Long) {
        if (!ResumePolicy.isWorthKeeping(positionMs, durationMs)) {
            clear(path)
            return
        }
        prefs.edit().putString(keyFor(path), ResumePolicy.encode(positionMs, System.currentTimeMillis())).apply()
        val all = prefs.all
        if (all.size > Capacity) {
            val lastUsed = all.mapValues { ResumePolicy.decodeSavedAt(it.value as? String) }
            prefs.edit().apply { ResumePolicy.keysToForget(lastUsed, Capacity).forEach(::remove) }.apply()
        }
    }

    fun clear(path: String) {
        val key = keyFor(path)
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    private companion object {
        const val Capacity = 300
    }
}

/** A subtitle file sitting next to a video. */
internal class Sidecar(val fileName: String, val extension: String, val language: String?, val mimeType: String)

/** Finds `movie.srt`, `movie.en.srt` and `movie.pt-BR.forced.srt` beside `movie.mp4`, the way every desktop player does. */
internal object SidecarSubtitles {
    val Mimes = mapOf(
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "ttml" to "application/ttml+xml"
    )

    /** Things people add after the language, which don't change what language it is. */
    private val Qualifiers = setOf("forced", "sdh", "cc", "hi", "full", "default")

    private val LanguageTag = Regex("^([a-zA-Z]{2,3})(?:[-_]([a-zA-Z0-9]{2,4}))?$")

    fun match(videoName: String, siblingNames: List<String>): List<Sidecar> {
        val base = videoName.substringBeforeLast('.', videoName)
        val prefix = "$base."
        return siblingNames.mapNotNull { name ->
            if (!name.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
            val extension = FileKinds.extensionOf(name)
            val mime = Mimes[extension] ?: return@mapNotNull null
            val middle = name.substring(prefix.length).substringBeforeLast('.', "")
            val language = languageIn(middle)
            // `movie.en.srt` names a language. `movie.final.cut.srt` doesn't name anything useful.
            if (middle.isNotEmpty() && language == null) return@mapNotNull null
            Sidecar(name, extension, language, mime)
        }
            // A plain `movie.srt` is the likeliest to be what the file's owner wants on first.
            .sortedWith(compareBy({ it.language != null }, { it.fileName }))
    }

    /** The language tag in what's between a video's name and a subtitle's extension. */
    private fun languageIn(middle: String): String? {
        if (middle.isEmpty()) return null
        val parts = middle.split('.').filter { it.lowercase(Locale.ROOT) !in Qualifiers }
        val tag = parts.singleOrNull() ?: return null
        val match = LanguageTag.matchEntire(tag) ?: return fromEnglishName(tag)
        val language = match.groupValues[1].lowercase(Locale.ROOT)
        val region = match.groupValues[2]
        return if (region.isEmpty()) language else "$language-${region.uppercase(Locale.ROOT)}"
    }

    // Files are often named `movie.English.srt`. ISO codes are all this needs to return, for the label and the charset.
    private fun fromEnglishName(name: String): String? = EnglishNames[name.lowercase(Locale.ROOT)]

    private val EnglishNames: Map<String, String> = listOf(
        "english" to "en", "french" to "fr", "spanish" to "es", "german" to "de", "italian" to "it",
        "portuguese" to "pt", "russian" to "ru", "arabic" to "ar", "hindi" to "hi", "urdu" to "ur",
        "chinese" to "zh", "japanese" to "ja", "korean" to "ko", "turkish" to "tr", "greek" to "el",
        "hebrew" to "he", "persian" to "fa", "polish" to "pl", "dutch" to "nl", "thai" to "th",
        "vietnamese" to "vi", "indonesian" to "id", "ukrainian" to "uk", "bengali" to "bn"
    ).toMap()

    /** What a picker row calls it: "English" when the file says, or the extension when it doesn't. */
    fun labelFor(sidecar: Sidecar, inLocale: Locale): String {
        val language = sidecar.language ?: return sidecar.extension.uppercase(Locale.ROOT)
        val name = Locale.forLanguageTag(language).getDisplayName(inLocale)
        return name.ifEmpty { language }.replaceFirstChar { it.titlecase(inLocale) }
    }
}

/**
 * Subtitles written before UTF-8 won are still everywhere, and the player reads anything that isn't UTF-8 as if it
 * were, which is where the garbled accents come from. So a file in an older encoding gets rewritten to a copy the
 * player reads properly. The bytes don't say which encoding it is, but the language usually narrows it to one.
 */
internal object SubtitleEncoding {
    /** Null when the bytes are fine as they are, or the charset to read them with when they aren't. */
    fun needsConverting(bytes: ByteArray, language: String?, deviceLanguage: String): Charset? {
        if (hasUnicodeMark(bytes) || isValidUtf8(bytes)) return null
        return charsetFor(language ?: deviceLanguage)
    }

    private fun hasUnicodeMark(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return true
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return true
        return bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    fun charsetFor(language: String): Charset {
        val base = language.substringBefore('-').lowercase(Locale.ROOT)
        val name = when (base) {
            "ru", "uk", "bg", "be", "sr", "mk" -> "windows-1251"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"
            "el" -> "windows-1253"
            "tr", "az" -> "windows-1254"
            "he", "iw", "yi" -> "windows-1255"
            "ar", "fa", "ur", "ps", "ku" -> "windows-1256"
            "lt", "lv", "et" -> "windows-1257"
            "vi" -> "windows-1258"
            "th" -> "windows-874"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            "zh" -> if (language.endsWith("TW", true) || language.endsWith("HK", true) || language.contains("Hant", true)) "Big5" else "GBK"
            else -> "windows-1252"
        }
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
    }
}

/** How a time reads, in the player's controls. */
internal fun formatVideoTime(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) + 500) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
