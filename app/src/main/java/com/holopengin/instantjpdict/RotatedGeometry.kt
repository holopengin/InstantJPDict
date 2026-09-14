package com.holopengin.instantjpdict

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * #53: pure geometry for opt-in rotated line detection.
 *
 * The bundled PP-OCRv6 detector is a DB segmentation net, so its mask carries
 * no box — geometry is decided downstream. The axis-aligned path takes each
 * connected component's min/max X/Y; this object recovers the same component's
 * minimum-area rectangle from its boundary pixels, which is what PaddleOCR's
 * default `quad` postprocess emits (`cv2.minAreaRect`). Nothing here touches
 * Android: the fit, the unclip, the upright-crop frame and the local→source
 * mapping are pinned by JVM unit tests, and only the warp itself (a
 * `setPolyToPoly` draw) lives in the engine.
 *
 * The frame convention: `c0..c3` are the crop's `(0,0),(w,0),(w,h),(0,h)` in
 * source pixels. For a horizontal Line, local x is the reading axis and local
 * y the cross axis; for a vertical Line, local x is the cross axis and local y
 * the reading axis. That is exactly the crop space `OcrEngine.computeCharBoxes`
 * already works in, so char boxes computed there map straight back out.
 */
object RotatedGeometry {

    /** A fitted frame within this many degrees of the upright axes is treated
     *  as axis-aligned and stays on the default (rect) path: the unrotate
     *  cannot buy anything visible at this angle, and the default path is both
     *  simpler and more stable. */
    const val AXIS_ALIGNED_TOL_DEG = 1.0f

    /** Same orientation rule the engine uses for axis-aligned boxes (#28):
     *  near-square counts as horizontal so a lone upright character is never
     *  fed to the model sideways. */
    const val VERTICAL_MIN_ASPECT = 1.25f

    private const val RAD_TO_DEG = 57.29577951308232f
    private const val EPS = 1e-6f

    // ── the fit ─────────────────────────────────────────────────────────────

    /**
     * Fit the minimum-area rectangle to [count] interleaved x,y points, as the
     * upright-crop frame [JpDictQuad]. The rotation is recovered by testing
     * every convex-hull edge direction (a minimum-area enclosing rectangle
     * always has a side collinear with a hull edge); O(h²) over the hull, on
     * an opt-in path only.
     *
     * Returns null when the points are degenerate (fewer than three distinct
     * non-collinear points, e.g. a three-pixel noise blob), so the caller can
     * drop the component.
     */
    fun fitQuad(points: FloatArray, count: Int): JpDictQuad? {
        if (count < 3 || points.size < count * 2) return null
        val hull = convexHull(points, count) ?: return null
        val h = hull.size / 2

        var bestArea = Float.MAX_VALUE
        var cx = 0f
        var cy = 0f
        var ax = 1f
        var ay = 0f
        var aLen = 0f
        var bLen = 0f
        for (i in 0 until h) {
            val j = (i + 1) % h
            var dx = hull[2 * j] - hull[2 * i]
            var dy = hull[2 * j + 1] - hull[2 * i + 1]
            val len = hypot(dx, dy)
            if (len <= EPS) continue
            dx /= len
            dy /= len
            val nx = -dy
            val ny = dx

            var tMin = Float.MAX_VALUE
            var tMax = -Float.MAX_VALUE
            var sMin = Float.MAX_VALUE
            var sMax = -Float.MAX_VALUE
            for (k in 0 until h) {
                val px = hull[2 * k]
                val py = hull[2 * k + 1]
                val t = px * dx + py * dy
                val s = px * nx + py * ny
                if (t < tMin) tMin = t
                if (t > tMax) tMax = t
                if (s < sMin) sMin = s
                if (s > sMax) sMax = s
            }
            val area = (tMax - tMin) * (sMax - sMin)
            if (area < bestArea - EPS) {
                bestArea = area
                val tc = (tMin + tMax) * 0.5f
                val sc = (sMin + sMax) * 0.5f
                cx = tc * dx + sc * nx
                cy = tc * dy + sc * ny
                ax = dx
                ay = dy
                aLen = tMax - tMin
                bLen = sMax - sMin
            }
        }
        if (bestArea == Float.MAX_VALUE) return null

        // Which fitted axis is the long one? The long axis is the reading axis
        // when it lies closer to vertical than to horizontal (and the box is
        // elongated enough); otherwise the axis closer to horizontal is the
        // reading axis — for a near-square box that is not necessarily the
        // longer one. The chosen axes point right/down so the frame is never
        // mirrored, and [JpDictQuad.tiltDeg] is the clockwise turn from
        // upright.
        var lx: Float
        var ly: Float
        val longLen: Float
        val shortLen: Float
        if (aLen >= bLen) {
            lx = ax
            ly = ay
            longLen = aLen
            shortLen = bLen
        } else {
            lx = -ay
            ly = ax
            longLen = bLen
            shortLen = aLen
        }
        val vertical = longLen >= shortLen * VERTICAL_MIN_ASPECT && abs(ly) > abs(lx)
        val localX: QuadPoint
        val localY: QuadPoint
        val w: Float
        val hgt: Float
        if (vertical) {
            if (ly < 0f) {
                lx = -lx
                ly = -ly
            }
            // reading = local y; local x = reading rotated 90° CCW = (ry, -rx).
            localX = QuadPoint(ly, -lx)
            localY = QuadPoint(lx, ly)
            w = shortLen
            hgt = longLen
        } else {
            // reading = the fitted axis closer to horizontal, which for a
            // near-square box is not necessarily the longer one: a 20x22 box
            // must not use its slightly-longer vertical axis as the reading
            // axis and come out turned 90°.
            val useD = abs(ax) >= abs(ay)
            lx = if (useD) ax else -ay
            ly = if (useD) ay else ax
            if (lx < 0f) {
                lx = -lx
                ly = -ly
            }
            // reading = local x; local y = reading rotated 90° CW = (-ry, rx).
            localX = QuadPoint(lx, ly)
            localY = QuadPoint(-ly, lx)
            w = if (useD) aLen else bLen
            hgt = if (useD) bLen else aLen
        }

        val hw = w * 0.5f
        val hh = hgt * 0.5f
        val ox = cx - hw * localX.x - hh * localY.x
        val oy = cy - hw * localX.y - hh * localY.y
        return JpDictQuad(
            QuadPoint(ox, oy),
            QuadPoint(ox + w * localX.x, oy + w * localX.y),
            QuadPoint(ox + w * localX.x + hgt * localY.x, oy + w * localX.y + hgt * localY.y),
            QuadPoint(ox + hgt * localY.x, oy + hgt * localY.y),
        )
    }

