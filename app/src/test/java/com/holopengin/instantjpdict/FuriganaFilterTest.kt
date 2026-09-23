package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-level furigana filter (the batched path `OcrEngine.filterFurigana`
 * uses). The per-pair predicates are pinned by [FuriganaRuleTest]; this pins
 * that the one-call page filter keeps the same verdicts — a mapping or
 * orientation-gate bug here would silently keep or drop whole ruby strips.
 */
class FuriganaFilterTest {

    private fun rect(l: Int, t: Int, r: Int, b: Int) = JpDictRect(l, t, r, b)

    @Test
    fun verticalRubyIsDropped_andAStackedFragmentIsKept() {
        // Big column, ruby to its right, stacked fragment sharing the column's
        // x-range (never ruby: its centre lies inside the big box).
        val raw = listOf(rect(100, 100, 150, 400), rect(170, 150, 200, 230), rect(110, 150, 140, 230))
        val uncl = listOf(rect(90, 90, 160, 420), rect(165, 145, 205, 235), rect(100, 145, 150, 235))
        assertEquals(
            listOf(true, false, true),
            FuriganaRule.filter(raw, uncl, 1000, 1000).toList(),
        )
    }

    @Test
    fun horizontalRubyIsDropped_butATallShortLineIsKept() {
        val raw = listOf(rect(100, 100, 500, 160), rect(150, 60, 350, 80), rect(600, 60, 800, 110))
        val uncl = listOf(rect(90, 90, 510, 170), rect(140, 55, 360, 85), rect(590, 55, 810, 115))
        assertEquals(
            listOf(true, false, true),
            FuriganaRule.filter(raw, uncl, 1000, 1000).toList(),
        )
    }

    @Test
    fun fewerThanTwoBoxes_canNeverHoldRuby() {
        assertEquals(
            listOf(true),
            FuriganaRule.filter(listOf(rect(100, 100, 150, 400)), listOf(rect(90, 90, 160, 420)), 1000, 1000).toList(),
        )
        assertTrue(FuriganaRule.filter(emptyList(), emptyList(), 1000, 1000).isEmpty())
    }
}
