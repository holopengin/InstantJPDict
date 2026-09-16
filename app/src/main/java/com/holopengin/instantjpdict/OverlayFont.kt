package com.holopengin.instantjpdict

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.util.Log
import android.widget.TextView

/**
 * #84: the face the OCR overlay paints in — sans/gothic (Noto Sans JP) or
 * serif/mincho (Noto Serif JP) — and the switch that selects it.
 *
 * Both faces ship in the APK under `assets/fonts/` as static Regular instances
 * of the upstream variable fonts, whole (nothing is subset: the overlay draws
 * dictionary text of arbitrary breadth). Relying on `Typeface.SERIF` instead
 * would resolve through the device's own font configuration, and Android
 * guarantees the family name resolves — not that the serif family carries
 * Japanese glyphs — so a serif choice could silently fall back to sans.
 *
 * Only Regular ships, so emphasis is synthetic: [apply] fake-bolds the
 * `TextView`, and [LineOverlayView] fake-bolds its `Paint` for the lookup
 * highlight. (A `Typeface.BOLD` request against a single-font family selects
 * the same Regular outlines, not a heavier face.)
 *
 * The choice is stored as a string in [OcrEngine.PREFS_NAME] under [PREF_FACE]
 * — the one store every other overlay setting uses — and read at the point of
 * use (overlay construction), so the next lookup picks a change up.
 * [DEFAULT_FACE] is the sans face, the appearance the overlay had before the
 * switch existed.
 */
object OverlayFont {
    /** SharedPreferences key (#84), in [OcrEngine.PREFS_NAME] like every other toggle. */
    const val PREF_FACE = "overlay_font_face"
    const val FACE_SANS = "sans"
    const val FACE_SERIF = "serif"

    /**
     * Today's appearance: the sans face the overlay drew before the switch
     * existed.
     *
     * NOT metric-identical to the platform face, which is what the first cut of
     * this got wrong: the bundled Noto's `hhea` line box is 1.448 em against the
     * platform font's ~1.0 em, so adopting it as the default silently widened
     * every line of dictionary text. [OverlayTextMetrics] is what corrects that;
     * the claim is recorded here because this constant's doc used to assert the
     * opposite and would mislead the next reader.
     */
    const val DEFAULT_FACE = FACE_SANS

    /**
     * The bundled faces. Static Regular instances of the upstream variable fonts
     * (the variable default is Thin/ExtraLight, so shipping one as-is would paint
     * a hairline); GSUB `vert`+`vrt2` and the vertical metrics are preserved and
     * verified — see the licence notices under assets/licenses/notices/.
     */
    const val SANS_ASSET = "fonts/NotoSansJP-Regular.ttf"
    const val SERIF_ASSET = "fonts/NotoSerifJP-Regular.ttf"

    /** A stored value this build does not know reads as the default, never as a crash. */
    fun normalize(stored: String?): String = if (stored == FACE_SERIF) FACE_SERIF else FACE_SANS

    /** The asset a face value names, defaulting exactly like [normalize]. */
    fun assetOf(face: String?): String = if (normalize(face) == FACE_SERIF) SERIF_ASSET else SANS_ASSET

    fun read(prefs: SharedPreferences): String = normalize(prefs.getString(PREF_FACE, DEFAULT_FACE))

    fun write(prefs: SharedPreferences, face: String) {
        prefs.edit().putString(PREF_FACE, normalize(face)).apply()
    }

    fun face(ctx: Context): String = read(prefsOf(ctx))

    fun setFace(ctx: Context, face: String) = write(prefsOf(ctx), face)

    private fun prefsOf(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()
    private var cachedFace: String? = null
    private var regular: Typeface? = null
    private var bold: Typeface? = null

    /** The bundled face the setting selects; [bold] derives the synthetic bold of the same face. */
    fun typeface(ctx: Context, bold: Boolean = false): Typeface {
        val face = face(ctx)
        synchronized(lock) {
            if (cachedFace != face) {
                regular = load(ctx, assetOf(face))
                this.bold = Typeface.create(regular, Typeface.BOLD)
                cachedFace = face
            }
            return if (bold) this.bold!! else regular!!
        }
    }

    /**
     * Stamp [tv] with the selected face. One helper for every `TextView` the
     * overlay builds, so the glyphs, dictionary panel, alternatives,
     * neighbour chips and pitch line stay one face.
     */
    fun apply(ctx: Context, tv: TextView, bold: Boolean = false) {
        tv.typeface = typeface(ctx, bold)
        tv.paint.isFakeBoldText = bold
    }

    /**
     * [apply] for a block of body text — a definition, an example, the source
     * caption. The bundled faces carry a taller line box than the platform font
     * this layout was tuned against, so the excess is removed between lines here
     * rather than at every call site. See [OverlayTextMetrics] for the measured
     * numbers and why the top/bottom edges need their own padding.
     *
     * Callers must not also set `setLineSpacing` or `includeFontPadding` after
     * this, or they re-introduce the conflict it exists to resolve.
     */
    fun applyBody(ctx: Context, tv: TextView, bold: Boolean = false) {
        apply(ctx, tv, bold)
        tv.includeFontPadding = false
        tv.setLineSpacing(0f, OverlayTextMetrics.lineHeightMultiplier)
    }

    /**
     * The vertical padding a body-text block needs at its top and bottom, in
     * pixels. Split out because it is applied to the *container* an example or
     * sense sits in, not to the text view, and the two edges differ.
     */
    fun bodyTopPaddingPx(ctx: Context): Int =
        (OverlayTextMetrics.BODY_TOP_PADDING_DP * ctx.resources.displayMetrics.density).toInt()

    fun bodyBottomPaddingPx(ctx: Context): Int =
        (OverlayTextMetrics.BODY_BOTTOM_PADDING_DP * ctx.resources.displayMetrics.density).toInt()

    private fun load(ctx: Context, asset: String): Typeface = try {
        Typeface.createFromAsset(ctx.assets, asset) ?: Typeface.SANS_SERIF
    } catch (e: Exception) {
        // The asset is bundled, so this only fires on a broken APK; fall back to
        // the platform's sans face rather than crashing the overlay.
        Log.w("OverlayFont", "bundled font '$asset' unavailable, falling back to the platform sans face", e)
        Typeface.SANS_SERIF
    }
}
