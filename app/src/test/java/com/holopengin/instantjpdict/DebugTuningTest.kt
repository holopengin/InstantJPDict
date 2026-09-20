package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps
import com.holopengin.instantjpdict.util.KanaSizeFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1/#86: the debug screen's reset contract, pinned.
 *
 * `DebugTuning` makes the reset and the SeekBar rows read one list, so they cannot
 * disagree about *which* controls exist. What structure cannot say is whether the
 * list still covers every control the screen offers: these tests name the expected
 * keys — built from the same `PREF_*` constants the call sites use, so a renamed
 * key moves both sides — and fail if a row or feature is dropped from the list.
 *
 * The expected key sets below are the contract. Adding a genuinely new tunable
 * means adding it here too; that edit is the deliberate second look the old
 * comment-only invariant never got.
 */
class DebugTuningTest {

    @Test
    fun the_reset_owns_every_tunable_the_debug_screen_shows() {
        assertEquals(
            listOf(
                OcrEngine.PREF_DET_THRESH,
                OcrEngine.PREF_DET_UNCLIP,
                OcrEngine.PREF_X_OVERLAP,
                OcrEngine.PREF_REC_SQUISH,
                OverlayBackdrop.PREF_SCREENSHOT_ALPHA,
                KanaSizeFix.PREF_EPSILON,
                ProtoCrosshairView.PREF_GAP,
            ),
            DebugTuning.rows.map { it.key },
        )
    }

    @Test
    fun the_reset_owns_every_feature_switch() {
        // Pitch accent is a home-screen setting now, not a debug-screen control,
        // so the tuning reset deliberately does not own it (see DebugTuning).
        assertEquals(
            listOf(
                DoubleTapZoom.PREF_ENABLED,
                BlankGaps.PREF_ENABLED,
                OcrEngine.PREF_DET_ROTATED,
                OcrEngine.PREF_DET_FURIGANA_SCREEN,
                OcrEngine.PREF_DET_FURIGANA_CAMERA,
            ),
            DebugTuning.features.map { it.key },
        )
    }

    @Test
    fun every_row_has_a_usable_range() {
        DebugTuning.rows.forEach { row ->
            assertTrue(
                "${row.label}: default ${row.default} is outside [${row.min}, ${row.max}]",
                row.default in row.min..row.max,
            )
            assertTrue("${row.label}: step ${row.step} must be positive", row.step > 0f)
            assertTrue("${row.label}: empty range [${row.min}, ${row.max}]", row.min < row.max)
        }
    }

    @Test
    fun keys_are_unique_across_rows_and_features() {
        val keys = DebugTuning.rows.map { it.key } + DebugTuning.features.map { it.key }
        assertEquals("a key is listed twice", keys.size, keys.toSet().size)
    }
}
