package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106 package 2: the **ink sample** — is anything actually drawn inside a node's
 * rect?
 *
 * [ScreenTextInk.hasInk] is the whole policy and it takes sampled luminances, so
 * the thresholds that decide whether a recovered node becomes a line are pinned
 * here rather than on a device. These are the four cases the rule has to tell
 * apart, and each one is a real screen: a blank panel, a line of text, a video
 * letterbox, and a rect with no area at all.
 *
 * The second half of the contract matters as much as the decision itself and is
 * pinned by the *shape* of these tests: the sample is a **filter, never a
 * router**. Every case below can only remove a recovered node from the result.
 * Nothing here can make recognition run somewhere — that is structural, in
 * [ScreenTextRouteTest], which shows the ink predicate is consulted only for
 * `recovered` and never enters `recognise`.
 */
class ScreenTextInkTest {

    /** A page-background sample: flat white, which is what a browser renders. */
    private fun flatWhite(n: Int = 32) = IntArray(n) { 255 }

    /** Flat mid-grey: an unpainted panel, the phantom signature. */
    private fun flatGrey(n: Int = 32) = IntArray(n) { 128 }

    /** [copy] of a flat level with [dark] of its samples replaced by [ink]. */
    private fun inked(base: Int, count: Int, ink: Int): IntArray {
        val out = IntArray(32) { base }
        for (i in 0 until count) out[i] = ink
        return out
    }

    // ── the four cases the rule must separate ────────────────────────────────

    @Test
    fun aFlatBackgroundIsNotDrawnOn() {
        // A node the tree reports but nothing renders behind: a collapsed view, a
        // zero-alpha one, a stale virtual node. The rect shows the window
        // background and nothing else, so there is no line here.
        assertFalse(ScreenTextInk.hasInk(flatWhite()))
        assertFalse(ScreenTextInk.hasInk(flatGrey()))
    }

    @Test
    fun textOnABackgroundIsDrawnOn() {
        // The case recovery exists for: detection missed this node, but the text is
        // on the screen. Ink and background in one rect — that IS the signature,
        // and it is why the sample is spread-based rather than darkness-based.
        assertTrue(ScreenTextInk.hasInk(inked(255, 6, 20)))
        // Faintest plausible antialiasing: one dark sample out of 32 still counts.
        assertTrue(ScreenTextInk.hasInk(inked(255, 1, 180)))
        // …and the same on a dark theme.
        assertTrue(ScreenTextInk.hasInk(inked(18, 8, 230)))
    }

    @Test
    fun aUniformlyBlackRegionIsDrawnOn() {
        // Zero spread, and still "drawn". No Android window background is
        // #000000, so a region this black was painted — a video letterbox, an
        // image, a dark surface. Dropping a recovered node here would lose text on
        // exactly the pages a manga or video reader draws over.
        assertTrue(ScreenTextInk.hasInk(IntArray(32) { 0 }))
        assertTrue(ScreenTextInk.hasInk(IntArray(32) { ScreenTextInk.DARK_FLOOR }))
    }

    @Test
    fun anEmptySampleIsNotDrawnOn() {
        // No evidence either way. `isDrawn` turns the only two ways to get here
        // (a rect with no area, an unreadable bitmap) into `true` before calling,
        // so a caller that reaches this has genuinely sampled nothing.
        assertFalse(ScreenTextInk.hasInk(IntArray(0)))
    }

    // ── the thresholds, pinned by name ───────────────────────────────────────

    @Test
    fun theSpreadThresholdIsExactlyWhereTheConstantSaysItIs() {
        // One level below the threshold is "blank", exactly at it is "drawn". A
        // threshold that moved would change which recovered nodes the overlay shows
        // and neither the tests above nor a device run would say so.
        val justUnder = IntArray(32) { if (it == 0) 255 - ScreenTextInk.SPREAD_MIN + 1 else 255 }
        val exactly = IntArray(32) { if (it == 0) 255 - ScreenTextInk.SPREAD_MIN else 255 }
        assertFalse(ScreenTextInk.hasInk(justUnder))
        assertTrue(ScreenTextInk.hasInk(exactly))
    }

    @Test
    fun theDarkFloorAdmitsOnlyNearBlackAndTheSpreadTestIsIndependent() {
        // A dark-mode background (#121212 = 18) is above the floor, so an un-drawn
        // node over one is still dropped; a pure black surface is not.
        assertFalse(ScreenTextInk.hasInk(IntArray(32) { 18 }))
        assertTrue(ScreenTextInk.hasInk(IntArray(32) { 0 }))
        // And the floor never *rejects* a spread rect: the two are an or, not a
        // veto, so a wide-range sample passes whatever its extremes are.
        assertTrue(ScreenTextInk.hasInk(inked(255, 4, 0)))
        assertTrue(ScreenTextInk.hasInk(inked(0, 4, 255)))
    }

    @Test
    fun aSingleSampleIsJudgedOnItsOwn() {
        // A rect one pixel wide or tall (a caret, a one-character node) samples one
        // point. The rules have to have an answer, and it is the same one: a lone
        // dark pixel is a drawn surface, a lone white one is not.
        assertTrue(ScreenTextInk.hasInk(intArrayOf(0)))
        assertFalse(ScreenTextInk.hasInk(intArrayOf(255)))
        assertFalse(ScreenTextInk.hasInk(intArrayOf(200)))
    }

    // ── the sample size, which is a cost claim the code makes ────────────────

    @Test
    fun theGridIsSmallEnoughToBeACoarseSample() {
        // "A few dozen `getPixel` calls, single-digit milliseconds" is a claim the
        // ink filter rests on: a per-pixel read of every node rect would cost more
        // than the recognition it protects. 8×4 is 32 samples per recovered node,
        // and a node smaller than the grid samples at most one pixel per cell.
        assertEquals(8, ScreenTextInk.COLUMNS)
        assertEquals(4, ScreenTextInk.ROWS)
        assertEquals(32, ScreenTextInk.COLUMNS * ScreenTextInk.ROWS)
    }
}
