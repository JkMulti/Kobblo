package com.kobblo.gallery.file.explorer.gallery.search

import android.content.Context
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.explorer.search.SearchText
import com.kobblo.gallery.file.explorer.gallery.MediaFacts
import com.kobblo.gallery.file.explorer.gallery.PhotoDetails
import com.kobblo.gallery.file.explorer.gallery.ProbeFlags
import com.kobblo.gallery.file.explorer.gallery.isCameraFile
import com.kobblo.gallery.file.explorer.gallery.isGifFile
import com.kobblo.gallery.file.explorer.gallery.isMotionPhotoName
import com.kobblo.gallery.file.explorer.gallery.isPanorama
import com.kobblo.gallery.file.explorer.gallery.isRawFile
import com.kobblo.gallery.file.explorer.gallery.isScreenshotFile
import java.text.DateFormatSymbols
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The words for what a file is. */
internal enum class MediaWord { PHOTO, VIDEO, GIF, SCREENSHOT, RAW, PANORAMA, MOTION, FAVORITE, CAMERA }

/**
 * One word of a search. Every word has to fit, in any order, but a word only has to fit one way: "2023" is a year or
 * part of a name, and "paris" is a folder, a file or the place a photo was taken. So there's nothing to learn and no
 * filter to set first, and "beach video 2023" just works.
 */
internal class QueryWord(
    /** Lower case, to look for in names and folders. Null where the word was only ever a date or a size. */
    val text: String?,
    /** The same with accents gone, for place names and tags. */
    val folded: String? = text?.let(::foldAccents),
    val from: Long = Long.MIN_VALUE,
    val until: Long = Long.MAX_VALUE,
    val year: Int? = null,
    /** Which months it fits, as bits with January at the bottom. Zero means it says nothing about months. */
    val months: Int = 0,
    val kind: MediaWord? = null,
    val minBytes: Long = 0L,
    val maxBytes: Long = Long.MAX_VALUE
) {
    val hasRange: Boolean get() = from != Long.MIN_VALUE || until != Long.MAX_VALUE
    val hasSize: Boolean get() = minBytes != 0L || maxBytes != Long.MAX_VALUE
}

internal class MediaQuery(val words: List<QueryWord>) {
    val isBlank: Boolean get() = words.isEmpty()

