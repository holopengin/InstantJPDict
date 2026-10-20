package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.util.Definitions
import java.util.Locale

/**
 * #108: ranking for English-to-Japanese lookup.
 *
 * The database query is deliberately a bounded scan: `dictionary.definitions`
 * is a JSON text column, so there is no useful index for an arbitrary substring
 * and the query has to inspect rows. It uses `instr` and `replace`, not JSON1,
 * because Android's SQLite does not expose JSON1 on every release the app
 * supports. The Android DAO caps the candidates by dictionary priority and
 * popularity; this object then filters out markup-only hits, scores the real
 * gloss matches, and keeps the best [RESULT_LIMIT].
 *
 * Ranking has three signals, strongest first:
 *  - exact gloss match (a whole sense equals the query) beats a whole-word hit,
 *    which beats a bare substring;
 *  - earlier senses rank above later ones;
 *  - earlier text within a sense breaks ties, then popularity.
 *
 * [Definitions.plain] flattens a row's structured content. For JMdict that is
 * one line per sense, so [Hit.senseIndex] is a real sense number. Jitendex packs
 * all senses into one row, so its lines are a single flattened string and the
 * offset ratio is the stand-in for sense position. The ordering is still
 * monotonic with the rendered gloss order, which is what the screen needs.
 */
object DefinitionSearch {

    /**
     * Rows fetched from SQLite per query. A common English word can match
     * thousands of entries; ordering the scan by dictionary priority and
     * popularity keeps the useful candidates inside this cap while bounding the
     * time spent parsing definitions. Rare words match far fewer rows.
     */
    const val CANDIDATE_LIMIT = 2_000

    /** Cards rendered for one query. */
    const val RESULT_LIMIT = 50

    /** Longest literal query accepted by the manual screen. */
    const val QUERY_MAX = 64

    internal data class Hit(
        val entry: DictionaryEntry,
        val exactness: Int,
        val senseIndex: Int,
        val offsetRatio: Double,
    )

    private val WHITESPACE = Regex("\\s+")

    /** Trim, case-fold, collapse whitespace, and cap the literal query. */
    fun normalizeQuery(text: String): String? {
        val query = text.trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .take(QUERY_MAX)
        return query.ifEmpty { null }
    }

    /**
     * Filter [entries] to rows whose readable definition contains [query], then
     * return the best [limit] entries in display order.
     */
    fun rank(
        entries: List<DictionaryEntry>,
        query: String,
        limit: Int = RESULT_LIMIT,
    ): List<DictionaryEntry> {
        val needle = normalizeQuery(query) ?: return emptyList()
        if (entries.isEmpty()) return emptyList()

        val bestPerEntry = LinkedHashMap<String, Hit>()
        for (entry in entries) {
            val hit = hit(entry, needle) ?: continue
            val key = if (entry.id != 0) {
                "id:${entry.id}"
            } else {
                "row:${entry.kanji}\u0000${entry.reading}\u0000${entry.dictionaryId}"
            }
            val current = bestPerEntry[key]
            if (current == null || COMPARATOR.compare(hit, current) < 0) {
                bestPerEntry[key] = hit
            }
        }

        return bestPerEntry.values
            .sortedWith(COMPARATOR)
            .take(limit)
            .map { it.entry }
    }

    internal fun hit(entry: DictionaryEntry, normalizedQuery: String): Hit? {
        val plain = Definitions.plain(entry.definitions)
        if (plain.isEmpty()) return null
        val lower = plain.lowercase(Locale.ROOT)
        val index = lower.indexOf(normalizedQuery)
        if (index < 0) return null

        var senseIndex = 0
        for (i in 0 until index) {
            if (lower[i] == '\n') senseIndex++
        }

        val exactness = when {
            plain.lineSequence().any { it.trim().equals(normalizedQuery, ignoreCase = true) } -> 3
            isWholeWord(lower, index, normalizedQuery.length) -> 2
            else -> 1
        }
        return Hit(
            entry = entry,
            exactness = exactness,
            senseIndex = senseIndex,
            offsetRatio = index.toDouble() / lower.length.coerceAtLeast(1),
        )
    }

    private fun isWholeWord(text: String, start: Int, length: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(start + length)
        return (before == null || !before.isLetterOrDigit()) &&
            (after == null || !after.isLetterOrDigit())
    }

    private val COMPARATOR: Comparator<Hit> =
        compareByDescending<Hit> { it.exactness }
            .thenBy { it.senseIndex }
            .thenBy { it.offsetRatio }
            .thenByDescending { it.entry.popularity }
}
