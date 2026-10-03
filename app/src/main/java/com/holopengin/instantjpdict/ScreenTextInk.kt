package com.holopengin.instantjpdict

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.get

/**
 * #106: is anything actually **drawn** inside this rect?
 *
 * A node the tree reports is not proof that anything is on the screen: a
 * `SurfaceView`/GL surface covers the window and the tree keeps reporting the
 * views *underneath* it, a view at zero alpha or `GONE`-but-still-inflated stays
 * in the hierarchy, and a virtual node can outlive its content. For a box a
 * detected box vouches for, none of that matters — recognition reads the pixels
 * and finds whatever is there. For a **recovered** node it matters completely:
 * recovery exists precisely because no box covers it, so nothing checks the
 * pixels, and a phantom node becomes a line of Japanese text floating over a
 * video.
 *
 * This is that check, and it is deliberately only that: **a filter, never a
 * router.** It may drop a node whose rect the screenshot shows blank; there is no
 * path by which it can make recognition run somewhere it would not otherwise
 * (#106's rule for recovered nodes). A false negative costs one recovered line —
 * the same loss as a detection miss. A false positive costs a phantom line. Both
 * are small, which is what makes a coarse sample the right instrument: the
 * alternative (reading every pixel of every node rect) would cost more than the
 * recognition it is protecting.
 *
 * ## The decision
 *
 * [hasInk] is the whole policy and takes **sampled luminances**, not a `Bitmap`,
 * so it is pinned by host tests ([ScreenTextInkTest]) rather than on a device.
 * Two facts about a rect's pixels separate "drawn" from "not drawn":
 *
 * - **Spread.** Text on a background has both dark ink and light background in it,
 *   so `max - min` is large; a blank region of one colour has none. [SPREAD_MIN]
 *   is ~10% of full scale, far above the 8-bit wobble of a real screenshot
 *   (which is lossless — there is no compression noise to defend against) and far
 *   below the range of the faintest antialiased glyph, which still spans tens of
 *   levels against its background.
 * - **Uniformly black.** A large *pure black* rect is not "nothing there": no
 *   Android window background is #000000, so a region that black was painted —
 *   a video letterbox, an image, a dark surface, a scrim. Treating it as blank
 *   would drop text on exactly the surfaces a manga or video page draws over, so
 *   [DARK_FLOOR] admits it. Uniform mid-grey and white are the opposite case and
 *   are the phantom signature: that is the window background showing through a
 *   node that is not drawn.
 *
 * ## The sample
 *
 * [isDrawn] takes at most [COLUMNS] × [ROWS] single pixels, spread across the
 * rect at cell centres — a few dozen `getPixel` calls, single-digit milliseconds
 * even for a full-screen node, and no allocation per node beyond the two small
 * arrays. A coarse grid is what makes the filter possible at all; the spread test
 * is what makes a coarse grid safe, because ink on any background is a *contrast*
 * phenomenon and 32 samples of a line of text will land on both sides of it.
 */
object ScreenTextInk {

    private const val TAG = "ScreenTextInk"

    /** Samples across a node's width. */
    const val COLUMNS = 8

    /** Samples down a node's height. */
    const val ROWS = 4

    /**
     * #106: the luminance range, in 0..255 levels, at which a rect is called
     * drawn-on.
     *
     * ~10% of full scale. A blank region is flat to within a level or two; the
     * faintest antialiased glyph edge against its background still spans tens.
     */
    const val SPREAD_MIN = 24

    /**
     * #106: the luminance at or below which a **uniform** rect is called drawn
     * on, whatever its spread.
     *
     * Pure black and near-black. No Android window background is this dark, so a
     * region this black was painted by something — which is the case recovery
     * must not drop. A dark-*mode* background is around #121212 and is above
     * this, so an un-drawn node over one is still dropped, as it should be.
     */
    const val DARK_FLOOR = 16

    /**
     * #106: is anything drawn in [rect] of [bitmap]?
     *
     * Returns true when the sample cannot be taken: a rect with no area, a
     * recycled bitmap, coordinates the bitmap does not have. The failure mode is
     * deliberate — "cannot tell" must not silently mean "nothing there", because
     * that would drop every recovered node on a capture that hit a bad frame,
     * and the ink sample's contract is that it only ever *removes* lines, never
     * creates recognition work.
     */
    fun isDrawn(bitmap: Bitmap, rect: JpDictRect): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return true
        if (bitmap.isRecycled) return true
        val samples = sample(bitmap, rect) ?: return true
        return hasInk(samples)
    }

    /**
     * #106: the pure decision — is a rect with these sampled luminances drawn on?
     *
     * Empty means "no evidence", which reads as not drawn: an empty sample can
     * only come from a rect with no area or one the bitmap could not be read at,
     * and [isDrawn] has already turned both of those into `true` before reaching
     * here. Kept as its own function so the policy is testable without a
     * `Bitmap`, which is the only part of this that has a threshold in it.
     */
    fun hasInk(luminances: IntArray): Boolean {
        if (luminances.isEmpty()) return false
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (l in luminances) {
            if (l < min) min = l
            if (l > max) max = l
        }
        return max - min >= SPREAD_MIN || max <= DARK_FLOOR
    }

    /**
     * The luminances at [COLUMNS] × [ROWS] cell centres of [rect], or null when
     * the bitmap could not be read.
     *
     * Clamps to the bitmap rather than rejecting: the rect was already clipped to
     * the screenshot by [ScreenTextReader], so this only covers the degenerate
     * cases, and a partially readable rect is still worth an answer.
     */
    fun sample(bitmap: Bitmap, rect: JpDictRect): IntArray? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val left = rect.left.coerceIn(0, w - 1)
        val top = rect.top.coerceIn(0, h - 1)
        val right = rect.right.coerceIn(left + 1, w)
        val bottom = rect.bottom.coerceIn(top + 1, h)
        val cols = minOf(COLUMNS, right - left)
        val rows = minOf(ROWS, bottom - top)
        val out = IntArray(cols * rows)
        var at = 0
        try {
            for (r in 0 until rows) {
                // Cell centres, so the samples spread over the rect rather than
                // crowding its edges; the last cell's centre is still inside it.
                val y = top + (bottom - top) * (2 * r + 1) / (2 * rows)
                for (c in 0 until cols) {
                    val x = left + (right - left) * (2 * c + 1) / (2 * cols)
                    out[at++] = luminance(bitmap[x, y])
                }
            }
        } catch (t: Throwable) {
            // A recycled or hardware-backed bitmap the capture is not holding any
            // more. Say so and let [isDrawn] keep the node.
            Log.w(TAG, "ink sample unreadable; treating the node as drawn", t)
            return null
        }
        return out
    }

    /**
     * ITU-R BT.601 luma, the same weighting `OcrEngine`'s own gray conversion
     * uses — so "dark" here means the same as "dark" to the rest of the pipeline.
     */
    private fun luminance(argb: Int): Int =
        (Color.red(argb) * 299 + Color.green(argb) * 587 + Color.blue(argb) * 114) / 1000
}
