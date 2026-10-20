package com.holopengin.instantjpdict

import java.util.Locale

/**
 * #108: Hepburn-style romaji to kana for the manual lookup's romaji mode.
 *
 * This is a front door to the existing kana lookup, not a second search path:
 * "shinjuku" becomes しんじゅく and the ordinary term chain runs from there.
 * The table covers Hepburn and the spellings people actually type (si/shi,
 * tu/tsu, zi/ji, hu/fu, di/du), the small-kana `x`/`l` forms, doubled
 * consonants (がっこう) and the `n`/`m` before a consonant (しんぶん).
 *
 * Ambiguities that need word context are not guessed. `kinyoubi` is parsed as
 * きにょうび rather than きんようび because `nyo` is a valid syllable; the
 * apostrophe form `kin'youbi` is the explicit way to ask for ん. Japanese
 * characters pass through untouched, so a user who switches modes with kana
 * already in the box is not punished for it.
 */
object Romaji {

    private val TABLE: List<Pair<String, String>> = listOf(
        // Vowels and the common small-kana spellings.
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
        "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
        "xtu" to "っ", "xtsu" to "っ", "ltu" to "っ", "ltsu" to "っ",
        "xwa" to "ゎ", "lwa" to "ゎ",

        // K
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "kwa" to "くぁ", "kwi" to "くぃ", "kwu" to "くぅ", "kwe" to "くぇ", "kwo" to "くぉ",

        // S
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "she" to "しぇ",

        // T
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ",
        "te" to "て", "to" to "と",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "che" to "ちぇ",
        "thi" to "てぃ", "dhi" to "でぃ",

        // N
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",

        // H
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ", "fyu" to "ふゅ",

        // M
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",

        // Y
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",

        // R
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",

        // W
        "wa" to "わ", "wo" to "を", "wi" to "うぃ", "we" to "うぇ",

        // G
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",

        // Z / J
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",

        // D
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",

        // B
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",

        // P
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",

        // V (rare, but cheap and unambiguous)
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
    ).sortedByDescending { it.first.length }

    private val CONSONANTS = "bcdfghjklmnpqrstvwxyz".toSet()

    /** Convert [input] to kana, preserving anything that is not romaji. */
    fun toKana(input: String): String {
        val text = normalizeMacrons(input.lowercase(Locale.ROOT))
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)

            // ん: n before a consonant, at the end, or before an apostrophe.
            // n before a vowel or y is a syllable (na/ni/nya), so the table
            // gets those first.
            if (c == 'n' && (next == null || next == '\'' || (next !in "aiueoy" && next !in "AIUEOY"))) {
                out.append('ん')
                i += if (next == '\'') 2 else 1
                continue
            }

            // ん: the common "shimbun" / "kanpai" spelling of m before b/p/m.
            if (c == 'm' && next != null && next in "bpm") {
                out.append('ん')
                i++
                continue
            }

            // っ: a doubled consonant (gakkou, kitte, matcha's tch).
            if (c in CONSONANTS && c != 'n' && (next == c || (c == 't' && next == 'c'))) {
                out.append('っ')
                i++
                continue
            }

            // Skip separators that only exist to disambiguate syllables.
            if (c == '\'' || c == '-') {
                i++
                continue
            }

            val match = TABLE.firstOrNull { text.startsWith(it.first, i) }
            if (match != null) {
                out.append(match.second)
                i += match.first.length
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Hepburn macrons are one character where the kana spelling uses two.
     * `tōkyō` therefore has to become `toukyou` before the table sees it.
     */
    private fun normalizeMacrons(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                'ā' -> append("aa")
                'ī' -> append("ii")
                'ū' -> append("uu")
                'ē' -> append("ee")
                'ō' -> append("ou")
                else -> append(c)
            }
        }
    }
}