    companion object {
        private const val MB = 1L shl 20
        private const val MaxWords = 24
        private const val LargeBytes = 10 * MB

        /** The most words a date phrase is read across, however long a translation's is. */
        private const val MaxPhraseWords = 4

        private val KindWords = mapOf(
            "photo" to MediaWord.PHOTO, "photos" to MediaWord.PHOTO,
            "picture" to MediaWord.PHOTO, "pictures" to MediaWord.PHOTO,
            "image" to MediaWord.PHOTO, "images" to MediaWord.PHOTO,
            "video" to MediaWord.VIDEO, "videos" to MediaWord.VIDEO, "movie" to MediaWord.VIDEO, "movies" to MediaWord.VIDEO,
            "gif" to MediaWord.GIF, "gifs" to MediaWord.GIF,
            "screenshot" to MediaWord.SCREENSHOT, "screenshots" to MediaWord.SCREENSHOT,
            "raw" to MediaWord.RAW,
            "panorama" to MediaWord.PANORAMA, "panoramas" to MediaWord.PANORAMA, "pano" to MediaWord.PANORAMA,
            "motion" to MediaWord.MOTION,
            "favorite" to MediaWord.FAVORITE, "favorites" to MediaWord.FAVORITE,
            "favourite" to MediaWord.FAVORITE, "favourites" to MediaWord.FAVORITE, "starred" to MediaWord.FAVORITE,
            "camera" to MediaWord.CAMERA
        )

        private val Seasons = mapOf(
            "spring" to Season.SPRING,
            "summer" to Season.SUMMER,
            "autumn" to Season.AUTUMN,
            "fall" to Season.AUTUMN,
            "winter" to Season.WINTER
        )

        private val LargeWords = setOf("large", "big")

        private val Dates = mapOf(
            "today" to DateWord.TODAY,
            "yesterday" to DateWord.YESTERDAY,
            "this week" to DateWord.THIS_WEEK,
            "last week" to DateWord.LAST_WEEK,
            "this month" to DateWord.THIS_MONTH,
            "last month" to DateWord.LAST_MONTH,
            "this year" to DateWord.THIS_YEAR,
            "last year" to DateWord.LAST_YEAR,
            "this spring" to DateWord.THIS_SPRING,
            "last spring" to DateWord.LAST_SPRING,
            "this summer" to DateWord.THIS_SUMMER,
            "last summer" to DateWord.LAST_SUMMER,
            "this autumn" to DateWord.THIS_AUTUMN,
            "last autumn" to DateWord.LAST_AUTUMN,
            "this fall" to DateWord.THIS_AUTUMN,
            "last fall" to DateWord.LAST_AUTUMN,
            "this winter" to DateWord.THIS_WINTER,
            "last winter" to DateWord.LAST_WINTER
        )

        private val SizeBefore = Regex("""^([<>])(\d+(?:\.\d+)?)(kb|mb|gb)$""")
        private val SizeAfter = Regex("""^(\d+(?:\.\d+)?)(kb|mb|gb)\+$""")

        /**
         * Reads what someone typed. English words always work, and so do month names in their own language and the
         * words of the app's language in [local].
         */
        fun parse(
            raw: String,
            nowMillis: Long = System.currentTimeMillis(),
            locale: Locale = Locale.getDefault(),
            zone: TimeZone = TimeZone.getDefault(),
            local: SearchWords = SearchWords.None
        ): MediaQuery {
            val tokens = raw.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().take(MaxWords)
            val months = monthWords(locale)
            val dates = if (local.dates.isEmpty()) Dates else Dates + local.dates
            // Names of several words in the app's language, such as "capturas de tela", read as one like a date.
            val namePhrases = (local.kinds.keys + local.seasons.keys + local.large).filter { ' ' in it }
            val longest = (dates.keys + namePhrases).maxOf { phrase -> phrase.count { it == ' ' } + 1 }.coerceAtMost(MaxPhraseWords)
            val words = ArrayList<QueryWord>(tokens.size)
            var at = 0
            while (at < tokens.size) {
                // The longest phrase that fits wins, so "last week" is read as one date rather than two words.
                var length = minOf(longest, tokens.size - at)
                var matched: QueryWord? = null
                while (length > 0 && matched == null) {
                    val phrase = if (length == 1) tokens[at] else tokens.subList(at, at + length).joinToString(" ")
                    matched = dates[phrase]?.let { dateWord(phrase, it, nowMillis, zone) }
                        ?: if (length > 1) phraseWord(phrase, local) else null
                    if (matched == null) length--
                }
                if (matched != null) {
                    words.add(matched)
                    at += length
                    continue
                }
                words.add(wordFor(tokens[at], months, local))
                at++
            }
            return MediaQuery(words)
        }

        /**
         * A kind, season or size named in several words of the app's language. Read word by word its small words
         * ("de", "d'") would match nothing, and every word has to fit, so the search would find nothing at all.
         */
        private fun phraseWord(phrase: String, local: SearchWords): QueryWord? {
            local.kinds[phrase]?.let { return QueryWord(null, kind = it) }
            local.seasons[phrase]?.let { return QueryWord(null, months = it.months) }
            if (phrase in local.large) return QueryWord(null, minBytes = LargeBytes)
            return null
        }

        private fun wordFor(token: String, months: Map<String, Int>, local: SearchWords): QueryWord {
            token.toIntOrNull()?.takeIf { token.length == 4 && it in 1900..2100 }?.let { return QueryWord(token, year = it) }
            months[token]?.let { return QueryWord(token, months = 1 shl it) }
            (Seasons[token] ?: local.seasons[token])?.let { return QueryWord(token, months = it.months) }
            (KindWords[token] ?: local.kinds[token])?.let { return QueryWord(token, kind = it) }
            if (token in LargeWords || token in local.large) return QueryWord(token, minBytes = LargeBytes)
            SizeBefore.find(token)?.let { match ->
                val bytes = bytesOf(match.groupValues[2], match.groupValues[3])
                return if (match.groupValues[1] == ">") QueryWord(null, minBytes = bytes) else QueryWord(null, maxBytes = bytes)
            }
            SizeAfter.find(token)?.let { match -> return QueryWord(null, minBytes = bytesOf(match.groupValues[1], match.groupValues[2])) }
            return QueryWord(token)
        }

        private fun bytesOf(number: String, unit: String): Long {
            val scale = when (unit) {
                "kb" -> 1L shl 10
                "mb" -> MB
                else -> 1L shl 30
            }
            return (number.toDouble() * scale).toLong()
        }

        private fun monthWords(locale: Locale): Map<String, Int> {
            val found = HashMap<String, Int>()
            for (symbols in listOf(DateFormatSymbols(Locale.ENGLISH), DateFormatSymbols(locale))) {
                symbols.months.forEachIndexed { month, name -> if (name.isNotEmpty()) found[name.lowercase(Locale.ROOT)] = month }
                symbols.shortMonths.forEachIndexed { month, name ->
                    if (name.isNotEmpty()) found[name.lowercase(Locale.ROOT).trimEnd('.')] = month
                }
            }
            // "may" reads the same either way, and "mar" or "dec" as part of a name still match as text.
            return found
        }

        private fun dateWord(phrase: String, date: DateWord, nowMillis: Long, zone: TimeZone): QueryWord {
            val calendar = Calendar.getInstance(zone, Locale.ROOT)
            calendar.timeInMillis = nowMillis
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            date.season?.let { return season(calendar, it, last = date.back > 0) }
            when (date.unit) {
                Calendar.WEEK_OF_YEAR -> calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                Calendar.MONTH -> calendar.set(Calendar.DAY_OF_MONTH, 1)
                Calendar.YEAR -> calendar.set(Calendar.DAY_OF_YEAR, 1)
            }
            calendar.add(date.unit, -date.back)
            val from = calendar.timeInMillis
            calendar.add(date.unit, 1)
            return QueryWord(phrase.takeUnless { ' ' in it }, from = from, until = calendar.timeInMillis)
        }

        /**
         * The months of a season: the last one that has finished, or the one this year. Seasons are the northern
         * hemisphere's here, which is a limit of saying it in one word rather than something a setting should fix.
         */
        private fun season(calendar: Calendar, season: Season, last: Boolean): QueryWord {
            val thisYear = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val firstMonth = season.firstMonth
            val isWinter = season == Season.WINTER
            // Three months from its first, and a winter runs into the next year.
            val year = when {
                !last -> if (isWinter && month <= Calendar.FEBRUARY) thisYear - 1 else thisYear
                isWinter -> if (month == Calendar.DECEMBER) thisYear - 1 else thisYear - 2 + (if (month <= Calendar.FEBRUARY) 0 else 1)
                month >= firstMonth + 3 -> thisYear
                else -> thisYear - 1
            }
            calendar.set(year, firstMonth, 1)
            val from = calendar.timeInMillis
            calendar.add(Calendar.MONTH, 3)
            return QueryWord(null, from = from, until = calendar.timeInMillis)
        }
    }
}

