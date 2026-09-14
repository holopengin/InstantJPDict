package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #53: how a rotated Line reaches the overlay — the render tilt, the glyph size
 * measured in the Line's own upright frame, and the #78 container re-fit moving
 * quads the same way it moves rects. All pure JVM; the drawing itself is
 * verified on device.
 */
class RotatedLineResultTest {

    private fun line(
        text: String = "あいう",
        charBoxes: List<JpDictRect> = emptyList(),
        isVertical: Boolean = false,
        seqLenTotal: Int = 0,
        cropW: Int = 0,
        cropH: Int = 0,
        quad: JpDictQuad? = null,
    ) = LineResult(
        text = text,
        charBoxes = charBoxes,
        alternatives = emptyList(),
        isVertical = isVertical,
        seqLenTotal = seqLenTotal,
        cropW = cropW,
        cropH = cropH,
        quad = quad,
    )

    @Test
    fun defaultPathGlyphSizeIsStillTheLargestCharBoxHeight() {
        val l = line(
            charBoxes = listOf(
                JpDictRect(0, 0, 10, 30),
                JpDictRect(10, 0, 20, 50),
                JpDictRect(20, 0, 30, 40),
            ),
        )
        assertEquals(50, l.glyphSizePx())
    }

    @Test
    fun rotatedHorizontalGlyphSizeComesFromTheUprightFrame() {
        // Char boxes are AABBs of the rotated cells; the cross size is cropH.
        val quad = JpDictQuad.fromRect(JpDictRect(0, 0, 40, 30))
        val l = line(
            charBoxes = listOf(JpDictRect(0, 0, 30, 30)),
            cropW = 40,
            cropH = 30,
            quad = quad,
        )
        assertEquals(30, l.glyphSizePx())
        assertEquals(0f, l.tiltDeg, 0.001f)
    }

    @Test
    fun rotatedVerticalGlyphSizeIsTheLocalTimestepLength() {
        val quad = JpDictQuad.fromRect(JpDictRect(0, 0, 20, 80))
        val l = line(
            isVertical = true,
            seqLenTotal = 8,
            cropW = 20,
            cropH = 80,
            quad = quad,
        )
        assertEquals(10, l.glyphSizePx())
    }

    @Test
    fun tiltDegFollowsTheQuad() {
        val pts = floatArrayOf(
            85.17949192431122f, 85.6698729810778f,
            119.82050807568876f, 105.6698729810778f,
            114.82050807568876f, 114.33012701892218f,
            80.17949192431122f, 94.33012701892218f,
        )
        val quad = RotatedGeometry.fitQuad(pts, 4)!!
        assertEquals(30f, line(quad = quad).tiltDeg, 0.01f)
        assertEquals(0f, line().tiltDeg, 0.001f)
    }

    @Test
    fun aRotatedLineCarriesItsQuadAndAnUprightOneDoesNot() {
        assertNull(line().quad)
        val quad = JpDictQuad.fromRect(JpDictRect(0, 0, 10, 20))
        assertEquals(quad, line(quad = quad).quad)
    }

    @Test
    fun containerRefitMovesRectsAndQuadsTogether() {
        val controller = OcrOverlayStateController()
        val rect = JpDictRect(10, 20, 30, 40)
        val quad = JpDictQuad.fromRect(rect)
        controller.activeLineBoxes = listOf(LineBox(rect, quad))
        controller.activeLineResults = mutableListOf(
            line(charBoxes = listOf(JpDictRect(10, 20, 15, 40)), quad = quad),
        )

        controller.refitBoxes(ImageShareFit.Refit(scaleX = 2f, scaleY = 2f, offsetX = 10f, offsetY = 20f))

        assertEquals(JpDictRect(30, 60, 70, 100), controller.activeLineBoxes[0].rect)
        val movedQuad = controller.activeLineBoxes[0].quad!!
        assertEquals(30f, movedQuad.c0.x, 0.001f)
        assertEquals(60f, movedQuad.c0.y, 0.001f)
        assertEquals(70f, movedQuad.c1.x, 0.001f)
        assertEquals(60f, movedQuad.c1.y, 0.001f)
        assertEquals(30f, movedQuad.c3.x, 0.001f)
        assertEquals(100f, movedQuad.c3.y, 0.001f)
        assertEquals(JpDictRect(30, 60, 40, 100), controller.activeLineResults[0]!!.charBoxes[0])
        assertEquals(movedQuad, controller.activeLineResults[0]!!.quad)
    }

    @Test
    fun aSameSizeContainerLeavesQuadsAlone() {
        val controller = OcrOverlayStateController()
        val rect = JpDictRect(10, 20, 30, 40)
        val quad = JpDictQuad.fromRect(rect)
        controller.activeLineBoxes = listOf(LineBox(rect, quad))
        controller.refitBoxes(ImageShareFit.Refit(1f, 1f, 0f, 0f))
        val after = controller.activeLineBoxes.single()
        assertEquals(rect, after.rect)
        assertEquals(quad, after.quad)
    }
}
