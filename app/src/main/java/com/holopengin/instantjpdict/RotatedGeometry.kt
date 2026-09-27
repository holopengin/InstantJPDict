package com.holopengin.instantjpdict

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import uniffi.nav_graph_core.RotatedGeometryPoint as RustPoint
import uniffi.nav_graph_core.RotatedGeometryQuad as RustQuad
import uniffi.nav_graph_core.RotatedGeometryRect as RustRect
import uniffi.nav_graph_core.rotatedGeometryAabb
import uniffi.nav_graph_core.rotatedGeometryAxisAlignedBoundDeg
import uniffi.nav_graph_core.rotatedGeometryAxisAlignedQuantTolPx
import uniffi.nav_graph_core.rotatedGeometryAxisAlignedTolDeg
import uniffi.nav_graph_core.rotatedGeometryFitQuad
import uniffi.nav_graph_core.rotatedGeometryFilterEnclosingBlobsIndices
import uniffi.nav_graph_core.rotatedGeometryInset
import uniffi.nav_graph_core.rotatedGeometryIsVertical
import uniffi.nav_graph_core.rotatedGeometryMapLocalRect
import uniffi.nav_graph_core.rotatedGeometryTiltDeg
import uniffi.nav_graph_core.rotatedGeometryUnclip
import uniffi.nav_graph_core.rotatedGeometryVerticalMinAspect

/**
 * #53: pure geometry for opt-in rotated line detection.
 *
 * The fit, orientation predicates, unclip/inset arithmetic, local→source
 * mapping, and enclosing-blob filter now live in the PC crate's
 * `jpdict_core::models` module. This object is the Android facade: it keeps
 * the pre-conversion API and converts the app's four-corner `JpDictQuad` /
 * `JpDictRect` values to the UniFFI records at the boundary.
 *
 * The frame convention is unchanged: `c0..c3` are the crop's
 * `(0,0),(w,0),(w,h),(0,h)` in source pixels. For a horizontal Line, local x
 * is the reading axis and local y the cross axis; for a vertical Line, local x
 * is the cross axis and local y the reading axis. The Rust model stores the
 * same frame as centre + width + height + the local-x angle, and reconstructs
 * the corners in this exact order.
 */
object RotatedGeometry {

    /**
     * A fitted frame within this many degrees of the upright axes is treated
     * as axis-aligned and stays on the default (rect) path. Read from
     * `jpdict_core` at first use (UniFFI cannot export consts).
     */
    val AXIS_ALIGNED_TOL_DEG: Float = rotatedGeometryAxisAlignedTolDeg()

    /** PC fit-quantization allowance in pixels, read from `jpdict_core`. */
    val AXIS_ALIGNED_QUANT_TOL_PX: Float = rotatedGeometryAxisAlignedQuantTolPx()

    /** PC vertical-orientation threshold, read from `jpdict_core`. */
    val VERTICAL_MIN_ASPECT: Float = rotatedGeometryVerticalMinAspect()

    /** #102: singular-pivot threshold for [sourceToLocal], relative to the
     *  largest entry of the eight-unknown system. See its KDoc for the
     *  measurements behind the number. */
    private const val SOLVE_TOL = 1e-9

    /**
     * Widened axis-aligned bound in degrees for a frame whose long side is
     * [longSide] px: `max(tolDeg, atan(1.5px / longSide))`.
     */
    fun axisAlignedBoundDeg(longSide: Float, tolDeg: Float = AXIS_ALIGNED_TOL_DEG): Float =
        rotatedGeometryAxisAlignedBoundDeg(longSide, tolDeg)

    /**
     * Fit the minimum-area rectangle to [count] interleaved x,y points.
     * Returns null for a degenerate point set.
     */
    fun fitQuad(points: FloatArray, count: Int): JpDictQuad? =
        rotatedGeometryFitQuad(points.toList(), count.toLong())?.toQuad()

    /** The frame's local-height/local-width vertical rule. */
    fun isVertical(quad: JpDictQuad): Boolean =
        rotatedGeometryIsVertical(quad.toRust())

    /** Clockwise rotation of the frame, in the same y-down sign as before. */
    fun tiltDeg(quad: JpDictQuad): Float =
        rotatedGeometryTiltDeg(quad.toRust())

    /** Grow both local axes by the DB unclip amount. */
    fun unclip(quad: JpDictQuad, ratio: Float): JpDictQuad =
        rotatedGeometryUnclip(quad.toRust(), ratio).toQuad()

    /** Shrink (positive) or grow (negative) along the frame's local axes. */
    fun inset(quad: JpDictQuad, xInset: Float, yInset: Float): JpDictQuad =
        rotatedGeometryInset(quad.toRust(), xInset, yInset).toQuad()

