package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #84 follow-up: the overlay's body-text metrics.
 *
 * Two things are asserted here, and the first is the important one:
 *
 *  1. The line-height multiplier stays ZERO. A first attempt at the chopped-off
 *     definitions set it negative, blaming the bundled face's roomy `hhea` box.
 *     That was the wrong cause (the real one was `FlowLayout` reporting a height
 *     short of its wrapped content — see `FlowRowMathTest`). Against the bundled
 *     face the correct value is zero, and this test exists so the mistaken fix is
 *     not reinstated.
 *  2. The measured font constants still describe the shipped assets, via the same
 *     hand-rolled sfnt reader `OverlayFontTest` uses, so a font swap fails the
 *     build rather than silently changing how the overlay is spaced.
 */
class OverlayTextMetricsTest {

    @Test
    fun line_height_is_left_to_the_face() {
        // Zero is the corrected answer, not an oversight: the bundled face's box
        // is sized to keep CJK glyphs from colliding across lines, and trimming
        // it re-introduces that crowding. The clipping this was once "fixing" is
        // handled by FlowLayout reporting its true height.
        assertEquals(0f, OverlayTextMetrics.LINE_HEIGHT_MULTIPLIER, 0f)
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
        // One constant describes both faces only because they are metric-close;
        // this says so rather than assuming it.
        assertEquals(
            "shipped Noto Serif JP has diverged; one shared constant is no longer valid",
            OverlayTextMetrics.NOTO_LINE_HEIGHT_EM,
            hheaLineHeightEm(OverlayFont.SERIF_ASSET),
            0.02f,
        )
    }

    @Test
    fun the_faces_box_is_taller_than_their_typographic_metrics() {
        // The fact that misled the first fix: Noto CJK's hhea box (1.448 em) is
        // deliberately larger than the typographic 1.0 em. Pinned so the
        // reasoning in OverlayTextMetrics stays anchored to a measurement.
        val font = bytes(OverlayFont.SANS_ASSET)
        val head = tableOffset(font, "head") ?: error("no head table")
        val upm = readU16(font, head + 18).toFloat()
        val os2 = tableOffset(font, "OS/2") ?: error("no OS/2 table")
        val typo = (readS16(font, os2 + 68) - readS16(font, os2 + 70) + readS16(font, os2 + 72)) / upm

        assertTrue("expected the hhea box to exceed the typographic one", OverlayTextMetrics.NOTO_LINE_HEIGHT_EM > typo)
        assertEquals("typographic line height should be ~1.0 em", 1.0f, typo, 0.01f)
    }

    @Test
    fun body_blocks_are_tight() {
        // The density request: consecutive definitions should read as a list, so
        // the explicit padding must stay small — the glyph box already supplies
        // most of the vertical rhythm for CJK.
        assertTrue(
            "top padding should not pad the block: ${OverlayTextMetrics.BODY_TOP_PADDING_DP}",
            OverlayTextMetrics.BODY_TOP_PADDING_DP <= 1,
        )
        assertTrue(
            "bottom padding too loose for a list: ${OverlayTextMetrics.BODY_BOTTOM_PADDING_DP}",
            OverlayTextMetrics.BODY_BOTTOM_PADDING_DP <= 2,
        )
        assertTrue(
            "bottom padding must not be negative: ${OverlayTextMetrics.BODY_BOTTOM_PADDING_DP}",
            OverlayTextMetrics.BODY_BOTTOM_PADDING_DP >= 0,
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
