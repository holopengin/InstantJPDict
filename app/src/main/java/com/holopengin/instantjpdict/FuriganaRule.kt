package com.holopengin.instantjpdict

/**
 * #28/#99: the furigana (ruby) geometry rules, pure so they are host-tested.
 *
 * Matching runs on RAW contour geometry (pre-unclip: unclip padding fabricates
 * overlap for stacked fragments); only the gap tests use UNCLIPPED boxes (raw
 * gutters are real pixels, unclip closes them to ruby distance). Orientation
 * gating (vertical vs horizontal, near-square counts as both) stays with the
 * caller; [OcrEngine]'s `filterFurigana` owns the index-aligned pass.
 *
 * Both rules require a significant size difference in BOTH dimensions:
 * vertical needs a much shorter height AND a narrower width; horizontal
 * (since #99) needs thinness AND a much shorter width. Thinness alone used to
 * be enough horizontally, and it ate real short lines on a receipt — detail
 * text under a heading, same-ish width — as if they were ruby.
 */
internal object FuriganaRule {

    /** Vertical: small long-side < 30% of large long-side. */
    private const val SIZE_RATIO = 0.3f
    /** Horizontal ruby runs long but thin; measured ~0.65-0.70 of main height here
     * (vertical ruby stays short-only). */
    private const val THIN_RATIO = 0.75f
    /** Horizontal unclipped short-side ceiling: thin ruby here runs ~0.7 of main
     * height; full-height short lines (≈1.0) must survive. */
    private const val HSHORT_RATIO = 0.85f
    /** Small short-side < 65% of large short-side: ruby glyphs run smaller;
     * full-width short lines (か？」) and short columns survive this. */
    private const val WIDTH_RATIO = 0.65f
    /** #99 horizontal long-side ceiling, checked on the unclipped boxes like
     * [WIDTH_RATIO]: a thin line that is nearly as wide as its neighbour is real
     * text, not ruby. Ruby strips measured at or below ~0.65 of their line width
     * still pass. */
    private const val HWIDTH_RATIO = 0.65f
    /** Gap <= 50% of large short-side. */
    private const val GAP_RATIO = 0.5f
    /** Overlap >= 50% of small long-side. */
    private const val OVERLAP_RATIO = 0.5f
    /** Absolute ceiling: real short columns (e.g. 458px) dwarf ruby runs even when
     * the ratio matches — ruby longer than 12% of the image side is not ruby. #28 */
    private const val MAX_FRAC = 0.12f
    /** Absolute floor on the ANNOTATED box: ruby hugs full-size body text, not
     * compact blocks (logo boxes, badges). Catches caption strips above logo
     * blocks. #28 */
    private const val BIG_MIN_FRAC = 0.2f

    /** Tiny vertical box hugging a much larger vertical box (either side). #28
     * Center must lie OUTSIDE the big box: stacked column fragments (tail of the
     * column above/below, overlapping only via unclip padding) share its x-range.
     * Size/center/overlap use RAW contour geometry; gap uses UNCLIPPED (raw
     * gutters are real pixels, unclipped closes them to ruby distance). */
    fun isRubyVertical(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgH: Int,
    ): Boolean {
        if (bRaw.height() < imgH * BIG_MIN_FRAC) return false
        if (sRaw.height() >= bRaw.height() * SIZE_RATIO) return false
        if (sRaw.height() >= imgH * MAX_FRAC) return false
        if (sUn.width() >= bUn.width() * WIDTH_RATIO) return false
        val cx = (sRaw.left + sRaw.right) / 2
        if (cx >= bRaw.left && cx <= bRaw.right) return false
        if (gapLen(sUn.left, sUn.right, bUn.left, bUn.right) > bUn.width() * GAP_RATIO) return false
        if (overlapLen(sRaw.top, sRaw.bottom, bRaw.top, bRaw.bottom) < sRaw.height() * OVERLAP_RATIO) return false
        return true
    }

    /** Tiny horizontal box right above a much larger horizontal box. #28 (same
     * split). The candidate must be smaller in BOTH dimensions (#99): thinness
     * alone let a short receipt line — detail text under a heading, same-ish
     * width — be dropped as if it were ruby. Horizontal ruby is thin AND much
     * shorter than the annotated line; a thin but wide line is real text.
     * (Vertical has the same both-axes shape: much shorter and much narrower.) */
    fun isRubyHorizontal(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgW: Int, imgH: Int,
    ): Boolean {
        if (bRaw.width() < imgW * BIG_MIN_FRAC) return false
        if (sRaw.height() >= bRaw.height() * THIN_RATIO) return false
        if (sRaw.height() >= imgH * MAX_FRAC) return false
        if (sUn.height() >= bUn.height() * HSHORT_RATIO) return false
        if (sUn.width() >= bUn.width() * HWIDTH_RATIO) return false
        // Above-ness on RAW geometry: unclip grows both boxes toward each other
        // (~18px mutual encroachment here), flipping genuinely-above ruby to
        // overlapping. #28
        if (sRaw.bottom > bRaw.top + 2) return false
        if (bUn.top - sUn.bottom > bUn.height() * GAP_RATIO) return false
        if (overlapLen(sRaw.left, sRaw.right, bRaw.left, bRaw.right) < sRaw.width() * OVERLAP_RATIO) return false
        return true
    }

    private fun overlapLen(a1: Int, a2: Int, b1: Int, b2: Int): Int =
        (minOf(a2, b2) - maxOf(a1, b1)).coerceAtLeast(0)

    private fun gapLen(a1: Int, a2: Int, b1: Int, b2: Int): Int =
        maxOf(0, maxOf(a1, b1) - minOf(a2, b2))
}
