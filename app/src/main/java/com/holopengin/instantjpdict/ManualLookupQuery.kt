package com.holopengin.instantjpdict

/**
 * #108 follow-up: romaji is part of the ordinary Japanese lookup now.
 *
 * A box containing Japanese goes through unchanged. A box containing only
 * ASCII goes through [Romaji] first. The katakana fallback is separate because
 * a hiragana word and its katakana spelling are the same query to a reader but
 * not to the database: てすと and テスト are different strings, and the
 * dictionary usually carries the katakana one.
 */
object ManualLookupQuery {

    private val HIRAGANA = '\u3041'..'\u3096'
    private val KATAKANA = '\u30A1'..'\u30FA'
    private val KANJI = '\u4E00'..'\u9FFF'
    private val KANJI_EXT = '\u3400'..'\u4DBF'
    private val HALF_WIDTH_KATAKANA = '\uFF66'..'\uFF9D'
    private val PROLONGED_SOUND = '\u30FC'

    /** True when the text already contains something Japanese. */
    fun hasJapanese(text: String): Boolean = text.any {
        it in HIRAGANA || it in KATAKANA || it in KANJI || it in KANJI_EXT ||
            it in HALF_WIDTH_KATAKANA || it == PROLONGED_SOUND
    }

    /**
     * The first query to try. Japanese passes through; ASCII is converted from
     * romaji. A blank box yields null.
     */
    fun primary(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (hasJapanese(trimmed)) trimmed else Romaji.toKana(trimmed)
    }

    /**
     * Katakana spellings to try when the primary query found nothing. Only
     * hiragana-bearing, kanji-free queries get this: a kanji query has no
     * meaningful katakana form, and a query that is already katakana is its own
     * fallback.
     */
    fun katakanaFallbacks(primary: String): List<String> {
        if (primary.none { it in HIRAGANA }) return emptyList()
        if (primary.any { it in KANJI || it in KANJI_EXT }) return emptyList()
        return Romaji.katakanaVariants(primary)
    }
}
