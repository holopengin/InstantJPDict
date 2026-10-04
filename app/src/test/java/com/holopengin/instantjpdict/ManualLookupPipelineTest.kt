package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.holopengin.instantjpdict.data.DictionaryEntry
import com.holopengin.instantjpdict.util.Deinflector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #89: the manual lookup runs the SAME chain as a tap.
 *
 * [OcrOverlayStateController.lookupText] and [OcrOverlayStateController.lookup]
 * differ only in where `followingText` comes from — the tap slices the page from
 * the tapped index, the box takes the user's string — and everything after that
 * is one implementation. These tests pin that the shared chain is reachable from
 * a bare string (no OCR geometry, no tap state) and that it produces the entries
 * a tap on the same word would, including the deinflection chain, the per-entry
 * dictionary split and the per-kanji second pass.
 *
 * The chain is exercised through a tiny in-memory [DictionaryProvider], so the
 * test needs no database — the same seam [DeinflectionChainTest] /
 * [FormatPerDictTest] use for the individual stages.
 */
class ManualLookupPipelineTest {

    private fun testDeinflector() = Deinflector(
        """
        {
          "past": [{"kanaIn": "た", "kanaOut": "る", "rulesIn": [], "rulesOut": ["v1"]}]
        }
        """.trimIndent().reader()
    )

    private fun entry(
        kanji: String,
        reading: String,
        rules: String = "v1",
        definitions: String = """["to eat"]""",
        dictId: Int = 0,
        onyomi: String? = null,
        kunyomi: String? = null,
    ) = DictionaryEntry(
        kanji = kanji,
        reading = reading,
        definitions = definitions,
        rules = rules,
        popularity = 0,
        dictionaryId = dictId,
        onyomi = onyomi,
        kunyomi = kunyomi,
    )

    private class FakeProvider(private val rows: List<DictionaryEntry>) : DictionaryProvider {
        override suspend fun findByTexts(texts: List<String>): List<DictionaryEntry> =
            rows.filter { it.kanji in texts || it.reading in texts }

        override suspend fun dictionaryNames(): Map<Int, String> = mapOf(0 to "JMdict", 2 to "KANJIDIC")
    }

    private fun controller(rows: List<DictionaryEntry>): OcrOverlayStateController =
        OcrOverlayStateController().apply {
            deinflector = testDeinflector()
            dictionaryProvider = FakeProvider(rows)
            gson = Gson()
        }

    @Test
    fun lookupTextResolvesAWordThroughTheSharedChain() = runBlocking {
        val c = controller(listOf(entry("食べる", "たべる")))

        val out = c.lookupText("食べる")

        assertEquals(listOf("食べる"), out.map { it.term })
        assertEquals("JMdict", out[0].dictionaryName)
    }

    @Test
    fun lookupText_deinflectsExactlyLikeTheTapPathWould() = runBlocking {
        // 食べた is in no dictionary; the chain has to reach 食べる through the
        // "past" rule and carry the chain for the popup's "食べた → 食べる" row.
        val c = controller(listOf(entry("食べる", "たべる")))

        val out = c.lookupText("食べた")

        val hit = out.first { it.term == "食べる" }
        assertEquals("past", hit.deinflection?.steps?.single())
        assertEquals("食べた", hit.deinflection?.surface)
        // The surface form itself never matched, so it is not rendered.
        assertTrue(out.none { it.term == "食べた" })
    }

    @Test
    fun lookupText_searchesTheLongestMatchStartingAtTheFirstCharacter() = runBlocking {
        // "食べる。" — the longest dictionary match beginning at 食 is 食べる, not
        // the trailing 。; the chain must find it the way a tap on 食 does.
        val c = controller(listOf(entry("食べる", "たべる")))

        val out = c.lookupText("食べる。")

        assertEquals(listOf("食べる"), out.map { it.term })
    }

    @Test
    fun lookupText_appendsThePerKanjiSecondPass() = runBlocking {
        // #43-adjacent: a matched term's own kanji are looked up individually and
        // the KANJIDIC row (bearing on/kun) is appended after the term. Pin that
        // the manual path runs that pass too, not just the first findByTexts.
        val c = controller(
            listOf(
                entry("食", "たべる", definitions = """["eat"]"""),
                entry("食", "ショク", dictId = 2, onyomi = "ショク", kunyomi = "たべる"),
            )
        )

        val out = c.lookupText("食")

        assertTrue("the KANJIDIC kanji row is appended", out.any { it.dictionaryName == "KANJIDIC" })
    }

    @Test
    fun lookupText_blankOrUnpreparedIsEmptyNotAnEmptyEntryList() = runBlocking {
        val prepared = controller(listOf(entry("食べる", "たべる")))
        assertTrue(prepared.lookupText("   ").isEmpty())
        assertTrue(prepared.lookupText("").isEmpty())

        // No provider/gson/deinflector wired: the manual screen must not crash,
        // it must simply find nothing.
        val bare = OcrOverlayStateController()
        assertTrue(bare.lookupText("食べる").isEmpty())
    }

    @Test
    fun theTapPathIsTheSameChainFromAWindow() = runBlocking {
        // The strongest statement of "one chain": build a one-line page in the
        // controller, tap it, and assert the tap's entries equal lookupText's for
        // the window the tap would slice. If the two ever fork, this fails.
        val c = controller(listOf(entry("食べる", "たべる")))
        c.activeLineResults = mutableListOf(
            LineResult(
                text = "食べる",
                charBoxes = (0 until 3).map { JpDictRect(100 + it * 32, 100, 124 + it * 32, 124) },
                alternatives = "食べる".map { mutableListOf(it to 1f) },
            )
        )
        c.updateGlobalData()

        val tap = c.lookup(0, 0) ?: error("the tap found nothing")
        val manual = c.lookupText("食べる")

        assertEquals(tap.matches.map { it.term }, manual.map { it.term })
        assertEquals(tap.maxLen, 3)
    }

    @Test
    fun theInputScopeMirrorsTheTapsTwentyCharacterWindow() {
        // The tap window is `globalIdx .. globalIdx + 20`; the manual window is
        // the same length from the first non-whitespace character.
        assertEquals("食べる", OcrOverlayStateController.inputScope("食べる"))
        assertEquals("食べる", OcrOverlayStateController.inputScope("  食べる"))
        assertEquals("", OcrOverlayStateController.inputScope("   "))

        val twentyOne = "あ".repeat(21)
        val scope = OcrOverlayStateController.inputScope(twentyOne)
        assertEquals(OcrOverlayStateController.LOOKUP_WINDOW, scope.length)
        assertEquals("あ".repeat(20), scope)
    }
}
