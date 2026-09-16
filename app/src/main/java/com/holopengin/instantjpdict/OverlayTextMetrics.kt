package com.holopengin.instantjpdict

/**
 * #84 follow-up: the vertical metrics the overlay's body text uses.
 *
 * Why this exists. #84 replaced the device-resolved default typeface with bundled
 * Noto Sans/Serif JP. Noto CJK's `hhea` box is roomy — `1160 / -288` (1.448 em)
 * against its own typographic `880 / -120` (1.0 em) — because CJK glyphs
 * legitimately reach that far: it is what keeps them from colliding across lines.
 *
 * A first attempt at the resulting "chopped-off definitions" blamed that box and
 * trimmed line height. That was wrong, and worth recording so it is not retried:
 * the actual cause was `OcrOverlayView.FlowLayout` measuring children with an
 * UNSPECIFIED height spec and reporting a total that omitted the last wrapped
 * row, so the caller laid the block out shorter than its content. See that
 * class's `onMeasure`, and `FlowRowMathTest` for the arithmetic.
 *
 * What survives here is the part that was genuinely missing: a single place for
 * the body-text paddings, and the measured font constants kept as documentation
 * of the face's metrics (asserted against the shipped assets by
 * `OverlayTextMetricsTest`, so a font swap fails the build).
 */
object OverlayTextMetrics {

    /**
     * Noto CJK's `hhea` line height in em, measured from the shipped assets.
     * The sans and serif faces are within a thousandth of each other, so one
     * constant describes both. Documentation and a swap guard, not a correction
     * factor: the face's own box is what the text is drawn with.
     */
    const val NOTO_LINE_HEIGHT_EM = 1.448f

    /**
     * Multiplier for `TextView.setLineSpacing(0f, m)`. Zero: the bundled face's
     * line box is the right one to use, and pulling it in re-introduces the
     * cross-line crowding the box exists to prevent. Named rather than inlined
     * so a future face with pathological metrics has one place to adjust.
     */
    const val LINE_HEIGHT_MULTIPLIER = 0f

    /**
     * Breathing room at the top and bottom of a block of body text, in dp.
     *
     * These set the density. The glyph box already carries most of the rhythm for
     * CJK, so the explicit padding only separates blocks — kept small so
     * consecutive definitions read as a list rather than as paragraphs.
     */
    const val BODY_TOP_PADDING_DP = 0
    const val BODY_BOTTOM_PADDING_DP = 1
}
