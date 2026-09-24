package com.holopengin.instantjpdict.util

import android.content.Context
import android.util.Log
import com.holopengin.instantjpdict.KanaSizeNcnn
import com.holopengin.instantjpdict.LineResult
import com.holopengin.instantjpdict.OcrEngine
import uniffi.nav_graph_core.KanaSizeScorer
import uniffi.nav_graph_core.kanaSizeCorrectLines
import uniffi.nav_graph_core.kanaSizeEpsilon

/**
 * Kana size correction (#44): let the byte-CNN decide the small/big form of a confusable
 * position, but only where the orthography allows it.
 *
 * **Always on.** The correction is applied to every page, with no preference read and no gate,
 * so a stored `kana_size_fix_enabled=false` from an install that predates this is inert. The
 * ε tunable below is the only setting left.
 *
 * ## The rule
 *
 * For each position whose character is a member of a size pair, the model emits p(big):
 *
 * - flip small -> big when `p > 1 - ε`
 * - flip big -> small when `p < ε`
 * - leave the middle band alone
 *
 * Since the util-core swap the policy itself lives in `jpdict_core::kana_size`
 * (`correct_lines`), which owns the candidate enumeration, the window batching, the
 * sigmoid and the ε bands; this object is the host side. The scorer is the JNI
 * [KanaSizeNcnn] (or a test lambda) and crosses into Rust as a
 * `KanaSizeScorer` callback — call-and-return, never stored.
 *
 * ε is [EPSILON] = 0.01. The measurement behind it (7,620 confusable bench positions, the
 * grounded rule applied at three epsilon bands) reproduced the app's original +8/+10/+5 on the
 * v2 artifact exactly, which is the check that the harness is wired right.
 *
 * The shipped artifact is nb_all. Its case is era, not modern accuracy:
 *
 * - on modern rows, nb_all sits ~4 restored flips per 6,274 positions (0.06%) behind a
 *   modern-only model — below the noise of a re-render;
 * - on pre-reform text a modern-only model is confidently wrong in one direction, calling a
 *   legitimate large つ small. Measured against JMdict — the headwords a lookup actually has to
 *   reach, not the book's own orthography — that destroys 29 reachable headwords on the legacy
 *   slice and restores 2, where nb_all destroys none and restores 2.
 *
 * A pre-reform *gate* was tried and removed. It decided page-wide from a kana ratio, so a small
 * scroll could flip a whole page's correction off, and with era-inclusive training the pattern it
 * keyed on no longer occurs. Choosing the artifact replaces it.
 *
 * The numbers above belong to the artifact that shipped; re-derive them with the bench if that
 * changes.
 *
 * ## Reversibility
 *
 * A flip writes the same `overrides[i]` entry a manual correction writes, so it is visible in
 * the text and undoable exactly like any other correction. The core returns the flip list; this
 * facade projects it onto the app's `LineResult.overrides` (the core has no such field).
 */
object KanaSizeFix {
    /**
     * Certainty required to flip, read from `jpdict_core` at first use (the measured default;
     * UniFFI cannot export consts). The middle band is deliberately left untouched.
     */
    val EPSILON: Float = kanaSizeEpsilon()

    /** Tunable copy of [EPSILON], so the threshold can be found on-device without a rebuild. */
    const val PREF_EPSILON = "kana_size_epsilon"
    val DEF_EPSILON: Float = EPSILON

    fun epsilon(ctx: Context): Float =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(PREF_EPSILON, DEF_EPSILON)

    private const val TAG = "KanaSizeFix"

    /**
     * The positions the model declined to change, lowest confidence first, as
     * `L<line>@<index> <char> p=<p>`. Deliberately no surrounding text: this is copied out of the
     * app and shared, so it must not carry the book's own words.
     */
    @Volatile
    var lastDeclined: String = ""
        private set

    /**
     * Apply the correction to a whole page. Unconditional: no preference is read, so a stored
     * `kana_size_fix_enabled=false` from an install that predates this cannot suppress it.
     */
    fun correctPage(ctx: Context, lines: List<LineResult>): List<LineResult> =
        try {
            val model = KanaSizeNcnn.load(ctx)
            if (model == null) {
                lines
            } else {
                apply(
                    lines,
                    score = { wins, bases -> model.logits(wins, bases) },
                    epsilon = epsilon(ctx))
            }
        } catch (t: Throwable) {
            // Throwable, not Exception: an UnsatisfiedLinkError from the native library is an
            // Error, and catching only Exception would let it take down recognition.
            Log.e(TAG, "kana size correction failed", t)
            lines
        }

    /**
     * The policy, with scoring injected so it is testable on the JVM without the native layer.
     * [score] takes `n * 40` window bytes and `n` pair indices and returns `n` logits; it is
     * wrapped as the Rust policy's `KanaSizeScorer` callback. A null or wrong-length result
     * leaves the page untouched, exactly as before.
     */
    internal fun apply(
        lines: List<LineResult>,
        score: (IntArray, IntArray) -> FloatArray?,
        /** Certainty required to flip; the ε tunable, or the measured default. */
        epsilon: Float = EPSILON,
    ): List<LineResult> {
        if (lines.isEmpty()) return lines
        val scorer = object : KanaSizeScorer {
            override fun score(windows: List<Int>, bases: List<Int>): List<Float>? =
                score(windows.toIntArray(), bases.toIntArray())?.toList()
        }
        val correction = kanaSizeCorrectLines(lines.map { it.text }, scorer, epsilon)
        lastDeclined = correction.declinedSummary
        if (correction.flips.isEmpty()) return lines

        val out = lines.toMutableList()
        for ((li, flips) in correction.flips.groupBy { it.line }) {
            val line = lines[li]
            val overrides = LinkedHashMap(line.overrides)
            // Each flip is the same `overrides[i] = char to p` entry a manual
            // correction writes; the corrected text comes from the core.
            for (f in flips) {
                if (f.index !in line.text.indices) continue
                overrides[f.index] = Char(f.to) to f.pBig
            }
            out[li] = line.copy(text = correction.texts[li], overrides = overrides)
        }
        return out
    }
}
