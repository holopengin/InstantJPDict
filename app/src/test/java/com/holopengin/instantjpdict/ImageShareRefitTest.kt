package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * #78: the re-fit transform — the maths that keeps OCR boxes on their glyphs when
 * the container changes size and the run is KEPT (the OCR view is no longer
 * re-created by a quarter turn: `ShareImageActivity` declares `configChanges` and
 * re-fits in place).
 *
 * Why it can be pinned here. Every box coordinate the overlay holds is a COMPOSITE
 * pixel, and the composite is composed at the container's own size so that mapping
 * is 1:1. The composite is rebuilt at the new size from the unrotated base image,
 * so the box has to be carried from the old composite's pixels to the new one's by
 * exactly the transform that places the picture in each — which is
 * [ImageShareFit.refit], pure ints in and a float transform out. What cannot be
 * pinned is whether the *drawn* result looks aligned: that needs eyes on a phone
 * (see the task report), and these tests are the closest thing to it — the
 * landmark checks below assert that a point of the PICTURE lands at the same
 * fractional position of the picture in both composites.
 *
 * The device's own numbers are used throughout: a ~1080x2400 window (1080x2400 at
 * density 2.625) and a 12 MP camera frame, whose upright picture is 3024x4032 —
 * the same shape as the portrait case in PreviewCropTest.
 */
class ImageShareRefitTest {

    /** The upright picture of a 12 MP portrait camera frame. */
    private val pictureW = 3024
    private val pictureH = 4032

    /** The window it is first composed for, and the same window turned over. */
    private val portrait = 1080 to 2400
    private val landscape = 2400 to 1080

    /** The placement of the (possibly rotated) picture in a composite of that size. */
    private fun placement(target: Pair<Int, Int>, turns: Int): ImageShareFit.Placement =
        ImageRotation.fitRotated(pictureW, pictureH, target.first, target.second, turns)

    /**
     * The composite pixel a point of the PICTURE is drawn at — [u]/[v] are
     * fractions of the picture, so they name the same point of the photo in the old
     * composite and in the new one whatever size and position it was given.
     */
    private fun pixelOf(placement: ImageShareFit.Placement, u: Float, v: Float): Pair<Int, Int> =
        (placement.left + u * placement.width).roundToInt() to
            (placement.top + v * placement.height).roundToInt()

    /**
     * THE TEST THIS FILE EXISTS FOR: a point of the picture, named as a pixel in the
     * composite that showed it before, must come out of the transform as the pixel
     * the same point of the picture is drawn at in the composite that shows it
     * after. That is the statement "the box still lands on its glyph" — a box is
     * placed from a pair of these, and if this holds for the corners it holds for
     * the box.
     *
     * Every landmark is checked: the corners (where the old and new letterboxes
     * differ most), the centre, and points along both mid-lines. One pixel of
     * tolerance, because the transform rounds and so do the placements.
     */
    private fun assertThePointOfThePictureLandsInBothComposites(
        before: ImageShareFit.Placement,
        after: ImageShareFit.Placement,
        what: String,
    ) {
        val refit = ImageShareFit.refit(before, after)
        val landmarks = listOf(
            0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f,
            0.5f to 0.5f, 0f to 0.5f, 1f to 0.5f, 0.5f to 0f, 0.5f to 1f,
            0.25f to 0.75f, 0.8f to 0.1f,
        )
        for ((u, v) in landmarks) {
            val (ox, oy) = pixelOf(before, u, v)
            val (nx, ny) = pixelOf(after, u, v)
            assertEquals(
                "$what: u=$u v=$v — x moved to ${refit.x(ox)}, should be $nx",
                nx.toFloat(), refit.x(ox).toFloat(), 1.0f
            )
            assertEquals(
                "$what: u=$u v=$v — y moved to ${refit.y(oy)}, should be $ny",
                ny.toFloat(), refit.y(oy).toFloat(), 1.0f
            )
        }
    }

    /**
     * The case the change is for, at every orientation the user can be in when the
     * phone is turned: the picture is NOT rotated by the window turn (`turns` is
     * carried through unchanged), the container simply changes shape, and the boxes
     * move with the picture.
     */
    @Test
    fun turningTheWindowCarriesEveryPointOfThePictureOntoItself() {
        for (turns in 0..3) {
            assertThePointOfThePictureLandsInBothComposites(
                placement(portrait, turns), placement(landscape, turns),
                "portrait -> landscape, turns=$turns",
            )
            assertThePointOfThePictureLandsInBothComposites(
                placement(landscape, turns), placement(portrait, turns),
                "landscape -> portrait, turns=$turns",
            )
        }
    }

    /**
     * The transform is a uniform scale plus a translation, never a rotation: the
     * two axes scale together (to within the placements' one-pixel arithmetic), the
     * offsets follow from the scale, and a box's own left-to-right order survives —
     * which is what would break if the content were turned by the re-fit.
     */
    @Test
    fun itIsAUniformScaleAndNotARotation() {
        for (turns in 0..3) {
            val before = placement(portrait, turns)
            val after = placement(landscape, turns)
            val refit = ImageShareFit.refit(before, after)
            assertTrue(
                "turns=$turns: scale ${refit.scaleX} vs ${refit.scaleY}",
                abs(refit.scaleX - refit.scaleY) < 0.01f
            )
            // The offsets are the ones the scale implies, from the placements.
            assertEquals(
                "turns=$turns offsetX",
                after.left - before.left * refit.scaleX, refit.offsetX.toFloat(), 0.5f
            )
            assertEquals(
                "turns=$turns offsetY",
                after.top - before.top * refit.scaleY, refit.offsetY.toFloat(), 0.5f
            )
            // A box, and the same box: left stays left, top stays top, both edges
            // scale by the same factor, so the box cannot end up mirrored or turned.
            val boxLeft = before.left + 100
            val boxTop = before.top + 40
            val boxRight = boxLeft + 60
            val boxBottom = boxTop + 60
            assertTrue(refit.x(boxLeft) < refit.x(boxRight))
            assertTrue(refit.y(boxTop) < refit.y(boxBottom))
            assertEquals(
                "turns=$turns: a square box stays square",
                (refit.x(boxRight) - refit.x(boxLeft)).toFloat(),
                (refit.y(boxBottom) - refit.y(boxTop)).toFloat(),
                // Plus or minus one pixel: the transform rounds each edge.
                1.0f
            )
        }
    }

