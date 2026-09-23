package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.OovCandidates as RustOovCandidates

/**
 * Component-level candidate policy for out-of-vocabulary characters (#44).
 *
 * Two shapes of the OOV failure are served here, both measured against the vendored
 * KRADFILE table ([ComponentTable]) rather than guessed:
 *
 *  - **deletion** — the head emits blank where a character should be, e.g. `と呟いて`
 *    -> `といて`. The head's top-K at that timestep is radical-consistent
 *    (`咳 咬 啦 哮 眩`), and [majorityComponents] turns that free evidence into the
 *    components a candidate must carry.
 *  - **substitution** — the head is confidently wrong and emits a component-space
 *    neighbour (`壜` -> `曇`, `俥` -> `庫`): 45 of 51 measured substitutions (88%)
 *    share a component with the truth. [neighboursOf] generates that neighbour set
 *    from the character the head actually emitted.
 *
 * Ranking is deliberately *not* done here — a text n-gram cannot separate `と呟いて`
 * from `と咲いて`, so the ordering this class exposes is component evidence only, and
 * the caller adds whatever LM it has. Do not quote a rank from this list without the
 * pool size beside it: the neighbour pool is large (median ~2.5 k when only one
 * component is shared), which is exactly why the IDF weighting exists.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/oov_candidates.rs`, exposed through `nav_graph_core`'s
 * UniFFI surface), so the algorithm has one source of truth. Both measured rules — the
 * intersected IDF fraction and the majority vote — are documented there; this class only
 * adapts types (`Char` ↔ `Int` code points) and keeps the API call sites already use.
 * The [ComponentTable] is shared with the Rust policy, not cloned.
 */
class OovCandidates(table: ComponentTable) {

    /**
     * The UniFFI handle this facade delegates to (`uniffi.nav_graph_core.OovCandidates`).
     * `internal` so a later shim in this module that takes it across the boundary (WP-10
     * `oov_suggestions`) can reach the installed policy; nothing public changes.
     */
    internal val inner: RustOovCandidates = RustOovCandidates(table.inner)

    /**
     * A character that could stand where [char] was emitted, with the strength of the
     * visual relation to the emitted character.
     *
     * @param char the candidate character
     * @param idfFraction share of the emitted character's component information (IDF
     *   mass) that this candidate also carries, in `[0, 1]`. Intersected, never the
     *   candidate's whole mass — see [neighboursOf].
     * @param sharesAllComponents the candidate carries **every** component of the
     *   emitted character — the near-identity relation (measured: ~4 candidates,
     *   right character first in 9/9 cases, the only configuration where
     *   auto-insertion was defensible, with n = 9)
     */
    data class Candidate(
        val char: Char,
        val idfFraction: Float,
        val sharesAllComponents: Boolean,
    )

    /**
     * Candidate characters for a character the head **emitted** (the substitution
     * mode), ordered by [Candidate.idfFraction] descending, ties broken by codepoint
     * so the list is deterministic.
     *
     * The pool is every kanji sharing **at least one** component with [emitted]
     * (excluding [emitted] itself). That is the measured pair of rules — "share any
     * component" covers 23/26 cases at ~2,455 candidates, and "share the rarest
     * component" trades coverage for a ~120-candidate list — so this method returns
     * the wide set and lets the caller gate on [Candidate.idfFraction] or
     * [Candidate.sharesAllComponents]. Sharing more *components* is the wrong
     * yardstick: of 51 substitutions 28 share exactly one and 6 share none, while
     * sharing `車` is not the evidence sharing `一` is.
     *
     * **The fraction MUST intersect.** The numerator is the IDF of the components the
     * candidate *shares* with the emitted character; the denominator is the emitted
     * character's whole IDF mass. An earlier version used the candidate's own total
     * component mass instead, which rewards carrying many common components and
     * scored 0/45 on the ranking task — meaningless. Signature detail kept from the
     * reference `idf_frac(a, b)`: a duplicate component in the emitted character's
     * decomposition would be summed twice in the denominator, so both are consumed
     * as the table stores them.
     *
     * An unknown character, or one whose components carry no IDF mass, yields an
     * empty list rather than an exception.
     */
    fun neighboursOf(emitted: Char): List<Candidate> =
        inner.neighboursOf(emitted.code).map {
            Candidate(
                char = Char(it.char),
                idfFraction = it.idfFraction,
                sharesAllComponents = it.sharesAllComponents,
            )
        }

    /**
     * Whether component evidence can discriminate at all for this character.
     *
     * The IDF fraction is measured **relative to the emitted character**, so a character
     * with a single component makes every one of its carriers a full match (fraction 1.0):
     * the tier then admits hundreds of unrelated characters and a cap picks between them by
     * codepoint — noise presented as evidence. Two or more components are what the
     * measurements rely on, because a common one dilutes the fraction below the tier
     * (`仲` = 化+中 shares only `中` at 0.22, and `中` plus a rare component at 0.41).
     */
    fun hasDiscriminatingComponents(emitted: Char): Boolean =
        inner.hasDiscriminatingComponents(emitted.code)

    /**
     * The components a top-K of characters **agree on**, by majority vote: a component
     * carried by at least [needFraction] of [topK] (at least one character). Ordered
     * strongest first — by how many of the top-K carry it, then by codepoint.
     *
     * **Majority voting, never a strict intersection.** For the measured top-5
     * `咳 咬 啦 哮 眩` the strict intersection is empty — `眩` (亠 幺 玄 目) has no 口 —
     * so intersecting all five filters every candidate away and the recovery silently
     * does nothing. The vote keeps 口 (4/5) and 亠 (3/5), and both are components of
     * the dropped `呟` (亠 口 幺 玄).
     *
     * `counts` counts *characters*, not component occurrences: a character repeating a
     * component in its decomposition still contributes one vote, mirroring
     * `set(table.get(c, []))` in the reference `shared_components`.
     *
     * If the vote leaves nothing (a high [needFraction], or a top-K with no agreement)
     * the single strongest component is returned rather than an empty set — the caller
     * then has a one-component filter instead of no filter at all. An empty [topK], or
     * one whose characters are all unknown, returns an empty list.
     *
     * @param needFraction share of [topK] that must carry a component; the default 0.5
     *   is the measured threshold.
     */
    fun majorityComponents(topK: List<Char>, needFraction: Double = 0.5): List<Char> =
        inner.majorityComponents(topK.map { it.code }, needFraction).map { Char(it) }
}
