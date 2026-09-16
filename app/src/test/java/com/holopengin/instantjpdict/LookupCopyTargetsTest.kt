package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DeinflectionChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #66: the two strings the lookup popup offers to copy. The seam is
 * [LookupCopyTargets.targets] — everything the view needs is in the result,
 * so the view owns no string-building logic and the trigger can be swapped.
 */
class LookupCopyTargetsTest {

    private fun entry(
        term: String,
        reading: String,
        headwordKanji: String = term,
        onyomi: String? = null,
        kunyomi: String? = null,
        chain: DeinflectionChain? = null,
    ) = FormattedEntry(
        term = term,
        readingGroups = listOf(
            FormattedReadingGroup(
                reading = reading,
                headwords = listOf(FormattedHeadword(headwordKanji, onyomi, kunyomi)),
                senseGroups = emptyList(),
                isKanjiEntry = onyomi != null || kunyomi != null,
            )
        ),
        deinflection = chain,
    )

    @Test
    fun deinflected_lookup_copies_surface_highlight_and_dict_form_headword() {
        val targets = LookupCopyTargets.targets(
            surfaceRun = "食べたかった",
            maxLen = 3,
            entries = listOf(
                entry("食べる", "たべる", chain = DeinflectionChain("食べた", listOf("past")))
            ),
        )

        assertEquals(listOf("Copy highlight", "Copy headword"), targets.map { it.label })
        assertEquals("食べた", targets[0].value)
        assertEquals("食べる", targets[1].value)
        assertEquals("lookup-highlight", targets[0].clipLabel)
        assertEquals("lookup-headword", targets[1].clipLabel)
    }

    @Test
    fun headword_is_the_raw_dict_form_term_without_the_reading() {
        // Kana surface, kanji headword: the motivating shape of "differs from
        // the surface text". The copied value must be the term, never the
        // furigana-annotated display pair (term + reading).
        val targets = LookupCopyTargets.targets(
            surfaceRun = "にほんじん",
            maxLen = 5,
            entries = listOf(entry("日本人", "にほんじん", headwordKanji = "日本人")),
        )

        assertEquals("にほんじん", targets.single { it.label == "Copy highlight" }.value)
        val headword = targets.single { it.label == "Copy headword" }.value
        assertEquals("日本人", headword)
        assertTrue("the reading must not leak into the copied headword", "にほんじん" !in headword)
    }

    @Test
    fun direct_match_offers_one_button_when_headword_equals_highlight() {
        // Nothing extra to copy: the highlight IS the headword. One affordance,
        // not two identical ones.
        val targets = LookupCopyTargets.targets(
            surfaceRun = "日本人",
            maxLen = 3,
            entries = listOf(entry("日本人", "にほんじん")),
        )

        assertEquals(1, targets.size)
        assertEquals("Copy highlight", targets.single().label)
        assertEquals("日本人", targets.single().value)
    }

    @Test
    fun no_entries_offers_only_the_highlight() {
        val targets = LookupCopyTargets.targets("読んだ", maxLen = 2, entries = emptyList())

        assertEquals(1, targets.size)
        assertEquals("読ん", targets.single().value)
        assertEquals("Copy highlight", targets.single().label)
    }

    @Test
    fun max_len_zero_is_capped_at_the_run_length_not_overrun() {
        // take() is defensive: a maxLen past the end copies the whole run, and
        // a non-positive one copies nothing.
        assertEquals("食べた", LookupCopyTargets.targets("食べた", 99, emptyList()).single().value)
        assertTrue(LookupCopyTargets.targets("食べた", 0, emptyList()).isEmpty())
    }

    @Test
    fun no_targets_when_nothing_to_copy() {
        assertTrue(LookupCopyTargets.targets("", 0, emptyList()).isEmpty())
        assertNull(LookupCopyTargets.headword(emptyList()))
    }
}
