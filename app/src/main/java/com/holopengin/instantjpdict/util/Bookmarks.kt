package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.data.Bookmark
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import uniffi.nav_graph_core.definitionFormatPlain
import uniffi.nav_graph_core.definitionFormatPlainAll

/**
 * #67: the stable identity of a bookmarked headword — glyph, reading, and the
 * dictionary it came from.
 *
 * Deliberately not `DictionaryEntry.id` / `DictionaryMeta.id`: both are Room
 * `autoGenerate` keys, and the importer replaces a dictionary by deleting its
 * meta row and re-inserting everything for a bundled asset, or by writing a
 * second copy for a re-imported zip — either way the ids change. A bookmark
 * keyed on an id would orphan; keyed on the dictionary *name* it stays
 * attached across a replacement.
 */
data class BookmarkKey(
    val kanji: String,
    val reading: String,
    val dictionaryName: String
)

/**
 * Everything the popup needs to save a headword with no second database read:
 * the identity plus the snapshotted definition text.
 */
data class BookmarkCandidate(
    val kanji: String,
    val reading: String,
    val dictionaryName: String,
    val definitionsText: String
) {
    val key: BookmarkKey get() = BookmarkKey(kanji, reading, dictionaryName)
}

/** #67: viewer ordering. */
object BookmarkSort {
    /**
     * Default is insertion order (oldest first). [newestFirst] is the recency
     * option. `createdAt` ties (two saves in the same millisecond) break on the
     * row id descending, so the newest-first list is a true reverse of the
     * default and never reshuffles between reads.
     */
    fun apply(rows: List<Bookmark>, newestFirst: Boolean): List<Bookmark> =
        if (newestFirst) {
            rows.sortedWith(compareByDescending<Bookmark> { it.createdAt }.thenByDescending { it.id })
        } else {
            rows.sortedWith(compareBy<Bookmark> { it.createdAt }.thenBy { it.id })
        }
}

/**
 * #67: flatten a dictionary entry's `definitions` JSON into plain text for the
 * bookmark snapshot / viewer / CSV. The JSON is Yomitan structured content —
 * arrays of sense strings, sometimes objects (sc-bank/table forms) — so this
 * walks it generically rather than assuming one shape, and never emits JSON
 * punctuation that would be meaningless to a reader.
 *
 * A malformed blob falls back to the raw string rather than losing the senses.
 *
 * Since the definition-format swap this is a thin facade over the PC
 * `jpdict_core` implementation (`core/src/definition_format.rs`, exposed
 * through `nav_graph_core`'s UniFFI surface), so the flattening has one
 * source of truth. The public API is unchanged.
 */
object Definitions {
    /** One plain-text line per top-level sense; nested glosses join with ", ". */
    fun plain(definitionsJson: String): String =
        definitionFormatPlain(definitionsJson)

    /** Several definition blobs (multiple entries in one dictionary block) as one blob of lines. */
    fun plainAll(definitionsJsonRows: List<String>): String =
        definitionFormatPlainAll(definitionsJsonRows)
}

/**
 * #67: CSV serialisation for the bookmark viewer's export. Pure, so escaping
 * is unit-tested — definitions routinely contain commas, quotation marks and
 * newlines, which is exactly where a naive `joinToString(",")` corrupts a file.
 *
 * Columns: `kanji,reading,dictionary,definitions,added_at`. This is the
 * Anki-friendly shape: front glyph, reading, a readable definition, the source
 * dictionary and an ISO-8601 UTC timestamp.
 */
object BookmarkCsv {
    const val HEADER = "kanji,reading,dictionary,definitions,added_at"

    /** RFC 4180 escaping: quote a field containing `,`, `"`, CR or LF, and double embedded quotes. */
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    fun render(rows: List<Bookmark>): String = buildString {
        append(HEADER).append('\n')
        rows.forEach { row ->
            append(escape(row.kanji)).append(',')
            append(escape(row.reading)).append(',')
            append(escape(row.dictionaryName)).append(',')
            append(escape(row.definitionsText)).append(',')
            append(escape(timestamp(row.createdAt))).append('\n')
        }
    }

    /** ISO-8601 UTC — locale-independent, so the export does not follow the phone's locale. */
    fun timestamp(epochMillis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

    /** e.g. `instant-jpdict-bookmarks-20260916-123456.csv` (UTC). */
    fun fileName(epochMillis: Long): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMillis))
        return "instant-jpdict-bookmarks-$stamp.csv"
    }
}
