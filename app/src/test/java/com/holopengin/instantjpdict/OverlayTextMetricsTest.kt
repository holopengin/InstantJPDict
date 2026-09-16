package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #84 follow-up: the line-metric correction for the bundled Noto faces.
 *
 * The regression this guards: #84 swapped the platform typeface for bundled
 * Noto Sans JP, whose `hhea` line box is 1.448 em against the platform's ~1.0
 * em. Every definition line inherited that extra ~45%, and `includeFontPadding
 * = false` meant text whose ink reached past the tight box got clipped at the
 * bottom of a wrapped line. Two symptoms, one cause — so the arithmetic that
 * undoes it is worth asserting rather than leaving in a comment.
 *
 * The constants are checked against the real shipped assets via the same
 * hand-rolled sfnt reader `OverlayFontTest` uses (no font library in the test
 * classpath): if a bundled face is ever swapped for one with different metrics,
 * this fails loudly instead of the overlay silently re-spacing itself.
 */
class OverlayTextMetricsTest {

    @Test
    fun the_correction_is_negative_because_noto_is_taller_than_the_target() {
        // Negative removes the excess; a positive value would add more space,
        // which is the bug being fixed.
        assertTrue(
            "expected a negative correction, got ${OverlayTextMetrics.lineHeightMultiplier}",
            OverlayTextMetrics.lineHeightMultiplier < 0f,
        )
    }

    @Test
    fun the_correction_is_the_exact_measured_excess() {
        assertEquals(
            OverlayTextMetrics.TARGET_LINE_HEIGHT_EM - OverlayTextMetrics.NOTO_LINE_HEIGHT_EM,
            OverlayTextMetrics.lineHeightMultiplier,
            0.0001f,
        )
    }

    @Test
    fun the_correction_can_never_collapse_lines_past_each_other() {
        // Android's multiplier is added on top of the font's line height, so a
        // value at or below -1.0 would invert the line box. The clamp keeps a
        // slightly loose layout rather than an unusable one.
        assertTrue(
            "multiplier must stay above -1.0, was ${OverlayTextMetrics.lineHeightMultiplier}",
            OverlayTextMetrics.lineHeightMultiplier > -1f,
        )
    }

    @Test
    fun the_constant_matches_the_shipped_sans_face() {
        assertEquals(
            "shipped Noto Sans JP line height moved; update OverlayTextMetrics",
            OverlayTextMetrics.NOTO_LINE_HEIGHT_EM,
            hheaLineHeightEm(OverlayFont.SANS_ASSET),
            0.002f,
        )
    }

    @Test
    fun the_constant_also_matches_the_shipped_serif_face() {
        // One constant serves both faces only because they are metric-close;
        // this is the test that says so rather than an assumption.
        assertEquals(
            "shipped Noto Serif JP has diverged; the single shared correction is no longer valid",
            OverlayTextMetrics.NOTO_LINE_HEIGHT_EM,
            hheaLineHeightEm(OverlayFont.SERIF_ASSET),
            0.02f,
        )
    }

    @Test
    fun the_correction_removes_most_of_the_excess_without_collapsing_the_box() {
        // Sanity on the magnitude: Noto is ~45% taller, so the correction should
        // be a substantial fraction of an em but must leave the box usable.
        val m = OverlayTextMetrics.lineHeightMultiplier
        assertTrue("correction is too timid to fix the spacing: $m", m < -0.3f)
        assertTrue("correction is aggressive enough to collapse lines: $m", m > -0.6f)
    }

    @Test
    fun both_edges_of_a_body_block_get_padding() {
        // setLineSpacing only reaches the gaps between lines, so the first
        // ascent and last descent need real padding. Zero on the bottom would
        // re-create the clip this fix removes.
        assertTrue(
            "top padding must be positive, was ${OverlayTextMetrics.BODY_TOP_PADDING_DP}",
            OverlayTextMetrics.BODY_TOP_PADDING_DP > 0,
        )
        assertTrue(
            "bottom padding must be positive, was ${OverlayTextMetrics.BODY_BOTTOM_PADDING_DP}",
            OverlayTextMetrics.BODY_BOTTOM_PADDING_DP > 0,
        )
    }

    // ————— sfnt reading (the OverlayFontTest precedent) —————

    private val fontBytes = mutableMapOf<String, ByteArray>()
    private fun bytes(rel: String): ByteArray =
        fontBytes.getOrPut(rel) { TestAssets.assetsFile(rel).readBytes() }

    /** `(ascent - descent + lineGap) / unitsPerEm` straight from the font. */
    private fun hheaLineHeightEm(rel: String): Float {
        val font = bytes(rel)
        val head = tableOffset(font, "head") ?: error("$rel has no head table")
        val upm = readU16(font, head + 18)
        val hhea = tableOffset(font, "hhea") ?: error("$rel has no hhea table")
        val ascender = readS16(font, hhea + 4)
        val descender = readS16(font, hhea + 6)
        val lineGap = readS16(font, hhea + 8)
        return (ascender - descender + lineGap).toFloat() / upm
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readS16(b: ByteArray, off: Int): Int =
        readU16(b, off).let { if (it >= 0x8000) it - 0x10000 else it }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun tableOffset(font: ByteArray, tag: String): Int? {
        val numTables = readU16(font, 4)
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            if (String(font, record, 4, Charsets.US_ASCII) == tag) {
                return readU32(font, record + 8).toInt()
            }
        }
        return null
    }
}