    /**
     * The frame's enclosing axis-aligned rectangle, rounded to whole pixels.
     * The Rust `BoundingBox` origin-plus-extent shape is converted to edges in
     * the same way as [mapLocalRect], so a whole-frame result agrees exactly.
     */
    fun aabb(quad: JpDictQuad): JpDictRect =
        rotatedGeometryAabb(quad.toRust()).toRect()

    /** Map a local crop rectangle into source pixels as an enclosing AABB. */
    fun mapLocalRect(quad: JpDictQuad, local: JpDictRect): JpDictRect =
        rotatedGeometryMapLocalRect(quad.toRust(), local.toRust()).toRect()

    /**
     * #102: the **inverse** of [mapLocalRect] — a source-space rect as an
     * enclosing rect in the frame's own upright space, where [frameW] ×
     * [frameH] is the unrotate's output size (`OcrEngine.localCropW/H`, the
     * size `OcrEngine.warpRotatedCrop` actually produced).
     *
     * A rotated Line's `charBoxes` are the AABBs of *tilted* cells
     * ([mapLocalRect] applied to each local cell), so a caller that wants to
     * show one of those cells has to come back this way before it can pad and
     * crop it: the previews crop in the upright frame, so the glyph is the way
     * up the recogniser saw it (#102).
     *
     * It is the frame's own inverse, not a fresh fit: the four corners of
     * [source] are mapped through the same transformation the unrotate draws
     * with — `Matrix.setPolyToPoly(quad.corners(), local, 4)`, i.e. the
     * inverse of [JpDictQuad.corners] — and the AABB of the four mapped points
     * is the answer.
     *
     * **The result encloses the cell's true local rect; it is not that rect.**
     * A source AABB is information-losing (a 40 × 20 AABB could have been a
     * 20 × 40 cell tilted 90°), and only points are mapped, so the four corners
     * come back as a local quad and their AABB is the closest sound answer.
     * Enclosing is the right direction for a preview: it shows a little more of
     * the line around the glyph, never a clipped one. The over-grab is the
     * shear the tilt adds — a square cell at 5° comes back ~17% wider, at 30°
     * ~1.9× — and it grows with the tilt, so it is invisible for a line the
     * axis-aligned gate barely admitted and generous for a steeply rotated one.
     *
     * Edges round **outward** (floor/ceil) so the enclosure survives its own
     * rounding, and the rect is clamped to the frame. A frame that does not
     * determine a transformation (degenerate or repeated corners) returns the
     * whole upright frame: a truthful preview of the line beats both a guess
     * and a crash. The result can be empty when [source] lies entirely outside
     * the frame; a caller crops nothing for that.
     *
     * Pure arithmetic — no `Bitmap`, no `Matrix` — so the frame maths is
     * host-testable, and it lives here rather than in the PC crate because its
     * authority is Android's `Matrix.setPolyToPoly`, the seam the unrotate
     * itself uses, not the PC geometry the other functions delegate to.
     */
    fun mapSourceRectToLocal(quad: JpDictQuad, source: JpDictRect, frameW: Int, frameH: Int): JpDictRect {
        val inverse = sourceToLocal(quad, frameW, frameH) ?: return JpDictRect(0, 0, frameW, frameH)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (px in doubleArrayOf(source.left.toDouble(), source.right.toDouble())) {
            for (py in doubleArrayOf(source.top.toDouble(), source.bottom.toDouble())) {
                // A corner on the horizon (the projective denominator's zero)
                // has no local position: the frame cannot describe it.
                val den = inverse[6] * px + inverse[7] * py + inverse[8]
                if (den == 0.0) return JpDictRect(0, 0, frameW, frameH)
                val x = (inverse[0] * px + inverse[1] * py + inverse[2]) / den
                val y = (inverse[3] * px + inverse[4] * py + inverse[5]) / den
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val left = floor(minX).toInt().coerceIn(0, frameW)
        val top = floor(minY).toInt().coerceIn(0, frameH)
        return JpDictRect(
            left,
            top,
            ceil(maxX).toInt().coerceIn(left, frameW),
            ceil(maxY).toInt().coerceIn(top, frameH),
        )
    }

    /**
     * The frame's inverse as a projective map `quad.corners()` →
     * `(0,0),(w,0),(w,h),(0,h)`: nine doubles, row-major, `x' = (m0x+m1y+m2) /
     * (m6x+m7y+m8)`. The corners the warp feeds to `setPolyToPoly` and the
     * rect it draws into, so this is that call's transformation and not an
     * approximation of it.
     *
     * Solved as the standard eight-unknown system (`h33` pinned to 1 — four
     * point correspondences, two equations each) by Gaussian elimination with
     * partial pivoting, in `Double`: the corners are f32, and an f32
     * elimination would lose the digits the enclosure argument above rests on.
     * Returns null when the system is singular, which is exactly when the four
     * corners do not determine a map (a degenerate or repeated corner).
     *
     * [SOLVE_TOL] is a pivot ratio against the largest matrix entry, chosen
     * from measurements: every frame this app produces — any rotation of a
     * non-degenerate rectangle, from 4 × 4 px up — leaves a pivot ratio above
     * 4e-8, while a degenerate frame lands at or below 1e-12, so the threshold
     * sits four orders clear of both.
     */
    private fun sourceToLocal(quad: JpDictQuad, frameW: Int, frameH: Int): DoubleArray? {
        val src = quad.corners()
        val w = frameW.toDouble()
        val h = frameH.toDouble()
        val dst = doubleArrayOf(0.0, 0.0, w, 0.0, w, h, 0.0, h)
        val a = DoubleArray(8 * 8)
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble()
            val y = src[2 * i + 1].toDouble()
            val bx = dst[2 * i]
            val by = dst[2 * i + 1]
            // x' row: x,y,1 on h11..h13 and -(x,y)·bx on h31,h32; y' row likewise.
            val rx = 2 * i * 8
            a[rx + 0] = x; a[rx + 1] = y; a[rx + 2] = 1.0
            a[rx + 6] = -x * bx; a[rx + 7] = -y * bx; b[2 * i] = bx
            val ry = (2 * i + 1) * 8
            a[ry + 3] = x; a[ry + 4] = y; a[ry + 5] = 1.0
            a[ry + 6] = -x * by; a[ry + 7] = -y * by; b[2 * i + 1] = by
        }
        var scale = 0.0
        for (v in a) scale = maxOf(scale, abs(v))
        if (scale <= 0.0) return null
        for (col in 0 until 8) {
            var pivotRow = col
            for (row in col + 1 until 8) {
                if (abs(a[row * 8 + col]) > abs(a[pivotRow * 8 + col])) pivotRow = row
            }
            val pivot = a[pivotRow * 8 + col]
            if (abs(pivot) <= SOLVE_TOL * scale) return null
            if (pivotRow != col) {
                for (k in 0 until 8) {
                    val t = a[col * 8 + k]; a[col * 8 + k] = a[pivotRow * 8 + k]; a[pivotRow * 8 + k] = t
                }
                val t = b[col]; b[col] = b[pivotRow]; b[pivotRow] = t
            }
            val d = a[col * 8 + col]
            for (row in col + 1 until 8) {
                val f = a[row * 8 + col] / d
                if (f == 0.0) continue
                for (k in col until 8) a[row * 8 + k] -= f * a[col * 8 + k]
                b[row] -= f * b[col]
            }
        }
        val sol = DoubleArray(8)
        for (row in 7 downTo 0) {
            var s = b[row]
            for (k in row + 1 until 8) s -= a[row * 8 + k] * sol[k]
            sol[row] = s / a[row * 8 + row]
        }
        return doubleArrayOf(sol[0], sol[1], sol[2], sol[3], sol[4], sol[5], sol[6], sol[7], 1.0)
    }

    /**
     * Drop frames enclosing several smaller, line-shaped frames.
     *
     * The Rust filter returns newly reconstructed corner values. The facade
     * uses its index-preserving companion here so the old Kotlin contract —
     * returning the original objects and their exact float bits — remains true.
     */
    fun filterEnclosingBlobs(quads: List<JpDictQuad>): List<JpDictQuad> {
        // The PC filter's <=2 identity guard; keep the original list (and its
        // object identity) for this boundary case.
        if (quads.size <= 2) return quads
        val indices = rotatedGeometryFilterEnclosingBlobsIndices(quads.map { it.toRust() })
        return indices.mapNotNull { index -> quads.getOrNull(index.toInt()) }
    }

    /** Distance helper retained for `JpDictQuad`'s derived local dimensions. */
    internal fun distance(a: QuadPoint, b: QuadPoint): Float = hypot(b.x - a.x, b.y - a.y)

    /** Unit-vector helper retained for `JpDictQuad`'s derived local axes. */
    internal fun unit(a: QuadPoint, b: QuadPoint): QuadPoint {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return if (len <= 1e-6f) QuadPoint(0f, 0f) else QuadPoint(dx / len, dy / len)
    }

    private fun QuadPoint.toRust(): RustPoint = RustPoint(x, y)

    private fun RustPoint.toPoint(): QuadPoint = QuadPoint(x, y)

    private fun JpDictQuad.toRust(): RustQuad = RustQuad(
        c0 = c0.toRust(),
        c1 = c1.toRust(),
        c2 = c2.toRust(),
        c3 = c3.toRust(),
    )

    private fun RustQuad.toQuad(): JpDictQuad = JpDictQuad(
        c0 = c0.toPoint(),
        c1 = c1.toPoint(),
        c2 = c2.toPoint(),
        c3 = c3.toPoint(),
    )

    private fun JpDictRect.toRust(): RustRect = RustRect(left, top, right, bottom)

    private fun RustRect.toRect(): JpDictRect = JpDictRect(left, top, right, bottom)
}
