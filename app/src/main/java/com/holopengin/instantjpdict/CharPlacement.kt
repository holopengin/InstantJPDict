package com.holopengin.instantjpdict

import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.PlaceOptions
import uniffi.nav_graph_core.charPlacementBlank
import uniffi.nav_graph_core.charPlacementDefaultOptions
import uniffi.nav_graph_core.charPlacementPlace
import uniffi.nav_graph_core.charPlacementTrailingPunct

internal object CharPlacement {

    val BLANK: Char = Char(charPlacementBlank())

    data class Step(val char: Char, val score: Float)

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The [Step]-shaped entry, kept for the callers that hold `Step`s (the
     * committed placement fixtures, and anything a host builds by hand).
     *
     * It is a thin adapter over [placeCells]: the only thing it does is convert
     * `Step(char, score)` into the `GapCell(ch, score)` the shim's boundary
     * takes, and that conversion is the *second* re-wrap of the same numbers
     * (the first being [TimestepTopK.capStepRows]). The page path no longer
     * comes through here — it hands the table's own `GapCell` rows to
     * [placeCells] — so this is off the hot path, where a fixture-shaped call
     * can afford it.
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
        options: PlaceOptions = charPlacementDefaultOptions(),
        /** Apply the line-ending closing-mark advance-cell post-pass. False is
         *  the raw Python-reference placement (`CharPlacementTest` parity). */
        trailingPunct: Boolean = true,
    ): List<Box> = placeCells(
        text = text,
        charCols = charCols,
        seqLenTotal = seqLenTotal,
        cropW = cropW,
        cropH = cropH,
        isVertical = isVertical,
        pixels = pixels,
        // A `List<List<Step>>` that is not already a `List<List<GapCell>>`
        // cannot be, so the conversion allocates — by design, and only here.
        cells = steps?.map { alts -> alts.map { GapCell(ch = it.char.code, score = it.score) } },
        options = options,
        trailingPunct = trailingPunct,
    )

    /**
     * The entry the page path uses: [cells] are already the shim's own
     * `List<List<GapCell>>` shape, so they cross the boundary **as they are** —
     * no `Step`, no second `GapCell`, no per-row copy.
     *
     * [TimestepTopK] owns the flat cell list the compact decode produced and
     * already knows each row's boundaries, so it can hand out the top-2 cells
     * of every timestep as the very `GapCell` objects it holds
     * ([TimestepTopK.capCellRows]) — the shim reads the same two records per
     * timestep it read before, and the page allocates none of them. The
     * truncation to two is not a saving of its own but the audited shape:
     * `jpdict_core::char_placement::runs_from_steps` reads `alts[0].0`,
     * `alts[0].1` and `alts[1].1` and nothing else, which is what
     * `TimestepTopKTest.capSteps_place_the_same_boxes_as_the_full_top_k`
     * proves over 64 shapes.
     */
    fun placeCells(
        text: String,
        charCols: FloatArray,
        seqLenTotal: Int,
        cropW: Int,
        cropH: Int,
        isVertical: Boolean,
        pixels: IntArray? = null,
        cells: List<List<GapCell>>? = null,
        options: PlaceOptions = charPlacementDefaultOptions(),
        /** Apply the line-ending closing-mark advance-cell post-pass (the same
         *  step desktop `compute_char_boxes_line_with` runs after CAP). The raw
         *  placement is the Python reference; the page path wants the pass. */
        trailingPunct: Boolean = true,
    ): List<Box> {
        
        // Rust reduces the crop's ink evidence itself. The production
        // re-decode path passes no pixels at all; tests may pass a crop.
        val raw = charPlacementPlace(
            text = text,
            charCols = charCols.toList(),
            seqLenTotal = seqLenTotal.toLong(),
            cropW = cropW.toUInt(),
            cropH = cropH.toUInt(),
            vertical = isVertical,
            pixels = pixels?.toList(),
            steps = cells,
            options = options,
        )
        val placed = if (trailingPunct) {
            charPlacementTrailingPunct(text, raw, isVertical, cropW.toUInt(), cropH.toUInt())
        } else {
            raw
        }
        return placed.map { Box(it.left, it.top, it.right, it.bottom) }
    }
}
