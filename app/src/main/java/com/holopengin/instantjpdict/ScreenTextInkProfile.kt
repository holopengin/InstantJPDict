package com.holopengin.instantjpdict

import kotlin.math.roundToInt

/**
 * #106: **how big is each stretch of a node's line actually drawn?**
 *
 * The overlay draws a tree-sourced line at ONE glyph size for the whole line
 * ([ScreenTextPlan.measuredScale] fits our measured advances to the row). When the
 * app drew the line at several sizes — Aedict's `外為: がいため rare`, headword
 * ~50px, reading ~33px, "rare" ~14px — the whole-line fit lands at the small end
 * and the headword comes out tiny: measured on the device, ours at 28-39px against
 * the app's 48-52px.
 *
 * A previous attempt sized **per character** from the ink inside our own character
 * cells. It failed for a reason that is structural rather than a tuning error: our
 * cells come from our own advances, so they do not line up with the app's glyphs
 * (Latin especially), and a cell straddling a size boundary reads whichever ink is
 * under it. On a uniform line that produced ratios spanning 0.5..2.5 and drew one
 * line at half a dozen sizes. The measurement is what has to be fixed first.
 *
 * ## What this measures instead
 *
 * The app's own glyphs, as ink heights across the row — not our cells:
 *
 * 1. **[profile]** is the vertical extent of ink in every column of the row
 *    ([inkProfile], sampled from the screenshot). This is the app's drawing.
 * 2. **A line-wide body size.** The row is one piece of text and most of it is the
 *    body size, so the **modal** per-character ink height ([BASELINE_MODE_BUCKET],
 *    [modalPeak]) is that body size's cap height. It is a mode rather than a
 *    percentile because the headword this is looking for is a large fraction of the
 *    columns: a two-character headword in a ten-character line already sits above
 *    the 80th percentile of the profile, so a percentile would hide it.
 * 3. **Sustained runs above the body.** A character is *large* when its own peak
 *    exceeds the body size by [GROW_RATIO], and a run survives only when
 *    [RUN_MIN_CHARS] characters in a row agree — so a single tall stroke or one
 *    wide Latin capital cannot start a run.
 * 4. **The run's size** is its local peak over the body's, so it is a ratio of
 *    *sizes*: a run drawn at twice the body size reports 2.
 *
 * Only Japanese characters can form a run ([isJapanese]). A proportionally-faced
 * Latin line's ascenders and capitals read 1.2-1.6x its x-height while being the
 * same size, and no ink measurement can tell that from a genuine size change — so
 * Latin is excluded, which is also what leaves every uniform Latin line untouched.
 *
 * ## Grow-only, on purpose
 *
 * Only enlargement is reported. A run that reads as *smaller* is left at the line's
 * size, because the small direction is where the uniform lines broke: an ellipsis
 * (`...`), a row of x-height letters, and a full stop all read well below the body
 * while being the same size as their neighbours, and shrinking them is exactly the
 * "drawn at a dozen different sizes" defect. Enlarging a run has no such failure
 * mode — nothing in a correctly-drawn uniform line reads *larger* than its own
 * body size, because the baseline IS that size.
 *
 * The task's own example makes the trade explicit: for `外為: がいため rare` the
 * **reading is the body size** (the headword is the larger run), so grow-only draws
 * the headword at its size and the reading at its own. Only "rare" — genuinely one
 * size below the body — stays at the body size, which is what the overlay already
 * does today and strictly better than the whole line at the small end.
 *
 * Pure: no `Bitmap`, no `android.graphics`, so the whole rule is pinned by host
 * tests ([ScreenTextInkProfileTest]). The Android side samples the profile; this
 * file only reasons about it.
 */
object ScreenTextInkProfile {

    /**
     * #106: the per-character peak heights are bucketed at this fraction of the
     * tallest, and the **most populated bucket** is the body size — the size the
     * most characters are drawn at.
     *
     * A percentile of the profile cannot work: a two-character headword in a
     * ten-character line is already 20% of the columns, so any high percentile lands
     * on the headword and hides the very run being looked for. The mode is the size
     * a *majority* of characters share, which is the definition of the body size and
     * is robust to how many columns each glyph happens to cover.
     */
    const val BASELINE_MODE_BUCKET = 0.05f

