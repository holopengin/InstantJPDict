package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106: the measurement that the per-run sizing rests on — the app's ink-height
 * profile across a row, and the sustained runs above the row's body size that can
 * be read from it.
 *
 * The numbers here are the ones measured off the device screenshot in #106:
 * Aedict's `外為: がいため rare` draws the headword at ~50px of ink, the reading at
 * ~33, and "rare" at ~14; the earlier per-character attempt regressed every uniform
 * line, so most of these tests are about what a **uniform** row must *not* produce.
 *
 * Every fixture builds its profile **one character per entry of the text**, with
 * each character's ink inside its own advance — a fixture that mismatches the two
 * tests the anchoring rather than the rule.
 */
class ScreenTextInkProfileTest {

    /**
     * A row profile: one entry per character of `heights`, each occupying [advance]
     * columns with its ink in the middle (one blank bearing column either side), as
     * a proportional face draws. `heights.size` must equal the text length.
     */
    private fun row(heights: List<Int>, advance: Int = 20): IntArray {
        val out = ArrayList<Int>()
        for (h in heights) {
            out.add(0)
            repeat(advance - 2) { out.add(h) }
            out.add(0)
        }
        return out.toIntArray()
    }

    /** Every character a full em wide — the CJK case, where advance == ink pitch. */
    private val fullWidth: (Char) -> Float = { 20f }

    // ── a uniform row produces nothing ──────────────────────────────────────