/** A season, by the month it starts in. */
internal enum class Season(val firstMonth: Int) {
    SPRING(Calendar.MARCH),
    SUMMER(Calendar.JUNE),
    AUTUMN(Calendar.SEPTEMBER),
    WINTER(Calendar.DECEMBER);

    /** Its three months, as bits with January at the bottom. */
    val months: Int get() = (0 until 3).fold(0) { bits, step -> bits or (1 shl ((firstMonth + step) % 12)) }
}

/** A stretch of time said in words: a day, week, month or year counted back from this one, or a season. */
internal enum class DateWord(val unit: Int = 0, val back: Int = 0, val season: Season? = null) {
    TODAY(Calendar.DAY_OF_MONTH, 0),
    YESTERDAY(Calendar.DAY_OF_MONTH, 1),
    THIS_WEEK(Calendar.WEEK_OF_YEAR, 0),
    LAST_WEEK(Calendar.WEEK_OF_YEAR, 1),
    THIS_MONTH(Calendar.MONTH, 0),
    LAST_MONTH(Calendar.MONTH, 1),
    THIS_YEAR(Calendar.YEAR, 0),
    LAST_YEAR(Calendar.YEAR, 1),
    THIS_SPRING(season = Season.SPRING),
    LAST_SPRING(back = 1, season = Season.SPRING),
    THIS_SUMMER(season = Season.SUMMER),
    LAST_SUMMER(back = 1, season = Season.SUMMER),
    THIS_AUTUMN(season = Season.AUTUMN),
    LAST_AUTUMN(back = 1, season = Season.AUTUMN),
    THIS_WINTER(season = Season.WINTER),
    LAST_WINTER(back = 1, season = Season.WINTER)
}

