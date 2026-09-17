package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DeinflectionChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals(listOf("Copy highlight", "Copy word"), targets.map { it.label })
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
        val headword = targets.single { it.label == "Copy word" }.value
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

    // --- #66 follow-up: the neighbour-chip menu -------------------------

    @Test
    fun neighbour_menu_offers_highlight_character_and_headword_in_that_order() {
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "食べたかった",
            maxLen = 3,
            character = "食",
            entries = listOf(entry(term = "食べる", reading = "たべる")),
        )

        assertEquals(
            listOf("Copy highlight", "Copy character", "Copy word"),
            menu.map { it.label },
        )
        assertEquals(listOf("食べた", "食", "食べる"), menu.map { it.value })
        // The clip labels are the ones LookupCopyTargets already owns, so a
        // paste target can tell which kind of string it received.
        assertEquals(
            listOf("lookup-highlight", "lookup-character", "lookup-headword"),
            menu.map { it.clipLabel },
        )
    }

    @Test
    fun neighbour_menu_drops_the_character_when_it_is_the_whole_highlight() {
        // A one-character lookup would otherwise offer the same string twice.
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "食",
            maxLen = 1,
            character = "食",
            entries = emptyList(),
        )

        assertEquals(listOf("Copy highlight"), menu.map { it.label })
    }

    @Test
    fun neighbour_menu_keeps_the_character_when_the_highlight_is_longer() {
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "食べた",
            maxLen = 2,
            character = "べ",
            entries = emptyList(),
        )

        assertEquals(listOf("Copy highlight", "Copy character"), menu.map { it.label })
        assertEquals(listOf("食べ", "べ"), menu.map { it.value })
    }

    @Test
    fun neighbour_menu_falls_back_to_character_only_when_the_highlight_is_empty() {
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "",
            maxLen = 0,
            character = "",
            entries = emptyList(),
        )

        assertTrue(menu.isEmpty())
    }

    @Test
    fun neighbour_menu_keeps_the_headword_even_when_it_equals_the_highlight() {
        // Unlike `targets`, the entry long-press/menu is an explicit request for
        // that string, so equality is not a reason to hide it.
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "読んだ",
            maxLen = 2,
            character = "読",
            entries = listOf(entry(term = "読ん", reading = "よん")),
        )

        assertEquals(listOf("Copy highlight", "Copy character", "Copy word"), menu.map { it.label })
        assertEquals("読ん", menu.last().value)
    }

    // --- #66 follow-up: the entry long-press ---------------------------

    @Test
    fun headword_target_keeps_the_dict_form_for_a_deinflected_entry() {
        val target = LookupCopyTargets.headwordTarget(listOf(entry(term = "食べる", reading = "たべる")))

        assertEquals("食べる", target?.value)
        assertEquals("Copy word", target?.label)
        assertEquals("lookup-headword", target?.clipLabel)
    }

    @Test
    fun headword_target_is_absent_when_the_entry_has_no_term() {
        assertNull(LookupCopyTargets.headwordTarget(emptyList()))
        assertNull(LookupCopyTargets.headwordTarget(listOf(entry(term = "", reading = ""))))
    }

    // --- #66 follow-up: the confirmation wording ------------------------

    @Test
    fun the_confirmation_leads_with_the_copied_string() {
        assertEquals("食べる copied", LookupCopyTargets.copiedConfirmation("食べる"))
        assertEquals("食べた copied", LookupCopyTargets.copiedConfirmation("食べた"))
    }

    @Test
    fun the_confirmation_is_not_the_old_label_form() {
        // The maintainer asked for "{} copied", not a "Copied: {}" label.
        val text = LookupCopyTargets.copiedConfirmation("食")
        assertTrue(text.startsWith("食"))
        assertFalse("no colon label form: $text", text.contains("Copied"))
        assertFalse("no colon: $text", text.contains(":"))
    }

    // --- #66 follow-up: the menu wording --------------------------------

    @Test
    fun the_menu_says_copy_not_save() {
        // "Save" implied persistence into the #67 store; these go to the
        // clipboard, and the label has to say so.
        val menu = LookupCopyTargets.neighbourMenu(
            surfaceRun = "食べた",
            maxLen = 2,
            character = "べ",
            entries = listOf(entry(term = "食べる", reading = "たべる")),
        )

        assertTrue("all items are Copy-*: ${menu.map { it.label }}", menu.all { it.label.startsWith("Copy ") })
        assertFalse("none say Save: ${menu.map { it.label }}", menu.any { it.label.contains("Save") })
    }
}