    /**
     * #106: how far above the baseline a stretch must reach to be drawn larger.
     *
     * 1.35 is wide enough that no variation *within* one size can trigger it: a
     * Japanese kana's tall stroke reads about 1.05x the body, and on a real Latin
     * line the tallest cluster (ascenders and capitals) reads about 1.2-1.6x, so
     * both stay put. A headword genuinely drawn a size up reads about 1.5x.
     */
    const val GROW_RATIO = 1.35f

    /** #106: at least this many characters must agree before a large run exists. */
    const val RUN_MIN_CHARS = 2

    /**
     * #106: the fraction of a character's advance whose ink is read as that
     * character's peak — the central 70%, so ~15% is trimmed at each end.
     *
     * The anchoring of our advances to the app's ink is approximate: our glyph
     * boundaries and the app's do not coincide, and a character's full advance can
     * reach the ink of the character beside it. Reading only the middle keeps a body
     * character next to a large run from borrowing the large run's ink (which would
     * start the run one character early), and vice versa.
     */
    const val SPAN_TRIM = 0.70f

    /** #106: the tallest a single run's scale may be, so one bad capture cannot blow a glyph up. */
    const val MAX_SCALE = 3f

    /**
     * #106: the vertical ink extent of every column of [rect] in [bitmap][profileOf]
     * — the app's own glyphs, one number per column, `0` where the column is blank.
     *
     * The row is [rect]'s own x span; the search for ink is [rect]'s y span, so a
     * descender from the line above cannot be read as a taller glyph here. This is
     * the whole sampling contract: nothing below needs the bitmap.
     */
    fun inkProfile(height: Int, width: Int, isInk: (x: Int, y: Int) -> Boolean): IntArray {
        val out = IntArray(width)
        for (x in 0 until width) {
            var top = -1
            var bottom = -1
            for (y in 0 until height) {
                if (isInk(x, y)) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            out[x] = if (top < 0) 0 else bottom - top + 1
        }
        return out
    }

    /**
     * #106: one stretch of the row drawn at a size above the body, as a character
     * range — the unit the painter lays out separately.
     *
     * @property start first character index of the run, into the text as given.
     * @property endExclusive one past its last character.
     * @property scale the run's size as a multiple of the body size, `>= 1`.
     */
    data class LargerRun(val start: Int, val endExclusive: Int, val scale: Float)

    /**
     * #106: the large runs of one row, in text order.
     *
     * [profile] is [inkProfile]'s output for the row the text is drawn in; [text]
     * is the node's string (the same string its character boxes are laid out from);
     * [advanceOf] is a character's advance in pixels **at the size the line is drawn
     * at today** (the caller measures it with the face it paints in, exactly as
     * [ScreenTextPlan.charBoxesAt] does), so the character positions this maps runs
     * onto are the positions the boxes already use; and [rowHeight] is the row's own
     * height, which sets the envelope's window.
     *
     * Positions come from the advances anchored to the profile's **ink span**, not to
     * the row's full width: the app left-aligns its text and the detected box hugs
     * that ink, so the first and last ink columns are the string's own ends. Anchoring
     * there is what lets a short line in a wide box be measured at all.
     *
     * Nothing is returned when the profile has no ink (an undrawn node, a bitmap the
     * caller could not read), when the text is empty, or when no run reaches
     * [GROW_RATIO] — which is every uniform line, so those are byte-for-byte the line
     * the overlay drew before this existed.
     */
    fun largerRuns(
        profile: IntArray,
        text: String,
        advanceOf: (Char) -> Float,
        rowHeight: Int,
    ): List<LargerRun> {
        if (text.isEmpty() || profile.isEmpty() || rowHeight <= 0) return emptyList()
        val inkStart = profile.indexOfFirst { it > 0 }
        if (inkStart < 0) return emptyList()
        val inkEnd = profile.indexOfLast { it > 0 } + 1

        // Character geometry: our advances, anchored to the app's ink span. The
        // app left-aligns its text and the detected box hugs that ink, so the first
        // and last ink columns are the string's own ends — which is what lets a short
        // line in a wide box be measured at all.
        val advances = FloatArray(text.length) { advanceOf(text[it]) }
        val totalAdvance = advances.sum()
        if (totalAdvance <= 0f) return emptyList()
        val pxPerAdvance = (inkEnd - inkStart).toFloat() / totalAdvance

        // Per-character peak: the tallest ink in the character's own span. Only the
        // central [SPAN_TRIM] of the span is read, so the first and last character of
        // a large run do not borrow the ink of the body character beside them — the
        // anchoring is approximate and a full span would reach into the neighbour.
        val local = FloatArray(text.length)
        var cursor = inkStart.toFloat()
        for (i in text.indices) {
            val width = advances[i] * pxPerAdvance
            val from = (cursor + width * (1f - SPAN_TRIM) * 0.5f).roundToInt()
                .coerceIn(inkStart, inkEnd - 1)
            val to = (cursor + width * (1f + SPAN_TRIM) * 0.5f).roundToInt()
                .coerceIn(from + 1, inkEnd)
            var best = 0f
            for (x in from until to) if (profile[x] > best) best = profile[x].toFloat()
            local[i] = best
            cursor += width
        }

        val baseline = modalPeak(local)
        if (baseline <= 0f) return emptyList()

        // A character is "large" when it clears the body size; a run must be a whole
        // run of large characters, or the tallest single stroke starts one. Japanese
        // characters only: a proportionally-faced Latin line's ascenders and capitals
        // read 1.2-1.6x its x-height while being the same size, and no ink measurement
        // can tell that from a genuine size change — so a Latin run is never enlarged
        // and a uniform Latin line is untouched by construction.
        val large = BooleanArray(text.length) {
            isJapanese(text[it]) && local[it] > baseline * GROW_RATIO && local[it] > 0f
        }
        val out = ArrayList<LargerRun>()
        var i = 0
        while (i < text.length) {
            if (!large[i]) {
                i++
                continue
            }
            var j = i
            while (j < text.length && large[j]) j++
            if (j - i >= RUN_MIN_CHARS) {
                var sum = 0f
                for (k in i until j) sum += local[k]
                val scale = (sum / (j - i) / baseline).coerceIn(1f, MAX_SCALE)
                out.add(LargerRun(i, j, scale))
            }
            i = j
        }
        return out
    }

    /**
     * #106: the body size's per-character peak — the **most common** peak among the
     * characters, bucketed at [BASELINE_MODE_BUCKET] of the tallest so that the
     * ordinary glyph-to-glyph wobble does not split one size over several buckets.
     *
     * `0` when no character has ink.
     */
    private fun modalPeak(local: FloatArray): Float {
        var tallest = 0f
        for (v in local) if (v > tallest) tallest = v
        if (tallest <= 0f) return 0f
        val bucket = tallest * BASELINE_MODE_BUCKET
        if (bucket <= 0f) return tallest
        val groups = LinkedHashMap<Int, MutableList<Float>>()
        for (v in local) {
            if (v <= 0f) continue
            val key = (v / bucket).roundToInt()
            groups.getOrPut(key) { ArrayList() }.add(v)
        }
        val best = groups.values.maxByOrNull { it.size } ?: return 0f
        return best.sum() / best.size
    }

    /**
     * #106: a character whose ink height is a reliable size signal.
     *
     * Japanese (kana and kanji) draws its glyphs at a near-uniform fraction of the
     * em box, so a kana's ink and a kanji's ink are both close to their size's cap
     * height and can be compared. Proportional Latin has no such property — an
     * `x` and an `H` at one size differ by ~1.6x of ink — so Latin is excluded from
     * the enlargement, which is also what keeps every uniform Latin line — the ones
     * the earlier per-character attempt broke — byte-for-byte unchanged.
     */
    private fun isJapanese(ch: Char): Boolean = ch.code > 0x7F

    /**
     * #106: one scale per character of [text] — the `1f`-defaulted array the painter
     * reads, `largeRuns` flattened onto it. Kept beside [largerRuns] so a caller
     * that only wants the array does not have to re-flatten the runs.
     */
    fun scales(profile: IntArray, text: String, advanceOf: (Char) -> Float, rowHeight: Int): FloatArray {
        val scales = FloatArray(text.length) { 1f }
        for (run in largerRuns(profile, text, advanceOf, rowHeight)) {
            for (i in run.start until run.endExclusive) scales[i] = run.scale
        }
        return scales
    }
}