/**
 * The search words of the app's own language, beside the English ones that always work. Every key is lower case with
 * single spaces, and comes with and without its accents, so "ete" finds what "été" does.
 */
internal class SearchWords(
    val kinds: Map<String, MediaWord> = emptyMap(),
    val seasons: Map<String, Season> = emptyMap(),
    val large: Set<String> = emptySet(),
    val dates: Map<String, DateWord> = emptyMap()
) {
    companion object {
        val None = SearchWords()

        private val Whitespace = Regex("\\s+")

        fun from(context: Context): SearchWords {
            val resources = context.resources
            return read { resources.getString(it) }
        }

        /** Builds the words from each string's comma-separated synonyms, which [text] looks up by resource. */
        fun read(text: (Int) -> String): SearchWords {
            fun words(id: Int): List<String> = text(id).split(',')
                .map { it.trim().lowercase(Locale.ROOT).replace(Whitespace, " ") }
                .filter { it.isNotEmpty() }
                .flatMap { listOf(it, foldAccents(it)) }
                .distinct()

            fun <T> table(vararg ideas: Pair<Int, T>): Map<String, T> {
                val found = HashMap<String, T>()
                for ((id, value) in ideas) for (word in words(id)) found.putIfAbsent(word, value)
                return found
            }

            return SearchWords(
                kinds = table(
                    R.string.media_search_words_photo to MediaWord.PHOTO,
                    R.string.media_search_words_video to MediaWord.VIDEO,
                    R.string.media_search_words_gif to MediaWord.GIF,
                    R.string.media_search_words_screenshot to MediaWord.SCREENSHOT,
                    R.string.media_search_words_raw to MediaWord.RAW,
                    R.string.media_search_words_panorama to MediaWord.PANORAMA,
                    R.string.media_search_words_motion to MediaWord.MOTION,
                    R.string.media_search_words_favorite to MediaWord.FAVORITE,
                    R.string.media_search_words_camera to MediaWord.CAMERA
                ),
                seasons = table(
                    R.string.media_search_words_spring to Season.SPRING,
                    R.string.media_search_words_summer to Season.SUMMER,
                    R.string.media_search_words_autumn to Season.AUTUMN,
                    R.string.media_search_words_winter to Season.WINTER
                ),
                large = words(R.string.media_search_words_large).toSet(),
                dates = table(
                    R.string.media_search_words_today to DateWord.TODAY,
                    R.string.media_search_words_yesterday to DateWord.YESTERDAY,
                    R.string.media_search_words_this_week to DateWord.THIS_WEEK,
                    R.string.media_search_words_last_week to DateWord.LAST_WEEK,
                    R.string.media_search_words_this_month to DateWord.THIS_MONTH,
                    R.string.media_search_words_last_month to DateWord.LAST_MONTH,
                    R.string.media_search_words_this_year to DateWord.THIS_YEAR,
                    R.string.media_search_words_last_year to DateWord.LAST_YEAR,
                    R.string.media_search_words_this_spring to DateWord.THIS_SPRING,
                    R.string.media_search_words_last_spring to DateWord.LAST_SPRING,
                    R.string.media_search_words_this_summer to DateWord.THIS_SUMMER,
                    R.string.media_search_words_last_summer to DateWord.LAST_SUMMER,
                    R.string.media_search_words_this_autumn to DateWord.THIS_AUTUMN,
                    R.string.media_search_words_last_autumn to DateWord.LAST_AUTUMN,
                    R.string.media_search_words_this_winter to DateWord.THIS_WINTER,
                    R.string.media_search_words_last_winter to DateWord.LAST_WINTER
                )
            )
        }
    }
}

