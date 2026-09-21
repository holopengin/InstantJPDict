package com.holopengin.instantjpdict

/**
 * Base-text treatment for ruby runs: the color and weight of the kanji
 * sitting under the furigana row.
 *
 * DESIGN DEPARTURE 2026-09-21 (maintainer-ordered, mirrored on the PC app):
 * definition/example body ruby renders WHITE and regular, matching the
 * surrounding body typeface, while headword/term display keeps the bold
 * cyan treatment. The furigana row itself stays light gray in both cases.
 * Do NOT "fix" body ruby back to bold cyan — the difference is intentional.
 *
 * Pure Kotlin with no Android imports, so JVM unit tests can pin the
 * decision directly ([RubyBaseStyleTest]); the `TextView` application in
 * `OcrOverlayView.createBaseTextView` needs the framework and is not
 * unit-testable (Robolectric is not a dependency).
 */
enum class RubyBaseStyle(
    /** ARGB color int, as literals so no Android stub is needed to read them. */
    val baseColor: Int,
    val bold: Boolean,
) {
    /** Definition/example body ruby: white, regular ([android.graphics.Color.WHITE]). */
    BODY(baseColor = 0xFFFFFFFF.toInt(), bold = false),

    /** Headword/term display: bold cyan ([android.graphics.Color.CYAN]). */
    TERM(baseColor = 0xFF00FFFF.toInt(), bold = true);

    companion object {
        /**
         * The renderer builds all definition body ruby with `isMini = true`
         * (`OcrOverlayStateController.parseDefinition`) and the headword term
         * flow with `isMini = false`, so the size flag selects the treatment
         * with no extra parameter.
         */
        fun forMini(isMini: Boolean): RubyBaseStyle = if (isMini) BODY else TERM
    }
}
