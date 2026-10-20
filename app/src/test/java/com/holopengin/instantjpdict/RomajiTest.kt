package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #108: the romaji front door. Every case is the exact string a user types,
 * converted to the kana the existing lookup chain expects.
 */
class RomajiTest {

    @Test
    fun hepburnWordsBecomeTheKanaTheDictionaryUses() {
        assertEquals("ねこ", Romaji.toKana("neko"))
        assertEquals("たべる", Romaji.toKana("taberu"))
        assertEquals("しんじゅく", Romaji.toKana("shinjuku"))
        assertEquals("とり", Romaji.toKana("tori"))
        assertEquals("とうきょう", Romaji.toKana("toukyou"))
        assertEquals("おおさか", Romaji.toKana("oosaka"))
        assertEquals("とうきょう", Romaji.toKana("tōkyō"))
    }

    @Test
    fun commonAlternativeSpellingsReachTheSameKana() {
        assertEquals("し", Romaji.toKana("si"))
        assertEquals("し", Romaji.toKana("shi"))
        assertEquals("つ", Romaji.toKana("tu"))
        assertEquals("つ", Romaji.toKana("tsu"))
        assertEquals("じ", Romaji.toKana("zi"))
        assertEquals("じ", Romaji.toKana("ji"))
        assertEquals("ふ", Romaji.toKana("hu"))
        assertEquals("ふ", Romaji.toKana("fu"))
        assertEquals("ち", Romaji.toKana("ti"))
        assertEquals("ち", Romaji.toKana("chi"))
    }

    @Test
    fun smallTsuAndSokuonAreHandled() {
        assertEquals("がっこう", Romaji.toKana("gakkou"))
        assertEquals("まっちゃ", Romaji.toKana("matcha"))
        assertEquals("きって", Romaji.toKana("kitte"))
        assertEquals("ざっし", Romaji.toKana("zasshi"))
    }

    @Test
    fun nasalBeforeAConsonantIsHatsuonNotASyllable() {
        assertEquals("しんぶん", Romaji.toKana("shimbun"))
        assertEquals("かんぱい", Romaji.toKana("kanpai"))
        assertEquals("げんき", Romaji.toKana("genki"))
        assertEquals("おんな", Romaji.toKana("onna"))
        assertEquals("ん", Romaji.toKana("n"))
        assertEquals("きんようび", Romaji.toKana("kin'youbi"))
    }

    @Test
    fun alreadyJapaneseTextPassesThrough() {
        assertEquals("ねこ", Romaji.toKana("ねこ"))
        assertEquals("猫", Romaji.toKana("猫"))
        assertEquals("", Romaji.toKana(""))
    }

    @Test
    fun uppercaseInputIsFoldedAndSmallKanaFormsWork() {
        assertEquals("ねこ", Romaji.toKana("NEKO"))
        assertEquals("っ", Romaji.toKana("xtu"))
        assertEquals("ゃ", Romaji.toKana("xya"))
        assertEquals("ぁ", Romaji.toKana("la"))
    }
}
