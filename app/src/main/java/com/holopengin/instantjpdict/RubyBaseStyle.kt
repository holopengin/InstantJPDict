package com.holopengin.instantjpdict

import kotlin.math.pow
import kotlin.math.roundToInt
import uniffi.nav_graph_core.rubyStyleForMini

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
 * Since the util-core swap the decision itself lives in the PC
 * `jpdict_core::ruby_style` module, exposed through `nav_graph_core`'s UniFFI
 * surface, so both codebases read one source of truth ([RubyBaseStyleTest]
 * now executes that Rust code). This enum only maps the Rust result onto a
 * constant: `base` is linear RGB there and becomes an sRGB ARGB int here,
 * `bold` crosses directly, and the display sizes are dropped because
 * `OcrOverlayView.createBaseTextView` sets type sizes at the `TextView` site.
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
         *
         * Delegates to `jpdict_core::ruby_style::ruby_style(is_mini)` and
         * resolves the returned colour/weight to the enum constant that
         * carries them. A result that matches no constant means the Rust rule
         * and this enum have diverged; throw rather than paint the wrong
         * colour.
         */
        fun forMini(isMini: Boolean): RubyBaseStyle {
            val style = rubyStyleForMini(isMini)
            val baseColor = style.base.toArgb()
            return RubyBaseStyle.entries
                .firstOrNull { it.baseColor == baseColor && it.bold == style.bold }
                ?: throw IllegalStateException(
                    "jpdict_core::ruby_style returned no matching RubyBaseStyle: " +
                        "base=${style.base} -> ARGB 0x%08X, bold=${style.bold}".format(baseColor)
                )
        }
    }
}

/**
 * Convert the PC side's linear-RGB triple (`jpdict_core::ruby_style`) to an
 * opaque sRGB ARGB int.
 *
 * PC resolves colours in the linear space iced paints in (white is
 * `[1.0, 1.0, 1.0]`), while Android's `setTextColor` expects sRGB-encoded
 * 8-bit channels. Per channel, the standard sRGB transfer function applies:
 * `12.92c` for `c <= 0.0031308`, otherwise `1.055c^(1/2.4) - 0.055`. The
 * pinned endpoints are exact — 0.0 encodes to 0 and 1.0 to 255 — so today's
 * white/cyan bases pass through unchanged; the gamma step stays so a mid-tone
 * (e.g. the gray ruby row, should Android ever paint it) is not silently
 * darkened. The returned alpha is always opaque.
 */
private fun List<Float>.toArgb(): Int {
    require(size == 3) { "expected a linear-RGB triple, got $size components: $this" }
    val red = linearToSrgb(this[0])
    val green = linearToSrgb(this[1])
    val blue = linearToSrgb(this[2])
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/** One linear-RGB channel to an sRGB byte. */
private fun linearToSrgb(linear: Float): Int {
    val encoded = if (linear <= 0.0031308f) {
        12.92f * linear
    } else {
        1.055f * linear.pow(1f / 2.4f) - 0.055f
    }
    return (encoded * 255f).roundToInt().coerceIn(0, 255)
}
