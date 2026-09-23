package com.holopengin.instantjpdict

import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.furiganaIsRubyHorizontal
import uniffi.nav_graph_core.furiganaIsRubyVertical

/**
 * #28/#99: the furigana (ruby) geometry rules, pure so they are host-tested.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/furigana.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the rule has one source of truth; the ratios and the
 * geometry notes live there. This object only adapts [JpDictRect]
 * (`left`/`top`/`right`/`bottom`) to the shared `BoundingBox` record
 * (`x`/`y`/`w`/`h`) and forwards the image dimensions.
 *
 * Matching runs on RAW contour geometry (pre-unclip: unclip padding fabricates
 * overlap for stacked fragments); only the gap and short-side tests use
 * UNCLIPPED boxes (raw gutters are real pixels, unclip closes them to ruby
 * distance). Orientation gating (vertical vs horizontal, near-square counts as
 * both) stays with the caller; [OcrEngine]'s `filterFurigana` owns the
 * index-aligned pass.
 *
 * Both rules require a significant size difference in BOTH dimensions:
 * vertical needs a much shorter height AND a narrower width; horizontal
 * (since #99) needs thinness AND a much shorter width. Thinness alone used to
 * be enough horizontally, and it ate real short lines on a receipt — detail
 * text under a heading, same-ish width — as if they were ruby.
 */
internal object FuriganaRule {

    /** Tiny vertical box hugging a much larger vertical box (either side). #28
     * Center must lie OUTSIDE the big box: stacked column fragments (tail of the
     * column above/below, overlapping only via unclip padding) share its x-range.
     * Size/center/overlap use RAW contour geometry; gap uses UNCLIPPED (raw
     * gutters are real pixels, unclipped closes them to ruby distance). */
    fun isRubyVertical(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgH: Int,
    ): Boolean = furiganaIsRubyVertical(
        sRaw.toBoundingBox(), bRaw.toBoundingBox(), sUn.toBoundingBox(), bUn.toBoundingBox(), imgH,
    )

    /** Tiny horizontal box right above a much larger horizontal box. #28 (same
     * split). The candidate must be smaller in BOTH dimensions (#99): thinness
     * alone let a short receipt line — detail text under a heading, same-ish
     * width — be dropped as if it were ruby. Horizontal ruby is thin AND much
     * shorter than the annotated line; a thin but wide line is real text.
     * (Vertical has the same both-axes shape: much shorter and much narrower.) */
    fun isRubyHorizontal(
        sRaw: JpDictRect, bRaw: JpDictRect, sUn: JpDictRect, bUn: JpDictRect, imgW: Int, imgH: Int,
    ): Boolean = furiganaIsRubyHorizontal(
        sRaw.toBoundingBox(), bRaw.toBoundingBox(), sUn.toBoundingBox(), bUn.toBoundingBox(), imgW, imgH,
    )

    /** [JpDictRect] is `left`/`top`/`right`/`bottom`; the shared UniFFI record is
     * origin + extents, so `w`/`h` are the edge differences. */
    private fun JpDictRect.toBoundingBox(): BoundingBox =
        BoundingBox(x = left, y = top, w = right - left, h = bottom - top)
}
