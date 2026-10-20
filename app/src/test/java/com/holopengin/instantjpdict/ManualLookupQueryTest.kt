package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #108 follow-up: romaji is an input spelling for the Japanese mode. */
class ManualLookupQueryTest {

    @Test
    fun asciiGoesThroughRomajiAndJapanesePassesThrough() {
        assertEquals("ねこ", ManualLookupQuery.primary("neko"))
        assertEquals("ねこ", ManualLookupQuery.primary("ねこ"))
        assertEquals("猫", ManualLookupQuery.primary("猫"))
        assertNull(ManualLookupQuery.primary("   "))
    }

    @Test
    fun japaneseScriptIsDetectedAcrossTheScriptsTheAppCanReceive() {
        assertTrue(ManualLookupQuery.hasJapanese("ねこ"))
        assertTrue(ManualLookupQuery.hasJapanese("テスト"))
        assertTrue(ManualLookupQuery.hasJapanese("猫"))
        assertTrue(ManualLookupQuery.hasJapanese("ｶﾀｶﾅ"))
        assertFalse(ManualLookupQuery.hasJapanese("neko"))
    }

    @Test
    fun katakanaFallbackIsOfferedOnlyForHiraganaQueries() {
        assertTrue(ManualLookupQuery.katakanaFallbacks("てすと").contains("テスト"))
        assertTrue(ManualLookupQuery.katakanaFallbacks("びいる").contains("ビール"))
        assertTrue(ManualLookupQuery.katakanaFallbacks("猫").isEmpty())
        assertTrue(ManualLookupQuery.katakanaFallbacks("テスト").isEmpty())
    }
}
