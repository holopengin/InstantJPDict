package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #67 follow-up: the bookmark toggle's glyph.
 *
 * The toggle used to be its own full-width text row ("☆ Bookmark" /
 * "★ Bookmarked"), which pushed every entry taller and read as a line of prose
 * rather than a control. It is now a single star in the entry's top-right
 * corner, and the glyph is pinned here because "one star, never a word" is a
 * product decision the maintainer asked for, not an incidental string.
 */
class BookmarkGlyphTest {

    @Test
    fun unsaved_renders_a_single_hollow_star() {
        assertEquals("☆", BookmarkGlyph.of(saved = false))
    }

    @Test
    fun saved_renders_a_single_filled_star() {
        assertEquals("★", BookmarkGlyph.of(saved = true))
    }

    @Test
    fun the_glyph_is_never_a_word() {
        listOf(true, false).forEach { saved ->
            val glyph = BookmarkGlyph.of(saved)
            assertEquals("is exactly one character: '$glyph'", 1, glyph.length)
            assertFalse("carries no label text: '$glyph'", glyph.contains("Bookmark"))
            assertFalse("has no trailing space: '$glyph'", glyph != glyph.trim())
        }
    }

    @Test
    fun the_two_states_are_distinguishable() {
        // Saved must not read as unsaved at a glance; these are different code
        // points (U+2605 vs U+2606), not the same glyph recoloured.
        assertTrue(BookmarkGlyph.of(saved = true) != BookmarkGlyph.of(saved = false))
    }
}
