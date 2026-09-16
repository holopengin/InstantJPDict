package com.holopengin.instantjpdict

/**
 * #66: one string a lookup offers to copy.
 *
 * [label] is the affordance/confirmation text, [clipLabel] is the
 * `ClipDescription` label handed to
 * [android.content.ClipData.newPlainText], and [value] is the exact string
 * placed on the clipboard.
 */
data class CopyTarget(
    val label: String,
    val clipLabel: String,
    val value: String,
)

/**
 * #66: the copy targets for one lookup — the highlighted surface text and the
 * dictionary-form headword.
 *
 * Pure and Android-free on purpose. The view renders whatever this returns, so
 * the trigger (popup buttons today; a long-press later) can be swapped without
 * touching *which* string is copied, and the two strings stay pinned by unit
 * tests. The headword is [FormattedEntry.term] — the dict-form term — never
 * the furigana/reading-annotated display pair, so a deinflected or redirected
 * lookup copies 食べる while the highlight stays 食べた.
 */
object LookupCopyTargets {
    const val HIGHLIGHT_CLIP_LABEL = "lookup-highlight"
    const val HEADWORD_CLIP_LABEL = "lookup-headword"
    const val CHARACTER_CLIP_LABEL = "lookup-character"

    /**
     * The targets for one lookup, highlight first — the order the popup shows
     * them. [surfaceRun] is the OCR text from the tapped character onward
     * (`Result.cacheKey`) and [maxLen] the matched surface length
     * (`Result.maxLen`), so `surfaceRun.take(maxLen)` is exactly the substring
     * the overlay highlights. The headword is the top-ranked entry's
     * [FormattedEntry.term]; it is dropped when empty, or when it equals the
     * highlight because a direct lookup then has nothing extra to offer.
     */
    fun targets(surfaceRun: String, maxLen: Int, entries: List<FormattedEntry>): List<CopyTarget> {
        val out = mutableListOf<CopyTarget>()
        val highlight = surfaceRun.take(maxLen.coerceAtLeast(0))
        if (highlight.isNotEmpty()) {
            out += CopyTarget("Copy highlight", HIGHLIGHT_CLIP_LABEL, highlight)
        }
        headword(entries)
            ?.takeIf { it.isNotEmpty() && it != highlight }
            ?.let { out += CopyTarget("Copy headword", HEADWORD_CLIP_LABEL, it) }
        return out
    }

    /** The dictionary-form term of the top-ranked entry, or null when there is none. */
    fun headword(entries: List<FormattedEntry>): String? = entries.firstOrNull()?.term

    /**
     * The confirmation shown after a copy — the copied string, then "copied".
     *
     * The maintainer's wording: a sentence about what happened ("食べる copied")
     * rather than a label ("Copied: 食べる"). Pure so the phrasing is pinned in
     * unit tests: the Android 13+ system clipboard chip is the nicer feedback
     * but is the platform's call to show, so this toast is the channel the app
     * actually guarantees and the one worth asserting.
     */
    fun copiedConfirmation(value: String): String = "$value copied"

    /**
     * The headword target alone, for the entry long-press (#66 follow-up).
     *
     * Unlike [targets] this never suppresses the headword for equalling the
     * highlight: a long-press on an entry is an unambiguous request for *that
     * entry's* headword, so there is no "nothing extra to offer" case to fold
     * away. Returns null only when the entry has no term at all.
     */
    fun headwordTarget(entries: List<FormattedEntry>): CopyTarget? =
        headword(entries)
            ?.takeIf { it.isNotEmpty() }
            ?.let { CopyTarget("Copy headword", HEADWORD_CLIP_LABEL, it) }

    /**
     * The menu a long-press on a neighbour-list character offers (#66
     * follow-up) — all three strings that position makes available:
     *
     *  - the highlighted surface run ([surfaceRun] trimmed to [maxLen]), which
     *    is what the overlay currently paints yellow;
     *  - the single character that was long-pressed ([character]);
     *  - the dictionary headword ([entries]), for when the tap landed on a
     *    deinflected form and the dict form is what the user wants.
     *
     * The character item is dropped when it is identical to the highlight (a
     * one-character lookup would otherwise offer the same string twice), and
     * the headword when it has no term. Order is highlight, character,
     * headword: the same highlight-first order [targets] uses, so the two
     * menus read consistently.
     */
    fun neighbourMenu(
        surfaceRun: String,
        maxLen: Int,
        character: String,
        entries: List<FormattedEntry>,
    ): List<CopyTarget> {
        val out = mutableListOf<CopyTarget>()
        val highlight = surfaceRun.take(maxLen.coerceAtLeast(0))
        if (highlight.isNotEmpty()) {
            out += CopyTarget("Copy highlight", HIGHLIGHT_CLIP_LABEL, highlight)
        }
        character.takeIf { it.isNotEmpty() && it != highlight }
            ?.let { out += CopyTarget("Copy character", CHARACTER_CLIP_LABEL, it) }
        headword(entries)
            ?.takeIf { it.isNotEmpty() }
            ?.let { out += CopyTarget("Copy headword", HEADWORD_CLIP_LABEL, it) }
        return out
    }
}
