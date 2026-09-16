package com.holopengin.instantjpdict

import com.google.gson.Gson
import com.holopengin.instantjpdict.data.DictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #67: what the popup's bookmark toggle actually saves.
 *
 * The view is not unit-testable; [OcrOverlayStateController.formatDictionaryResults]
 * is the seam that decides the headword identity and the definition snapshot, so
 * the toggle's payload is pinned here.
 */
class BookmarkCandidateTest {

    private fun entry(
        kanji: String,
        reading: String,
        dictId: Int,
        definitions: String,
        onyomi: String? = null,
        kunyomi: String? = null
    ) = DictionaryEntry(
        kanji = kanji,
        reading = reading,
        definitions = definitions,
        rules = "",
        popularity = 0,
        dictionaryId = dictId,
        onyomi = onyomi,
        kunyomi = kunyomi
    )

    @Test
    fun saves_the_first_rendered_headword_and_its_dictionary() {
        val out = OcrOverlayStateController().formatDictionaryResults(
            listOf(TermMatch("食べる", listOf(entry("食べる", "たべる", 1, "[\"to eat\"]")))),
            Gson(),
            mapOf(1 to "JMdict")
        )
        val candidate = out.single().bookmark!!
        assertEquals("食べる", candidate.kanji)
        assertEquals("たべる", candidate.reading)
        assertEquals("JMdict", candidate.dictionaryName)
        assertEquals("to eat", candidate.definitionsText)
    }

    @Test
    fun the_same_headword_in_two_dictionaries_yields_distinct_keys() {
        val out = OcrOverlayStateController().formatDictionaryResults(
            listOf(
                TermMatch(
                    "君",
                    listOf(
                        entry("君", "きみ", 1, "[\"you\"]"),
                        entry("君", "きみ", 2, "[\"lord\"]", onyomi = "クン", kunyomi = "きみ")
                    )
                )
            ),
            Gson(),
            mapOf(1 to "JMdict", 2 to "KANJIDIC")
        )
        val jm = out.first { it.dictionaryName == "JMdict" }.bookmark!!
        val kd = out.first { it.dictionaryName == "KANJIDIC" }.bookmark!!
        assertEquals("君", jm.kanji)
        assertEquals("君", kd.kanji)
        assertNotEquals(jm.key, kd.key)
    }

    @Test
    fun a_block_whose_dictionary_has_no_name_has_no_bookmark_toggle() {
        val out = OcrOverlayStateController().formatDictionaryResults(
            listOf(TermMatch("食べる", listOf(entry("食べる", "たべる", 7, "[\"to eat\"]")))),
            Gson(),
            emptyMap()
        )
        assertNull(out.single().bookmark)
    }
}
