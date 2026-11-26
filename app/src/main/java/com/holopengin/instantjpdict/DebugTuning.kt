package com.holopengin.instantjpdict

import com.holopengin.instantjpdict.util.BlankGaps

/**
 * G1/#86: the debug screen's tuning surface as data.
 *
 * "Reset all tuning to defaults" used to re-type every key the SeekBar rows were
 * built from, and its comment could only *state* the invariant ("a control the
 * reset does not know about is a bug") — a new row added above it silently
 * survived a reset. Now both the rows and the reset iterate [rows]/[features],
 * so the invariant is structural; [DebugTuningTest] pins that these lists cover
 * every control, which is the part structure cannot say on its own.
 *
 * Deliberately not here: `OverlayFont.PREF_FACE` (the overlay typeface),
 * `PitchAccent.PREF_PITCH_ENABLED` (moved to the home screen) and the debug-log
 * toggle — those are switches you turn on to read something, not tunables a
 * reset should flip back mid-session, so a tuning reset leaves them alone.
 * Every constant referenced below is a `const val`, so this file (and its test)
 * stay Android-free.
 */

/** One SeekBar/EditText tunable row: label, preference key, default, range, step. */
internal data class TuningRow(
    val label: String,
    val key: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float,
    /** Stored as an Int preference (and parsed as one) rather than a Float. */
    val isInt: Boolean = false,
)

/** One feature checkbox: preference key and default. */
internal data class FeatureRow(val key: String, val default: Boolean)

internal object DebugTuning {

    /** The tunables, in the order the debug screen shows them. */
    val rows: List<TuningRow> = listOf(
        TuningRow("PPOCR_DET_THRESH", OcrEngine.PREF_DET_THRESH, OcrEngine.DEF_DET_THRESH, 0.05f, 0.95f, 0.01f),
        TuningRow("PPOCR_DET_UNCLIP_RATIO", OcrEngine.PREF_DET_UNCLIP, OcrEngine.DEF_DET_UNCLIP, 0.5f, 3.0f, 0.01f),
        TuningRow("X_OVERLAP_THRESHOLD", OcrEngine.PREF_X_OVERLAP, OcrEngine.DEF_X_OVERLAP, 0.0f, 1.0f, 0.01f),
        TuningRow("REC_SQUISH_FACTOR", OcrEngine.PREF_REC_SQUISH, OcrEngine.DEF_REC_SQUISH, 0.2f, 1.0f, 0.1f),
        TuningRow(
            "OVERLAY_SCREENSHOT_ALPHA",
            OverlayBackdrop.PREF_SCREENSHOT_ALPHA, OverlayBackdrop.DEF_SCREENSHOT_ALPHA, 0.3f, 1.0f, 0.05f,
        ),
        TuningRow("CROSSHAIR_GAP", ProtoCrosshairView.PREF_GAP, ProtoCrosshairView.DEF_GAP, 0.005f, 0.10f, 0.005f),
    )

    /** The feature switches the debug screen owns, which the reset also owns. */
    val features: List<FeatureRow> = listOf(
        FeatureRow(DoubleTapZoom.PREF_ENABLED, DoubleTapZoom.DEF_ENABLED),
        FeatureRow(BlankGaps.PREF_ENABLED, BlankGaps.DEF_ENABLED),
        FeatureRow(OcrEngine.PREF_DET_ROTATED, OcrEngine.DEF_DET_ROTATED),
        FeatureRow(OcrEngine.PREF_DET_FURIGANA_SCREEN, OcrEngine.DEF_DET_FURIGANA),
        FeatureRow(OcrEngine.PREF_DET_FURIGANA_CAMERA, OcrEngine.DEF_DET_FURIGANA),
        FeatureRow(OcrEngine.PREF_BOX_PLACEMENT_CAP, OcrEngine.DEF_BOX_PLACEMENT_CAP),
    )
}