internal fun foldAccents(text: String): String {
    var ascii = true
    for (c in text) if (c.code >= 128) {
        ascii = false
        break
    }
    if (ascii) return text
    return Normalizer.normalize(text, Normalizer.Form.NFD).replace(CombiningMarks, "")
}

private val CombiningMarks = Regex("\\p{Mn}+")

/**
 * The month and year of a moment. The feed runs in date order, so nearly every file falls in the same month as the
 * last one, and a calendar only has to work once each time it crosses into a new month.
 */
private class MonthCursor(zone: TimeZone) {
    private val calendar = Calendar.getInstance(zone, Locale.ROOT)
    private var from = Long.MAX_VALUE
    private var until = Long.MIN_VALUE
    var year = 0
        private set
    var month = 0
        private set

    fun moveTo(moment: Long) {
        if (moment in from until until) return
        calendar.timeInMillis = moment
        year = calendar.get(Calendar.YEAR)
        month = calendar.get(Calendar.MONTH)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        from = calendar.timeInMillis
        calendar.add(Calendar.MONTH, 1)
        until = calendar.timeInMillis
    }
}

/** Everything a search needs to read about the library. */
internal class SearchLibrary(
    val files: List<FileItem>,
    val facts: Map<Int, MediaFacts> = emptyMap(),
    val flags: Map<Int, Int> = emptyMap(),
    val details: Map<Int, PhotoDetails> = emptyMap(),
    /** What names and folders are searched below, so "storage" doesn't find everything. */
    val root: String = "/storage/emulated/0",
    val placeNames: (Int) -> List<String> = { emptyList() },
    val zone: TimeZone = TimeZone.getDefault()
)

/**
 * The files that fit, in the order given. Locked files are never here at all: finding one by the camera that took
 * it would say something about what the lock is keeping private.
 *
 * @param checkpoint called every so often, so a new keystroke can stop this.
 */
internal fun searchMedia(query: MediaQuery, library: SearchLibrary, checkpoint: () -> Unit = {}): List<FileItem> {
    if (query.isBlank) return emptyList()
    val words = query.words
    val cursor = MonthCursor(library.zone)
    val needsMonth = words.any { it.year != null || it.months != 0 }
    val results = ArrayList<FileItem>()

    // Photos share their details, so each distinct one is only asked about once.
    val detailMatches = java.util.IdentityHashMap<PhotoDetails, Int>()
    fun detailMask(details: PhotoDetails): Int = detailMatches.getOrPut(details) {
        var mask = 0
        val places = if (details.place >= 0) library.placeNames(details.place) else emptyList()
        words.forEachIndexed { index, word ->
            val text = word.folded ?: return@forEachIndexed
            val hit = details.camera?.let { SearchText.containsIgnoringCase(foldAccents(it), text) } == true ||
                places.any { SearchText.containsIgnoringCase(foldAccents(it), text) } ||
                details.tags.any { SearchText.containsIgnoringCase(foldAccents(it), text) }
            if (hit && index < 32) mask = mask or (1 shl index)
        }
        mask
    }

    for ((row, file) in library.files.withIndex()) {
        if (row and 0x7FF == 0) checkpoint()
        if (file.isLocked) continue
        if (needsMonth) cursor.moveTo(file.lastModified)
        val nameFrom = if (file.path.startsWith(library.root)) library.root.length else 0
        val details = library.details[file.id]
        var fits = true
        for (index in words.indices) {
            val word = words[index]
            val byText = word.text != null && SearchText.containsIgnoringCase(file.path, word.text, nameFrom)
            if (byText) continue
            if (word.text != null && details != null && detailMask(details) and (1 shl index) != 0) continue
            if (word.hasRange) {
                if (file.lastModified >= word.from && file.lastModified < word.until) continue
            }
            if (word.year != null && cursor.year == word.year) continue
            if (word.months != 0 && (word.months shr cursor.month) and 1 == 1) continue
            if (word.kind != null && isKind(word.kind, file, library)) continue
            if (word.hasSize && file.sizeBytes >= word.minBytes && file.sizeBytes < word.maxBytes) continue
            fits = false
            break
        }
        if (fits) results.add(file)
    }
    return results
}

