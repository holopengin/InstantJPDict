package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.toGapLine
import com.holopengin.instantjpdict.util.toGapPlanLine
import com.holopengin.instantjpdict.util.toLineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.nav_graph_core.blankGapsApply
import uniffi.nav_graph_core.blankGapsPlan

/**
 * The plan half of the blank-gap boundary: [BlankGaps.apply] asks Rust *where*
 * the placeholders go and grows its own lists, instead of crossing the whole
 * [LineResult] in and out.
 *
 * [BlankGapsTest] pins the resulting policy. This pins the *equivalence* with the
 * round trip it replaced, which is the claim that lets the payload shrink:
 * `blankGapsApply(gapLine)` on the host, applied to the same line, must produce
 * the identical `LineResult`.
 *
 * One documented difference, and it is a loss the plan removes: the round trip
 * carries every alternative through `GapCell.ch: Int`, and a lone surrogate —
 * the first UTF-16 unit of a supplementary-plane vocabulary entry, which the
 * decode boundary deliberately keeps — is not a Rust `char`, so it comes back as
 * U+FFFD. The plan never carries the alternatives, so it keeps the cell the
 * decode produced. [the_round_trip_would_flatten_a_lone_surrogate] pins that as
 * the only allowed difference.
 *
 * Fixture geometry is the measured one (`GapDetectorTest`): five characters at
 * y-centres 10/30/50/90/110 → spacings 20/20/40/20, median 20, one gap at index 3.
 */
class BlankGapsPlanTest {

    private val text5 = "あいうえお"

    private fun verticalBoxes(centres: List<Int>, height: Int = 20): List<JpDictRect> =
        centres.map { JpDictRect(left = 0, top = it - height / 2, right = 40, bottom = it - height / 2 + height) }

    private fun line(
        text: String = text5,
        boxes: List<JpDictRect> = verticalBoxes(listOf(10, 30, 50, 90, 110)),
        isVertical: Boolean = true,
        cols: FloatArray = floatArrayOf(),
        alternatives: List<MutableList<Pair<Char, Float>>>? = null,
        rawAlternatives: List<List<Pair<Char, Float>>> = emptyList(),
        overrides: MutableMap<Int, Pair<Char, Float>> = mutableMapOf(),
        crop: Triple<Int, Int, Int>? = null,
    ): LineResult = LineResult(
        text = text,
        charBoxes = boxes,
        alternatives = alternatives
            ?: MutableList(text.length) { mutableListOf(text[it] to 1f) },
        isVertical = isVertical,
        charCols = cols,
        rawAlternatives = rawAlternatives,
        overrides = overrides,
        cropW = crop?.first ?: 0,
        cropH = crop?.second ?: 0,
        seqLenTotal = crop?.third ?: 0,
    )

    /** The lane the plan replaced: the whole line across and back. */
    private fun roundTrip(l: LineResult): LineResult = blankGapsApply(l.toGapLine()).toLineResult(l)

    private fun assertSameLine(expected: LineResult, actual: LineResult, why: String) {
        assertEquals("$why: text", expected.text, actual.text)
        assertEquals("$why: boxes", expected.charBoxes, actual.charBoxes)
        assertTrue("$why: cols", expected.charCols.contentEquals(actual.charCols))
        assertEquals("$why: overrides", expected.overrides, actual.overrides)
        assertEquals("$why: vertical", expected.isVertical, actual.isVertical)
        assertEquals("$why: raw", expected.rawAlternatives, actual.rawAlternatives)
        assertEquals("$why: alternatives size", expected.alternatives.size, actual.alternatives.size)
        for (i in actual.alternatives.indices) {
            val e = expected.alternatives[i]
            val a = actual.alternatives[i]
            assertEquals("$why: row $i size", e.size, a.size)
            for (j in a.indices) {
                if (e[j] == a[j]) continue
                // The only tolerated difference: the round trip's U+FFFD for a
                // lone surrogate, which the plan lane keeps as the decode made it.
                assertEquals("$why: row $i cell $j is the U+FFFD the round trip invents", '�', e[j].first)
                assertTrue(
                    "$why: row $i cell $j kept a lone surrogate",
                    a[j].first.code in 0xD800..0xDFFF,
                )
                assertEquals("$why: row $i cell $j score", e[j].second, a[j].second, 0f)
            }
        }
    }

