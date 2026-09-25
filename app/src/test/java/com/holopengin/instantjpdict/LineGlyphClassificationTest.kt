package com.holopengin.instantjpdict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-character halfwidth classification the overlay renderer caches per
 * line ([LineOverlayView.halfWidth]) — 2026-09-25 overlay-install perf pass.
 *
 * `isHalfWidth` is a UniFFI crossing — measured 4.3 ms for the 183 characters of
 * one page's overlay — and the draw loop used to pay it for every character on
 * every redraw (a highlight, a cursor move, a pan). It is now asked once, when
 * the line is installed, and read out of a `BooleanArray` afterwards. The
 * rendering decisions that consume it (#49's line-height sizing and the 0.9
 * ASCII trim) must be byte-identical, which reduces to one property: the array
 * is the per-character answer, in text order, one entry per character and no
 * more. That is what is pinned here — the renderer itself is a View and its
 * drawing is verified on device.
 */
class LineGlyphClassificationTest {

    @Test
    fun the_cache_is_the_live_answer_per_character_in_text_order() {
        // A mixed line: the two classes the renderer branches on, several of each,
        // so an off-by-one in the array cannot pass.
        val text = "Aあｲ1本ｶZ語"       // ASCII, kanji, halfwidth katakana
        assertArrayEquals(
            text.map { OcrEngine.isHalfWidth(it) }.toBooleanArray(),
            LineOverlayView.halfWidthOf(text),
        )
    }

    @Test
    fun halfwidth_sinks_and_ascii_are_half_and_fullwidth_are_not() {
        val flags = LineOverlayView.halfWidthOf("あAｲア1")
        assertFalse("kanji", flags[0])
        assertTrue("ASCII", flags[1])
        assertTrue("halfwidth katakana", flags[2])
        assertFalse("fullwidth katakana", flags[3])
        assertTrue("digit", flags[4])
    }

    @Test
    fun one_entry_per_character_so_a_box_past_the_text_has_no_entry() {
        // The cache is sized to the TEXT, not the boxes: a CharBox with no
        // character (the length mismatch the draw loop tolerates) is exactly the
        // case the renderer's live-call fallback exists for, and it is the case
        // this sizing makes detectable.
        assertEquals(3, LineOverlayView.halfWidthOf("あいc").size)
        assertEquals(0, LineOverlayView.halfWidthOf("").size)
    }

    @Test
    fun the_override_path_can_really_change_a_classification() {
        // Why the cache is rebuilt on `updateLine` and not merely at
        // construction: correcting a character can flip which sizing branch the
        // renderer takes, so a carried-over entry would draw the corrected glyph
        // with its predecessor's scale.
        assertFalse(LineOverlayView.halfWidthOf("カ")[0])
        assertTrue(LineOverlayView.halfWidthOf("ｶ")[0])
    }
}
