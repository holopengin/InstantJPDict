package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.JpDictRect
import com.holopengin.instantjpdict.LineResult
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.GapLine
import uniffi.nav_graph_core.GapOverride
import uniffi.nav_graph_core.GapResult
import uniffi.nav_graph_core.blankGapDefaultHorizontalRatio
import uniffi.nav_graph_core.blankGapDefaultTimestepStridePx
import uniffi.nav_graph_core.blankGapDefaultVerticalRatio
import uniffi.nav_graph_core.blankGapDetect
import uniffi.nav_graph_core.blankGapDetectWith
import uniffi.nav_graph_core.blankGapMedian
import uniffi.nav_graph_core.blankGapMinEmittedChars
import uniffi.nav_graph_core.blankGapTimestepBlankChar
import uniffi.nav_graph_core.blankGapTimestepColumns

/**
 * One detected deletion site on a recognised line (#44, plan Task 2.2).
 *
 * The spacing trigger is the measured one: a character dropped by the recogniser
 * leaves roughly **twice** the median spacing between the neighbouring emitted
 * characters (measured 2.00× at deletion sites versus 1.00× for ordinary adjacent
 * pairs over 231 paired lines).
 *
 * @property insertAt the **character index in [LineResult.text]** the placeholder
 *   belongs at: the gap sits between `text[insertAt - 1]` and `text[insertAt]`.
 * @property ratio this pair's spacing divided by the line's own median spacing
 *   (scale-invariant: pixels and timesteps give the same number).
 * @property spanPx the pair's spacing in pixels; estimated from timesteps (and the
 *   crop length) when no char boxes are available.
 */
data class Gap(
    val insertAt: Int,
    val ratio: Float,
    val spanPx: Float,
) {
    /** The plan document's name for [ratio]; same value, kept so downstream
     *  call sites can use either spelling. */
    val pitchRatio: Float get() = ratio
}

/**
 * Spacing-ratio detector for characters the recogniser dropped (#44, plan Task 2.2).
 *
 * Since the util-core swap the detector itself lives in `jpdict_core::blank_gaps`
 * (per-orientation thresholds, the three geometry sources — char boxes, CTC columns,
 * the raw-alternatives timestep walk — and the guards against unusable geometry);
 * this class is the host side. See the Rust module for the measurements behind the
 * 1.6/1.8 thresholds.
 */
class GapDetector(
    /** Trigger for vertical (tategumi) lines. Measured recall 1.00, false 0.00% at 1.6. */
    val verticalThreshold: Float = DEFAULT_VERTICAL_RATIO,
    /** Trigger for horizontal lines. 1.8 alone; horizontal also needs the component signal. */
    val horizontalThreshold: Float = DEFAULT_HORIZONTAL_RATIO,
) {

    /** The threshold that applies to a line of this orientation. */
    fun thresholdFor(isVertical: Boolean): Float =
        if (isVertical) verticalThreshold else horizontalThreshold

    /** Detect gaps using the line's own orientation threshold. */
    fun detect(line: LineResult): List<Gap> =
        blankGapDetect(line.toGapLine(), verticalThreshold, horizontalThreshold).map { it.toGap() }

    /**
     * Detect gaps using an explicit [threshold] (lets a caller sweep the curve
     * without rebuilding the detector).
     *
     * A pair triggers when `spacing / medianSpacing >= threshold` — the
     * `>=` form is the one the measured sweep is quoted in ("ratio ≥ 1.6").
     */
    fun detect(line: LineResult, threshold: Float): List<Gap> =
        blankGapDetectWith(line.toGapLine(), threshold).map { it.toGap() }

    companion object {
        /** Vertical trigger, read from Rust at first use (UniFFI cannot export consts). */
        val DEFAULT_VERTICAL_RATIO: Float = blankGapDefaultVerticalRatio()

        /** Horizontal trigger, read from Rust. */
        val DEFAULT_HORIZONTAL_RATIO: Float = blankGapDefaultHorizontalRatio()

        /** Model downsampling stride, for when the crop length is unknown. */
        val DEFAULT_TIMESTEP_STRIDE_PX: Float = blankGapDefaultTimestepStridePx()

        /** Fewer emitted characters than this cannot produce a spacing pair. */
        val MIN_EMITTED_CHARS: Int = blankGapMinEmittedChars().toInt()
    }
}

/** The detector's `Gap` as the exported record. */
private fun GapResult.toGap(): Gap = Gap(insertAt.toInt(), ratio, spanPx)

/** Blank is stored in `rawAlternatives` as the ideographic space (`OcrEngine.kt:2159`). */
internal val TIMESTEP_BLANK_CHAR: Char = Char(blankGapTimestepBlankChar())

/** Median of a float array; 0 for an empty array. Even counts average the middles. */
internal fun medianOf(values: FloatArray): Float = blankGapMedian(values.toList())

/**
 * Emitted character → CTC timestep column, recovered from
 * [LineResult.rawAlternatives] (`OcrEngine.kt`). The walk's assumptions (head is
 * the first entry, blank resets the repeat state, a space never collapses) live in
 * the Rust port; this forwards the data.
 */
internal fun timestepColumns(raw: List<List<Pair<Char, Float>>>): FloatArray =
    blankGapTimestepColumns(raw.map { step -> step.map { (c, s) -> GapCell(c.code, s) } })
        .toFloatArray()

/**
 * The mobile `LineResult` subset the gap pipeline reads and writes, as the
 * exported record. Fields the pipeline never touches (the quad, chunk boxes, the
 * sample path) stay on the Kotlin side; [toLineResult] copies them back from the
 * receiver.
 */
internal fun LineResult.toGapLine(): GapLine = GapLine(
    text = text,
    isVertical = isVertical,
    charBoxes = charBoxes.map { BoundingBox(it.left, it.top, it.right - it.left, it.bottom - it.top) },
    alternatives = alternatives.map { alts -> alts.map { (c, s) -> GapCell(c.code, s) } },
    rawAlternatives = rawAlternatives.map { alts -> alts.map { (c, s) -> GapCell(c.code, s) } },
    charCols = charCols.toList(),
    overrides = overrides.map { (index, v) -> GapOverride(index, v.first.code, v.second) },
    cropW = cropW,
    cropH = cropH,
    cropX = cropX,
    cropY = cropY,
    seqLenTotal = seqLenTotal,
)

/** The record back as a `LineResult`, taking every untouched field from [base]. */
internal fun GapLine.toLineResult(base: LineResult): LineResult = base.copy(
    text = text,
    charBoxes = charBoxes.map { JpDictRect(it.x, it.y, it.x + it.w, it.y + it.h) },
    alternatives = alternatives.map { alts -> alts.map { Char(it.ch) to it.score }.toMutableList() },
    charCols = charCols.toFloatArray(),
    overrides = overrides.associateTo(LinkedHashMap()) { it.index to (Char(it.ch) to it.score) },
)
