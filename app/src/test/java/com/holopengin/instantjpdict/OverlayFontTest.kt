package com.holopengin.instantjpdict

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * #84: the overlay face switch — the stored choice, the pref round trip, and the
 * two bundled fonts the choices point at.
 *
 * The preference lives in [OcrEngine.PREFS_NAME] on a device; here an in-memory
 * Proxy-backed [SharedPreferences] stands in, so [OverlayFont.read]/[OverlayFont.write]
 * are exercised without Robolectric. The font half reads the committed assets and
 * parses their sfnt/GSUB headers, so a swap that dropped `vert`/`vrt2` (the #47
 * vertical-substitution feature the overlay sets) fails the build rather than
 * shipping tofu punctuation.
 */
class OverlayFontTest {

    // ————— the choice —————

    @Test
    fun the_default_is_the_sans_face_the_overlay_always_drew() {
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.DEFAULT_FACE)
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.normalize(null))
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.normalize(""))
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.normalize(OverlayFont.FACE_SANS))
        assertEquals(OverlayFont.FACE_SERIF, OverlayFont.normalize(OverlayFont.FACE_SERIF))
    }

    @Test
    fun an_unknown_stored_value_reads_as_the_default_never_as_a_crash() {
        val prefs = FakePrefs()
        prefs.values[OverlayFont.PREF_FACE] = "mincho" // a value from nowhere
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.read(prefs.prefs))
        // ... and it resolves to the default asset, not to nothing.
        assertEquals(OverlayFont.SANS_ASSET, OverlayFont.assetOf("mincho"))
    }

    @Test
    fun each_choice_names_its_own_bundled_asset() {
        assertEquals("fonts/NotoSansJP-Regular.ttf", OverlayFont.assetOf(OverlayFont.FACE_SANS))
        assertEquals("fonts/NotoSerifJP-Regular.ttf", OverlayFont.assetOf(OverlayFont.FACE_SERIF))
    }

    @Test
    fun the_face_pref_round_trips() {
        val prefs = FakePrefs().prefs
        assertEquals("a fresh install reads the default", OverlayFont.FACE_SANS, OverlayFont.read(prefs))
        OverlayFont.write(prefs, OverlayFont.FACE_SERIF)
        assertEquals(OverlayFont.FACE_SERIF, OverlayFont.read(prefs))
        OverlayFont.write(prefs, OverlayFont.FACE_SANS)
        assertEquals(OverlayFont.FACE_SANS, OverlayFont.read(prefs))
    }

    // ————— the bundled fonts —————

    @Test
    fun both_bundled_faces_are_truetype_fonts_of_the_intended_family() {
        assertBundledFont(OverlayFont.SANS_ASSET, "Noto Sans JP")
        assertBundledFont(OverlayFont.SERIF_ASSET, "Noto Serif JP")
    }

    @Test
    fun both_bundled_faces_carry_the_vert_and_vrt2_features() {
        // #47: the overlay renders vertical lines with fontFeatureSettings
        // "'vert' 1"; a bundled face without the substitution would draw
        // horizontal punctuation in vertical text.
        assertTrue("vert missing from ${OverlayFont.SANS_ASSET}",
            "vert" in gsubFeatureTags(bytes(OverlayFont.SANS_ASSET)))
        assertTrue("vrt2 missing from ${OverlayFont.SANS_ASSET}",
            "vrt2" in gsubFeatureTags(bytes(OverlayFont.SANS_ASSET)))
        assertTrue("vert missing from ${OverlayFont.SERIF_ASSET}",
            "vert" in gsubFeatureTags(bytes(OverlayFont.SERIF_ASSET)))
        assertTrue("vrt2 missing from ${OverlayFont.SERIF_ASSET}",
            "vrt2" in gsubFeatureTags(bytes(OverlayFont.SERIF_ASSET)))
    }

    @Test
    fun neither_face_is_subset_to_a_sample_of_glyphs() {
        // Dictionary text is arbitrary, so the whole face ships: a subset would
        // render tofu for any character the build did not happen to sample.
        assertTrue("sans looks subset: ${numGlyphs(bytes(OverlayFont.SANS_ASSET))} glyphs",
            numGlyphs(bytes(OverlayFont.SANS_ASSET)) > 10_000)
        assertTrue("serif looks subset: ${numGlyphs(bytes(OverlayFont.SERIF_ASSET))} glyphs",
            numGlyphs(bytes(OverlayFont.SERIF_ASSET)) > 10_000)
    }

    // ————— helpers —————

    /** One read per asset per test, not per assertion: each file is 5.7–8 MB. */
    private val fontBytes = mutableMapOf<String, ByteArray>()
    private fun bytes(rel: String): ByteArray =
        fontBytes.getOrPut(rel) { TestAssets.assetsFile(rel).readBytes() }

    private fun assertBundledFont(rel: String, family: String) {
        val bytes = bytes(rel)
        assertEquals("$rel is not a TrueType sfnt (magic ${readU32(bytes, 0)})",
            0x00010000L, readU32(bytes, 0))
        assertTrue("$rel does not carry the family name '$family'", containsUtf16Be(bytes, family))
    }

    // sfnt table directory: u16 numTables at 4, records of tag(4)/checksum(4)/offset(4)/length(4)
    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun tableOffset(font: ByteArray, tag: String): Int? {
        val numTables = readU16(font, 4)
        for (i in 0 until numTables) {
            val record = 12 + i * 16
            if (String(font, record, 4, Charsets.US_ASCII) == tag) {
                return readU32(font, record + 8).toInt()
            }
        }
        return null
    }

    private fun numGlyphs(font: ByteArray): Int {
        val maxp = tableOffset(font, "maxp") ?: error("no maxp table")
        return readU16(font, maxp + 4)
    }

    /** Feature tags in GSUB's FeatureList (version 1.x header: FeatureList offset at +6). */
    private fun gsubFeatureTags(font: ByteArray): Set<String> {
        val gsub = tableOffset(font, "GSUB") ?: error("no GSUB table")
        val featureList = gsub + readU16(font, gsub + 6)
        val count = readU16(font, featureList)
        return (0 until count).map { i ->
            String(font, featureList + 2 + i * 6, 4, Charsets.US_ASCII)
        }.toSet()
    }

    private fun containsUtf16Be(haystack: ByteArray, value: String): Boolean {
        val needle = value.toByteArray(Charsets.UTF_16BE)
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * In-memory SharedPreferences for the round trip: the methods the helper may
     * use are backed, anything else fails loudly rather than silently returning a
     * wrong value.
     */
    private class FakePrefs {
        val values = mutableMapOf<String, Any?>()

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "putStringSet" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "putBoolean" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "apply" -> Unit
                "commit" -> java.lang.Boolean.TRUE
                "toString" -> "FakePrefs.Editor"
                else -> throw UnsupportedOperationException("FakePrefs.Editor.${method.name}")
            }
        } as SharedPreferences.Editor

        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] as? String ?: args[1]
                "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                "getAll" -> values.toMap()
                "toString" -> "FakePrefs(values=$values)"
                else -> throw UnsupportedOperationException("FakePrefs.${method.name}")
            }
        } as SharedPreferences
    }
}
