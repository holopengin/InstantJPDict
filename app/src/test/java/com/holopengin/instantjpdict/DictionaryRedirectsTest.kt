package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.DictionaryRedirects
import org.junit.Test

import org.junit.Assert.*

// #65: pure JMdict pointer entries resolve to their ?query= targets;
// entries with real definitional content never redirect. Shapes below are
// verbatim from JMdict_english term banks (yomidevs).
class DictionaryRedirectsTest {
    // あかーん → あかん (real row).
    private val pureSingle = """[{"content": {"content": ["⟶", {"content": "あかん", "href": "?query=あかん&wildcards=off", "lang": "ja", "tag": "a"}], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"""

    // アホんだら → あほんだら + 阿呆陀羅 (shape mirrors the real row, which
    // renders as "⟶, 阿呆陀羅, （, あほんだら, ）").
    private val pureDual = """[{"content": {"content": ["⟶", {"content": "阿呆陀羅", "href": "?query=阿呆陀羅&wildcards=off", "lang": "ja", "tag": "a"}, "（", {"content": "あほんだら", "href": "?query=あほんだら&wildcards=off", "lang": "ja", "tag": "a"}, "）"], "style": {"fontSize": "130%"}, "tag": "span"}, "type": "structured-content"}]"""

    // ヽ (real row): genuine glossary + see-also references — not a redirect.
    private val glossWithRefs = """[{"content": [{"content": {"content": "repetition mark in katakana", "tag": "li"}, "data": {"content": "glossary"}, "lang": "en", "style": {"listStyleType": "circle"}, "tag": "ul"}, {"content": {"content": ["see: ", {"content": "一の字点", "href": "?query=一の字点&wildcards=off", "lang": "ja", "tag": "a"}, {"content": " kana iteration mark", "data": {"content": "refGlosses"}, "style": {"fontSize": "65%", "verticalAlign": "middle"}, "tag": "span"}], "tag": "li"}, "data": {"content": "references"}, "lang": "en", "style": {"listStyleType": "'➡️ '"}, "tag": "ul"}], "type": "structured-content"}]"""

    // 說教 (Jitendex 2026.08.11.0, real row): the pointer is wrapped in a
    // div whose data.content is "redirect-glossary", and the v3 deinflection
    // pair follows it. The wrapper must not count as definitional content.
    private val jitendexRedirect = """[{"content":{"content":["⟶",{"content":[{"content":["説",{"content":"せっ","tag":"rt"}],"tag":"ruby"},{"content":["教",{"content":"きょう","tag":"rt"}],"tag":"ruby"}],"href":"?query=%E8%AA%AC%E6%95%99&wildcards=off&primary_reading=%E3%81%9B%E3%81%A3%E3%81%8D%E3%82%87%E3%81%86","lang":"ja","tag":"a"}],"data":{"content":"redirect-glossary"},"lang":"ja","tag":"div"},"type":"structured-content"},["説教",["redirected from 說教"]]]"""

    @Test
    fun pure_single_resolves() {
        assertEquals(listOf("あかん"), DictionaryRedirects.extractTargets(pureSingle))
    }

    @Test
    fun pure_dual_resolves_both() {
        assertEquals(
            listOf("阿呆陀羅", "あほんだら"),
            DictionaryRedirects.extractTargets(pureDual)
        )
    }

    @Test
    fun jitendex_redirect_glossary_resolves() {
        assertEquals(listOf("説教"), DictionaryRedirects.extractTargets(jitendexRedirect))
    }

    @Test
    fun gloss_with_see_also_does_not_redirect() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets(glossWithRefs))
    }

    @Test
    fun plain_gloss_does_not_redirect() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("\"ただの定義\""))
    }

    @Test
    fun malformed_and_empty_yield_nothing() {
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("not json{["))
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets(""))
        assertEquals(emptyList<String>(), DictionaryRedirects.extractTargets("[]"))
    }

    @Test
    fun target_cap_applies() {
        assertEquals(1, DictionaryRedirects.extractTargets(pureDual, maxTargets = 1).size)
    }
}
