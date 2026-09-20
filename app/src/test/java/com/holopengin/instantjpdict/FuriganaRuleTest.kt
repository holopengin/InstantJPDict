package com.holopengin.instantjpdict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #28/#99: the furigana geometry rules, pinned on the shapes that matter.
 *
 * The receipt regression (#99): the horizontal rule used to accept any thin box
 * above a bigger line, so a short real line — detail text under a heading,
 * nearly as wide as the line it relates to — was dropped as ruby. Horizontal
 * candidates now need both dimensions, exactly as the vertical rule always did.
 */
class FuriganaRuleTest {

    private val img = 1000

    private fun rect(l: Int, t: Int, r: Int, b: Int) = JpDictRect(l, t, r, b)

    // ————— horizontal —————

    @Test
    fun thinRubyStripAboveItsLine_isRuby() {
        val big = rect(100, 300, 600, 360)     // 500x60 line
        val small = rect(200, 276, 320, 300)   // 120x24: thin and much shorter
        val smallUn = rect(188, 266, 332, 308)
        val bigUn = rect(90, 290, 610, 370)
        assertTrue(FuriganaRule.isRubyHorizontal(small, big, smallUn, bigUn, img, img))
    }

    @Test
    fun thinButWideRealLine_isNotRuby() {
        // The receipt shape: small detail text above a heading — thin, but
        // nearly as wide as the line below it. Must survive as a real line.
        val big = rect(100, 300, 600, 360)
        val small = rect(100, 270, 600, 300)   // 500x30
        val smallUn = rect(85, 258, 615, 312)
        val bigUn = rect(90, 290, 610, 370)
        assertFalse(FuriganaRule.isRubyHorizontal(small, big, smallUn, bigUn, img, img))
    }

    // ————— vertical —————

    @Test
    fun narrowShortColumnBesideItsColumn_isRuby() {
        val big = rect(300, 50, 360, 850)      // 60x800 column
        val small = rect(276, 200, 300, 300)   // 24x100: much shorter and narrower
        val smallUn = rect(266, 190, 310, 310)
        val bigUn = rect(290, 40, 370, 860)
        assertTrue(FuriganaRule.isRubyVertical(small, big, smallUn, bigUn, img))
    }

    @Test
    fun fullWidthShortColumn_isNotRuby() {
        val big = rect(300, 50, 360, 850)
        val small = rect(300, 200, 360, 300)   // same glyph width, just shorter
        val smallUn = rect(290, 190, 370, 310)
        val bigUn = rect(290, 40, 370, 860)
        assertFalse(FuriganaRule.isRubyVertical(small, big, smallUn, bigUn, img))
    }
}