    /** Convex hull (monotone chain) of [count] interleaved points, as an
     *  interleaved array, or null when fewer than three distinct non-collinear
     *  points exist. Collinear points are dropped, so hull.size >= 6 when
     *  non-null. */
    private fun convexHull(points: FloatArray, count: Int): FloatArray? {
        val order = (0 until count).sortedWith(
            compareBy({ points[2 * it] }, { points[2 * it + 1] })
        )
        fun cross(o: Int, a: Int, b: Int): Float {
            val ox = points[2 * o]
            val oy = points[2 * o + 1]
            return (points[2 * a] - ox) * (points[2 * b + 1] - oy) -
                (points[2 * a + 1] - oy) * (points[2 * b] - ox)
        }
        val hull = IntArray(2 * count)
        var k = 0
        for (ii in order.indices) {
            val p = order[ii]
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0f) k--
            hull[k++] = p
        }
        if (k < 3) return null
        val lowerSize = k + 1
        for (ii in count - 2 downTo 0) {
            val p = order[ii]
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], p) <= 0f) k--
            hull[k++] = p
        }
        // hull[0..k-2] is the hull; hull[k-1] repeats hull[0].
        val out = FloatArray((k - 1) * 2)
        for (i in 0 until k - 1) {
            out[2 * i] = points[2 * hull[i]]
            out[2 * i + 1] = points[2 * hull[i] + 1]
        }
        return out
    }

    // ── frame helpers ───────────────────────────────────────────────────────

    /** The vertical-Line rule on the frame's own local sizes (pinned to
     *  [VERTICAL_MIN_ASPECT] like the axis-aligned `isVerticalBox`). */
    fun isVertical(quad: JpDictQuad): Boolean =
        quad.localHeight >= quad.localWidth * VERTICAL_MIN_ASPECT

    /** Clockwise degrees the overlay must rotate this frame's glyphs by:
     *  the reading axis' angle away from the upright axis (x for horizontal
     *  Lines, y for vertical ones). The sign matches `View.rotation` and
     *  `Canvas.rotate` (positive = clockwise). */
    fun tiltDeg(quad: JpDictQuad): Float = if (isVertical(quad)) {
        val y = quad.yAxis
        atan2(-y.x, y.y) * RAD_TO_DEG
    } else {
        val x = quad.xAxis
        atan2(x.y, x.x) * RAD_TO_DEG
    }

    /** DB unclip on the frame's own axes: grow both local axes by
     *  `localArea × ratio / localPerimeter` per side. The axis-aligned path
     *  does exactly this on the min/max box; the rotated path does it on the
     *  fitted rect, so the expansion follows the rotation. */
    fun unclip(quad: JpDictQuad, ratio: Float): JpDictQuad {
        val w = quad.localWidth
        val h = quad.localHeight
        if (w <= EPS || h <= EPS) return quad
        val expand = w * h * ratio / (2f * (w + h))
        return inset(quad, -expand, -expand)
    }

    /** Shrink (positive) or grow (negative) the frame along its local x/y
     *  axes, corner-wise. Positive insets keep the centre. */
    fun inset(quad: JpDictQuad, xInset: Float, yInset: Float): JpDictQuad {
        val x = quad.xAxis
        val y = quad.yAxis
        return JpDictQuad(
            QuadPoint(quad.c0.x + xInset * x.x + yInset * y.x, quad.c0.y + xInset * x.y + yInset * y.y),
            QuadPoint(quad.c1.x - xInset * x.x + yInset * y.x, quad.c1.y - xInset * x.y + yInset * y.y),
            QuadPoint(quad.c2.x - xInset * x.x - yInset * y.x, quad.c2.y - xInset * x.y - yInset * y.y),
            QuadPoint(quad.c3.x + xInset * x.x - yInset * y.x, quad.c3.y + xInset * x.y - yInset * y.y),
        )
    }

    /** The frame's enclosing axis-aligned rect, rounded to whole pixels. */
    fun aabb(quad: JpDictQuad): JpDictRect = JpDictRect(
        minOf(quad.c0.x, quad.c1.x, quad.c2.x, quad.c3.x).roundToInt(),
        minOf(quad.c0.y, quad.c1.y, quad.c2.y, quad.c3.y).roundToInt(),
        maxOf(quad.c0.x, quad.c1.x, quad.c2.x, quad.c3.x).roundToInt(),
        maxOf(quad.c0.y, quad.c1.y, quad.c2.y, quad.c3.y).roundToInt(),
    )

    /** A box in the frame's local crop space as a source-space AABB: map the
     *  four local corners through the frame (origin `c0`, axes [JpDictQuad.xAxis]/
     *  [JpDictQuad.yAxis]) and take the enclosing rect. The default
     *  (axis-aligned) frame maps one-to-one, so upright char boxes are
     *  untouched. */
    fun mapLocalRect(quad: JpDictQuad, local: JpDictRect): JpDictRect {
        val x = quad.xAxis
        val y = quad.yAxis
        val lx0 = local.left.toFloat()
        val lx1 = local.right.toFloat()
        val ly0 = local.top.toFloat()
        val ly1 = local.bottom.toFloat()
        fun px(lx: Float, ly: Float) = quad.c0.x + lx * x.x + ly * y.x
        fun py(lx: Float, ly: Float) = quad.c0.y + lx * x.y + ly * y.y
        val p0x = px(lx0, ly0)
        val p0y = py(lx0, ly0)
        val p1x = px(lx1, ly0)
        val p1y = py(lx1, ly0)
        val p2x = px(lx1, ly1)
        val p2y = py(lx1, ly1)
        val p3x = px(lx0, ly1)
        val p3y = py(lx0, ly1)
        return JpDictRect(
            minOf(p0x, p1x, p2x, p3x).roundToInt(),
            minOf(p0y, p1y, p2y, p3y).roundToInt(),
            maxOf(p0x, p1x, p2x, p3x).roundToInt(),
            maxOf(p0y, p1y, p2y, p3y).roundToInt(),
        )
    }

    // ── small vector helpers ────────────────────────────────────────────────

    internal fun distance(a: QuadPoint, b: QuadPoint): Float = hypot(b.x - a.x, b.y - a.y)

    internal fun unit(a: QuadPoint, b: QuadPoint): QuadPoint {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        return if (len <= EPS) QuadPoint(0f, 0f) else QuadPoint(dx / len, dy / len)
    }
}
