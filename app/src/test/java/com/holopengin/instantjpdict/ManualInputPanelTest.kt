package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #103: the manual character entry panel's own numbers, now that they are dp
 * rather than the raw pixels the panel was built with.
 *
 * The panel is built programmatically and its View construction cannot run on
 * the JVM, so what is pinned here is the arithmetic the view hands to
 * `LayoutParams` — the one part of a restyle that is easy to break silently
 * and impossible to see in a screenshot of the wrong side of a change:
 *
 *  - the preview is still the 120 dp box (the halved 240 dp one), because the
 *    alternatives list sizes its preview off the same character and the two
 *    are meant to show the same glyph at the same scale;
 *  - the panel is still pinned to the top, clear of the status bar, and
 *    **its bottom edge does not move when the preview is halved** — the
 *    height the preview gives up is added to the top margin, so the
 *    keyboard cannot reach the field and the panel does not jump;
 *  - the scrim stays a translucent black at the weight it has always had.
 */
class ManualInputPanelTest {

    // ── the preview box ────────────────────────────────────────────────────

    @Test
    fun thePreviewIsTheHalvedFullBox() {
        assertEquals(120, ManualInputPanel.PREVIEW_DP)
        assertEquals(
            ManualInputPanel.FULL_PREVIEW_DP / 2,
            ManualInputPanel.PREVIEW_DP,
        )
    }

    @Test
    fun thePreviewIsDpSoItIsTheSamePhysicalSizeOnEveryDevice() {
        // The halving used to be `(density * 240).toInt() / 2`, which loses a
        // pixel to the truncation on an odd density; dp and truncation are both
        // pinned here so a rounding change cannot make the box 1 px off.
        for ((density, expected) in listOf(
            1f to 120,
            1.5f to 180,
            2f to 240,
            2.625f to 315,
            2.75f to 330,
            3f to 360,
            3.5f to 420,
            4f to 480,
        )) {
            assertEquals("density=$density", expected, ManualInputPanel.previewPx(density))
        }
    }

    // ── the anchor ─────────────────────────────────────────────────────────

    @Test
    fun thePanelStartsBelowTheStatusBarWithItsOwnGap() {
        for (density in listOf(1f, 2f, 2.625f, 3f)) {
            val statusBar = 63 // a 24 dp status bar at that density
            val margin = ManualInputPanel.topMarginPx(statusBar, density)
            assertTrue(
                "density=$density margin=$margin must clear the status bar",
                margin > statusBar,
            )
            // The gap is the named one, not an accident of the arithmetic.
            assertEquals(
                "density=$density",
                statusBar + ManualInputPanel.gapPx(density) + ManualInputPanel.previewPx(density),
                margin,
            )
        }
    }

    @Test
    fun halvingThePreviewLeavesTheBottomEdgeWhereItWas() {
        // The invariant the top margin exists for: the panel is top-anchored
        // so the keyboard cannot cover it, and a change to the preview's height
        // must be paid for out of the top, not out of the bottom. So the bottom
        // edge (margin + preview height) is a function of the FULL box alone —
        // if the halving ever stops being compensated, the panel's bottom edge
        // moves by 120 dp and the field ends up under the keyboard.
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 4f)) {
            for (statusBar in listOf(0, 48, 63, 100)) {
                val bottomEdge =
                    ManualInputPanel.topMarginPx(statusBar, density) + ManualInputPanel.previewPx(density)
                assertEquals(
                    "density=$density statusBar=$statusBar",
                    statusBar + ManualInputPanel.gapPx(density) +
                        ManualInputPanel.fullPreviewPx(density),
                    bottomEdge,
                )
                // … and the same edge an un-halved preview would have had.
                assertEquals(
                    "density=$density statusBar=$statusBar",
                    ManualInputPanel.bottomEdgePx(statusBar, density),
                    bottomEdge,
                )
            }
        }
    }

    // ── the rest of the panel's numbers ────────────────────────────────────

    @Test
    fun everyPanelDimensionIsAPositiveWholeNumberOfDp() {
        val dp = listOf(
            ManualInputPanel.FULL_PREVIEW_DP,
            ManualInputPanel.PREVIEW_DP,
            ManualInputPanel.PANEL_PADDING_DP,
            ManualInputPanel.TITLE_GAP_DP,
            ManualInputPanel.FIELD_WIDTH_DP,
            ManualInputPanel.BUTTON_GAP_DP,
            ManualInputPanel.TOP_GAP_DP,
            ManualInputPanel.CORNER_RADIUS_DP,
        )
        for (value in dp) assertTrue("$value dp", value > 0)
        // The corner is Material 3's `shapeAppearanceCornerExtraLarge` (28dp),
        // which is the corner the M3 dialog container shape uses — named here so
        // it cannot quietly become an M2 4dp box the moment the fill changes.
        assertEquals(28, ManualInputPanel.CORNER_RADIUS_DP)
    }

    @Test
    fun everyDimensionConvertsThroughTheSameDpExpression() {
        // Each dimension is converted by the panel's own single `dp -> px`
        // function, so the numbers the view lays out with and the numbers pinned
        // here are the same arithmetic — `(dp * density).toInt()`, truncating
        // like the preview box always has. Spelled out over the whole set rather
        // than sampled, so a dimension that quietly stops using it (and starts
        // borrowing `HarbourUi.dp`) shows up as a different conversion.
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 3f, 4f)) {
            fun expect(dp: Int) = (dp * density).toInt()
            assertEquals("padding", expect(ManualInputPanel.PANEL_PADDING_DP), ManualInputPanel.panelPaddingPx(density))
            assertEquals("title gap", expect(ManualInputPanel.TITLE_GAP_DP), ManualInputPanel.titleGapPx(density))
            assertEquals("field width", expect(ManualInputPanel.FIELD_WIDTH_DP), ManualInputPanel.fieldWidthPx(density))
            assertEquals("button gap", expect(ManualInputPanel.BUTTON_GAP_DP), ManualInputPanel.buttonGapPx(density))
            assertEquals("top gap", expect(ManualInputPanel.TOP_GAP_DP), ManualInputPanel.gapPx(density))
        }
    }

    @Test
    fun theFieldIsWideEnoughForACharacterAndItsComposition() {
        // One fullwidth glyph at the field's own 20 sp is ~20 dp; the IME's
        // composition string (romaji, or a paste) is what actually has to fit,
        // and a field narrower than a tenth of the screen would start clipping
        // it. Spelled out in dp so the ratio is checkable by hand.
        for (density in listOf(1f, 2f, 2.625f, 3f)) {
            val width = ManualInputPanel.fieldWidthPx(density)
            assertTrue("density=$density width=$width", width > 0)
            assertEquals(
                "density=$density",
                (ManualInputPanel.FIELD_WIDTH_DP * density).toInt(),
                width,
            )
            assertTrue("density=$density", ManualInputPanel.FIELD_WIDTH_DP >= 160)
        }
    }

    @Test
    fun theScrimStaysATranslucentBlackOfTheWeightItHasAlwaysHad() {
        // Not a palette role: a tap-to-dismiss scrim over the page, whose
        // weight is how much of the page still shows through. Pinned so a
        // "while I am here" change to the alpha cannot land unnoticed.
        val scrim = ManualInputPanel.SCRIM_COLOR
        assertEquals(0, scrim and 0xFFFFFF)
        assertEquals(180, scrim ushr 24 and 0xFF)
    }
}
