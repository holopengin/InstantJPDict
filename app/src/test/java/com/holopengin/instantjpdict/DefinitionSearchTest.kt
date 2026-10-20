package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.data.DictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #108: the EN→JP ranking is a pure function over database rows, so the rules
 * the issue asks for can be pinned without a database: exact glosses before
 * whole-word matches before substrings, earlier senses before later ones, and
 * rows that only matched JSON markup are discarded.
 */
class DefinitionSearchTest {

    private fun entry(
        id: Int,
        kanji: String,
        definitions: String,
        popularity: Int = 0,
        dictId: Int = 0,
    ) = DictionaryEntry(
        id = id,
        kanji = kanji,
        reading = kanji,
        definitions = definitions,
        rules = "",
        popularity = popularity,
        dictionaryId = dictId,
    )

    @Test
    fun anExactGlossBeatsAWholeWordAndAWholeWordBeatsASubstring() {
        val rows = listOf(
            entry(1, "偉い", """["great"]"""),
            entry(2, "食べる", """["to eat"]"""),
            entry(3, "食べ物", """["eat"]"""),
        )

        val out = DefinitionSearch.rank(rows, "eat")

        assertEquals(listOf("食べ物", "食べる", "偉い"), out.map { it.kanji })
    }

    @Test
    fun anEarlierSenseBeatsALaterOne() {
        val late = entry(1, "遅い", """["food","to eat"]""")
        val early = entry(2, "早い", """["to eat","food"]""")

        val out = DefinitionSearch.rank(listOf(late, early), "eat")

        assertEquals(listOf("早い", "遅い"), out.map { it.kanji })
    }

    @Test
    fun aMatchOnlyInJsonMarkupIsDiscarded() {
        // "span" is a tag value, not a gloss. The SQL-side substring scan can
        // find it; the ranking must not turn it into a result.
        val row = entry(1, "日本語", """[{"tag":"span","content":"日本語"}]""")

        assertTrue(DefinitionSearch.rank(listOf(row), "span").isEmpty())
    }

    @Test
    fun structuredJitendexGlossIsSearchableThroughItsPlainText() {
        val row = entry(
            1,
            "食べる",
            """[{"type":"structured-content","content":[{"tag":"span","content":"to eat"}]}]""",
        )

        assertEquals(listOf("食べる"), DefinitionSearch.rank(listOf(row), "eat").map { it.kanji })
    }

    @Test
    fun duplicateRowsCollapseToTheBestMatch() {
        val weak = entry(1, "食べる", """["great"]""")
        val strong = entry(1, "食べる", """["eat"]""")

        val out = DefinitionSearch.rank(listOf(weak, strong), "eat")

        assertEquals(1, out.size)
        assertEquals("""["eat"]""", out.single().definitions)
    }

    @Test
    fun resultsAreCappedAndBlankQueriesReturnNothing() {
        val rows = (1..10).map { entry(it, "語$it", """["eat"]""") }

        assertEquals(3, DefinitionSearch.rank(rows, "eat", limit = 3).size)
        assertTrue(DefinitionSearch.rank(rows, "   ").isEmpty())
        assertEquals("to eat", DefinitionSearch.normalizeQuery("  To   Eat  "))
        assertNull(DefinitionSearch.normalizeQuery(""))
    }

    @Test
    fun queryIsCappedAtTheDocumentedLiteralLength() {
        val long = "a".repeat(DefinitionSearch.QUERY_MAX + 20)
        assertEquals(DefinitionSearch.QUERY_MAX, DefinitionSearch.normalizeQuery(long)?.length)
    }

    @Test
    fun theCandidateCapStaysInsideTheManualLookupBudgetOnTheHost() {
        val rows = (1..DefinitionSearch.CANDIDATE_LIMIT).map {
            entry(it, "語$it", """["to eat number $it"]""")
        }

        val start = System.nanoTime()
        val out = DefinitionSearch.rank(rows, "eat")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("DefinitionSearch rank ${rows.size} rows: ${elapsedMs}ms")

        assertEquals(DefinitionSearch.RESULT_LIMIT, out.size)
        assertTrue("ranking ${rows.size} rows took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
