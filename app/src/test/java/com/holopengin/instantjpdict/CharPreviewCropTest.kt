package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * #102: the geometry behind the upright character-crop previews — the source →
 * local mapping that carries a rotated Line's char box into the frame the
 * unrotate produced, and the two padding rules that follow from it.
 *
 * The pixels are verified on device (`CharPreviewUprightTest`); what is pinned
 * here is the arithmetic the three crop sites share, which is pure and so can
 * be pinned in full rather than sampled: a rotated cell round-trips through its
 * source AABB enclosing itself and grown by exactly the tilt's shear, a
 * quarter-turned frame round-trips it exactly, the mapped rect never leaves the
 * frame, a degenerate frame degrades instead of throwing, and an axis-aligned
 * Line — `quad == null`, the overwhelmingly common case — keeps the exact rect
 * the sites inlined before #102.
 */
class CharPreviewCropTest {

    // ── frames ─────────────────────────────────────────────────────────────

    /**
     * A frame with a given local size and tilt, positioned by its centre. This
     * is the *geometric* definition of a frame (what [JpDictQuad]'s corners
     * mean), independent of how one is fitted, so the mapping tests can state
     * a tilt outright. Production frames come from [fittedFrame] — see the
     * note there about frames that pin a convention production never sees.
     */
    private fun quad(cx: Float, cy: Float, w: Float, h: Float, tiltDeg: Float): JpDictQuad {
        val a = Math.toRadians(tiltDeg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val vx = -uy
        val vy = ux
        val hw = w / 2f
        val hh = h / 2f
        return JpDictQuad(
            QuadPoint(cx - hw * ux - hh * vx, cy - hw * uy - hh * vy),
            QuadPoint(cx + hw * ux - hh * vx, cy + hw * uy - hh * vy),
            QuadPoint(cx + hw * ux + hh * vx, cy + hw * uy + hh * vy),
            QuadPoint(cx - hw * ux + hh * vx, cy - hw * uy + hh * vy),
        )
    }

    /** The unrotate's output size for [q] — `OcrEngine.localCropW/H`, which the
     *  production seam applies to the frame's own local extents. */
    private fun frameW(q: JpDictQuad): Int = q.localWidth.roundToInt().coerceAtLeast(4)

    private fun frameH(q: JpDictQuad): Int = q.localHeight.roundToInt().coerceAtLeast(4)

    /** The old inline site arithmetic, spelled out, as the axis-aligned oracle. */
    private fun legacySourceRect(box: JpDictRect, padRatio: Float, srcW: Int, srcH: Int): JpDictRect {
        val padding = (box.height() * padRatio).toInt()
        return JpDictRect(
            (box.left - padding).coerceAtLeast(0),
            (box.top - padding).coerceAtLeast(0),
            (box.right + padding).coerceAtMost(srcW),
            (box.bottom + padding).coerceAtMost(srcH),
        )
    }

    // ── the inverse mapping ────────────────────────────────────────────────

    @Test
    fun anUprightFrameMapsItsOwnSourceBoxBackToItself() {
        // The tilt-0 case, which is also the one that can be checked exactly: a
        // frame's own AABB *is* its local rect, so the mapping has to hand it
        // back exactly — and by doing so it agrees with the rect the unrotate
        // draws into.
        val q = quad(500f, 400f, 400f, 48f, 0f)
        assertEquals(
            JpDictRect(0, 0, frameW(q), frameH(q)),
            RotatedGeometry.mapSourceRectToLocal(q, q.toRect(), frameW(q), frameH(q)),
        )
    }

    /**
     * A fitted frame: the four outer pixel corners of a `w × h` rect at
     * [tiltDeg], as the detector's boundary walk produces them, run through the
     * real fit. Frames in the app always come from here (then `unclip`/
     * `inset`, which preserve the angle), so a test frame built any other way
     * can pin a convention production never sees.
     */
    private fun fittedFrame(w: Float, h: Float, tiltDeg: Float, cx: Float = 500f, cy: Float = 500f): JpDictQuad {
        val a = Math.toRadians(tiltDeg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val vx = -uy
        val vy = ux
        val hw = w / 2f
        val hh = h / 2f
        return RotatedGeometry.fitQuad(
            floatArrayOf(
                cx - hw * ux - hh * vx, cy - hw * uy - hh * vy,
                cx + hw * ux - hh * vx, cy + hw * uy - hh * vy,
                cx + hw * ux + hh * vx, cy + hw * uy + hh * vy,
                cx - hw * ux + hh * vx, cy - hw * uy + hh * vy,
            ),
            4,
        )!!
    }

    @Test
    fun aQuarterTurnedFrameRoundTripsItsCells() {
        // The extreme shear, and the reason the mapping is not a transpose: a
        // vertical line's frame is the source's axes swapped (local x is the
        // *cross* axis), so a cell here is taller than it is wide and its
        // source AABB is wider than it is tall. Both readings of "a quarter
        // turn" are covered — the 40x200 rect fitted at 90° canonicalises to
        // the upright vertical frame, and the 200x40 rect fitted at 30° keeps
        // its tilt with the axes swapped — and in each the round trip encloses
        // the cell it started from.
        for (q in listOf(fittedFrame(40f, 200f, 0f), fittedFrame(40f, 200f, 30f))) {
            val w = frameW(q)
            val h = frameH(q)
            for (cell in listOf(
                JpDictRect(w / 5, h / 5, w / 5 + w / 2, h / 5 + h / 2),
                JpDictRect(0, 0, w / 3, h / 3),
                JpDictRect(2, 2, w - 2, h - 2),
            )) {
                val back = RotatedGeometry.mapSourceRectToLocal(q, q.mapLocalRect(cell), w, h)
                assertTrue("cell=$cell -> $back must not clip", back.left <= cell.left && back.top <= cell.top)
                assertTrue("cell=$cell -> $back must not clip", back.right >= cell.right && back.bottom >= cell.bottom)
                // And it comes back in the same frame: a cell taller than wide
                // in a vertical line's frame stays taller than wide here, or
                // the preview would show the glyph on its side.
                assertTrue("cell=$cell -> $back keeps the frame's orientation", back.height() > back.width())
            }
        }
    }

    @Test
    fun aTiltedCellComesBackEnclosingItselfGrownByTheTiltsShear() {
        // The round trip through a source AABB is lossy in between, and the
        // loss is exactly the shear: un-rotating the AABB's four corners gives
        // a local quad `sin 2θ` wider than the cell across and `sin 2θ` taller
        // along it (both zero at 0° and 90°, maximal at 45°). Pinned as the
        // formula rather than a tolerance, so the number is checkable by hand,
        // with two pixels of slack for the outward rounding.
        val w = 200
        val h = 80
        val cell = JpDictRect(80, 30, 120, 50) // 40 x 20, centred in the frame
        for (tilt in listOf(3f, 15f, 30f, 45f, 60f, 75f, 90f)) {
            val q = quad(300f, 300f, w.toFloat(), h.toFloat(), tilt)
            val back = RotatedGeometry.mapSourceRectToLocal(q, q.mapLocalRect(cell), w, h)
            val shear = sin(2.0 * Math.toRadians(tilt.toDouble())).toFloat()
            val growW = cell.height() * shear
            val growH = cell.width() * shear
            assertTrue("tilt=$tilt $back must not clip", back.left <= cell.left && back.top <= cell.top)
            assertTrue("tilt=$tilt $back must not clip", back.right >= cell.right && back.bottom >= cell.bottom)
            assertEquals("tilt=$tilt width", cell.width() + growW, back.width().toFloat(), 2f)
            assertEquals("tilt=$tilt height", cell.height() + growH, back.height().toFloat(), 2f)
        }
    }

    @Test
    fun aCellAtTheFramesEdgeStillComesBackEnclosingItself() {
        // The same round trip for cells flush against the frame, where the
        // answer is clamped to the frame — and containment survives it, because
        // the clamp can only grow the rect back into the frame.
        val q = quad(540f, 600f, 400f, 48f, 30f)
        val w = frameW(q)
        val h = frameH(q)
        for (cell in listOf(
            JpDictRect(0, 0, 24, 48),
            JpDictRect(w - 24, 0, w, h),
            JpDictRect(120, 0, 180, 48),
            JpDictRect(10, 4, 60, 44),
        )) {
            val back = RotatedGeometry.mapSourceRectToLocal(q, q.mapLocalRect(cell), w, h)
            assertTrue("cell=$cell -> $back", back.left <= cell.left && back.top <= cell.top)
            assertTrue("cell=$cell -> $back", back.right >= cell.right && back.bottom >= cell.bottom)
        }
    }

    @Test
    fun theMappingIsTheInverseOfTheFramesOwnForwardMap() {
        // The load-bearing claim, over the whole tilt range a fit can produce:
        // composing `mapLocalRect` (the forward map the char boxes are built
        // with) with `mapSourceRectToLocal` (this inverse) brings a cell back
        // to itself, enlarged only by the tilt's shear. If the two ever
        // disagreed about *which* frame they are in, the previews would crop
        // the wrong part of the unrotated bitmap — and it would show up here
        // as a cell that moved, rather than as a preview that looked wrong.
        for ((w, h) in listOf(200f to 40f, 40f to 200f, 90f to 90f)) {
            for (deg in -89..89 step 7) {
                val q = fittedFrame(w, h, deg.toFloat())
                if (q.isAxisAligned()) continue
                val lw = frameW(q)
                val lh = frameH(q)
                // A small centred cell: the shear formula below describes the
                // unclamped answer, and a cell touching an edge is (correctly)
                // clamped back to the frame instead.
                val cell = JpDictRect(lw / 2 - lw / 8, lh / 2 - lh / 8, lw / 2 + lw / 8, lh / 2 + lh / 8)
                val back = RotatedGeometry.mapSourceRectToLocal(q, q.mapLocalRect(cell), lw, lh)
                val tilt = abs(q.tiltDeg)
                val shear = sin(2.0 * Math.toRadians(tilt.toDouble())).toFloat()
                assertTrue("${w}x$h@$deg cell=$cell -> $back", back.left <= cell.left && back.top <= cell.top)
                assertTrue("${w}x$h@$deg cell=$cell -> $back", back.right >= cell.right && back.bottom >= cell.bottom)
                // The grown size, clamped to the frame — a cell near a frame
                // edge in a steeply tilted frame is (correctly) cut back to it,
                // which the clamp in the expectation accounts for.
                assertEquals(
                    "${w}x$h@$deg cell=$cell width",
                    minOf(cell.width() + cell.height() * shear, lw.toFloat()),
                    back.width().toFloat(),
                    3f,
                )
                assertEquals(
                    "${w}x$h@$deg cell=$cell height",
                    minOf(cell.height() + cell.width() * shear, lh.toFloat()),
                    back.height().toFloat(),
                    3f,
                )
                // And a whole-frame cell, which has to come back as the whole
                // frame however hard the tilt rounds it.
                assertEquals(
                    "${w}x$h@$deg whole frame",
                    JpDictRect(0, 0, lw, lh),
                    RotatedGeometry.mapSourceRectToLocal(q, q.mapLocalRect(JpDictRect(0, 0, lw, lh)), lw, lh),
                )
            }
        }
    }

    @Test
    fun theMappedRectNeverLeavesTheFrame() {
        // A char box can hang off the frame's edge (the source AABB of a cell
        // at the top of a tilted line reaches past the frame), so the mapped
        // rect is clamped — otherwise the crop would be handed a rect outside
        // the upright bitmap.
        val q = quad(540f, 600f, 400f, 48f, 30f)
        val w = frameW(q)
        val h = frameH(q)
        for (box in listOf(
            JpDictRect(0, 0, w, h),
            JpDictRect(-200, -200, -10, -10),
            JpDictRect(w + 10, h + 10, w + 400, h + 400),
            JpDictRect(-400, 0, 0, h),
            JpDictRect(w, 0, w + 400, h),
        )) {
            val r = RotatedGeometry.mapSourceRectToLocal(q, box, w, h)
            assertTrue("$box -> $r left", r.left in 0..w)
            assertTrue("$box -> $r top", r.top in 0..h)
            assertTrue("$box -> $r right", r.right in r.left..w)
            assertTrue("$box -> $r bottom", r.bottom in r.top..h)
        }
    }

    @Test
    fun aBoxWhollyOffTheFrameComesBackEmptyRatherThanThrowing() {
        val q = quad(540f, 600f, 400f, 48f, 30f)
        val w = frameW(q)
        val h = frameH(q)
        for (box in listOf(
            JpDictRect(0, 9000, 20, 9020),
            JpDictRect(0, 0, 20, 20),
            JpDictRect(9000, 0, 9020, 20),
        )) {
            val r = RotatedGeometry.mapSourceRectToLocal(q, box, w, h)
            assertTrue("$box -> $r", r.width() == 0 || r.height() == 0)
        }
    }

    @Test
    fun aDegenerateFrameGivesTheWholeUprightCrop() {
        // Corners that do not determine a map: all four the same point, three
        // collinear, a zero-area sliver. `warpRotatedCrop` returns null for
        // these (setPolyToPoly fails on them), so the preview shows nothing —
        // the mapping's answer is only here so the *function* is total: it can
        // be handed anything, and a host test can call it, without a NaN or a
        // negative extent escaping into a Rect.
        val degenerate = listOf(
            JpDictQuad(QuadPoint(10f, 10f), QuadPoint(10f, 10f), QuadPoint(10f, 10f), QuadPoint(10f, 10f)),
            JpDictQuad(QuadPoint(0f, 0f), QuadPoint(40f, 0f), QuadPoint(80f, 0f), QuadPoint(80f, 40f)),
            JpDictQuad(QuadPoint(0f, 0f), QuadPoint(100f, 0f), QuadPoint(100f, 0f), QuadPoint(0f, 0f)),
        )
        for (q in degenerate) {
            assertEquals(
                JpDictRect(0, 0, 40, 30),
                RotatedGeometry.mapSourceRectToLocal(q, JpDictRect(0, 0, 10, 10), 40, 30),
            )
        }
    }

    // ── the padding ────────────────────────────────────────────────────────

    @Test
    fun theLocalPaddingIsAppliedInTheUprightFrame() {
        val q = quad(540f, 600f, 400f, 200f, 30f)
        val w = frameW(q)
        val h = frameH(q)
        val box = q.mapLocalRect(JpDictRect(120, 60, 160, 140))
        val local = RotatedGeometry.mapSourceRectToLocal(q, box, w, h)
        for ((ratio, where) in listOf(
            CharPreviewCrop.ALTERNATIVES_PAD_RATIO to "alternatives",
            CharPreviewCrop.MANUAL_PAD_RATIO to "manual",
        )) {
            val rect = CharPreviewCrop.localRect(q, box, ratio, w, h)
            val pad = (local.height() * ratio).toInt()
            assertEquals("$where left", (local.left - pad).coerceAtLeast(0), rect.left)
            assertEquals("$where top", (local.top - pad).coerceAtLeast(0), rect.top)
            assertEquals("$where right", (local.right + pad).coerceAtMost(w), rect.right)
            assertEquals("$where bottom", (local.bottom + pad).coerceAtMost(h), rect.bottom)
            // The margin is a fraction of the *upright* box's height. Had it
            // stayed on the source AABB — the pre-#102 arithmetic — a tilted
            // glyph's margin would grow with its tilt, and a steeply rotated
            // line's preview would be all margin. These two cases are chosen so
            // the padding is not clamped away, so the difference is visible.
            val inSource = CharPreviewCrop.sourceRect(box, ratio, w, h)
            assertTrue("$where pads in local space, not source space", rect != inSource)
        }
        // The alternatives preview keeps room around the glyph in every axis …
        val alternatives = CharPreviewCrop.localRect(q, box, CharPreviewCrop.ALTERNATIVES_PAD_RATIO, w, h)
        assertTrue("alternatives left", alternatives.left > 0)
        assertTrue("alternatives top", alternatives.top > 0)
        assertTrue("alternatives right", alternatives.right < w)
        assertTrue("alternatives bottom", alternatives.bottom < h)
        // … and the manual dialog's margin is the wider one, in the same frame.
        assertTrue(
            CharPreviewCrop.localRect(q, box, CharPreviewCrop.MANUAL_PAD_RATIO, w, h).width() >
                alternatives.width(),
        )
    }

    @Test
    fun aLocalCropAtTheFrameEdgeIsClampedToTheFrame() {
        // Padding is a padding, not a licence to crop outside the bitmap.
        val q = quad(540f, 600f, 400f, 48f, 30f)
        val w = frameW(q)
        val h = frameH(q)
        for (cell in listOf(
            JpDictRect(0, 0, 20, h),
            JpDictRect(w - 20, 0, w, h),
            JpDictRect(0, 0, 30, 12),
            JpDictRect(0, h - 12, 30, h),
        )) {
            val rect = CharPreviewCrop.localRect(q, q.mapLocalRect(cell), CharPreviewCrop.MANUAL_PAD_RATIO, w, h)
            assertTrue("$cell -> $rect left", rect.left >= 0)
            assertTrue("$cell -> $rect top", rect.top >= 0)
            assertTrue("$cell -> $rect right", rect.right <= w)
            assertTrue("$cell -> $rect bottom", rect.bottom <= h)
            assertTrue("$cell -> $rect has area", rect.width() > 0 && rect.height() > 0)
        }
    }

    // ── the axis-aligned Line is untouched ─────────────────────────────────

    @Test
    fun anAxisAlignedLineCropsExactlyAsItDidBefore() {
        // `quad == null` is the default path and the overwhelmingly common one:
        // `sourceRect` has to reproduce the three inline sites' arithmetic
        // exactly — same padding truncation, same one-sided clamps — because
        // the rect *is* the preview, and the crop is `createBitmap` at that
        // rect. Spelled out over a spread of boxes, paddings and page sizes
        // rather than sampled.
        val boxes = listOf(
            JpDictRect(0, 0, 10, 10),
            JpDictRect(3, 7, 41, 39),
            JpDictRect(0, 0, 1080, 2400),
            JpDictRect(1070, 2390, 1080, 2400),
            JpDictRect(500, 500, 500, 500),
            JpDictRect(1, 1, 2, 2),
            JpDictRect(200, 300, 260, 380),
        )
        val sizes = listOf(1080 to 2400, 720 to 1280, 4032 to 3024, 2400 to 1080)
        for ((srcW, srcH) in sizes) {
            for (box in boxes) {
                for (ratio in listOf(0f, 0.2f, 0.5f, 1f)) {
                    assertEquals(
                        "box=$box ratio=$ratio page=${srcW}x$srcH",
                        legacySourceRect(box, ratio, srcW, srcH),
                        CharPreviewCrop.sourceRect(box, ratio, srcW, srcH),
                    )
                }
            }
        }
    }
}
