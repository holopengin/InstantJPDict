package com.holopengin.instantjpdict

import android.content.Context
import androidx.core.content.edit

/**
 * #106: the per-app override for "let the accessibility tree answer where it
 * can" — **auto / always nodes / always OCR**.
 *
 * #106's rule 5 makes the auto path safe by construction (a screen with no text
 * nodes recognises every box exactly as it always did), but "safe by
 * construction" is still a claim about *this* app's nodes. An app that publishes
 * an accessibility label which is not the drawn text, a virtual node that
 * reports something the screen does not show, a screen reader's own overlay
 * chrome — any of those makes the tree answer with characters that are not on the
 * screen, and the failure is silent (no crash, no error, just wrong text on the
 * overlay). This is the escape hatch for it: force one path or the other for an
 * app, and the split log line tells which was taken.
 *
 * ## Storage
 *
 * The service's own `accessibility_prefs` file, the one #105's update guard
 * already writes — not `OcrEngine.PREFS_NAME`, which is the OCR tunables'
 * [DebugTuning] surface. Deliberately so: the override is about the *service's*
 * behaviour and belongs with the service's state, and a debug-settings control
 * that writes there would be resetting a different file from the one it displays.
 *
 * The value is a [String] rather than an enum ordinal so a stored value from a
 * future build reads as `auto` here rather than silently selecting whatever
 * ordinal 1 means by then. An unrecognised value is [AUTO], which is the path
 * #106 ships as the default.
 *
 * ## Who reads it
 *
 * [OcrAccessibilityService]'s host reads it once per capture, before the tree
 * walk: [OCR] skips the walk entirely (nothing is going to use the result), and
 * otherwise the tree is read and the view applies the mode. **The share activity
 * has no tree and ignores this entirely** — there is nothing for an override to
 * choose between.
 */
object ScreenTextPrefs {

    /** #106: the shared-preferences file. #105's `ACCESSIBILITY_PREFS`. */
    const val PREFS_NAME = "accessibility_prefs"

    /** #106: the key inside [PREFS_NAME]. */
    const val KEY_MODE = "screen_text_mode"

    /** #106: the default mode, and the value of any unrecognised stored one. */
    const val AUTO = "auto"

    /**
     * #106: **nodes only** — never recognise. Every visible Japanese-bearing node
     * becomes a line (still behind the ink sample), and a detected box no node
     * covers is simply not shown rather than recognised.
     *
     * The setting to reach for when the tree is *right* and the recogniser is
     * wrong for this app — a stylised font, a hand-drawn map, a game with kanji
     * in its UI — and the recognition half is what cannot cope. Its cost is that
     * anything the tree does not publish is lost, which is why it is not the
     * default.
     */
    const val NODES = "nodes"

    /**
     * #106: **always OCR** — today's path, unchanged and unfiltered.
     *
     * The setting to reach for when the tree lies: an accessibility label that
     * is not the drawn text, a virtual node reporting phantom content. Detection
     * and recognition both run exactly as they did before #106.
     */
    const val OCR = "ocr"

    /** The modes, in the order a settings control should offer them. */
    val MODES: List<String> = listOf(AUTO, NODES, OCR)

    /** The stored mode, or [AUTO] when unset or unrecognised. Never throws. */
    fun mode(context: Context): String {
        val stored = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, AUTO)
        } catch (_: Exception) {
            null
        }
        return if (stored in MODES) stored!! else AUTO
    }

    /**
     * Store [mode]; an unrecognised value stores [AUTO] rather than writing
     * something the next read has to special-case.
     */
    fun setMode(context: Context, mode: String) {
        val value = if (mode in MODES) mode else AUTO
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_MODE, value) }
    }

    /** Whether this mode reads the tree at all ([OCR] does not). */
    fun readsTree(mode: String): Boolean = mode != OCR

    /** Whether this mode ever recognises ([NODES] does not). */
    fun recognises(mode: String): Boolean = mode != NODES
}
