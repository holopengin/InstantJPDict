package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN DEPARTURE 2026-09-21 (maintainer-ordered, mirrored on the PC app):
 * definition/example body ruby matches the surrounding body typeface
 * (white, regular) instead of the headword treatment (bold cyan).
 *
 * Coverage honesty: applying the style to a `TextView` needs the Android
 * framework (Robolectric is not a dependency), so what is pinned here is
 * the JVM-testable decision — the [RubyBaseStyle] mapping itself, plus the
 * parser fact that every definition ruby takes the body path (`isMini`).
 */
class RubyBaseStyleTest {

    // 0xFFFFFFFF = Color.WHITE, 0xFF00FFFF = Color.CYAN; literals because
    // android.graphics.Color is a stub on the JVM unit-test classpath.

    @Test
    fun bodyRuby_isWhiteRegular() {
        val style = RubyBaseStyle.forMini(isMini = true)
        assertEquals(RubyBaseStyle.BODY, style)
        assertEquals(0xFFFFFFFF.toInt(), style.baseColor)
        assertFalse(style.bold)
    }

    @Test
    fun termRuby_isBoldCyan() {
        val style = RubyBaseStyle.forMini(isMini = false)
        assertEquals(RubyBaseStyle.TERM, style)
        assertEquals(0xFF00FFFF.toInt(), style.baseColor)
        assertTrue(style.bold)
    }

    @Test
    fun parsedDefinitionRuby_alwaysTakesBodyPath() {
        // parseDefinition is the only producer of DefinitionNode.Ruby, and it
        // builds every one with isMini = true — so renderDefinition always
        // resolves body ruby through BODY, never TERM.
        val nodes = OcrOverlayStateController().parseDefinition(
            mapOf("tag" to "ruby", "content" to listOf("漢字", mapOf("content" to "かんじ")))
        )
        assertEquals(1, nodes.size)
        val ruby = nodes.single() as DefinitionNode.Ruby
        assertEquals("漢字", ruby.term)
        assertEquals("かんじ", ruby.reading)
        assertTrue(ruby.isMini)
        assertEquals(RubyBaseStyle.BODY, RubyBaseStyle.forMini(ruby.isMini))
    }
}
