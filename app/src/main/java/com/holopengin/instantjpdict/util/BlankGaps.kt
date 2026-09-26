package com.holopengin.instantjpdict.util

import android.content.Context
import com.holopengin.instantjpdict.JpDictRect
import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine
import com.holopengin.instantjpdict.rawTopK
import uniffi.nav_graph_core.BoundingBox
import uniffi.nav_graph_core.GapPlan
import uniffi.nav_graph_core.GapPlanLine
import uniffi.nav_graph_core.blankGapsPlan

/**
 * Feature 2 (#44): materialise a character the recogniser dropped as a **tappable blank**.
 *
 * The placeholder is [OcrEngine.GAP_CHAR] (U+25CC, a dotted circle). Tapping it opens the
 * alternatives panel — the dictionary lookup deliberately returns null for a placeholder —
 * and the panel always offers the manual IME entry, which is how the ground-truth character
 * gets typed in (decision 5: show nothing and stay clickable, because there is a manual
 * character entry mode). Filling a blank writes an ordinary `overrides[i]` entry, so it is
 * reversible exactly like any other correction.
 *
 * ## Why vertical only
 *
 * M5 measured the trigger per orientation, and they are not the same problem:
 *
 * | bench | rule | recall | false rate |
 * |---|---|---|---|
 * | vertical (`vert_large`) | spacing ratio >= 1.6 | 1.00 | 0.00% |
 * | horizontal (trails x2) | spacing ratio >= 1.6 | 0.60 | 13% |
 *
 * So a horizontal line is not eligible at all: the detector's own per-orientation threshold
 * would still admit gaps there, and 13% of ordinary horizontal intervals look like gaps.
 *
 * ## Idempotence
 *
 * Applying this twice must not insert twice. A line that already carries a placeholder is
 * returned unchanged, and insertions run right-to-left so the plan's indices (computed
 * against the original text) stay valid as the text grows.
 *
 * Since the util-core swap the detection lives in `jpdict_core::blank_gaps`; this object
 * keeps the eligibility policy and the identity short-circuits mobile relies on
 * (`assertSame` on an untouched line).
 *
 * ## The line does not cross
 *
 * `blankGapsApply` used to take the whole `LineResult` over the boundary and hand the whole
 * line back: the per-character alternatives and the per-timestep top-K lists came in only to
 * be cloned and grown by one entry, which is the largest per-line crossing on a recognised
 * page after the char boxes. Now only the **detector's own inputs** cross, in one record
 * argument, and what comes back is a [GapPlan] — the indices, columns and interpolated
 * boxes, plus the three "was this list full-length?" decisions, so nothing is re-measured
 * here in Kotlin's UTF-16 units. The insertion itself is [withGapInsertions] below, growing
 * the host's own lists.
 *
 * `rawAlternatives` is the detector's *last-resort* geometry source (after char boxes and CTC
 * columns) and the bulk of the old payload, so the first call leaves it out; the plan's
 * `needsRawAlternatives` says whether the walk would have been read, and only an empty plan
 * plus a "yes" spends a second call on it.
 */
object BlankGaps {
    const val PREF_ENABLED = "blank_gaps_enabled"
    const val DEF_ENABLED = true

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_ENABLED, DEF_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, enabled).apply()
    }

    /** [apply] when the setting is on, else the line unchanged. */
    fun applyIfEnabled(ctx: Context, line: LineResult): LineResult =
        if (isEnabled(ctx)) apply(line) else line

    /**
     * Insert a placeholder for every measured gap in [line], or return it unchanged when
     * the line is horizontal, already carries a placeholder, or has no gaps.
     */
    fun apply(line: LineResult): LineResult {
        if (!line.isVertical) return line
        if (line.text.indexOf(OcrEngine.GAP_CHAR) >= 0) return line
        // One call, one crossing: the detector's own inputs in, a plan out.
        // `rawAlternatives` is the detector's *last-resort* geometry (after the
        // char boxes and the CTC columns), so the first call leaves it out and
        // the plan says whether it would have been read. A gap the boxes can see
        // is unaffected by the raw lists, so only an empty plan *and* a "yes"
        // needs the second call — which on a recognised page is never.
        var plan = blankGapsPlan(line.toGapPlanLine(includeRawAlternatives = false))
        if (plan.needsRawAlternatives && plan.insertions.isEmpty()) {
            plan = blankGapsPlan(line.toGapPlanLine(includeRawAlternatives = true))
        }
        // Nothing inserted: keep the receiver's identity (the tests assertSame).
        if (plan.insertions.isEmpty()) return line
        return line.withGapInsertions(plan)
    }
}

