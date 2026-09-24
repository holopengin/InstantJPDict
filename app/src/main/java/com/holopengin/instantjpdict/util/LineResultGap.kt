package com.holopengin.instantjpdict.util

import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine
import uniffi.nav_graph_core.GapCell
import uniffi.nav_graph_core.blankGapsWithGapCharAt

/**
 * Insert the reversible blank placeholder [OcrEngine.GAP_CHAR] (U+25CC) at
 * character index [index] of this line (#44, plan Task 2.3).
 *
 * This is the *synthetic position* half of the gap pipeline: [GapDetector] says
 * **where** a character was dropped, this says what the line looks like once the
 * placeholder is materialised there. It is pure data — no UI, no model, no
 * dictionary; the placeholder is deliberately un-lookupable downstream
 * ([com.holopengin.instantjpdict.OcrOverlayStateController.lookup] returns `null`
 * for a character index whose text is `GAP_CHAR`, which is exactly the "clickable
 * blank, no definition" behaviour), so nothing here needs to change that file.
 *
 * ## What stays index-aligned
 *
 * `LineResult` carries parallel per-character lists; after insertion they must all
 * still describe the *same* characters at the *same* indices:
 *
 *  - `text` grows by one character, with the placeholder at [index]. The character
 *    that was at `index` is now at `index + 1` (and so on for the rest).
 *  - `charBoxes` grows by one, interpolated between the two neighbours — the box
 *    that was at `index` still belongs to its character, now at `index + 1`.
 *  - `alternatives` grows by one synthetic entry ([gapAlternatives]); the entry
 *    that was at `index` stays with its character at `index + 1`.
 *  - `charCols` (the CTC timestep column per emitted character, #49) grows by one
 *    at [index], so box recomputation from the very same columns still lines up.
 *  - `overrides` keys move: every key `>= index` shifts by +1, so an applied
 *    correction or a filled blank keeps pointing at the character it was applied
 *    to. Keys before [index] are untouched.
 *
 * Lists that carry no data (empty `charBoxes` before crop geometry is known, an
 * empty `alternatives`) are **left empty** rather than given a single mismatched
 * entry — "empty" means "unknown", and one entry cannot describe `n + 1`
 * characters.
 *
 * Since the util-core swap the insertion lives in `jpdict_core::blank_gaps`
 * (`with_gap_char_at`); this facade keeps the out-of-range identity (`assertSame`)
 * and the `Char` ↔ `Int` conversion.
 *
 * @param index character index to insert at, `0..text.length`. Out of range is a
 *   no-op: the receiver is returned unchanged.
 * @param column the CTC timestep column for the new position (the value the
 *   detector's fallback geometry worked in), inserted into `charCols`.
 * @param gapAlternatives the synthetic alternatives entry for the placeholder.
 *   Defaults to the placeholder convention: `GAP_CHAR` at score 0.
 */
fun LineResult.withGapCharAt(
    index: Int,
    column: Float,
    gapAlternatives: MutableList<Pair<Char, Float>> = mutableListOf(OcrEngine.GAP_CHAR to 0f),
): LineResult {
    if (index < 0 || index > text.length) return this
    return blankGapsWithGapCharAt(
        toGapLine(),
        index.toLong(),
        column,
        gapAlternatives.map { (c, s) -> GapCell(c.code, s) },
    ).toLineResult(this)
}

/**
 * Alias for [withGapCharAt] under the name the subtask used. Same behaviour,
 * same arguments. Test-only (the #44 gap-insertion tests are written against
 * this name); production and the detector use [withGapCharAt].
 */
fun LineResult.withGapAt(
    index: Int,
    column: Float,
): LineResult = withGapCharAt(index, column)
