package com.holopengin.instantjpdict.util

import android.content.Context
import com.holopengin.instantjpdict.OcrEngine
import uniffi.nav_graph_core.pitchFallsBeyondWord
import uniffi.nav_graph_core.pitchMoraeOf
import uniffi.nav_graph_core.pitchPattern
import uniffi.nav_graph_core.pitchPositionsOf
import uniffi.nav_graph_core.pitchReadingOf

/**
 * Pitch-accent support (#43): Yomitan-compatible pitch payloads from any
 * imported pitch dictionary (we ship a Kanjium-derived one as a built-in),
 * plus the pure mora/contour math the renderer draws.
 *
 * Since the util-core swap this is a thin facade over the PC `jpdict_core`
 * implementation (`core/src/util/pitch.rs` + `core/src/util/japanese.rs`,
 * exposed through `nav_graph_core`'s UniFFI surface), so the parsing rules and
 * the Tokyo contour have one source of truth. Only [isEnabled]/[setEnabled]
 * touch SharedPreferences — they stay here because nothing crosses the
 * boundary that understands an Android context.
 */
object PitchAccent {
    /** SharedPreferences key for the pitch-accent toggle (#43). */
    const val PREF_PITCH_ENABLED = "pitch_accent_enabled"
    const val DEF_PITCH_ENABLED = false

    /**
     * Pitch dictionary vendored in the APK assets (#43), installed as a
     * built-in: it never appears in the dictionary manager and cannot be
     * deleted. Built by tools/build_pitch_dict.py from the pinned Kanjium
     * revision; see pitch/PROVENANCE.txt next to it for source, license and
     * SHA-256.
     */
    const val BUNDLED_ASSET = "pitch/kanjium_pitch_accents.zip"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_PITCH_ENABLED, DEF_PITCH_ENABLED)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(OcrEngine.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PITCH_ENABLED, enabled)
            .apply()
    }

    /**
     * Split a reading into morae. Each kana is one mora, except small kana
     * (ゃゅょ…), which fuse with the preceding kana: きょう = きょ + う (2).
     * っ, ー and ん keep their own mora: がっこう = 4, コーヒー = 4.
     *
     * Splitting is done by `jpdict_core::util::japanese::morae_of` through
     * `pitchMoraeOf`.
     */
    fun moraeOf(reading: String): List<String> = pitchMoraeOf(reading)

    /**
     * High/low per mora for a downstep [position] (Yomitan semantics: 0 =
     * heiban, no downstep; N = pitch falls after mora N).
     *
     * Tokyo contour: heiban is L then H (no fall inside the word); atamadaka
     * (1) is H then L; otherwise mora 1 is L, morae up to the accented one
     * are H, the rest fall. A position at or beyond the last mora therefore
     * renders like heiban *within* the word — the difference only shows on
     * the following particle, which the renderer draws as a fall arrow.
     *
     * The contour is computed by
     * `jpdict_core::util::japanese::pitch_pattern` through `pitchPattern`;
     * the mora count is widened to the `Long` the boundary takes.
     */
    fun pattern(moraCount: Int, position: Int): List<Boolean> =
        pitchPattern(moraCount.toLong(), position)

    /**
     * True when the downstep lands past the final mora (odaka, or the one
     * known-bad row whose position exceeds its mora count): the following
     * particle carries the fall, so the renderer draws a fall arrow
     * ([PitchAccentLine.FALL_ARROW][com.holopengin.instantjpdict.PitchAccentLine.FALL_ARROW]).
     *
     * Delegates to `jpdict_core::util::japanese::falls_beyond_word` through
     * `pitchFallsBeyondWord`.
     */
    fun fallsBeyondWord(moraCount: Int, position: Int): Boolean =
        pitchFallsBeyondWord(moraCount.toLong(), position)

    /**
     * Downstep positions from a stored definition payload, or null when this
     * entry is not pitch data. Detection is by payload shape
     * (`{"reading":…, "pitches":[{"position":N},…]}`), so it works for ANY
     * Yomitan pitch dictionary, not just the Kanjium build.
     *
     * Parsed by `jpdict_core::util::pitch::pitch_positions_of` through
     * `pitchPositionsOf` (duplicates collapse, order normalized, and the
     * numeric handling matches Gson's `asInt`, so `1.0` still parses).
     */
    fun positionsOf(definitionsJson: String): List<Int>? = pitchPositionsOf(definitionsJson)

    /**
     * Reading of a stored pitch payload (identity when absent).
     *
     * Extracted by `jpdict_core::util::pitch::pitch_reading_of` through
     * `pitchReadingOf`.
     */
    fun readingOf(definitionsJson: String): String? = pitchReadingOf(definitionsJson)
}
