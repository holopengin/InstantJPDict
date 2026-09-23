package com.holopengin.instantjpdict.util

import android.content.Context
import uniffi.nav_graph_core.CharLm as RustCharLm
import uniffi.nav_graph_core.charLmFromBytes

/**
 * Character n-gram language model (#44, Feature 2): the text prior that ranks candidates
 * where shape alone cannot — the deletion pools are ~767 candidates on median and the
 * component ordering over them is degenerate, so the LM is the only ranker available.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core` implementation
 * (`core/src/util/char_lm.rs`, exposed through `nav_graph_core`'s UniFFI surface), so the
 * algorithm has one source of truth. The packed-table format and ranking rules are
 * documented there; this class only adapts types (`Char` ↔ `Int` code points, `UInt` →
 * `Int`) and keeps the API call sites already use.
 */
class CharLm private constructor(internal val inner: RustCharLm) {
    /** Occurrences of [ngram], or 0 when it is unknown (or longer than the model's order). */
    fun count(ngram: CharSequence): Int = inner.count(ngram.toCodePoints()).toInt()

    /**
     * log P([ch] | [context]): the longest context the model knows, backed off one character
     * at a time, and a unigram prior when nothing longer is known. Unknown characters score
     * `-30f` so they sort last rather than tie with real evidence.
     */
    fun logProb(context: CharSequence, ch: Char): Float =
        inner.logProb(context.toCodePoints(), ch.code)

    /**
     * [candidates] ordered by how well [context] predicts them, best first. Ties keep the
     * input order, so a caller's own ranking still breaks them deterministically.
     */
    fun rank(context: CharSequence, candidates: List<Char>): List<Char> =
        inner.rank(context.toCodePoints(), candidates.map { it.code }).map { Char(it) }

    companion object {
        /** Mirrors `jpdict_core::util::char_lm::MAX_ORDER`; the packed table's order. */
        const val MAX_ORDER = 4

        /** Wrap packed bytes. Null when the header or length does not describe a table. */
        fun fromBytes(bytes: ByteArray): CharLm? =
            charLmFromBytes(bytes)?.let { CharLm(it) }

        /** Load the shipped model, or null when the asset is missing or malformed. */
        fun load(context: Context): CharLm? = try {
            context.assets.open("lm/char_lm.bin").use { fromBytes(it.readBytes()) }
        } catch (e: Exception) {
            null
        }

        private fun CharSequence.toCodePoints(): List<Int> = List(length) { this[it].code }
    }
}
