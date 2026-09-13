package com.holopengin.instantjpdict

import org.junit.Test

import org.junit.Assert.*

/**
 * #57 rotation follow-up: the geometry the rotate buttons depend on. The
 * buttons and the composition are view construction (an Android Canvas and
 * Context), so they cannot be JVM-tested; the quarter-turn bookkeeping and the
 * re-fit of the rotated image onto the view-sized canvas are plain math, so
 * they are pinned here instead — the same split [ImageShareInputTest] uses.
 *
 * The load-bearing case is [fitRotated_isDerivedFromTheSwappedDimensions]: a
 * 90° turn swaps the image's width and height, and OCR box coordinates are in
 * the composite's pixel space, so a fit computed from the pre-rotation
 * dimensions would leave every recognition box and hit rect off its glyph.
 */
class ImageRotationTest {

    @Test
    fun turn_accumulatesClockwiseAndWrapsEveryFourthPress() {
        assertEquals(1, ImageRotation.turn(0, clockwise = true))
        assertEquals(2, ImageRotation.turn(1, clockwise = true))
        assertEquals(3, ImageRotation.turn(2, clockwise = true))
        assertEquals(0, ImageRotation.turn(3, clockwise = true))
    }

    @Test
    fun turn_counterclockwiseIsTheInverse() {
        assertEquals(3, ImageRotation.turn(0, clockwise = false))
        assertEquals(2, ImageRotation.turn(3, clockwise = false))
        assertEquals(0, ImageRotation.turn(1, clockwise = false))
    }

    @Test
    fun turn_fourPressesOfEitherDirectionReturnToTheStart() {
        var cw = 0
        repeat(4) { cw = ImageRotation.turn(cw, clockwise = true) }
        assertEquals(0, cw)
        var ccw = 0
        repeat(4) { ccw = ImageRotation.turn(ccw, clockwise = false) }
        assertEquals(0, ccw)
    }

    @Test
    fun degrees_areTheMatrixTurnsForEachQuarter() {
        assertEquals(0f, ImageRotation.degrees(0), 0f)
        assertEquals(90f, ImageRotation.degrees(1), 0f)
        assertEquals(180f, ImageRotation.degrees(2), 0f)
        assertEquals(270f, ImageRotation.degrees(3), 0f)
        // Normalised, so a caller passing a wrapped count still gets a real turn.
        assertEquals(270f, ImageRotation.degrees(-1), 0f)
        assertEquals(0f, ImageRotation.degrees(4), 0f)
    }

    @Test
    fun rotatedSize_swapsOnQuarterTurnsOnly() {
        assertEquals(2000 to 1000, ImageRotation.rotatedSize(2000, 1000, 0))
        assertEquals(1000 to 2000, ImageRotation.rotatedSize(2000, 1000, 1))
        assertEquals(2000 to 1000, ImageRotation.rotatedSize(2000, 1000, 2))
        assertEquals(1000 to 2000, ImageRotation.rotatedSize(2000, 1000, 3))
    }

    @Test
    fun fitRotated_isDerivedFromTheSwappedDimensions() {
        // A wide image on a tall canvas: upright it letterboxes top and bottom…
        assertEquals(
            ImageShareFit.Placement(0, 750, 1000, 500),
            ImageRotation.fitRotated(2000, 1000, 1000, 2000, 0)
        )
        // …turned, it exactly fills the canvas — same source, same canvas, a
        // different fit, because the dimensions were swapped first.
        assertEquals(
            ImageShareFit.Placement(0, 0, 1000, 2000),
            ImageRotation.fitRotated(2000, 1000, 1000, 2000, 1)
        )
        assertEquals(
            ImageShareFit.Placement(0, 0, 1000, 2000),
            ImageRotation.fitRotated(2000, 1000, 1000, 2000, 3)
        )
        // Half a turn is back to the upright fit.
        assertEquals(
            ImageShareFit.Placement(0, 750, 1000, 500),
            ImageRotation.fitRotated(2000, 1000, 1000, 2000, 2)
        )
    }

    @Test
    fun fitRotated_movesTheLetterboxBandAcrossAxes() {
        // 400x100 into a square: upright the bars are horizontal, turned they
        // are vertical. Asserting both is what pins the recomputation.
        assertEquals(
            ImageShareFit.Placement(0, 75, 200, 50),
            ImageRotation.fitRotated(400, 100, 200, 200, 0)
        )
        assertEquals(
            ImageShareFit.Placement(75, 0, 50, 200),
            ImageRotation.fitRotated(400, 100, 200, 200, 1)
        )
    }

    @Test
    fun fitRotated_differsFromTheUnrotatedFitOnAQuarterTurn() {
        // The trap in one assertion: reusing the pre-rotation fit is not the
        // same placement, it is a wrong one. Only a quarter turn differs.
        val unrotated = ImageShareFit.fitCenter(2000, 1000, 1000, 2000)
        assertNotEquals(unrotated, ImageRotation.fitRotated(2000, 1000, 1000, 2000, 1))
        assertNotEquals(unrotated, ImageRotation.fitRotated(2000, 1000, 1000, 2000, 3))
        assertEquals(unrotated, ImageRotation.fitRotated(2000, 1000, 1000, 2000, 0))
        assertEquals(unrotated, ImageRotation.fitRotated(2000, 1000, 1000, 2000, 2))
    }

    @Test
    fun fitRotated_equalsTheUprightFitOfTheRotatedSource() {
        // The rule stated the long way round: turning the image and fitting it
        // is the same as fitting the image that is already turned.
        assertEquals(
            ImageShareFit.fitCenter(1000, 2000, 1080, 2400),
            ImageRotation.fitRotated(2000, 1000, 1080, 2400, 1)
        )
        assertEquals(
            ImageShareFit.fitCenter(1000, 2000, 1080, 2400),
            ImageRotation.fitRotated(2000, 1000, 1080, 2400, 3)
        )
    }

    @Test
    fun fitRotated_alwaysFitsInsideTheCanvasAndStaysCentred() {
        // The alignment guarantee, over the orientation/size combinations that
        // actually occur: whatever the turn, the composite is covered by a
        // placement that lies inside the view-sized canvas and is centred in it.
        val targets = listOf(1080 to 2400, 2400 to 1080, 800 to 800)
        val sources = listOf(4000 to 3000, 3000 to 4000, 500 to 4000, 4000 to 500)
        for ((tw, th) in targets) {
            for ((sw, sh) in sources) {
                for (turns in 0..3) {
                    val p = ImageRotation.fitRotated(sw, sh, tw, th, turns)
                    val label = "${sw}x$sh turns=$turns on ${tw}x$th gave $p"
                    assertTrue(label, p.left >= 0 && p.top >= 0)
                    assertTrue(label, p.left + p.width <= tw && p.top + p.height <= th)
                    val dx = (p.left + p.width / 2) - tw / 2
                    val dy = (p.top + p.height / 2) - th / 2
                    assertTrue(label, dx in -1..1 && dy in -1..1)
                }
            }
        }
    }

    @Test
    fun fitRotated_degenerateSourceStaysTargetSized() {
        assertEquals(
            ImageShareFit.Placement(0, 0, 40, 40),
            ImageRotation.fitRotated(0, 10, 40, 40, 1)
        )
        assertEquals(
            ImageShareFit.Placement(0, 0, 0, 0),
            ImageRotation.fitRotated(10, 10, 0, 0, 1)
        )
    }
}
