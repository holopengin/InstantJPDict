package com.holopengin.instantjpdict.util

import java.io.Reader
import uniffi.nav_graph_core.Deinflector as RustDeinflector
import uniffi.nav_graph_core.deinflectorFromJsonStr

data class DeinflectionResult(
    val term: String,
    /** Human-readable reason labels, outermost step first (e.g. ["past"]). */
    val reasons: List<String>,
    val type: List<String>
)

/** Surface form plus the deinflection steps that produced a dictionary term.
 *  Carried alongside lookup candidates so the popup can show the chain
 *  (e.g. 食べた → 食べる · past) without extra deinflect runs. */
data class DeinflectionChain(
    val surface: String,
    val steps: List<String>
) {
    /** Compact one-line rendering: "surface → term · step1 · step2". */
    fun label(term: String): String =
        if (steps.isEmpty()) "$surface → $term"
        else "$surface → $term · " + steps.joinToString(" · ")
}

/**
 * Deinflects Japanese text by applying known conjugation rules.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/deinflector.rs`, exposed through
 * `nav_graph_core`'s UniFFI surface), so the algorithm has one source of truth.
 * Rules are parsed in Rust; this class only reads the rule text and maps the
 * returned record's `ruleTypes` field onto [DeinflectionResult.type], keeping
 * the API call sites already use.
 */
class Deinflector private constructor(private val inner: RustDeinflector) {
    /** Parse the rule JSON behind [reader]. Malformed or unreadable rules yield
     *  an empty deinflector — the pre-swap swallow-to-empty behaviour. */
    constructor(reader: Reader) : this(load(reader))

    fun deinflect(text: String): List<DeinflectionResult> =
        inner.deinflect(text).map { DeinflectionResult(it.term, it.reasons, it.ruleTypes) }

    companion object {
        private fun load(reader: Reader): RustDeinflector = try {
            deinflectorFromJsonStr(reader.readText()) ?: RustDeinflector.empty()
        } catch (e: Exception) {
            RustDeinflector.empty()
        }
    }
}
