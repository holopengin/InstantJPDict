package com.holopengin.instantjpdict

/**
 * #103: the manual character entry panel's own numbers, in dp, and the one
 * piece of arithmetic that is not a dimension — where the panel is anchored.
 *
 * The panel is built programmatically in `OcrOverlayView.showManualInput`, so
 * these used to be inline literals: a preview box computed in dp, and
 * everything else (paddings, the entry's width, the gaps, the gap under the
 * status bar) in raw pixels. At 2.625 that is 10 dp of panel padding and an
 * 83 dp-wide entry field, so the panel's proportions were a function of the
 * device's density rather than of the design. Naming them here states the
 * design, and keeps the invariant #102 landed — the top-anchored panel whose
 * **bottom edge does not move** when the preview changes — as arithmetic the
 * host tests can pin instead of a comment describing it.
 *
 * The colours are NOT here: since #103 the panel is drawn from the app theme
 * (`HarbourUi` + Material widgets), so day/night and Material You flow through
 * without a palette to maintain. The scrim is the one exception, and it is
 * deliberate — see [SCRIM_COLOR].
 *
 * Free of Android calls, so it is unit-testable (`ManualInputPanelTest`).
 */
object ManualInputPanel {

    /**
     * The preview box at its full size. It is shown HALVED (see [PREVIEW_DP]):
     * at 240 dp it was gigantic, and being the widest child it set the whole
     * panel's width.
     */
    const val FULL_PREVIEW_DP = 240

    /**
     * The preview box as drawn: [FULL_PREVIEW_DP] halved, and the size it has
     * always been drawn at. The alternatives list sizes its own preview off
     * the same character, so the two stay at different scales on purpose.
     */
    const val PREVIEW_DP = FULL_PREVIEW_DP / 2

    /** The panel's inner padding — Material 3's dialog content padding. */
    const val PANEL_PADDING_DP = 24

    /**
     * The title's gap, above and below it. One value: the panel is a single
     * column of four things and the title only needs to be a heading, not a
     * section — an asymmetric gap is a distinction the panel has nothing to
     * make.
     */
    const val TITLE_GAP_DP = 8

    /** The entry field's width — one character plus whatever the IME composes on the way there. */
    const val FIELD_WIDTH_DP = 200

    /** Space between the field and the Confirm button. */
    const val BUTTON_GAP_DP = 16

    /** The panel's own gap between the status bar and the top of the panel. */
    const val TOP_GAP_DP = 40

    /**
     * The panel's corner radius: Material 3's `shapeAppearanceCornerExtraLarge`
     * (28dp), which is the corner the M3 dialog container shape uses — the role
     * this panel is filling with `colorSurfaceContainerHigh`.
     */
    const val CORNER_RADIUS_DP = 28

    /**
     * The tap-to-dismiss scrim the panel's blocker paints: 70% black.
     *
     * Deliberately NOT a theme colour, and the one literal left in the panel.
     * A scrim is not a surface — it has no Material role, and the two M3
     * candidates would both be wrong here: `colorSurfaceContainerHigh` is the
     * panel's own fill (a scrim of it would be a translucent grey wash, and
     * the *page* under it is what has to recede), and the M3 modal scrim's
     * 32% (`m3_comp_scrim_container_opacity`, what a dialog window dims its
     * background by) is too thin to keep the OCR boxes underneath from
     * competing with the panel. 70% is the weight this scrim has always had,
     * and how much of the page still shows through the panel is a legibility
     * call, not a palette one. #104 is where the overlay's surfaces are looked
     * at together; if that ticket moves this, it moves [SCRIM_COLOR] with it.
     */
    val SCRIM_COLOR: Int = 0xB4000000.toInt()

    fun fullPreviewPx(density: Float): Int = px(FULL_PREVIEW_DP, density)

    fun previewPx(density: Float): Int = px(PREVIEW_DP, density)

    fun panelPaddingPx(density: Float): Int = px(PANEL_PADDING_DP, density)

    fun titleGapPx(density: Float): Int = px(TITLE_GAP_DP, density)

    fun fieldWidthPx(density: Float): Int = px(FIELD_WIDTH_DP, density)

    fun buttonGapPx(density: Float): Int = px(BUTTON_GAP_DP, density)

    fun gapPx(density: Float): Int = px(TOP_GAP_DP, density)

    /**
     * Where the panel's bottom edge sits, in px from the top of the overlay:
     * clear of the status bar, [TOP_GAP_DP] below it, and one FULL preview box
     * tall from there.
     *
     * This is the quantity the anchoring exists to hold still. A keyboard
     * opened over a vertically centred panel covered exactly what the user was
     * typing (the IME does not resize an accessibility overlay), so the panel
     * is pinned to the top where the keyboard cannot reach it — which makes
     * its bottom edge the thing that must not move when anything above it
     * changes size.
     */
    fun bottomEdgePx(statusBarHeightPx: Int, density: Float): Int =
        statusBarHeightPx + gapPx(density) + fullPreviewPx(density)

    /**
     * The panel's top margin: [bottomEdgePx] less the preview actually drawn,
     * so the halving is paid for out of the top of the panel and its bottom
     * edge lands exactly where an un-halved preview would have put it.
     */
    fun topMarginPx(statusBarHeightPx: Int, density: Float): Int =
        bottomEdgePx(statusBarHeightPx, density) - previewPx(density)

    /**
     * The one dp -> px expression in the panel. The view converts every one of
     * its dimensions through here rather than through `HarbourUi.dp`, so the
     * numbers the panel is built from and the numbers the test pins cannot come
     * from two conversions that merely happen to agree.
     */
    private fun px(dp: Int, density: Float): Int = (dp * density).toInt()
}
