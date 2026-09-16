package com.holopengin.instantjpdict

/**
 * #84 follow-up: line metrics for the overlay's text, given the bundled faces.
 *
 * Why this exists. #84 replaced the device-resolved default typeface with bundled
 * Noto Sans/Serif JP. Noto CJK's `hhea` box is deliberately roomy —
 * `1160 / -288` (1.448 em) against its own typographic `880 / -120` (1.0 em) —
 * because CJK glyphs legitimately reach that far and the box is what keeps them
 * from colliding across lines. Android's `TextView` lays text out with the
 * `hhea` box, and the overlay sets `includeFontPadding = false` on most of its
 * text, which asks for the *tight* glyph box instead.
 *
 * Those two are in direct conflict, and the result was visible: every line got
 * ~1.45 em of line height (the "large spaces between entries"), while text whose
 * ink reaches past the tight box had its descenders and final wrapped line
 * clipped (the "line-break bottoms cut off"). `OverlayFont`'s original comment
 * claimed the bundled sans was metric-identical to the platform's, which is not
 * true and was the source of the mistake.
 *
 * The resolution taken (maintainer's call): keep the bundled faces and fix the
 * metrics here rather than reverting the fonts. Two knobs, because the two
 * symptoms need opposite treatment:
 *
 *  - [lineHeightMultiplier] used as `setLineSpacing(0f, m)` — Noto's own box is
 *    taller than the old platform font's, so a negative multiplier pulls
 *    *between-line* space back down without touching the first line's box. This
 *    is what restores the old entry density.
 *  - [BODY_TOP_PADDING_DP] / [BODY_BOTTOM_PADDING_DP] — the first and last lines
 *    are not affected by line spacing at all, so the clip at a wrapped line's
 *    bottom is fixed by giving those edges real padding rather than by shrinking
 *    the box further.
 *
 * Kept Android-free and expressed in em/sp terms so a test can assert the
 * arithmetic (see `OverlayTextMetricsTest`) instead of it living only in a
 * comment.
 */
object OverlayTextMetrics {

    /**
     * Noto CJK's `hhea` line height in em, measured from the shipped asset.
     * The bundled sans and serif are within a thousandth of each other, so one
     * constant serves both faces.
     */
    const val NOTO_LINE_HEIGHT_EM = 1.448f

    /**
     * The line height the overlay's spacing was originally tuned against — the
     * platform Japanese sans as Android ships it resolves to roughly one em of
     * line box for a one-em font size.
     */
    const val TARGET_LINE_HEIGHT_EM = 1.0f

    /**
     * Multiplier for `TextView.setLineSpacing(0f, m)`, where the resulting line
     * height is `1.0 em + m` (Android adds the multiplier as an extra on top of
     * the font's own line height). Negative, because Noto's box is larger than
     * the target: this removes the excess rather than adding space.
     *
     * Clamped at 0 from below: a multiplier that would collapse lines past
     * touching is worse than the spacing being slightly loose, so the correction
     * is allowed to be partial but never to invert.
     */
    val lineHeightMultiplier: Float =
        (TARGET_LINE_HEIGHT_EM - NOTO_LINE_HEIGHT_EM).coerceAtMost(0f)

    /**
     * Breathing room at the top and bottom of a block of body text, in dp.
     *
     * Needed because `setLineSpacing` only affects the gaps *between* lines: the
     * first line's ascent and the last line's descent are outside its reach, and
     * with `includeFontPadding = false` the descenders at a wrapped line's bottom
     * were being cut. A couple of dp is enough to clear the ink without
     * reintroducing the spacing this fix is removing.
     */
    const val BODY_TOP_PADDING_DP = 2
    const val BODY_BOTTOM_PADDING_DP = 3
}
