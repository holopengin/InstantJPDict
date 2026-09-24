package com.holopengin.instantjpdict

import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.PlaceOptions
import uniffi.nav_graph_core.charPlacementBlank
import uniffi.nav_graph_core.charPlacementPlace

/**
 * CTC-Anchored Placement (CAP): the per-character box placement algorithm.
 *
 * Since the char-placement conversion this is a thin facade over the PC
 * `jpdict_core::char_placement` implementation (exposed through
 * `nav_graph_core`'s UniFFI surface), so the algorithm has one source of truth
 * — the float64 port of the normative Python reference in
 * `tools/char_placement/place.py`. The pipeline (greedy CTC runs, the
 * Huber-reweighted layout template, anchor selection, robust ink refinement,
 * measured extents, translate-apart, the shared-boundary final pass) and its
 * porting rules are documented there. This object only adapts types and keeps
 * the API `OcrEngine.computeCharBoxes` and `CharPlacementTest` already use:
 * `Char` ↔ `Int` code points, `FloatArray`/`IntArray` → `List`, `Int` →
 * `Long`/`UInt`, and the 27-field [Options] → the generated `PlaceOptions`
 * record.
 *
 * The mobile port is `Float`; the Rust port is `f64`. The shim widens the f32
 * inputs (and narrows the boxes back), which is the closer of the two ports to
 * the Python reference.
 */
internal object CharPlacement {

    /**
     * Blank class as the decoder hands it over (`decodeChar(0)`), read from
     * `jpdict_core::char_placement::BLANK` (U+3000). UniFFI cannot export
     * consts, so this is a `val` rather than a `const val`.
     */
    val BLANK: Char = Char(charPlacementBlank())

    /** One timestep's top-1..K: the decoded character and its raw logit. */
    data class Step(val char: Char, val score: Float)

    /** Advance-class/optical-class knobs; defaults are the evaluated ones. */
    data class Options(
        val inkMaxPullEm: Float = 0.45f,
        val windowEm: Float = 0.6f,
        val windowStride: Float = 0.4f,
        val anchorTolEm: Float = 0.4f,
        val anchorTolStride: Float = 1.2f,
        val huberEm: Float = 0.35f,
        val minMassFrac: Float = 0.12f,
        val maxSpreadEm: Float = 0.8f,
        val confFloor: Float = 1.0f,
        val refinePasses: Int = 1,
        // Final pass (see the algorithm doc): boundaries instead of midpoints.
        val finalPass: Boolean = true,
        val extentPadPx: Float = 1f,
        val extentFloor: Float = 0.5f,
        val extentWindowEm: Float = 0.15f,
        val extentGrowFrac: Float = 0.3f,
        val splitFloorFrac: Float = 1f,
        val minHalfPx: Float = 2f,
        // Punctuation/small-kana window fallback (see the ink pass).
        val punctSpreadFallback: Boolean = true,
        // Neighbour-blob retry for CENTER-class glyphs (the で/の dakuten case).
        val bimodalRetry: Boolean = true,
        val bimodalValleyEm: Float = 0.20f,
        val bimodalMinFrac: Float = 0.12f,
        val punctFallbackWindowEm: Float = 0.5f,
        val punctFallbackMaxEm: Float = 0.6f,
        // Translate-before-split: push overlapping pairs apart into their
        // neighbours' slack before falling back to a split.
        val translateOverlap: Boolean = true,
        val translateMaxEm: Float = 0.04f,
        val translatePasses: Int = 2,
        val translateGateCut: Boolean = false,
    )

    /** Output box in crop pixels; the cross axis is the whole crop. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * Place boxes for [text]'s characters (the PC `place_with` entry point).
     *
     * [pixels] is an ARGB crop (`cropW * cropH`); a short array (or `null`)
     * skips the ink pass. [steps] is the per-timestep top-K; a count that does
     * not match [text] falls back to one-timestep runs at [charCols]. Returns
     * one box per character, each spanning the whole cross axis; an empty
     * `text` or a non-positive [seqLenTotal] returns an empty list.
     */
    fun place(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropW: Int,
        cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        steps: List<List<Step>>? = null,
        options: Options = Options(),
    ): List<Box> = charPlacementPlace(
        text = text,
        charCols = charCols.toList(),
        seqLenTotal = seqLenTotal.toLong(),
        cropW = cropW.toUInt(),
        cropH = cropH.toUInt(),
        vertical = isVertical,
        pixels = pixels?.toList(),
        steps = steps?.map { alts -> alts.map { GapCell(ch = it.char.code, score = it.score) } },
        options = options.toRust(),
    ).map { Box(it.left, it.top, it.right, it.bottom) }

    /** The 27 mobile knobs → the generated record; the three PC-internal
     * knobs (`ridge_ls`, `profile_band`, `profile_smooth`) keep their shipped
     * defaults in the shim. */
    private fun Options.toRust(): PlaceOptions = PlaceOptions(
        inkMaxPullEm = inkMaxPullEm,
        windowEm = windowEm,
        windowStride = windowStride,
        anchorTolEm = anchorTolEm,
        anchorTolStride = anchorTolStride,
        huberEm = huberEm,
        minMassFrac = minMassFrac,
        maxSpreadEm = maxSpreadEm,
        confFloor = confFloor,
        refinePasses = refinePasses,
        finalPass = finalPass,
        extentPadPx = extentPadPx,
        extentFloor = extentFloor,
        extentWindowEm = extentWindowEm,
        extentGrowFrac = extentGrowFrac,
        splitFloorFrac = splitFloorFrac,
        minHalfPx = minHalfPx,
        punctSpreadFallback = punctSpreadFallback,
        bimodalRetry = bimodalRetry,
        bimodalValleyEm = bimodalValleyEm,
        bimodalMinFrac = bimodalMinFrac,
        punctFallbackWindowEm = punctFallbackWindowEm,
        punctFallbackMaxEm = punctFallbackMaxEm,
        translateOverlap = translateOverlap,
        translateMaxEm = translateMaxEm,
        translatePasses = translatePasses,
        translateGateCut = translateGateCut,
    )
}
