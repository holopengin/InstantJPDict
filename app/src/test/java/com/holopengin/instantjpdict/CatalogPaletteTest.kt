package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #71 follow-up: the catalog dialog's day/night palette.
 *
 * The bug this guards: the dialog's detail text was a single hardcoded
 * `#444444`, which is dark grey on the dark `DayNight` dialog surface and was
 * reported as unreadable. The failure was invisible in whatever mode it was
 * written in, so the invariant worth pinning is not "these exact values" but
 * "the night variant is lighter than the day variant" — the property whose
 * absence caused the bug.
 *
 * Relative luminance is used rather than a naive channel sum so the direction
 * is meaningful for the red and green variants too.
 */
class CatalogPaletteTest {

    private val day = CatalogPalette.forNight(night = false)
    private val night = CatalogPalette.forNight(night = true)

    /** WCAG relative luminance, 0 (black) .. 1 (white). */
    private fun luminance(argb: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG contrast ratio between two opaque colours. */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val darkSurface = 0xFF1E1E1E.toInt()
    private val lightSurface = 0xFFFFFFFF.toInt()

    @Test
    fun detail_text_is_readable_on_the_surface_it_lands_on() {
        // The reported bug, as a threshold: #444444 on the dark surface was
        // ~1.6:1. AA for body text is 4.5:1; hold every variant to it.
        assertTrue(
            "day neutral is too faint on the light surface: " +
                "${"%.2f".format(contrast(day.neutral, lightSurface))}:1",
            contrast(day.neutral, lightSurface) >= 4.5,
        )
        assertTrue(
            "night neutral is too faint on the dark surface: " +
                "${"%.2f".format(contrast(night.neutral, darkSurface))}:1",
            contrast(night.neutral, darkSurface) >= 4.5,
        )
    }

    @Test
    fun the_old_hardcoded_grey_would_fail_that_threshold_on_the_dark_surface() {
        // Proves the test would have caught the shipped bug rather than merely
        // describing the fix.
        assertTrue(
            "the old #444444 does not meet AA on the dark surface; the test is meaningful",
            contrast(0xFF444444.toInt(), darkSurface) < 4.5,
        )
    }

    @Test
    fun the_night_detail_text_is_lighter_than_the_day_one() {
        assertTrue(
            "night neutral ${luminance(night.neutral)} must exceed day ${luminance(day.neutral)}",
            luminance(night.neutral) > luminance(day.neutral),
        )
    }

    @Test
    fun every_semantic_colour_flips_for_night() {
        // Each is legible on exactly one surface, so none may be shared.
        assertTrue("ok", luminance(night.ok) > luminance(day.ok))
        assertTrue("warn", luminance(night.warn) > luminance(day.warn))
        assertTrue("err", luminance(night.err) > luminance(day.err))
    }

    @Test
    fun the_semantic_colours_stay_distinguishable_in_both_modes() {
        // A palette that collapsed to one grey would "pass" the luminance tests
        // while losing the meaning the colours carry.
        assertEquals(4, setOf(day.neutral, day.ok, day.warn, day.err).size)
        assertEquals(4, setOf(night.neutral, night.ok, night.warn, night.err).size)
    }

    @Test
    fun the_mode_is_read_as_a_whole_palette_not_per_colour() {
        // Guards against a future edit mixing day and night values: the two
        // modes must differ, and asking twice for the same mode must be stable.
        assertNotEquals(day.neutral, night.neutral)
        assertEquals(CatalogPalette.forNight(true), CatalogPalette.forNight(true))
        assertNotEquals(CatalogPalette.forNight(true), CatalogPalette.forNight(false))
    }
}
