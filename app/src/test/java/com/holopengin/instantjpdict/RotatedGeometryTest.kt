package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #53: the pure geometry behind opt-in rotated line detection. The detector is a
 * DB segmentation net, so its mask carries no box — the rotation is recovered by
 * fitting a minimum-area rectangle to the same connected-component pixels the
 * axis-aligned path fits min/max to. These tests pin that fit, the frame it
 * defines (corners are the crop's `(0,0),(w,0),(w,h),(0,h)`), the tilt the
 * renderer rotates glyphs by, and the unclip/unwarp arithmetic.
 *
 * Every expected number is computed independently (Python, screen coordinates
 * with y down) from the shape described in the test name, not by re-running the
 * code under test.
 */
class RotatedGeometryTest {

    // ── the fit ─────────────────────────────────────────────────────────────

    @Test
    fun anAxisAlignedComponentFitsItsPixelExtents() {
        // A 20x10 block of pixels x in [10,30), y in [30,40): represented by the
        // four outer pixel corners, as the detector's boundary walk produces them.
        val pts = floatArrayOf(
            10f, 30f, 30f, 30f, 30f, 40f, 10f, 40f,
        )
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        assertEquals(JpDictRect(10, 30, 30, 40), quad.toRect())
        assertEquals(20f, quad.localWidth, 0.01f)
        assertEquals(10f, quad.localHeight, 0.01f)
        assertEquals(0f, quad.tiltDeg, 0.01f)
        assertFalse(RotatedGeometry.isVertical(quad))
        assertTrue(quad.isAxisAligned())
    }

    @Test
    fun aRotatedHorizontalRectFitsTheSameRectItWasMadeFrom() {
        // A 40x10 rect centred on (100,100), turned 30 degrees clockwise.
        // Corners computed by hand (independent of the implementation).
        val pts = floatArrayOf(
            85.17949192431122f, 85.6698729810778f,
            119.82050807568876f, 105.6698729810778f,
            114.82050807568876f, 114.33012701892218f,
            80.17949192431122f, 94.33012701892218f,
        )
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        assertEquals(100f, quad.center.x, 0.01f)
        assertEquals(100f, quad.center.y, 0.01f)
        assertEquals(40f, quad.localWidth, 0.01f)
        assertEquals(10f, quad.localHeight, 0.01f)
        assertEquals(30f, quad.tiltDeg, 0.01f)
        assertFalse(RotatedGeometry.isVertical(quad))
        assertFalse(quad.isAxisAligned())
        listOf(quad.c0, quad.c1, quad.c2, quad.c3).forEachIndexed { i, c ->
            assertEquals("c$i.x", pts[2 * i], c.x, 0.01f)
            assertEquals("c$i.y", pts[2 * i + 1], c.y, 0.01f)
        }
    }

    @Test
    fun aRotatedVerticalColumnReadsTopToBottomAndKeepsTheColumnsOwnRotation() {
        // A 10x40 column centred on (50,60), turned 15 degrees clockwise.
        // The unrotated column reads down the local y axis; a clockwise turn
        // moves the reading direction to (-sin15, cos15) (bottom swings left).
        val pts = floatArrayOf(
            50.34675177060507f, 39.38738824870603f,
            60.00601003349575f, 41.97557869973124f,
            49.65324822939492f, 80.61261175129397f,
            39.99398996650424f, 78.02442130026876f,
        )
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        assertTrue(RotatedGeometry.isVertical(quad))
        assertEquals(10f, quad.localWidth, 0.01f)
        assertEquals(40f, quad.localHeight, 0.01f)
        // +15 clockwise, the same sign View.rotation/canvas.rotate take.
        assertEquals(15f, quad.tiltDeg, 0.01f)
        assertEquals(50f, quad.center.x, 0.01f)
        assertEquals(60f, quad.center.y, 0.01f)
    }

    @Test
    fun nearSquareBoxesStayHorizontalLikeTheAxisAlignedRule() {
        // 20x22: below the engine's 1.25 vertical aspect, so horizontal.
        val pts = floatArrayOf(0f, 0f, 20f, 0f, 20f, 22f, 0f, 22f)
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        assertFalse(RotatedGeometry.isVertical(quad))
        assertTrue(quad.isAxisAligned())
    }

    @Test
    fun interiorAndDuplicatePointsDoNotDisturbTheFit() {
        val pts = floatArrayOf(
            0f, 0f,
            10f, 0f,
            10f, 20f,
            0f, 20f,
            5f, 10f,   // interior
            10f, 0f,   // duplicate corner
            5f, 0f,    // edge point
        )
        val quad = RotatedGeometry.fitQuad(pts, 7)!!
        assertEquals(JpDictRect(0, 0, 10, 20), quad.toRect())
    }

    @Test
    fun aDegeneratePointSetHasNoFit() {
        assertNull(RotatedGeometry.fitQuad(floatArrayOf(1f, 1f), 1))
        assertNull(RotatedGeometry.fitQuad(floatArrayOf(0f, 0f, 10f, 10f), 2))
        // Collinear: the hull has no area, so no rectangle.
        assertNull(RotatedGeometry.fitQuad(floatArrayOf(0f, 0f, 5f, 5f, 10f, 10f), 3))
    }

    // ── the unclip ──────────────────────────────────────────────────────────

    @Test
    fun unclipExpandsAlongTheBoxesOwnAxesAndKeepsTheCentre() {
        // 40x10 axis aligned at the origin: area 400, perimeter 100,
        // expand = area * ratio / perimeter = 400*1.5/100 = 6 per side.
        val quad = JpDictQuad.fromRect(JpDictRect(0, 0, 40, 10))
        val out = RotatedGeometry.unclip(quad, 1.5f)
        assertEquals(JpDictRect(-6, -6, 46, 16), out.toRect())
        assertEquals(52f, out.localWidth, 0.01f)
        assertEquals(22f, out.localHeight, 0.01f)
        assertEquals(quad.center.x, out.center.x, 0.001f)
        assertEquals(quad.center.y, out.center.y, 0.001f)
    }

    @Test
    fun unclipOnARotatedBoxGrowsBothLocalAxes() {
        // The same 20x10 box, turned 25 degrees: area 200, perimeter 60,
        // expand = 200*1.5/60 = 5 per side, along the box's own axes.
        val moved = JpDictQuad(
            QuadPoint(93.050013f, 91.242278f),
            QuadPoint(111.176169f, 99.694644f),
            QuadPoint(106.949987f, 108.757722f),
            QuadPoint(88.823831f, 100.305356f),
        )
        val out = RotatedGeometry.unclip(moved, 1.5f)
        assertEquals(moved.localWidth + 10f, out.localWidth, 0.05f)
        assertEquals(moved.localHeight + 10f, out.localHeight, 0.05f)
        assertEquals(moved.center.x, out.center.x, 0.001f)
        assertEquals(moved.center.y, out.center.y, 0.001f)
    }

    // ── the frame: local crop rectangle → source pixels ─────────────────────

    @Test
    fun anAxisAlignedFrameMapsLocalBoxesOneToOne() {
        val quad = JpDictQuad.fromRect(JpDictRect(10, 30, 30, 40))
        assertEquals(JpDictRect(12, 34, 18, 38), quad.mapLocalRect(JpDictRect(2, 4, 8, 8)))
    }

    @Test
    fun aRotatedFrameMapsLocalCornersOntoTheFittedCorners() {
        val pts = floatArrayOf(
            85.17949192431122f, 85.6698729810778f,
            119.82050807568876f, 105.6698729810778f,
            114.82050807568876f, 114.33012701892218f,
            80.17949192431122f, 94.33012701892218f,
        )
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        // The whole local crop is exactly the quad's AABB.
        assertEquals(quad.toRect(), quad.mapLocalRect(JpDictRect(0, 0, 40, 10)))
        // A local sub-box maps to a rotated rectangle; since char boxes are
        // whole-pixel AABBs, the mapped centre can sit up to half a pixel from
        // the exact rotated centre (rounding on each edge).
        val mapped = quad.mapLocalRect(JpDictRect(0, 0, 10, 5))
        val expectedCx = 85.17949192431122f + 5f * 0.8660254f + 2.5f * -0.5f
        val expectedCy = 85.6698729810778f + 5f * 0.5f + 2.5f * 0.8660254f
        assertEquals(expectedCx, (mapped.left + mapped.right) / 2f, 0.51f)
        assertEquals(expectedCy, (mapped.top + mapped.bottom) / 2f, 0.51f)
        assertTrue(mapped.width() > 10)
        assertTrue(mapped.height() > 5)
    }

    // ── routing: upright content stays on the default path ──────────────────

    @Test
    fun aFitWithinTheToleranceIsAxisAlignedSoItCanTakeTheRectPath() {
        val level = RotatedGeometry.fitQuad(
            floatArrayOf(90f, 95f, 110f, 95f, 110f, 105f, 90f, 105f), 4,
        )!!
        assertTrue(level.isAxisAligned())
        assertTrue(level.isAxisAligned(0.5f))

        // 0.5 degrees: still inside the 1 degree routing tolerance.
        val half = RotatedGeometry.fitQuad(
            floatArrayOf(
                90.04401344685016f, 94.9129250296954f,
                110.04325190813358f, 95.08745573966289f,
                109.95598655314984f, 105.0870749703046f,
                89.95674809186642f, 104.91254426033711f,
            ), 4,
        )!!
        assertEquals(0.5f, half.tiltDeg, 0.01f)
        assertTrue(half.isAxisAligned())

        // 5 degrees: a real rotation, so the unrotate path is needed.
        val five = RotatedGeometry.fitQuad(
            floatArrayOf(
                90.47383173282083f, 94.14746908206469f,
                110.39772569465573f, 95.89058393701785f,
                109.52616826717914f, 105.85253091793531f,
                89.60227430534424f, 104.10941606298215f,
            ), 4,
        )!!
        assertEquals(5f, five.tiltDeg, 0.01f)
        assertFalse(five.isAxisAligned())
    }

    // ── the value type ──────────────────────────────────────────────────────

    @Test
    fun fromRectPinsTheCornerOrderTheFrameIsBuiltOn() {
        val quad = JpDictQuad.fromRect(JpDictRect(10, 30, 30, 40))
        assertEquals(10f, quad.c0.x, 0f)
        assertEquals(30f, quad.c0.y, 0f)
        assertEquals(30f, quad.c1.x, 0f)
        assertEquals(30f, quad.c1.y, 0f)
        assertEquals(30f, quad.c2.x, 0f)
        assertEquals(40f, quad.c2.y, 0f)
        assertEquals(10f, quad.c3.x, 0f)
        assertEquals(40f, quad.c3.y, 0f)
        assertEquals(JpDictRect(10, 30, 30, 40), quad.toRect())
        assertEquals(20f, quad.localWidth, 0f)
        assertEquals(10f, quad.localHeight, 0f)
    }

    @Test
    fun aVerticalRectFitsAsAVerticalQuadWhoseLocalYIsTheReadingAxis() {
        val quad = JpDictQuad.fromRect(JpDictRect(10, 30, 20, 70))
        assertTrue(RotatedGeometry.isVertical(quad))
        assertEquals(10f, quad.localWidth, 0.001f)
        assertEquals(40f, quad.localHeight, 0.001f)
        assertEquals(0f, quad.tiltDeg, 0.001f)
        // Local x runs right, local y runs down (the crop's own axes).
        assertEquals(1f, quad.xAxis.x, 0.001f)
        assertEquals(0f, quad.xAxis.y, 0.001f)
        assertEquals(0f, quad.yAxis.x, 0.001f)
        assertEquals(1f, quad.yAxis.y, 0.001f)
    }

    @Test
    fun aLineBoxIsAnAxisAlignedRectUntilAQuadIsSupplied() {
        val rect = JpDictRect(1, 2, 3, 4)
        val plain = LineBox(rect)
        assertFalse(plain.isRotated)
        assertEquals(rect, plain.rect)

        val quad = JpDictQuad.fromRect(rect)
        val rotated = LineBox(rect, quad)
        assertTrue(rotated.isRotated)
        assertEquals(quad, rotated.quad)
    }

    // ── blob filtering ──────────────────────────────────────────────────────

    /** Same shape as [JpDictQuad.fromRect], turned [deg] degrees clockwise
     *  about the rect's centre. Built here rather than via fitQuad so the
     *  expected containment is plain from the coordinates. */
    private fun rotatedRect(rect: JpDictRect, deg: Float): JpDictQuad {
        val rad = Math.toRadians(deg.toDouble())
        val c = Math.cos(rad).toFloat()
        val s = Math.sin(rad).toFloat()
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        fun p(x: Float, y: Float): QuadPoint {
            val dx = x - cx
            val dy = y - cy
            return QuadPoint(cx + dx * c - dy * s, cy + dx * s + dy * c)
        }
        return JpDictQuad(
            p(rect.left.toFloat(), rect.top.toFloat()),
            p(rect.right.toFloat(), rect.top.toFloat()),
            p(rect.right.toFloat(), rect.bottom.toFloat()),
            p(rect.left.toFloat(), rect.bottom.toFloat()),
        )
    }

    @Test
    fun aFrameEnclosingTwoSmallerLinesIsDropped() {
        val blob = JpDictQuad.fromRect(JpDictRect(0, 0, 300, 300))
        val lineA = JpDictQuad.fromRect(JpDictRect(10, 10, 210, 35))
        val lineB = JpDictQuad.fromRect(JpDictRect(10, 100, 210, 125))
        assertEquals(listOf(lineA, lineB), RotatedGeometry.filterEnclosingBlobs(listOf(blob, lineA, lineB)))
    }

    @Test
    fun aFrameEnclosingTwoSmallerLinesIsDroppedWhenTiltedToo() {
        val blob = rotatedRect(JpDictRect(0, 0, 300, 300), 30f)
        val lineA = rotatedRect(JpDictRect(20, 20, 220, 45), 30f)
        val lineB = rotatedRect(JpDictRect(20, 120, 220, 145), 30f)
        assertEquals(listOf(lineA, lineB), RotatedGeometry.filterEnclosingBlobs(listOf(blob, lineA, lineB)))
    }

    @Test
    fun aFrameEnclosingOnlyOneLineIsKept() {
        val blob = JpDictQuad.fromRect(JpDictRect(0, 0, 300, 300))
        val lineA = JpDictQuad.fromRect(JpDictRect(10, 10, 210, 35))
        assertEquals(listOf(blob, lineA), RotatedGeometry.filterEnclosingBlobs(listOf(blob, lineA)))
    }

    @Test
    fun enclosedMarksAndCrumbsAreNotLines() {
        val blob = JpDictQuad.fromRect(JpDictRect(0, 0, 300, 300))
        // Two 30x30 squares: small, but not Line-shaped.
        val markA = JpDictQuad.fromRect(JpDictRect(10, 10, 40, 40))
        val markB = JpDictQuad.fromRect(JpDictRect(100, 100, 130, 130))
        assertEquals(listOf(blob, markA, markB), RotatedGeometry.filterEnclosingBlobs(listOf(blob, markA, markB)))
    }

    @Test
    fun aFrameEnclosingAComparablySizedFrameIsKept() {
        val outer = JpDictQuad.fromRect(JpDictRect(0, 0, 300, 300))
        // 250x250 = 0.69 of the outer area: smaller, but not substantially.
        val innerA = JpDictQuad.fromRect(JpDictRect(10, 10, 260, 260))
        val innerB = JpDictQuad.fromRect(JpDictRect(20, 20, 240, 40))
        assertEquals(listOf(outer, innerA, innerB), RotatedGeometry.filterEnclosingBlobs(listOf(outer, innerA, innerB)))
    }

    @Test
    fun disjointLinesAreNeverDropped() {
        val lines = listOf(
            JpDictQuad.fromRect(JpDictRect(0, 0, 200, 30)),
            JpDictQuad.fromRect(JpDictRect(0, 50, 200, 80)),
            JpDictQuad.fromRect(JpDictRect(0, 100, 200, 130)),
        )
        assertEquals(lines, RotatedGeometry.filterEnclosingBlobs(lines))
    }

    @Test
    fun aDroppedBlobLeavesTheEnclosedLinesInOrder() {
        val blob = JpDictQuad.fromRect(JpDictRect(0, 0, 400, 300))
        val lineA = JpDictQuad.fromRect(JpDictRect(5, 5, 205, 30))
        val lineB = JpDictQuad.fromRect(JpDictRect(5, 50, 205, 75))
        val far = JpDictQuad.fromRect(JpDictRect(500, 500, 700, 530))
        assertEquals(listOf(lineA, lineB, far), RotatedGeometry.filterEnclosingBlobs(listOf(lineA, blob, lineB, far)))
    }
}
