package com.holopengin.instantjpdict

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