/**
 * The subset of this line the gap planner reads, as the exported record.
 *
 * [includeRawAlternatives] is false for the first call of a recognised line: the
 * per-timestep top-K lists are the detector's last-resort geometry, and by far
 * the largest thing this pipeline would otherwise hand over and get back.
 *
 * `internal` so the on-device parity/benchmark harness measures the payload the
 * facade really hands over instead of a second copy of it.
 */
internal fun LineResult.toGapPlanLine(includeRawAlternatives: Boolean): GapPlanLine = GapPlanLine(
    text = text,
    isVertical = isVertical,
    charBoxes = charBoxes.map { BoundingBox(it.left, it.top, it.right - it.left, it.bottom - it.top) },
    charCols = charCols.toList(),
    alternativesLen = alternatives.size.toLong(),
    // The compact table's own `GapCell`s, aliased row by row: a recognised line
    // keeps its top-15 as flat cells, so the rare second call no longer has to
    // expand 15 `Pair` + boxed `Float` objects per timestep to cross them.
    rawAlternatives = if (includeRawAlternatives) rawTopK.gapCellRows() else emptyList(),
    cropW = cropW,
    cropH = cropH,
    seqLenTotal = seqLenTotal,
)

/**
 * Grow every parallel per-character list for one [GapPlan] (mobile
 * `LineResult.withGapAt`, run once per planned gap).
 *
 * Right-to-left, so the plan's indices — computed against the original text — stay valid as
 * the text grows: a placeholder planned for index `i` ends up at `i` plus one per earlier
 * insertion. [GapPlan] says which lists grow, and this honours it verbatim:
 *
 *  - `text` gains [OcrEngine.GAP_CHAR] at the index (in code points, which is what the plan
 *    counts — a supplementary-plane character is one entry in a Kotlin `String` that is two
 *    UTF-16 units long, and the per-character lists are indexed in code points).
 *  - `charBoxes` gains the planned interpolated box, `alternatives` the synthetic
 *    placeholder entry ([OcrEngine.GAP_CHAR] at score 0), and `charCols` the planned column.
 *  - `overrides` keys move: every key `>= index` shifts by +1, so an applied correction or a
 *    filled blank keeps pointing at the character it was applied to. The placeholder itself
 *    carries none.
 *
 * A list the plan says does not grow is left exactly as it was ("empty means unknown" — one
 * entry cannot describe `n + 1` characters), which is the same rule
 * `LineResult.withGapCharAt` applies.
 */
internal fun LineResult.withGapInsertions(plan: GapPlan): LineResult {
    var text = this.text
    var boxes = charBoxes
    var alts = alternatives
    var cols = charCols
    var shifted = overrides.map { (index, value) -> index to value }
    for (insertion in plan.insertions.asReversed()) {
        val index = insertion.index.toInt()
        text = text.insertAtCodePoint(index, OcrEngine.GAP_CHAR)
        if (plan.growsCharBoxes) {
            val b = boxes.toMutableList()
            b.add(
                index,
                JpDictRect(
                    insertion.placeholderBox.x,
                    insertion.placeholderBox.y,
                    insertion.placeholderBox.x + insertion.placeholderBox.w,
                    insertion.placeholderBox.y + insertion.placeholderBox.h,
                ),
            )
            boxes = b
        }
        if (plan.growsAlternatives) {
            val a = alts.toMutableList()
            a.add(index, mutableListOf(OcrEngine.GAP_CHAR to 0f))
            alts = a
        }
        if (plan.growsCharCols) {
            cols = cols.insertAt(index, insertion.column)
        }
        shifted = shifted.map { (key, value) -> (if (key >= index) key + 1 else key) to value }
    }
    return copy(
        text = text,
        charBoxes = boxes,
        alternatives = alts,
        // A fresh array, like the boundary conversion produced: `LineResult`
        // is a data class and a FloatArray compares by identity.
        charCols = cols.copyOf(),
        // Ascending by key, like the boundary's `BTreeMap` → record list → map.
        overrides = shifted.sortedBy { it.first }
            .associateTo(LinkedHashMap()) { (index, value) -> index to value },
    )
}

/** [Char] at a **code point** index, not a UTF-16 unit index. */
private fun String.insertAtCodePoint(index: Int, ch: Char): String {
    var at = 0
    var seen = 0
    while (at < length && seen < index) {
        at += if (at + 1 < length &&
            Character.isHighSurrogate(this[at]) &&
            Character.isLowSurrogate(this[at + 1])
        ) {
            2
        } else {
            1
        }
        seen++
    }
    return substring(0, at) + ch + substring(at)
}

private fun FloatArray.insertAt(index: Int, value: Float): FloatArray {
    val out = FloatArray(size + 1)
    copyInto(out, 0, 0, index)
    out[index] = value
    copyInto(out, index + 1, index, size)
    return out
}