    // ── the equivalence ─────────────────────────────────────────────────────

    @Test
    fun the_plan_lane_reproduces_the_round_trip_on_the_measured_fixture() {
        val l = line()
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "measured fixture")
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお", BlankGaps.apply(l).text)
    }

    @Test
    fun the_plan_lane_reproduces_the_round_trip_with_every_list_growing() {
        val l = line(cols = floatArrayOf(0f, 2f, 4f, 8f, 10f), crop = Triple(40, 200, 25))
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "full lists")
        val out = BlankGaps.apply(l)
        assertEquals(6, out.charBoxes.size)
        assertEquals(6, out.alternatives.size)
        assertEquals(6, out.charCols.size)
        assertEquals(6f, out.charCols[3], 1e-4f)
    }

    @Test
    fun the_plan_lane_reproduces_the_round_trip_with_only_the_boxes_growing() {
        // No alternatives, no columns: the "empty means unknown" rule — those two
        // lists stay empty rather than get one mismatched entry.
        val l = line(alternatives = emptyList(), cols = floatArrayOf())
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "boxes only")
        val out = BlankGaps.apply(l)
        assertEquals(6, out.charBoxes.size)
        assertTrue(out.alternatives.isEmpty())
        assertEquals(0, out.charCols.size)
    }

    @Test
    fun the_plan_lane_reproduces_the_round_trip_with_several_gaps_and_overrides() {
        val l = line(
            text = "あいうえおかき",
            boxes = verticalBoxes(listOf(10, 30, 50, 90, 110, 150, 170)),
            cols = FloatArray(7) { it.toFloat() },
            overrides = mutableMapOf(0 to ('Z' to 1f), 6 to ('X' to 1f)),
            crop = Triple(40, 200, 25),
        )
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "two gaps")
        val out = BlankGaps.apply(l)
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお${OcrEngine.GAP_CHAR}かき", out.text)
        // Overrides follow their characters; a placeholder carries none.
        assertEquals('Z', out.overrides[0]!!.first)
        assertEquals('X', out.overrides[8]!!.first)
        assertEquals(2, out.overrides.size)
    }

    @Test
    fun the_plan_lane_reproduces_the_round_trip_when_the_boxes_cannot_describe_the_text() {
        // No char boxes: the detector falls through to the CTC columns, and the
        // per-timestep lists are still never handed over.
        val l = line(boxes = emptyList(), cols = floatArrayOf(0f, 2f, 4f, 8f, 10f))
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "columns geometry")
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお", BlankGaps.apply(l).text)
    }

    // ── the policy the plan carries ─────────────────────────────────────────

    @Test
    fun a_declined_line_is_returned_unchanged() {
        // Horizontal: not eligible at all.
        val horizontal = line(isVertical = false)
        assertSame(horizontal, BlankGaps.apply(horizontal))
        // Already carries a placeholder: idempotent.
        val once = BlankGaps.apply(line())
        assertSame(once, BlankGaps.apply(once))
        // Evenly spaced: no gap.
        val even = line(boxes = verticalBoxes(listOf(10, 30, 50, 70, 90)))
        assertSame(even, BlankGaps.apply(even))
        // Fewer than two characters: no spacing pair exists.
        val one = line(text = "あ", boxes = verticalBoxes(listOf(10)))
        assertSame(one, BlankGaps.apply(one))
    }

    @Test
    fun an_insertion_returns_a_new_line_and_a_decision_keeps_the_receiver() {
        val plain = line()
        val out = BlankGaps.apply(plain)
        assertNotSame(plain, out)
        // Nothing the plan touched is aliased to the receiver.
        assertTrue(out.charCols !== plain.charCols)
        assertNotSame(plain.alternatives, out.alternatives)
    }

    @Test
    fun the_plan_says_which_geometry_source_the_detector_reads() {
        // Boxes cover the text: the per-timestep lists are unreachable, so the
        // first call is the whole answer and the raw lists never cross.
        val boxes = blankGapsPlan(
            line(cols = floatArrayOf(0f, 2f, 4f, 8f, 10f)).toGapPlanLine(includeRawAlternatives = false)
        )
        assertEquals(false, boxes.needsRawAlternatives)
        assertEquals(1, boxes.insertions.size)
        assertEquals(3, boxes.insertions[0].index)
        // 6f: the midpoint of the neighbouring columns 4 and 8.
        assertEquals(6f, boxes.insertions[0].column, 1e-4f)
        assertTrue(boxes.growsCharBoxes && boxes.growsCharCols && boxes.growsAlternatives)
        // No columns to interpolate: the column is unknown (0), and the plan says
        // the list does not grow rather than growing it by a wrong value.
        val noCols = blankGapsPlan(line().toGapPlanLine(includeRawAlternatives = false))
        assertEquals(0f, noCols.insertions[0].column, 0f)
        assertTrue(noCols.growsCharBoxes)
        assertTrue(!noCols.growsCharCols)

        // No boxes and no columns: only the per-timestep walk can answer, and
        // the first call — which did not carry it — finds nothing. That empty plan
        // plus the flag is exactly the retry the facade makes.
        val neither = line(boxes = emptyList(), cols = floatArrayOf())
        val asked = blankGapsPlan(neither.toGapPlanLine(includeRawAlternatives = false))
        assertEquals(true, asked.needsRawAlternatives)
        assertTrue(asked.insertions.isEmpty())

        // With the columns, the walk is not needed.
        val cols = blankGapsPlan(
            line(boxes = emptyList(), cols = floatArrayOf(0f, 2f, 4f, 8f, 10f))
                .toGapPlanLine(includeRawAlternatives = false)
        )
        assertEquals(false, cols.needsRawAlternatives)
    }

    @Test
    fun the_walk_fallback_still_finds_its_gap_through_the_plan() {
        // 11 timesteps that decode to あいうえお with a dropped character between
        // the third and fourth: emitted columns 0, 2, 4, 8, 10 → spacings
        // 2/2/4/2, median 2, one gap at index 3.
        val step = { ch: Char, score: Float -> listOf(ch to score) }
        val blankStep = listOf('\u3000' to 0.99f)
        val l = line(
            boxes = emptyList(),
            cols = floatArrayOf(),
            rawAlternatives = listOf(
                step('あ', 1f), blankStep, step('い', 1f), blankStep, step('う', 1f),
                blankStep, blankStep, blankStep, step('え', 1f), blankStep, step('お', 1f),
            ),
        )
        assertSameLine(roundTrip(l), BlankGaps.apply(l), "timestep walk")
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお", BlankGaps.apply(l).text)
    }

    // ── the documented loss the plan removes ────────────────────────────────

    /**
     * The round trip's `Char` ↔ `Int` conversion has no Rust `char` for a lone
     * surrogate, so it flattens one to U+FFFD. The plan lane never carries the
     * alternatives, so the decode's own character survives. Pinning the *loss*
     * so nobody reads the equivalence above as "the round trip was lossless".
     */
    @Test
    fun the_round_trip_would_flatten_a_lone_surrogate() {
        val lone = '\uD83D' // the first UTF-16 unit of U+1F468, as the boundary emits it
        // A line whose alternatives list is *not* full-length, so the plan leaves
        // it alone and the comparison is a straight cell-for-cell one.
        val l = line(alternatives = listOf(mutableListOf(lone to 0.9f, 'あ' to 0.5f)))
        val flattened = roundTrip(l).alternatives[0][0].first
        assertEquals('�', flattened)
        assertEquals(lone, BlankGaps.apply(l).alternatives[0][0].first)
    }
}