private fun isKind(kind: MediaWord, file: FileItem, library: SearchLibrary): Boolean = when (kind) {
    MediaWord.PHOTO -> file.type == "image"
    MediaWord.VIDEO -> file.type == "video"
    MediaWord.GIF -> file.type == "image" && isGifFile(file.name, library.facts[file.id]?.mime)
    MediaWord.SCREENSHOT -> isScreenshotFile(file.path, file.name)
    MediaWord.RAW -> file.type == "image" && isRawFile(file.name, library.facts[file.id]?.mime)
    MediaWord.PANORAMA -> file.type == "image" && !isScreenshotFile(file.path, file.name) && library.facts[file.id].let { facts ->
        isPanorama(facts?.width ?: 0, facts?.height ?: 0, library.flags[file.id] ?: 0)
    }
    MediaWord.MOTION -> file.type == "image" && ((library.flags[file.id] ?: 0) and ProbeFlags.MOTION_PHOTO != 0 || isMotionPhotoName(file.name))
    MediaWord.FAVORITE -> file.isPinned
    MediaWord.CAMERA -> isCameraFile(file.path, file.name)
}

/** Ideas for what to search for, from what's really in this library. Each is a word that works when typed. */
internal class SearchHints(
    val kinds: List<String>,
    val years: List<String>,
    val cameras: List<String>,
    val places: List<String>,
    val tags: List<String>
)

internal fun buildSearchHints(library: SearchLibrary, kindNames: Map<MediaWord, String>, firstPlaceName: (Int) -> String?, limit: Int = 6): SearchHints {
    val cursor = MonthCursor(library.zone)
    val years = HashSet<Int>()
    val kinds = HashSet<MediaWord>()
    val cameras = HashMap<String, Int>()
    val places = HashMap<Int, Int>()
    val tags = HashMap<String, Int>()
    for (file in library.files) {
        if (file.isLocked) continue
        if (file.lastModified > 0L) {
            cursor.moveTo(file.lastModified)
            years.add(cursor.year)
        }
        if (file.type == "video") kinds.add(MediaWord.VIDEO)
        if (file.isPinned) kinds.add(MediaWord.FAVORITE)
        if (MediaWord.SCREENSHOT !in kinds && isScreenshotFile(file.path, file.name)) kinds.add(MediaWord.SCREENSHOT)
        if (file.type == "image") {
            if (MediaWord.GIF !in kinds && isGifFile(file.name, null)) kinds.add(MediaWord.GIF)
            if (MediaWord.RAW !in kinds && isRawFile(file.name, null)) kinds.add(MediaWord.RAW)
        }
        val details = library.details[file.id] ?: continue
        details.camera?.let { cameras[it] = (cameras[it] ?: 0) + 1 }
        if (details.place >= 0) places[details.place] = (places[details.place] ?: 0) + 1
        for (tag in details.tags) tags[tag] = (tags[tag] ?: 0) + 1
    }
    fun <T> top(counts: Map<T, Int>) = counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    return SearchHints(
        kinds = kindNames.filterKeys { it in kinds }.values.toList(),
        years = years.sortedDescending().map { it.toString() },
        cameras = top(cameras),
        places = top(places).mapNotNull(firstPlaceName),
        tags = top(tags)
    )
}
