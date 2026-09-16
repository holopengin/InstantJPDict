package com.holopengin.instantjpdict

/**
 * #67 follow-up: the character the bookmark toggle shows.
 *
 * The toggle lives in the top-right corner of a dictionary entry and must not
 * add height to the entry, so it carries one glyph and no text at all — a
 * "☆ Bookmark" label was what made the old row its own line. Kept Android-free
 * so the one-star rule is asserted in unit tests rather than by eye.
 */
object BookmarkGlyph {
    const val EMPTY = "☆" // U+2606 WHITE STAR
    const val FILLED = "★" // U+2605 BLACK STAR

    fun of(saved: Boolean): String = if (saved) FILLED else EMPTY
}
