package com.holopengin.instantjpdict.util

import uniffi.nav_graph_core.definitionFormatRedirectTargets

/**
 * JMdict redirect (pointer-entry) handling (#65).
 *
 * Variant spellings / search-only forms are entries whose definitions carry
 * no glossary text — only `a`-tag links with `?query=<headword>&wildcards=off`
 * hrefs (e.g. あかーん → あかん, rendered today as dead "⟶, あかん" text).
 * [extractTargets] returns those headwords so lookup can resolve them;
 * entries with any real definitional content yield nothing (their `see also`
 * links are not redirects).
 *
 * Since the definition-format swap this is a thin facade over the PC
 * `jpdict_core` implementation (`core/src/definition_format.rs`, exposed
 * through `nav_graph_core`'s UniFFI surface), so the redirect rule has one
 * source of truth. The public API is unchanged.
 */
object DictionaryRedirects {
    /**
     * Headwords this entry redirects to, or empty when the entry is not a
     * pure pointer. Malformed JSON also yields empty. Capped at
     * [maxTargets] (observed data tops out at 2).
     */
    fun extractTargets(definitionsJson: String, maxTargets: Int = 3): List<String> =
        definitionFormatRedirectTargets(definitionsJson, maxTargets.toLong())
}
