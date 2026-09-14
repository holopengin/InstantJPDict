package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #78: the FILL_CENTER crop geometry — which centred slice of the captured frame
 * the preview was filling, and therefore what goes to the recogniser.
 *
 * The point of pinning it: the crop follows the two ASPECTS and knows nothing
 * about which way up the phone is, so the landscape hold that the OCR view now
 * inherits crops to what that landscape preview showed, not to a stale portrait
 * aspect. The device's own numbers from [ProtoCameraActivity]'s KDoc are used —
 * a 12 MP frame stored 4032x3024 with the EXIF tag that makes it upright, and the
 * ~1080x2400 window — so the portrait case here is also the regression pin for
 * the crop that has been judged in the hand.
 */
class PreviewCropTest {

    /** The stored 12 MP frame, upright in a LANDSCAPE hold: no rotation tag. */
    private val uprightFrameLandscape = 4032 to 3024

    /** The same frame upright in a PORTRAIT hold: EXIF says a quarter turn. */
    private val uprightFramePortrait = 3024 to 4032

    @Test
    fun portraitHoldCutsTheSidesAndKeepsFullHeight() {
        // The crop that is already on the phone: view aspect 1080/2400 = 0.45.
        val region = PreviewCrop.cover(3024, 4032, 1080f / 2400f)
        assertEquals(0, region.top)
        assertEquals(4032, region.height)
        assertEquals(1814, region.width)
        assertEquals((3024 - 1814) / 2, region.left)
    }

    @Test
    fun landscapeHoldCutsTheTopAndBottomAndKeepsFullWidth() {
        // View aspect 2400/1080 = 2.22 against a landscape frame's 1.33: the frame
        // is the taller shape now, so the slice is the full width, top and bottom
        // falling off — the other branch, reached because the view turned over.
        val region = PreviewCrop.cover(4032, 3024, 2400f / 1080f)
        assertEquals(0, region.left)
        assertEquals(4032, region.width)
        assertEquals(1814, region.height)
        assertEquals((3024 - 1814) / 2, region.top)
    }

    @Test
    fun theSliceAlwaysHasTheViewsAspect() {
        val cases = listOf(
            Triple(uprightFramePortrait, 1080f / 2400f, "portrait hold"),
            Triple(uprightFramePortrait, 2400f / 1080f, "portrait hold, landscape view"),
            Triple(uprightFrameLandscape, 2400f / 1080f, "landscape hold"),
            Triple(uprightFrameLandscape, 1080f / 2400f, "landscape hold, portrait view"),
        )
        for ((frame, viewAspect, what) in cases) {
            val region = PreviewCrop.cover(frame.first, frame.second, viewAspect)
            val got = region.width.toFloat() / region.height.toFloat()
            assertTrue("$what: $got vs $viewAspect", kotlin.math.abs(got - viewAspect) < 0.01f)
        }
    }

    @Test
    fun theCropIsAlwaysCentredInTheFrame() {
        val cases = listOf(
            Triple(uprightFramePortrait, 1080f / 2400f, "portrait hold"),
            Triple(uprightFrameLandscape, 2400f / 1080f, "landscape hold"),
        )
        for ((frame, viewAspect, what) in cases) {
            val region = PreviewCrop.cover(frame.first, frame.second, viewAspect)
            assertEquals("$what top", (frame.second - region.height) / 2, region.top)
            assertEquals("$what left", (frame.first - region.width) / 2, region.left)
        }
    }

    @Test
    fun theCropNeverLeavesTheFrame() {
        // A view as wide or as tall as the display can be: the rect stays inside
        // the frame, so Bitmap.createBitmap is never handed an out-of-bounds rect.
        val extremes = listOf(0.1f, 0.45f, 0.75f, 1f, 1.33f, 2.22f, 10f)
        for (viewAspect in extremes) {
            for (frame in listOf(uprightFramePortrait, uprightFrameLandscape)) {
                val region = PreviewCrop.cover(frame.first, frame.second, viewAspect)
                assertTrue("$viewAspect in $frame", region.width in 1..frame.first)
                assertTrue("$viewAspect in $frame", region.height in 1..frame.second)
                assertTrue("$viewAspect in $frame", region.left >= 0)
                assertTrue("$viewAspect in $frame", region.top >= 0)
                assertTrue("$viewAspect in $frame", region.left + region.width <= frame.first)
                assertTrue("$viewAspect in $frame", region.top + region.height <= frame.second)
            }
        }
    }

    @Test
    fun aDegenerateFrameOrAspectGivesTheWholeFrame() {
        // What [ProtoCameraActivity.cropThenHandOff] reads as "the preview was not
        // laid out": the whole frame comes back, so it hands the capture over
        // untouched rather than cropping to nothing.
        assertEquals(PreviewCrop.Region(0, 0, 4032, 3024), PreviewCrop.cover(4032, 3024, 0f))
        assertEquals(PreviewCrop.Region(0, 0, 0, 0), PreviewCrop.cover(0, 0, 1f))
    }
}
