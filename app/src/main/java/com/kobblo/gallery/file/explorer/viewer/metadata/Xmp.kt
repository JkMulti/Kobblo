package com.kobblo.gallery.file.explorer.viewer.metadata

import androidx.annotation.StringRes
import com.kobblo.gallery.file.explorer.R

/**
 * Reads the simple name and value pairs out of an XMP packet. XMP is RDF, but what a viewer wants from
 * it is flat, and a regular expression gets there without an XML parser that would object to the
 * malformed packets real cameras write.
 */
object Xmp {
    private val AttributeRegex = Regex("""\s([A-Za-z][\w-]*):([A-Za-z][\w-]*)\s*=\s*"([^"]*)"""")
    private val ElementRegex = Regex("""<([A-Za-z][\w-]*):([A-Za-z][\w-]*)(?:\s[^>]*)?>([^<]+)</\1:\2>""")
    private val ListItemRegex = Regex("""<rdf:li(?:\s[^>]*)?>([^<]*)</rdf:li>""")
    private val ListParentRegex = Regex("""<([A-Za-z][\w-]*):([A-Za-z][\w-]*)(?:\s[^>]*)?>\s*<rdf:(?:Alt|Seq|Bag)>(.*?)</rdf:(?:Alt|Seq|Bag)>""", RegexOption.DOT_MATCHES_ALL)

    /** Namespace declarations and RDF's own structure aren't anyone's information. */
    private val Structural = setOf("xmlns", "xml", "rdf", "x")

    /** Every value in the packet as `prefix:Name` to its text, in the order they appear. */
    fun properties(xmp: String): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        for (match in AttributeRegex.findAll(xmp)) {
            val prefix = match.groupValues[1]
            if (prefix !in Structural) found.putIfAbsent("$prefix:${match.groupValues[2]}", decode(match.groupValues[3]))
        }
        for (match in ElementRegex.findAll(xmp)) {
            val prefix = match.groupValues[1]
            val value = match.groupValues[3].trim()
            if (prefix !in Structural && value.isNotEmpty()) found.putIfAbsent("$prefix:${match.groupValues[2]}", decode(value))
        }
        for (match in ListParentRegex.findAll(xmp)) {
            val prefix = match.groupValues[1]
            if (prefix in Structural) continue
            val items = ListItemRegex.findAll(match.groupValues[3]).map { decode(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
            if (items.isNotEmpty()) found.putIfAbsent("$prefix:${match.groupValues[2]}", items.joinToString(", "))
        }
        return found.map { it.key to it.value }
    }

    /** The keywords someone tagged a photo with, one by one, which [properties] would run together. */
    fun keywords(xmp: String): List<String> {
        if (!xmp.contains("subject")) return emptyList()
        val found = ArrayList<String>()
        for (match in ListParentRegex.findAll(xmp)) {
            if (match.groupValues[2] != "subject") continue
            ListItemRegex.findAll(match.groupValues[3]).mapTo(found) { decode(it.groupValues[1].trim()) }
        }
        return found.filter { it.isNotEmpty() }
    }

    /** A single value by its local name, whichever prefix a writer chose for it. */
    fun value(xmp: String, name: String): String? {
        val attribute = Regex("""[\s:]$name\s*=\s*"([^"]*)"""").find(xmp)
        if (attribute != null) return decode(attribute.groupValues[1])
        return Regex("""<[A-Za-z][\w-]*:$name(?:\s[^>]*)?>([^<]+)<""").find(xmp)?.groupValues?.get(1)?.trim()?.let(::decode)
    }

    /**
     * What a writer escapes inside a value, and what each stands for. A line feed reads as a space,
     * because a value is shown on one line. The ampersand is undone last: undone first, it would turn
     * text that spells out an entity, such as `&amp;lt;`, into the character itself.
     */
    private val Escapes = listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#10;" to " ", "&amp;" to "&")

    private fun decode(text: String): String =
        Escapes.fold(text) { plain, (escape, character) -> plain.replace(escape, character) }
}

/** The press tags inside Photoshop's resource block: captions, keywords, bylines. */
object Iptc {
    /** A data set's standard English name, which [parse] returns it by, and what the details panel calls it. */
    private class DataSet(val name: String, @StringRes val label: Int)

    private val DataSets = mapOf(
        5 to DataSet("Title", R.string.viewer_pdf_detail_title),
        25 to DataSet("Keywords", R.string.media_iptc_keywords),
        40 to DataSet("Instructions", R.string.media_iptc_instructions),
        55 to DataSet("Date Created", R.string.media_iptc_date_created),
        60 to DataSet("Time Created", R.string.media_iptc_time_created),
        80 to DataSet("Author", R.string.viewer_pdf_detail_author),
        85 to DataSet("Author Title", R.string.media_iptc_author_title),
        90 to DataSet("City", R.string.media_iptc_city),
        92 to DataSet("Sublocation", R.string.media_iptc_sublocation),
        95 to DataSet("State", R.string.media_iptc_state),
        101 to DataSet("Country", R.string.media_iptc_country),
        103 to DataSet("Reference", R.string.media_iptc_reference),
        105 to DataSet("Headline", R.string.media_iptc_headline),
        110 to DataSet("Credit", R.string.media_iptc_credit),
        115 to DataSet("Source", R.string.media_iptc_source),
        116 to DataSet("Copyright", R.string.media_iptc_copyright),
        120 to DataSet("Caption", R.string.media_iptc_caption),
        122 to DataSet("Writer", R.string.media_iptc_writer)
    )

    /** What to show for a name [parse] returned, in the app's language. */
    @StringRes
    fun labelOf(name: String): Int? = DataSets.values.firstOrNull { it.name == name }?.label
    private const val IptcResourceId = 0x0404

    /** City, sublocation, state, country code and country. */
    private val LocationDataSets = setOf(90, 92, 95, 100, 101)

    /** The same resource block with every place name taken out, and everything else as it was. */
    fun withoutLocation(photoshop: ByteArray): ByteArray {
        val (dataAt, size) = findIptcResource(photoshop) ?: return photoshop
        val kept = java.io.ByteArrayOutputStream(size)
        var at = dataAt
        val end = dataAt + size
        while (at + 5 <= end) {
            if (photoshop[at] != 0x1C.toByte()) break
            val record = photoshop[at + 1].toInt() and 0xFF
            val dataSet = photoshop[at + 2].toInt() and 0xFF
            val length = ((photoshop[at + 3].toInt() and 0xFF) shl 8) or (photoshop[at + 4].toInt() and 0xFF)
            if (length and 0x8000 != 0 || at + 5 + length > end) break
            if (!(record == 2 && dataSet in LocationDataSets)) kept.write(photoshop, at, 5 + length)
            at += 5 + length
        }
        // Anything the walk couldn't read stays, since it can't be told apart from the rest.
        kept.write(photoshop, at, end - at)
        val data = kept.toByteArray()
        if (data.size == size) return photoshop

        val out = java.io.ByteArrayOutputStream(photoshop.size)
        out.write(photoshop, 0, dataAt - 4)
        out.write(data.size ushr 24)
        out.write(data.size ushr 16)
        out.write(data.size ushr 8)
        out.write(data.size)
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        val rest = dataAt + size + (size and 1)
        if (rest < photoshop.size) out.write(photoshop, rest, photoshop.size - rest)
        return out.toByteArray()
    }

    /** Reads every data set in [photoshop], the block after its "Photoshop 3.0" prefix. Repeats are joined. */
    fun parse(photoshop: ByteArray): List<Pair<String, String>> {
        val data = findIptcResource(photoshop) ?: return emptyList()
        val found = LinkedHashMap<String, MutableList<String>>()
        var at = data.first
        val end = data.first + data.second
        while (at + 5 <= end) {
            if (photoshop[at] != 0x1C.toByte()) break
            val record = photoshop[at + 1].toInt() and 0xFF
            val dataSet = photoshop[at + 2].toInt() and 0xFF
            val length = ((photoshop[at + 3].toInt() and 0xFF) shl 8) or (photoshop[at + 4].toInt() and 0xFF)
            // The top bit marks an extended length, which only huge blobs use and nobody displays.
            if (length and 0x8000 != 0) break
            at += 5
            if (at + length > end) break
            if (record == 2) {
                DataSets[dataSet]?.name?.let { name ->
                    found.getOrPut(name) { ArrayList() }.add(String(photoshop, at, length, Charsets.UTF_8).trim())
                }
            }
            at += length
        }
        return found.map { it.key to it.value.joinToString(", ") }
    }

    /** Walks the 8BIM resources to the IPTC one, returning where its data is and how long. */
    private fun findIptcResource(block: ByteArray): Pair<Int, Int>? {
        var at = 0
        while (at + 12 <= block.size) {
            if (String(block, at, 4, Charsets.ISO_8859_1) != "8BIM") return null
            val id = ((block[at + 4].toInt() and 0xFF) shl 8) or (block[at + 5].toInt() and 0xFF)
            val nameLength = block[at + 6].toInt() and 0xFF
            // The name is a Pascal string, padded so its length byte and text fill an even number.
            val nameSize = (nameLength + 2) and 1.inv()
            val sizeAt = at + 6 + nameSize
            if (sizeAt + 4 > block.size) return null
            val size = ((block[sizeAt].toInt() and 0xFF) shl 24) or ((block[sizeAt + 1].toInt() and 0xFF) shl 16) or
                ((block[sizeAt + 2].toInt() and 0xFF) shl 8) or (block[sizeAt + 3].toInt() and 0xFF)
            if (size < 0) return null
            val dataAt = sizeAt + 4
            if (id == IptcResourceId) return dataAt to minOf(size, block.size - dataAt)
            at = dataAt + size + (size and 1)
        }
        return null
    }
}
