package com.holopengin.instantjpdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #93: the floating trigger's physical-space rotation geometry. The service
 * stores the button centre in natural device coordinates and re-derives the
 * logical pixels per rotation; these tests pin that contract, including the
 * cutout-side behaviour the maintainer asked for.
 */
class FloatingButtonPositionTest {
    private val size = 97
    private val portraitW = 1080
    private val portraitH = 2400
    private val landscapeW = 2400
    private val landscapeH = 1080

    /** Natural centre of the maintainer's portrait spot beside the cutout. */
    private val besideCutout = naturalCentre(
        x = 574, y = 15, viewW = size, viewH = size,
        logicalW = portraitW, logicalH = portraitH, rotation = ROTATION_NATURAL,
    )

    @Test
    fun portrait_naturalCentreIsTheLogicalCentre() {
        assertEquals(622.5f to 63.5f, besideCutout)
    }

    @Test
    fun rotation90_mapsThePhysicalSpotToTheLeftEdge() {
        val (x, y) = logicalTopLeft(
            besideCutout.first, besideCutout.second, size, size,
            landscapeW, landscapeH, ROTATION_90,
        )
        // Cutout is at logical left edge (0..118); the button sits beside it.
        assertEquals(15, x)
        assertEquals(409, y)
    }

    @Test
    fun rotation270_mapsThePhysicalSpotToTheRightEdge() {
        val (x, y) = logicalTopLeft(
            besideCutout.first, besideCutout.second, size, size,
            landscapeW, landscapeH, ROTATION_270,
        )
        // Same physical spot, other landscape direction: cutout edge flips.
        assertEquals(2288, x)
        assertEquals(574, y)
    }

    @Test
    fun rotateBack_returnsTheExactPortraitPixel() {
        // The natural centre is canonical: the intermediate orientation may
        // clamp, but it must not rewrite the centre, or the button would creep.
        logicalTopLeft(besideCutout.first, besideCutout.second, size, size, landscapeW, landscapeH, ROTATION_90)
        assertEquals(574 to 15, logicalTopLeft(
            besideCutout.first, besideCutout.second, size, size, portraitW, portraitH, ROTATION_NATURAL))
    }

    @Test
    fun rotation180_flipsBothAxes() {
        val (x, y) = logicalTopLeft(
            besideCutout.first, besideCutout.second, size, size,
            portraitW, portraitH, ROTATION_180,
        )
        assertEquals(409, x)
        assertEquals(2288, y)
    }

    @Test
    fun placementInLandscape_returnsTheSamePhysicalSpotInPortrait() {
        val landscapeSpot = naturalCentre(
            x = 300, y = 100, viewW = size, viewH = size,
            logicalW = landscapeW, logicalH = landscapeH, rotation = ROTATION_90,
        )
        val (x, y) = logicalTopLeft(
            landscapeSpot.first, landscapeSpot.second, size, size,
            portraitW, portraitH, ROTATION_NATURAL,
        )
        // Round-trip through the natural frame is the identity.
        assertEquals(300 to 100, logicalTopLeft(
            landscapeSpot.first, landscapeSpot.second, size, size,
            landscapeW, landscapeH, ROTATION_90,
        ))
        assertTrue(x in 0..portraitW - size)
        assertTrue(y in 0..portraitH - size)
    }

    @Test
    fun clamp_keepsTheWholeButtonOnScreen() {
        // A natural centre one button above the top-left physical corner.
        val (x, y) = logicalTopLeft(
            natCX = 10f, natCY = 10f, viewW = size, viewH = size,
            logicalW = landscapeW, logicalH = landscapeH, rotation = ROTATION_90,
        )
        assertEquals(0, x)
        assertEquals(landscapeH - size, y)
    }

    @Test
    fun degenerateDisplay_usesSafeDefaults() {
        assertEquals(0 to 0, logicalTopLeft(10f, 10f, size, size, 0, 0, ROTATION_90))
    }
}