    /**
     * The box a detect step draws around a line of text: an AABB in the old
     * composite's pixels, its corners moved, must enclose the pixels the glyphs are
     * drawn at in the new one. Checked on the LINE boxes (not just points) because
     * that is what the view positions views from.
     */
    @Test
    fun aDetectBoxEnclosesTheGlyphsInBothComposites() {
        val before = placement(portrait, 0)
        val after = placement(landscape, 0)
        val refit = ImageShareFit.refit(before, after)
        // A line across the middle of the picture, in old composite pixels.
        val boxLeft = pixelOf(before, 0.2f, 0.4f).first
        val boxTop = pixelOf(before, 0.2f, 0.4f).second
        val boxRight = pixelOf(before, 0.8f, 0.4f).first
        val boxBottom = pixelOf(before, 0.2f, 0.5f).second
        val moved = JpDictRect(
            refit.x(boxLeft), refit.y(boxTop), refit.x(boxRight), refit.y(boxBottom)
        )
        assertEquals("left edge", pixelOf(after, 0.2f, 0.4f).first.toFloat(), moved.left.toFloat(), 1.0f)
        assertEquals("top edge", pixelOf(after, 0.2f, 0.4f).second.toFloat(), moved.top.toFloat(), 1.0f)
        assertEquals("right edge", pixelOf(after, 0.8f, 0.4f).first.toFloat(), moved.right.toFloat(), 1.0f)
        assertEquals("bottom edge", pixelOf(after, 0.2f, 0.5f).second.toFloat(), moved.bottom.toFloat(), 1.0f)
        assertTrue("the moved box is still a box", moved.width() > 0 && moved.height() > 0)
    }

    /**
     * A container that did not change size asks for nothing — the identity — which
     * is what stops a same-size layout pass from moving boxes for no reason.
     */
    @Test
    fun theSameContainerIsTheIdentity() {
        val placement = placement(portrait, 0)
        val refit = ImageShareFit.refit(placement, placement)
        assertTrue(refit.isIdentity)
        for (value in listOf(0, 1, 539, 1080, 2400)) {
            assertEquals(value, refit.x(value))
            assertEquals(value, refit.y(value))
        }
    }

    /**
     * The property, for BOTH sides: an axis on which either placement has no pixels
     * has no correspondence to derive, so it must be the identity there — no scale
     * AND no offset — while the other axis is still transformed normally. A config
     * change is exactly when a container can be transiently degenerate (a zero-sized
     * axis mid-turn), so this is a state the app really passes through rather than a
     * nuisance input.
     *
     * It is also the regression pin for the bug it first caught: the scale was
     * guarded against division by zero while the OFFSET was still taken from the
     * degenerate side's own letterbox, which translated every box by a placement
     * that never existed. The maths was wrong on that axis, not the assertion.
     */
    @Test
    fun aDegenerateAxisIsLeftAloneRatherThanGivenABogusTransform() {
        // The OLD composite was never laid out: nothing is known about where the
        // picture sat, so a re-fit that cannot place it must move nothing at all.
        val nothingKnown = ImageShareFit.refit(
            ImageShareFit.Placement(0, 0, 0, 0),
            ImageShareFit.Placement(10, 20, 100, 100),
        )
        assertTrue("a container with no pixels cannot move boxes", nothingKnown.isIdentity)
        for (value in listOf(0, 1, 50, 1080)) {
            assertEquals(value, nothingKnown.x(value))
            assertEquals(value, nothingKnown.y(value))
        }

        // The same the other way round: the NEW container is the degenerate one, and
        // the boxes must not be collapsed onto its origin.
        val newSideDegenerate = ImageShareFit.refit(
            ImageShareFit.Placement(0, 100, 1080, 1440),
            ImageShareFit.Placement(0, 0, 0, 0),
        )
        assertTrue(newSideDegenerate.isIdentity)
        assertEquals(539, newSideDegenerate.x(539))
        assertEquals(820, newSideDegenerate.y(820))

        // One axis known and the other not: only the known axis is transformed, and
        // the unknown one is left exactly where it was.
        val halfKnown = ImageShareFit.refit(
            ImageShareFit.Placement(0, 0, 100, 0),
            ImageShareFit.Placement(0, 0, 200, 100),
        )
        assertFalse(halfKnown.isIdentity)
        assertEquals(2f, halfKnown.scaleX, 0f)
        assertEquals(0f, halfKnown.offsetX, 0f)
        assertEquals(1f, halfKnown.scaleY, 0f)
        assertEquals(0f, halfKnown.offsetY, 0f)
        assertEquals(300, halfKnown.x(150))
        assertEquals(150, halfKnown.y(150))
    }
}
