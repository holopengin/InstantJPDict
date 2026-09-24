package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.GapAlternative
import uniffi.nav_graph_core.gapCandidatesMax
import uniffi.nav_graph_core.gapContextBefore
import uniffi.nav_graph_core.gapFallback
import uniffi.nav_graph_core.gapGenerate
import uniffi.nav_graph_core.gapIsOfferable
import uniffi.nav_graph_core.gapKanaDefaults
import uniffi.nav_graph_core.gapPunctDefaults

/**
 * What to offer for a blank (#44, Feature 2).
 *
 * A gap is only worth filling when there is evidence for what went there, so the pool is
 * the set of characters the recogniser itself offered along the line — its per-character
 * top-K — restricted to what a real character can be, and the language model then ranks
 * that pool in the line's own context. Shape evidence proposes, the text prior disposes:
 * the component ordering over an unrestricted pool is degenerate, which is why the LM earns
 * its asset here.
 *
 * Deliberately narrow: the pool is the line's own character space, not the whole component
 * closure. Widening it to `ComponentTable` carriers is a later step, and it needs its own
 * measurement before it ships.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core` implementation
 * (`core/src/util/gap_candidates.rs`, exposed through `nav_graph_core`'s UniFFI surface), so
 * the algorithm has one source of truth. The pool filter, the class order and the back-off
 * cap are documented there; this object only adapts types (`Char` ↔ `Int` code points,
 * `Pair<Char, Float>` ↔ the `GapAlternative` record) and keeps the API call sites use.
 */
object GapCandidates {
    /**
     * The candidate cap, read from `jpdict_core` at first use (UniFFI cannot export consts,
     * so this is a `val`; it is still fine as a default parameter value).
     */
    val MAX: Int = gapCandidatesMax().toInt()

    /**
     * Whether a character the recogniser proposed is worth offering. Kanji, kana and
     * punctuation alike: the gap in vertical Japanese text is very often a 読点 or a bracket
     * (a line's own evidence for it is punctuation), so a kanji-only pool comes back empty
     * exactly where the evidence was there.
     */
    fun isOfferable(ch: Char): Boolean = gapIsOfferable(ch.code)

    /**
     * Candidates for the blank at [index], best first. [alternatives] is the line's
     * per-character top-K (anything else is ignored), and [lm] reorders the pool; without
     * one the pool keeps its discovery order.
     */
    fun generate(
        text: String,
        alternatives: List<List<Pair<Char, Float>>>,
        index: Int,
        lm: CharLm?,
        limit: Int = MAX,
    ): List<Char> = gapGenerate(
        alternatives.map { step -> step.map { (ch, score) -> GapAlternative(ch.code, score) } },
        limit.toLong(),
        lm?.inner,
        contextCodepoints(text, index),
    ).map { Char(it) }

    /** Punctuation a gap most often holds. Class order is fixed; see [fallback]. */
    val PUNCT_DEFAULTS: List<Char> = gapPunctDefaults().map { Char(it) }

    /** The kana it most often holds when it is not punctuation. */
    val KANA_DEFAULTS: List<Char> = gapKanaDefaults().map { Char(it) }

    /**
     * The fallback list, punctuation first. Never empty.
     *
     * The class order is deliberately *not* the model's. Measured over the vertical benches,
     * an order-4 character model asked to prefer punctuation over the continuation is
     * really running a frequency contest — a comma is common and the character that follows
     * a gap is often rare — so it fires on ~1 in 7 ordinary positions at the strictest
     * margin and puts kana like の above 、 at a gap. The model orders *within* a class,
     * where the candidates are the same kind of thing and the comparison means something.
     */
    fun fallback(text: String, index: Int, lm: CharLm?, limit: Int = MAX): List<Char> =
        gapFallback(limit.toLong(), lm?.inner, contextCodepoints(text, index)).map { Char(it) }

    /**
     * The characters before the gap, which is the context the back-off chain can use: the
     * model is order 4, so anything longer is ignored and the placeholder itself is dropped
     * rather than read as a real character.
     */
    fun contextBefore(text: String, index: Int): CharSequence =
        buildString { for (cp in gapContextBefore(text, index.toLong())) appendCodePoint(cp) }

    /**
     * The context as the code points the Rust boundary takes; [generate] and [fallback]
     * hand it straight back so the code-point round trip never goes through a Kotlin
     * `String` (a supplementary-plane character stays one code point, not a surrogate pair).
     */
    private fun contextCodepoints(text: String, index: Int): List<Int> =
        gapContextBefore(text, index.toLong())
}
