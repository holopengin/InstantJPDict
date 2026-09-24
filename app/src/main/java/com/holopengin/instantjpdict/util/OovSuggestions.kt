package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.SuggestionSource as RustSuggestionSource
import uniffi.nav_graph_core.oovSuggestionsAssemble
import uniffi.nav_graph_core.oovSuggestionsMaxComponentCandidates
import uniffi.nav_graph_core.oovSuggestionsMaxVariantCandidates

/**
 * Component-derived alternatives for a recognised character (#44, step 1).
 *
 * The head can only emit characters it has a class for, so its own top-15 can never
 * contain the character that was actually in the book when that character is outside its
 * vocabulary (`呟` 6,930 occurrences, `伜`, `壜` — measured: 86% of the emittable gap is
 * upstream of our prune, so no re-export reaches them). At the measured tier — neighbours
 * sharing IDF mass ≥ 0.7 with the emitted character — the right character lands in a
 * ~14-entry list for 12 of 59 measured substitutions with the truth top-3 in all 12, and
 * deletions get a pool covering 28 of 38.
 *
 * **Nothing here changes recognised text.** These are extra entries in a popup the user
 * already opens, so there is no over-correction risk; the measured over-correction budget
 * governs *auto-apply*, which is parked (see `docs/ocr-oov-correction-plan.md`).
 *
 * **Always on.** The suggestions are assembled at every call site that builds the list, with
 * no preference read and no gate: a stored `oov_suggestions_enabled=false` from an install
 * that predates this is inert, and nothing consults it. The measured in-list behaviour is the
 * feature, so there is nothing left to toggle between.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core` implementation
 * (`core/src/util/oov_suggestions.rs`, exposed through `nav_graph_core`'s UniFFI surface),
 * so the assembly rules have one source of truth. [assemble]'s `variantForms` stays a
 * lambda — its default is the single-sourced [KanjiVariants.obsoleteFormsOf] and the tests
 * pass custom lambdas — but a closure cannot cross the UniFFI boundary, so the facade
 * resolves it to the form list before the call and the Rust shim rebuilds the constant
 * closure internally.
 */
object OovSuggestions {
    /**
     * Caps on the generated groups, read from `jpdict_core` at first use (UniFFI cannot export
     * consts). Raised from 5/3 to 15/15 at the maintainer's request (#44): tapping a generated
     * entry rebuilds the list around it, so a longer list is what makes the kanji form space
     * walkable, and the popup scrolls. The measured ranking still decides the order, so the
     * useful entries stay at the front.
     */
    val MAX_COMPONENT_CANDIDATES: Int = oovSuggestionsMaxComponentCandidates().toInt()
    val MAX_VARIANT_CANDIDATES: Int = oovSuggestionsMaxVariantCandidates().toInt()

    /** Where a popup entry came from. The panel tints non-HEAD entries. */
    enum class Source { HEAD, COMPONENTS, VARIANT, LM }

    data class Suggestion(val char: Char, val source: Source)

    /**
     * The popup list for one character: the head's own ranking first and unchanged (it is
     * preferred whenever it is right), then component neighbours by descending IDF mass,
     * then the obsolete variant forms of the character itself (offered, never applied —
     * a fold replaces the lookup key, so the variant direction is guarded upstream).
     *
     * Duplicates are dropped across groups, the current character is always present so the
     * panel can mark it, and each generated group is capped.
     *
     * The list is assembled by `jpdict_core` through `oovSuggestionsAssemble`; this facade
     * only converts `Char` ↔ `Int` code points and maps the Rust source enum back to
     * [Source]. [variantForms] is resolved here for [current] because a closure cannot
     * cross the boundary — the default still resolves through the UniFFI-backed
     * [KanjiVariants.obsoleteFormsOf].
     */
    fun assemble(
        current: Char,
        headAlternatives: List<Char>,
        oov: OovCandidates?,
        variantForms: (Char) -> List<Char> = { KanjiVariants.obsoleteFormsOf(it) },
    ): List<Suggestion> =
        oovSuggestionsAssemble(
            current.code,
            headAlternatives.map { it.code },
            oov?.inner,
            variantForms(current).map { it.code },
        ).map { Suggestion(Char(it.ch), sourceOf(it.source)) }

    /** Map the Rust provenance back onto the Kotlin enum the panel tints by. */
    private fun sourceOf(source: RustSuggestionSource): Source = when (source) {
        RustSuggestionSource.HEAD -> Source.HEAD
        RustSuggestionSource.COMPONENTS -> Source.COMPONENTS
        RustSuggestionSource.VARIANT -> Source.VARIANT
        RustSuggestionSource.LM -> Source.LM
    }
}