    @Test
    fun aUniformRowOfOneSizeHasNoLargerRuns() {
        // Ten kana, all the same height. This is the line the previous per-character
        // attempt drew at half a dozen sizes; it must produce none.
        val text = "あ".repeat(10)
        val profile = row(List(text.length) { 40 })
        val runs = ScreenTextInkProfile.largerRuns(profile, text, fullWidth, rowHeight = 40)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    @Test
    fun latinAscendersAndDescendersDoNotCount() {
        // A proportionally-faced Latin line: x-height 25, ascenders/capitals 40,
        // descenders 16, punctuation 8 — one size, wildly different inks. The device
        // screenshot's own `(n) seam of joined boards...` line reads 0.47..1.24 x its
        // x-height. Latin is excluded from enlargement for exactly this reason.
        val profile = row(listOf(25, 25, 40, 25, 25, 25, 16, 25, 25, 25, 40, 8, 8), advance = 16)
        val runs = ScreenTextInkProfile.largerRuns(profile, "abcdefghijklm", fullWidth, rowHeight = 40)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    @Test
    fun kanaTallStrokesDoNotCount() {
        // On the real `板目: いため rare` row the reading's め reaches within 5% of the
        // kanji headword — the same size, a taller stroke. Nothing may grow.
        val profile = row(listOf(48, 48, 40, 40, 47), advance = 24)
        val runs = ScreenTextInkProfile.largerRuns(profile, "板目いため", fullWidth, rowHeight = 50)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    @Test
    fun anEmptyOrInklessProfileProducesNothing() {
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(),
            ScreenTextInkProfile.largerRuns(IntArray(0), "あいう", fullWidth, 40))
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(),
            ScreenTextInkProfile.largerRuns(IntArray(60), "あいう", fullWidth, 40))
    }

    // ── a short large headword is read ──────────────────────────────────────

    @Test
    fun aShortLargeHeadwordStillReadsLarge() {
        // The ticket's own row, heights aligned with `外為:がいためrare` — 2 headword
        // characters at 50, a 1-char colon at 30, 4 reading characters at 33, and 3
        // "rare" at 14. The reading is the body, so the headword must come back as a
        // ~1.5x run at characters 0..2 and nothing else.
        val profile = row(listOf(50, 50, 30, 33, 33, 33, 33, 20, 14, 14, 14))
        val runs = ScreenTextInkProfile.largerRuns(
            profile, "外為:がいためrare", fullWidth, rowHeight = 52,
        )
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
        assertTrue("scale=${runs[0].scale}", runs[0].scale in 1.35f..1.8f)
    }

    @Test
    fun aSingleTallCharacterDoesNotStartARun() {
        // One kanji taller than its neighbours in an otherwise uniform line — a run
        // needs RUN_MIN_CHARS characters, so this stays put.
        val profile = row(listOf(30, 30, 30, 48, 30, 30, 30, 30, 30))
        val runs = ScreenTextInkProfile.largerRuns(profile, "あいう漢えおかきく", fullWidth, rowHeight = 40)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    @Test
    fun aTailRunIsReadAtItsOwnSize() {
        // A body line with a large tail word of kana: characters 6..9 at 1.5x.
        val profile = row(listOf(30, 30, 30, 30, 30, 30, 45, 45, 45))
        val runs = ScreenTextInkProfile.largerRuns(profile, "あいうえおかきくけ", fullWidth, rowHeight = 40)
        assertEquals(1, runs.size)
        assertEquals(6, runs[0].start)
        assertEquals(9, runs[0].endExclusive)
    }

    @Test
    fun scalesFlattenTheRunsOntoEveryCharacter() {
        val profile = row(listOf(30, 30, 30, 30, 30, 30, 45, 45, 45))
        val scales = ScreenTextInkProfile.scales(profile, "あいうえおかきくけ", fullWidth, rowHeight = 40)
        assertEquals(9, scales.size)
        for (i in 0 until 6) assertEquals(1f, scales[i], 0.001f)
        val grown = scales[6]
        assertTrue("scale=$grown", grown > 1.35f)
        assertEquals(grown, scales[8], 0.001f)
    }

    // ── baseline selection ──────────────────────────────────────────────────

    @Test
    fun theBaselineIsTheBodyNotTheHeadword() {
        // A large run that is a *minority* of the row must not raise the baseline to
        // itself: 2 large characters in a 10-character line, so the mode lands on the
        // body and the headword is still visible.
        val profile = row(listOf(60, 60, 30, 30, 30, 30, 30, 30, 30, 30))
        val runs = ScreenTextInkProfile.largerRuns(profile, "あ".repeat(10), fullWidth, rowHeight = 60)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }

    @Test
    fun aMajorityLargeRowRaisesItsOwnBaselineAndSettles() {
        // If most of the row IS the large size, that size is the body — no run. A
        // line cannot be "all larger than itself".
        val profile = row(listOf(50, 50, 50, 50, 50, 50, 50, 50, 50, 30))
        val runs = ScreenTextInkProfile.largerRuns(profile, "あ".repeat(10), fullWidth, rowHeight = 50)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    // ── sampling ────────────────────────────────────────────────────────────

    @Test
    fun inkProfileMeasuresTheVerticalExtentOfEachColumn() {
        // Two columns of different extents, and a blank one between.
        // x=0: ink y 2..5 (height 4); x=1: ink y 0..0 (height 1); x=2: blank.
        val ink = setOf(0 to 2, 0 to 3, 0 to 4, 0 to 5, 1 to 0)
        val profile = ScreenTextInkProfile.inkProfile(height = 8, width = 3) { x, y -> (x to y) in ink }
        assertEquals(listOf(4, 1, 0), profile.toList())
    }

    // ── anchoring ───────────────────────────────────────────────────────────

    @Test
    fun runsAreAnchoredToTheInkSpanNotTheFullProfile() {
        // The profile has 40 blank columns on each side (a short line in a wide box).
        // The large run is still found at the string's own start.
        val body = row(listOf(50, 50, 30, 30, 30, 30))
        val padded = IntArray(40) + body + IntArray(40)
        val runs = ScreenTextInkProfile.largerRuns(padded, "あいうえおか", fullWidth, rowHeight = 50)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }

    @Test
    fun halfWidthAdvancesMapRunsToTheRightCharacters() {
        // `板目rare` — two fullwidth headword characters (advance 20) then four
        // halfwidth (advance 10). The run must land on 0..2, not on a character
        // count derived from the raw pixel width.
        val advance: (Char) -> Float = { if (it.code > 0x7F) 20f else 10f }
        val profile = row(listOf(50, 50, 30, 30, 30, 30))
        val runs = ScreenTextInkProfile.largerRuns(profile, "板目rare", advance, rowHeight = 50)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
    }

    @Test
    fun aLargeRunOfLatinIsNotEnlarged() {
        // Even four consecutive tall Latin characters — the shape that defeated the
        // ink measurement — must not grow, because a size change cannot be told from
        // the face's own changes in ink height.
        val profile = row(listOf(25, 25, 25, 25, 40, 40, 40, 40, 25, 25, 25, 25), advance = 16)
        val runs = ScreenTextInkProfile.largerRuns(profile, "aaaaJOINbbbb", fullWidth, rowHeight = 40)
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    // ── the real capture, as it was measured ────────────────────────────────

    /** Run-length decode of a captured column profile, so a real row fits in the test. */
    private fun rle(vararg runs: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        for ((value, count) in runs) repeat(count) { out.add(value) }
        return out.toIntArray()
    }

    @Test
    fun theRealHeadwordRowProducesNoRun() {
        // The column profile of `板目: いため rare` taken from the device screenshot
        // (`aedict-2.png`, row y778..827), and the row's own advances at its size.
        // Aedict drew the headword and the reading at the SAME size — the kanji ink
        // is 0.94em and the kana ink ~0.83em of that one size — so there is no larger
        // run, and none may be invented. This is the row that the earlier
        // per-character attempt drew at several sizes.
        val profile = rle(
            49 to 2, 0 to 12, 4 to 1, 7 to 1, 27 to 2, 25 to 1, 23 to 1, 21 to 1, 18 to 1, 16 to 1,
            49 to 5, 13 to 1, 15 to 1, 18 to 1, 38 to 1, 39 to 2, 26 to 1, 16 to 1, 46 to 1, 45 to 1,
            42 to 1, 47 to 1, 48 to 1, 49 to 4, 48 to 2, 47 to 1, 46 to 1, 45 to 1, 44 to 2, 43 to 1,
            42 to 1, 43 to 1, 44 to 2, 45 to 1, 46 to 1, 47 to 1, 48 to 2, 49 to 3, 4 to 1, 3 to 1,
            0 to 10, 48 to 5, 44 to 28, 48 to 5, 0 to 12, 27 to 1, 29 to 1, 31 to 3, 30 to 1, 29 to 1,
            25 to 1, 0 to 22, 18 to 1, 28 to 1, 30 to 1, 32 to 1, 34 to 2, 35 to 1, 7 to 2, 6 to 3,
            7 to 2, 9 to 1, 10 to 1, 11 to 1, 9 to 1, 7 to 1, 5 to 1, 2 to 1, 0 to 8, 2 to 1, 4 to 1,
            6 to 1, 9 to 1, 12 to 2, 14 to 1, 15 to 1, 18 to 1, 20 to 1, 17 to 1, 14 to 1, 11 to 1,
            7 to 1, 3 to 1, 0 to 10, 5 to 2, 38 to 1, 39 to 4, 36 to 1, 33 to 1, 30 to 1, 26 to 2,
            27 to 1, 23 to 1, 19 to 1, 15 to 1, 14 to 1, 40 to 1, 33 to 1, 34 to 1, 35 to 1, 36 to 3,
            37 to 3, 27 to 7, 28 to 4, 27 to 7, 0 to 9, 5 to 1, 13 to 1, 17 to 1, 20 to 1, 22 to 1,
            25 to 1, 26 to 1, 41 to 2, 42 to 2, 41 to 1, 31 to 1, 29 to 3, 28 to 3, 30 to 2, 31 to 1,
            30 to 1, 36 to 1, 46 to 1, 47 to 2, 46 to 3, 35 to 3, 34 to 1, 33 to 1, 32 to 2, 30 to 1,
            28 to 2, 26 to 1, 24 to 1, 21 to 1, 18 to 1, 13 to 1, 0 to 20, 15 to 1, 14 to 1, 2 to 5,
            1 to 1, 4 to 1, 13 to 1, 14 to 1, 15 to 4, 14 to 1, 13 to 1, 14 to 1, 13 to 1, 0 to 5,
            15 to 1, 14 to 1, 2 to 5, 1 to 1, 0 to 1, 7 to 1, 11 to 1, 13 to 1, 14 to 1, 15 to 5,
            14 to 1, 13 to 1, 5 to 1, 0 to 27,
        )
        // The row's advance pitch: a fullwidth glyph is one em; the row is 50px tall.
        val advance: (Char) -> Float = { if (it.code > 0x7F) 40f else 20f }
        val runs = ScreenTextInkProfile.largerRuns(
            profile, "板目: いため rare", advance, rowHeight = 50,
        )
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }

    @Test
    fun theRealUniformLatinRowProducesNoRun() {
        // `(n) seam of joined boards; cross grain (of w...` from the same screenshot
        // (row y852..894), whose ink runs 0.47..1.24 x its own x-height — the row the
        // earlier per-character attempt split into a dozen sizes.
        val profile = rle(
            42 to 2, 0 to 13, 13 to 1, 20 to 1, 24 to 1, 28 to 1, 30 to 1, 33 to 1, 34 to 1, 5 to 2,
            4 to 1, 30 to 3, 18 to 2, 13 to 1, 7 to 1, 4 to 1, 3 to 3, 4 to 1, 21 to 2, 20 to 2,
            17 to 1, 10 to 1, 2 to 1, 3 to 1, 4 to 2, 5 to 1, 34 to 2, 33 to 1, 30 to 1, 26 to 1,
            23 to 1, 16 to 1, 8 to 1, 0 to 15, 4 to 1, 17 to 1, 20 to 1, 22 to 1, 23 to 1, 24 to 2,
            25 to 5, 24 to 3, 22 to 1, 21 to 1, 19 to 1, 15 to 1, 0 to 4, 10 to 1, 15 to 1, 17 to 1,
            20 to 1, 21 to 1, 22 to 1, 24 to 3, 25 to 4, 24 to 2, 23 to 1, 22 to 1, 21 to 1, 18 to 1,
            15 to 1, 0 to 3, 5 to 1, 17 to 1, 20 to 1, 21 to 1, 23 to 2, 25 to 4, 24 to 3, 23 to 1,
            22 to 1, 23 to 2, 22 to 1, 20 to 1, 5 to 1, 0 to 5, 24 to 4, 5 to 1, 4 to 1, 3 to 1,
            4 to 6, 5 to 1, 24 to 1, 23 to 2, 21 to 1, 22 to 1, 4 to 9, 5 to 1, 24 to 1, 23 to 1,
            22 to 1, 21 to 1, 18 to 1, 0 to 15, 10 to 1, 14 to 1, 18 to 1, 20 to 1, 21 to 1, 22 to 1,
            24 to 3, 25 to 4, 24 to 2, 23 to 1, 22 to 1, 20 to 1, 19 to 1, 16 to 1, 13 to 1, 8 to 1,
            0 to 2, 4 to 4, 30 to 1, 32 to 1, 33 to 2, 14 to 5, 4 to 1, 3 to 1, 0 to 8, 2 to 4,
            3 to 1, 40 to 1, 41 to 2, 39 to 1, 35 to 1, 0 to 5, 10 to 1, 14 to 1, 18 to 1, 20 to 1,
            21 to 1, 22 to 1, 24 to 3, 25 to 4, 24 to 2, 23 to 1, 22 to 1, 20 to 1, 19 to 1, 16 to 1,
            13 to 1, 8 to 1, 0 to 4, 32 to 2, 33 to 2, 32 to 1, 0 to 6, 24 to 4, 5 to 2, 4 to 7, 5 to 1,
            24 to 1, 23 to 2, 22 to 1, 20 to 1, 0 to 4, 10 to 1, 15 to 1, 17 to 1, 20 to 1, 21 to 1,
            22 to 1, 24 to 3, 25 to 4, 24 to 2, 23 to 1, 22 to 1, 21 to 1, 18 to 1, 15 to 1, 0 to 3,
            10 to 1, 15 to 1, 18 to 1, 20 to 1, 22 to 1, 24 to 3, 25 to 4, 24 to 2, 22 to 1, 21 to 1,
            34 to 4, 0 to 17, 34 to 4, 20 to 1, 22 to 1, 23 to 1, 24 to 2, 25 to 4, 24 to 2, 22 to 2,
            20 to 1, 16 to 1, 12 to 1, 0 to 4, 10 to 1, 14 to 1, 18 to 1, 20 to 1, 21 to 1, 22 to 1,
            24 to 3, 25 to 4, 24 to 2, 23 to 1, 22 to 1, 20 to 1, 19 to 1, 16 to 1, 13 to 1, 8 to 1,
            0 to 3, 5 to 1, 17 to 1, 20 to 1, 21 to 1, 23 to 2, 25 to 4, 24 to 3, 23 to 1, 22 to 1,
            23 to 2, 22 to 1, 20 to 1, 5 to 1, 0 to 5, 24 to 4, 5 to 3, 4 to 5, 0 to 2, 10 to 1, 15 to 1,
            18 to 1, 20 to 1, 22 to 1, 24 to 3, 25 to 4, 24 to 2, 22 to 1, 21 to 1, 34 to 4, 0 to 5,
            4 to 1, 17 to 1, 20 to 1, 22 to 1, 23 to 1, 24 to 2, 25 to 5, 24 to 3, 22 to 1, 21 to 1,
            19 to 1, 15 to 1, 0 to 3, 3 to 1, 29 to 1, 30 to 1, 29 to 1, 28 to 1, 25 to 1, 0 to 15,
            10 to 1, 15 to 1, 18 to 1, 20 to 1, 22 to 1, 23 to 1, 24 to 2, 25 to 5, 24 to 2, 22 to 2,
            20 to 1, 18 to 1, 14 to 1, 0 to 4, 24 to 4, 5 to 3, 4 to 5, 0 to 1, 10 to 1, 14 to 1,
            18 to 1, 20 to 1, 21 to 1, 22 to 1, 24 to 3, 25 to 4, 24 to 2, 23 to 1,
        )
        val advance: (Char) -> Float = { if (it == ' ') 8f else 16f }
        val runs = ScreenTextInkProfile.largerRuns(
            profile, "(n) seam of joined boards; cross grain (of w...", advance, rowHeight = 43,
        )
        assertEquals(emptyList<ScreenTextInkProfile.LargerRun>(), runs)
    }
}
