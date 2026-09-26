package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.toGapPlanLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.nav_graph_core.blankGapsPlan

/**
 * The plan half of the blank-gap boundary: [BlankGaps.apply] asks Rust *where*
 * the placeholders go and grows its own lists, instead of crossing the whole
 * [LineResult] in and out.
 *
 * [BlankGapsTest] pins the resulting policy. This pins the *facade* — that the
 * plan is applied to the host's own lists exactly, on every geometry source and
 * every growth combination — because that is what the payload shrink rests on:
 * the whole-line crossing this lane replaced (`blankGapsApply`) is gone from the
 * boundary, so the expectations here are written out rather than diffed against
 * it.
 *
 * The *equivalence* with that removed crossing is not lost with it: the Rust
 * shim's `applying_the_plan_on_the_host_reproduces_apply` compares a host
 * applying the plan against `jpdict_core::blank_gaps::apply_blank_gaps` reached
 * directly, which is the same claim with a real reference on the other side.
 * What only this side can pin is the one thing the plan lane *removes* rather
 * than reproduces: a lone surrogate in the alternatives survives, because the
 * alternatives are never carried over at all.
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

    /**
     * The default per-character alternatives for [text], with a placeholder
     * entry at each index in [placeholderAt]. Names the fixture's own list rather
     * than re-deriving it, so the expectation reads as data.
     */
    private fun alts(text: String, vararg placeholderAt: Int): MutableList<MutableList<Pair<Char, Float>>> =
        MutableList(text.length) { i ->
            if (i in placeholderAt) mutableListOf(OcrEngine.GAP_CHAR to 0f) else mutableListOf(text[i] to 1f)
        }

    /** Every field the plan lane is allowed to touch, compared with no tolerance. */
    private fun assertSameLine(expected: LineResult, actual: LineResult, why: String) {
        assertEquals("$why: text", expected.text, actual.text)
        assertEquals("$why: boxes", expected.charBoxes, actual.charBoxes)
        assertTrue("$why: cols", expected.charCols.contentEquals(actual.charCols))
        assertEquals("$why: alternatives", expected.alternatives, actual.alternatives)
        assertEquals("$why: overrides", expected.overrides, actual.overrides)
        assertEquals("$why: vertical", expected.isVertical, actual.isVertical)
        assertEquals("$why: raw", expected.rawAlternatives, actual.rawAlternatives)
        assertEquals("$why: cropW", expected.cropW, actual.cropW)
        assertEquals("$why: cropH", expected.cropH, actual.cropH)
        assertEquals("$why: seqLenTotal", expected.seqLenTotal, actual.seqLenTotal)
    }

    // ── the plan, materialised ──────────────────────────────────────────────

    @Test
    fun the_plan_lane_inserts_the_measured_gap() {
        val l = line()
        val out = BlankGaps.apply(l)
        // The gap's placeholder box is interpolated between the two neighbours
        // it was dropped from: centres 50 and 90 → 70.
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお",
                charBoxes = verticalBoxes(listOf(10, 30, 50, 70, 90, 110)),
                alternatives = alts("あいう${OcrEngine.GAP_CHAR}えお", 3),
            ),
            out,
            "measured fixture",
        )
    }

    @Test
    fun the_plan_lane_grows_every_list() {
        val l = line(cols = floatArrayOf(0f, 2f, 4f, 8f, 10f), crop = Triple(40, 200, 25))
        val out = BlankGaps.apply(l)
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお",
                charBoxes = verticalBoxes(listOf(10, 30, 50, 70, 90, 110)),
                alternatives = alts("あいう${OcrEngine.GAP_CHAR}えお", 3),
                // 6f: the midpoint of the neighbouring columns 4 and 8.
                charCols = floatArrayOf(0f, 2f, 4f, 6f, 8f, 10f),
            ),
            out,
            "full lists",
        )
        assertEquals(6, out.charBoxes.size)
        assertEquals(6, out.alternatives.size)
        assertEquals(6, out.charCols.size)
        assertEquals(6f, out.charCols[3], 1e-4f)
    }

    @Test
    fun the_plan_lane_grows_only_the_boxes() {
        // No alternatives, no columns: the "empty means unknown" rule — those two
        // lists stay empty rather than get one mismatched entry.
        val l = line(alternatives = emptyList(), cols = floatArrayOf())
        val out = BlankGaps.apply(l)
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお",
                charBoxes = verticalBoxes(listOf(10, 30, 50, 70, 90, 110)),
            ),
            out,
            "boxes only",
        )
        assertEquals(6, out.charBoxes.size)
        assertTrue(out.alternatives.isEmpty())
        assertEquals(0, out.charCols.size)
    }

    @Test
    fun the_plan_lane_grows_several_gaps_and_shifts_overrides() {
        val l = line(
            text = "あいうえおかき",
            boxes = verticalBoxes(listOf(10, 30, 50, 90, 110, 150, 170)),
            cols = FloatArray(7) { it.toFloat() },
            overrides = mutableMapOf(0 to ('Z' to 1f), 6 to ('X' to 1f)),
            crop = Triple(40, 200, 25),
        )
        val out = BlankGaps.apply(l)
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお${OcrEngine.GAP_CHAR}かき",
                // Two interpolated centres, 70 and 130.
                charBoxes = verticalBoxes(listOf(10, 30, 50, 70, 90, 110, 130, 150, 170)),
                alternatives = alts("あいう${OcrEngine.GAP_CHAR}えお${OcrEngine.GAP_CHAR}かき", 3, 6),
                // Each planned column is the midpoint of two *original* columns,
                // so both land where the original text put them.
                charCols = floatArrayOf(0f, 1f, 2f, 2.5f, 3f, 4f, 4.5f, 5f, 6f),
                overrides = mutableMapOf(0 to ('Z' to 1f), 8 to ('X' to 1f)),
            ),
            out,
            "two gaps",
        )
        // Overrides follow their characters; a placeholder carries none.
        assertEquals('Z', out.overrides[0]!!.first)
        assertEquals('X', out.overrides[8]!!.first)
        assertEquals(2, out.overrides.size)
    }

    @Test
    fun the_plan_lane_works_when_the_boxes_cannot_describe_the_text() {
        // No char boxes: the detector falls through to the CTC columns, and the
        // per-timestep lists are still never handed over. There is no box to
        // interpolate, so the box list stays empty and the columns still grow.
        val l = line(boxes = emptyList(), cols = floatArrayOf(0f, 2f, 4f, 8f, 10f))
        val out = BlankGaps.apply(l)
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお",
                alternatives = alts("あいう${OcrEngine.GAP_CHAR}えお", 3),
                charCols = floatArrayOf(0f, 2f, 4f, 6f, 8f, 10f),
            ),
            out,
            "columns geometry",
        )
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
        val raw = listOf(
            step('あ', 1f), blankStep, step('い', 1f), blankStep, step('う', 1f),
            blankStep, blankStep, blankStep, step('え', 1f), blankStep, step('お', 1f),
        )
        val l = line(boxes = emptyList(), cols = floatArrayOf(), rawAlternatives = raw)
        val out = BlankGaps.apply(l)
        assertSameLine(
            l.copy(
                text = "あいう${OcrEngine.GAP_CHAR}えお",
                alternatives = alts("あいう${OcrEngine.GAP_CHAR}えお", 3),
            ),
            out,
            "timestep walk",
        )
        // The walk answers the geometry, so only the retry paid for the raw lists
        // (asserted above, unchanged) and nothing had to grow but the text and
        // the alternatives: there is no box to interpolate and no column known.
        assertTrue(out.charBoxes.isEmpty())
        assertEquals(0, out.charCols.size)
    }

    // ── the loss the plan lane keeps from making ────────────────────────────

    /**
     * A `Char` ↔ `Int` crossing has no Rust `char` for a lone surrogate, so the
     * removed whole-line lane flattened one to U+FFFD. The plan lane never
     * carries the alternatives — only their length — so the decode's own cell
     * survives. Pinning it so nobody reads the smaller payload as a lossy one.
     */
    @Test
    fun the_plan_lane_keeps_a_lone_surrogate_the_crossing_would_have_flattened() {
        val lone = '\uD83D' // the first UTF-16 unit of U+1F468, as the boundary emits it
        // A line whose alternatives list is *not* full-length, so the plan leaves
        // it alone and the assertion is a straight cell-for-cell one.
        val l = line(alternatives = listOf(mutableListOf(lone to 0.9f, 'あ' to 0.5f)))
        val out = BlankGaps.apply(l)
        assertEquals("あいう${OcrEngine.GAP_CHAR}えお", out.text)
        assertEquals(1, out.alternatives.size)
        assertEquals(lone, out.alternatives[0][0].first)
        assertEquals(0.9f, out.alternatives[0][0].second, 0f)
    }
}
