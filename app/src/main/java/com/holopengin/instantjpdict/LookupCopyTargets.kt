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
}
